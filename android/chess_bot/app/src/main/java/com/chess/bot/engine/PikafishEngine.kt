package com.chess.bot.engine

import android.content.Context
import com.chess.bot.game.Const
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import java.io.File

/** 引擎异常（对齐 python EngineError：绝不外泄底层 IO 异常）。 */
class EngineError(message: String) : RuntimeException(message)

/**
 * 单次 go 结果：着法 + 评估分（厘兵，正=当前行棋方占优）+ 思考层数。
 * matePly：主搜 `info score mate N` 解析出的将死步数（N>0=当前行棋方 N 步内将死；null=非将死局面）。
 * 用于绝杀探测：matePly==1 时返回的着法本身就是杀着，无需走完再二次调用引擎验证。
 */
data class EngineResult(
    val move: String?,
    val scoreCp: Int,
    val depth: Int,
    val matePly: Int? = null,
    /** UCI `bestmove X ponder Y` 中的 Y：引擎预测对手的应手（ponder 预搜起点）。 */
    val ponderMove: String? = null,
)

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

    /** 是否处于 ponder 预搜中（go ponder 已发、尚未 ponderhit/stop）。串行调用方据此决定走 ponderHit 还是重新 go。 */
    private var pondering = false

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
                LogBus.log(LogKind.DEBUG, LogTag.ENGINE, "首次启动：拷贝 NNUE 权重到 filesDir")
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
                val cfg = com.chess.bot.data.BotConfig.data
                writeLine("setoption name Threads value ${cfg.threads}")
                writeLine("setoption name Hash value ${cfg.hashMb}")
                writeLine("setoption name EvalFile value ${nnue.absolutePath}")
                writeLine("setoption name Rule60MaxPly value ${Const.ENGINE_RULE60_MAX_PLY}")
                writeLine("isready")
                waitFor("readyok", 15_000)
                LogBus.log(
                    LogKind.OK,
                    LogTag.ENGINE,
                    "pikafish 引擎已就绪（Threads=${cfg.threads} Hash=${cfg.hashMb}MB）"
                )
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
        stopPonder() // 清掉可能遗留的 ponder，避免与 ucinewgame 并发
        synchronized(lock) {
            synchronized(lines) { lines.clear() }
            writeLine("ucinewgame")
            writeLine("isready")
            waitFor("readyok", 15_000)
        }
    }

    /**
     * 发送局面并返回 EngineResult；无着法（终局）move=null。
     * movetimeMs / depthLimit 为显式覆盖（短时限重试、绝杀探测用）；
     * 缺省时按设置页「思考模式」构造 go 命令（时长 / 层数 / 双限先到为准）。
     */
    fun bestMove(
        context: Context,
        fen: String,
        movetimeMs: Int? = null,
        depthLimit: Int? = null,
    ): EngineResult {
        val snapshot = go(context, fen, movetimeMs, depthLimit)
        val score = parseScore(snapshot)
        val depth = parseDepth(snapshot)
        val matePly = parseMatePly(snapshot)
        for (line in snapshot) {
            if (line.startsWith("bestmove")) {
                val tokens = line.split(" ")
                if (tokens.size < 2) return EngineResult(null, score, depth, matePly)
                val move = tokens[1]
                // bestmove X ponder Y：Y 为引擎预测对手应手（UCI ponder 机制）
                val ponder = if (tokens.size >= 4 && tokens[2] == "ponder") tokens[3] else null
                return EngineResult(
                    if (move == "(none)") null else move,
                    score, depth, matePly, ponder,
                )
            }
        }
        return EngineResult(null, score, depth, matePly)
    }

    /** 对方在该局面是否无路可走（绝杀/困毙）。 */
    fun isMate(
        context: Context,
        fen: String,
        movetimeMs: Int = Const.ENGINE_MATE_PROBE_MS
    ): Boolean =
        bestMove(context, fen, movetimeMs = movetimeMs).move == null

    /**
     * 异步开始 ponder（UCI `go ponder`）：在「我方走子 + 预测敌着 Y」局面持续预搜我方应手。
     * 敌方真走 Y 时调 [ponderHit] 近乎瞬时取回我方着法，省去一次完整搜索（~movetime）。
     * 仅在敌方走子与预测一致时有效；不一致时调 [stopPonder] 丢弃并另行常规搜索。
     */
    fun startPonder(context: Context, fenAfterMyMove: String, predictedEnemyMove: String) {
        ensureStarted(context)
        synchronized(lock) {
            synchronized(lines) { lines.clear() }
            writeLine("position fen $fenAfterMyMove moves $predictedEnemyMove")
            // 限 movetime：避免移动端无限 ponder 占满 CPU，且 stop 时引擎能快速收尾回 bestmove
            writeLine("go ponder movetime ${com.chess.bot.data.BotConfig.data.movetimeMs}")
            pondering = true
        }
    }

    /** 敌方走子与预测一致 → 通知引擎继续并取预搜结果（我方应手）。 */
    fun ponderHit(): EngineResult {
        val snapshot = synchronized(lock) {
            synchronized(lines) { lines.clear() }
            writeLine("ponderhit")
            val waitMs = com.chess.bot.data.BotConfig.data.movetimeMs + 1_000L
            waitFor("bestmove", waitMs)
            synchronized(lines) { lines.toList() }
        }
        pondering = false
        val score = parseScore(snapshot)
        val depth = parseDepth(snapshot)
        val matePly = parseMatePly(snapshot)
        for (line in snapshot) {
            if (line.startsWith("bestmove")) {
                val tokens = line.split(" ")
                if (tokens.size < 2) return EngineResult(null, score, depth, matePly)
                val move = tokens[1]
                return EngineResult(if (move == "(none)") null else move, score, depth, matePly)
            }
        }
        return EngineResult(null, score, depth, matePly)
    }

    /** 中止 ponder（敌方未走预测着 / 中断 / 新局）。引擎回的 bestmove 属错误局面，丢弃。 */
    fun stopPonder() {
        synchronized(lock) {
            if (!pondering) return
            writeLine("stop")
            try {
                waitFor("bestmove", 2_000)
            } catch (e: Exception) {
                // 超时：引擎未在时限内回 bestmove（移动端慢 / 无限 ponder 未停）。
                // 必须重建干净进程，否则残留 ponder 状态会让后续 bestMove 的 go 不发 bestmove → 连锁超时。
                LogBus.log(
                    LogKind.WARN, LogTag.ENGINE,
                    "stopPonder 超时，重建引擎：${e.message}"
                )
                restart() // restart 内已置 pondering=false
                return
            }
            pondering = false
        }
    }

    /** 结束引擎进程（quit 只在此发送）。 */
    fun close() {
        synchronized(lock) {
            stopPonder()
            val p = process
            process = null
            if (p != null) kill(p)
        }
    }

    // ---------- 内部 ----------

    private fun go(
        context: Context,
        fen: String,
        movetimeMs: Int?,
        depthLimit: Int?
    ): List<String> {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                // 防御：若上一次 ponder 未干净停止（stop 超时残留），先强制清掉，
                // 否则在 ponder 状态下发 go 会让引擎不发 bestmove → 等 bestmove 超时
                if (pondering) {
                    LogBus.log(
                        LogKind.WARN,
                        LogTag.ENGINE,
                        "bestMove 前引擎仍处 ponder，强制 stopPonder"
                    )
                    stopPonder()
                }
                ensureStarted(context)
                synchronized(lock) {
                    synchronized(lines) { lines.clear() }
                    writeLine("position fen $fen")
                    writeLine(buildGoCommand(movetimeMs, depthLimit))
                    val waitMs =
                        (movetimeMs ?: com.chess.bot.data.BotConfig.data.movetimeMs) + 1_000L
                    waitFor("bestmove", waitMs)
                    val snapshot = synchronized(lines) { lines.toList() }
                    snapshot.lastOrNull { it.startsWith("info") }?.let { info ->
                        // 原始 info 行落文件日志（截断防巨行），排查引擎决策用
                        LogBus.log(LogKind.DEBUG, LogTag.ENGINE, "info ${info.take(160)}")
                    }
                    return snapshot
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

    /** 按思考模式构造 go 命令；显式覆盖优先（短时限重试/绝杀探测）。 */
    private fun buildGoCommand(movetimeMs: Int?, depthLimit: Int?): String {
        val mt = movetimeMs
        val dl = depthLimit
        return when {
            mt != null && dl != null -> "go depth $dl movetime $mt"
            mt != null -> "go movetime $mt"
            dl != null -> "go depth $dl"
            else -> when (com.chess.bot.data.BotConfig.data.thinkMode) {
                com.chess.bot.data.ThinkMode.TIME ->
                    "go movetime ${com.chess.bot.data.BotConfig.data.movetimeMs}"

                com.chess.bot.data.ThinkMode.DEPTH ->
                    "go depth ${com.chess.bot.data.BotConfig.data.depth}"

                com.chess.bot.data.ThinkMode.BOTH ->
                    "go depth ${com.chess.bot.data.BotConfig.data.depth} " +
                            "movetime ${com.chess.bot.data.BotConfig.data.movetimeMs}"
            }
        }
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
            pondering = false
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

        /** 从引擎输出行解析思考层数（取最后一条 info depth）。 */
        fun parseDepth(engineLines: List<String>): Int {
            var depth = 0
            for (line in engineLines) {
                if (!line.startsWith("info")) continue
                val tokens = line.split(" ")
                for (i in tokens.indices) {
                    if (tokens[i] == "depth" && i + 1 < tokens.size) {
                        tokens[i + 1].toIntOrNull()?.let { depth = it }
                    }
                }
            }
            return depth
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

        /**
         * 从引擎输出行解析将死步数（取最后一条 info score mate N）。
         * 返回 N（正=当前行棋方 N 步内将死；负=被将死）；非将死局面返回 null。
         * 注意：score 行可能先出 cp 后出 mate，须取「最后一条」score 行判定其 kind。
         */
        fun parseMatePly(engineLines: List<String>): Int? {
            var mate: Int? = null
            for (line in engineLines) {
                if (!line.startsWith("info")) continue
                val tokens = line.split(" ")
                for (i in tokens.indices) {
                    if (tokens[i] == "score" && i + 2 < tokens.size && tokens[i + 1] == "mate") {
                        mate = tokens[i + 2].toIntOrNull()
                    }
                }
            }
            return mate
        }
    }
}
