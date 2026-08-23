package com.chess.bot.engine

import android.content.Context
import com.chess.bot.game.Const
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import java.io.File

/** 引擎异常（对齐 python EngineError：绝不外泄底层 IO 异常）。 */
class EngineError(message: String) : RuntimeException(message)

/**
 * pikafish UCI 长进程客户端（移植 python engine.py）。
 *
 * - 二进制：nativeLibraryDir/libpikafish.so（APK jniLibs 方案，安装后可 exec）
 * - NNUE：assets 首启拷贝到 filesDir/pikafish.nnue；启动参数显式指定 EvalFile
 * - `quit` 只在 close() 时发送；无响应/进程退出自动重建并重试（共 3 次）
 */
class PikafishEngine private constructor() {

    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    private val lines = ArrayDeque<String>()
    private val lock = Any()

    /** 启动引擎子进程并完成 UCI 初始化（幂等）。 */
    fun ensureStarted(context: Context) {
        synchronized(lock) {
            if (process != null) return
            val app = context.applicationContext
            val exe = File(app.applicationInfo.nativeLibraryDir, "libpikafish.so")
            if (!exe.exists()) throw EngineError("找不到引擎: ${exe.absolutePath}")
            val cwd = app.filesDir
            cwd.mkdirs()
            val nnue = File(cwd, "pikafish.nnue")
            if (!nnue.exists()) {
                // 先写临时名再 rename：避免中途被杀残留截断权重文件
                LogBus.log(LogKind.INFO, "首次启动：拷贝 NNUE 权重到 filesDir")
                val tmp = File(cwd, "pikafish.nnue.tmp")
                app.assets.open("pikafish.nnue").use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tmp.renameTo(nnue)) {
                    tmp.copyTo(nnue, overwrite = true)
                    tmp.delete()
                }
            }
            val p = try {
                ProcessBuilder(exe.absolutePath)
                    .directory(cwd)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                throw EngineError("启动引擎失败: ${e.message}")
            }
            process = p
            writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
            Thread({ drain(p) }, "engine-drain").apply { isDaemon = true }.start()
            try {
                writeLine("uci")
                waitFor("uciok", 15_000)
                writeLine("setoption name Threads value ${Const.ENGINE_THREADS}")
                writeLine("setoption name Hash value ${Const.ENGINE_HASH_MB}")
                writeLine("setoption name EvalFile value ${nnue.absolutePath}")
                writeLine("setoption name Rule60MaxPly value ${Const.ENGINE_RULE60_MAX_PLY}")
                writeLine("isready")
                waitFor("readyok", 15_000)
                LogBus.log(LogKind.OK, "pikafish 已就绪")
            } catch (e: EngineError) {
                kill(p)
                process = null
                throw e
            }
        }
    }

    /** 通知引擎新对局开始（ucinewgame + isready）。 */
    fun newGame(context: Context) {
        ensureStarted(context)
        synchronized(lock) {
            synchronized(lines) { lines.clear() }
            writeLine("ucinewgame")
            writeLine("isready")
            waitFor("readyok", 15_000)
        }
    }

    /**
     * 发送局面并返回 (bestmove, score)；无着法（终局）返回 (null, 0)。
     * score 为 info score cp/mate（厘兵，正=当前行棋方占优；mate 映射 ±100000）。
     */
    fun bestMove(context: Context, fen: String, movetimeMs: Int = Const.ENGINE_MOVETIME_MS): Pair<String?, Int> {
        val snapshot = go(context, fen, movetimeMs)
        val score = parseScore(snapshot)
        for (line in snapshot) {
            if (line.startsWith("bestmove")) {
                val tokens = line.split(" ")
                if (tokens.size < 2) return null to score
                val move = tokens[1]
                return (if (move == "(none)") null else move) to score
            }
        }
        return null to score
    }

    /** 对方在该局面是否无路可走（绝杀/困毙）。 */
    fun isMate(context: Context, fen: String, movetimeMs: Int = Const.ENGINE_MATE_PROBE_MS): Boolean =
        bestMove(context, fen, movetimeMs).first == null

    /** 结束引擎进程（quit 只在此发送）。 */
    fun close() {
        synchronized(lock) {
            val p = process
            process = null
            if (p != null) kill(p)
        }
    }

    // ---------- 内部 ----------

    private fun go(context: Context, fen: String, movetimeMs: Int): List<String> {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                ensureStarted(context)
                synchronized(lock) {
                    synchronized(lines) { lines.clear() }
                    writeLine("position fen $fen")
                    writeLine("go movetime $movetimeMs")
                    waitFor("bestmove", movetimeMs + 1_000L)
                    return synchronized(lines) { lines.toList() }
                }
            } catch (e: EngineError) {
                lastError = e
                restart()
                if (attempt < 2) {
                    Thread.sleep(500)
                }
            }
        }
        throw EngineError("引擎异常：${lastError?.message}")
    }

    private fun writeLine(line: String) {
        val w = writer ?: throw EngineError("引擎进程已退出")
        try {
            w.write(line)
            w.newLine()
            w.flush()
        } catch (e: Exception) {
            throw EngineError("引擎进程已退出：${e.message}")
        }
    }

    private fun drain(p: Process) {
        try {
            // JVM 流解码默认 REPLACE：非法字节替换为 U+FFFD 而非抛异常（对齐 python errors="replace"）
            p.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(lines) {
                        lines.addLast(line)
                        while (lines.size > MAX_LINES) lines.removeFirst()
                    }
                }
            }
        } catch (_: Exception) {
            // 进程退出/流关闭：静默结束读取线程
        }
    }

    private fun waitFor(marker: String, timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            synchronized(lines) {
                for (i in lines.indices.reversed()) {
                    val stripped = lines[i].trim()
                    if (stripped == marker || stripped.startsWith("$marker ")) return
                }
            }
            Thread.sleep(5)
        }
        throw EngineError("引擎响应超时（等待 $marker）")
    }

    private fun restart() {
        synchronized(lock) {
            val p = process
            process = null
            if (p != null) kill(p)
        }
    }

    private fun kill(p: Process) {
        runCatching {
            runCatching { p.outputStream.write("quit\n".toByteArray()); p.outputStream.flush() }
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    companion object {
        private const val MAX_LINES = 2000

        @Volatile
        private var instance: PikafishEngine? = null

        fun get(): PikafishEngine =
            instance ?: synchronized(this) {
                instance ?: PikafishEngine().also { instance = it }
            }

        /** 从引擎输出行解析局面分数（取最后一条 info score）。 */
        fun parseScore(engineLines: List<String>): Int {
            var score = 0
            for (line in engineLines) {
                if (!line.startsWith("info")) continue
                val tokens = line.split(" ")
                for (i in tokens.indices) {
                    if (tokens[i] == "score" && i + 2 < tokens.size) {
                        val kind = tokens[i + 1]
                        val value = tokens[i + 2].toIntOrNull() ?: continue
                        score = when (kind) {
                            "cp" -> value
                            "mate" -> if (value > 0) 100000 - value else -100000 - value
                            else -> score
                        }
                    }
                }
            }
            return score
        }
    }
}
