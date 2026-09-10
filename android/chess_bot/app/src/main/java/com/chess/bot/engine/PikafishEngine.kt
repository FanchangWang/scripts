package com.chess.bot.engine

import android.content.Context
import com.chess.bot.game.Const
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import java.io.File

/** 引擎异常（对齐 python EngineError：绝不外泄底层 IO 异常）。 */
class EngineError(message: String) : RuntimeException(message)

/**
 * 单次 go 结果：着法 + 评估分（厘兵，正=当前行棋方占优）+ 思考层数。
 * matePly：主搜 `info score mate N` 解析出的将死步数（N>0=当前行棋方 N 步内将死；null=非将死局面）。
 * 仅 matePly==1 语义有效（本步即杀着）——绝杀判断只认 info mate。
 */
data class EngineResult(
    val move: String?,
    /** 厘兵；null = 本次搜索无任何有效 info 行（上层显示「评估 -」，不再用 0 占位）。 */
    val scoreCp: Int?,
    /** null = 无有效 info。 */
    val depth: Int?,
    val matePly: Int? = null,
    /** UCI `bestmove X ponder Y` 中的 Y：引擎预测对手的应手（ponder 预搜起点）。 */
    val ponderMove: String? = null,
    /** 选择性搜索深度（真实思考层次信号）。 */
    val seldepth: Int = 0,
    /** 本次搜索节点数（硬计算量指标）。 */
    val nodes: Long = 0L,
)

/**
 * pikafish UCI 长进程客户端（移植 python engine.py）。
 *
 * - 二进制：nativeLibraryDir/libpikafish.so（APK jniLibs 方案，安装后可 exec）
 * - NNUE：assets 首启拷贝到 filesDir/pikafish.nnue；启动参数显式指定 EvalFile
 * - `quit` 只在 close() 时发送；无响应/进程退出自动重建并重试（共 3 次）
 *
 * 2026-09-11 引擎使用流程改造（SPEC pikafish-movetime-refactor）：
 * - 主搜一律 `go movetime <TARGET>`（R1）：皮卡鱼 check_time 到点自停并交 bestmove，
 *   App 不再掐表、stop 仅作兜底；原 `go infinite` + 质量门控整链（伪影 A1/A2、五维 qualityOk、
 *   近杀/盲区/停更止损）全部删除（R4/R5），着法直接取 bestmove。
 * - position 改「基线 FEN + moves 全列表」（R3）：App 不逐手拼完整 FEN，
 *   halfmove / 重复局面由引擎按 moves 自算。
 * - ponder 改 `go ponder movetime T`（R2）：movetime 是累积搜索时长（ponder 期不检查），
 *   命中 ponderhit 即刻交手；硬顶 = TARGET + ENGINE_HARD_CAP_APPEND_MS 仅作兜底（R6）。
 *   命令送达由 [abortPonderIfUnacknowledged] 自检（A 项）。
 * - drain 线程只解析 info 维护 currentInfo，不再做任何过滤/采样。
 */
class PikafishEngine private constructor() {

    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    private val lines = ArrayDeque<String>()
    private val lock = Any()

    /** 是否处于 ponder 预搜中（go ponder 已发、尚未 ponderhit/stop）。串行调用方据此决定走 ponderHit 还是重新 go。 */
    private var pondering = false

    /** go ponder 发出时刻（纳秒）：命令送达自检 [abortPonderIfUnacknowledged] 与 [ponderElapsedMs] 共用时钟。 */
    @Volatile
    private var ponderStartNs = 0L

    /** 本次 ponder 的 movetime（ms），仅日志用。 */
    @Volatile
    private var ponderMovetimeMs = 0

    /** 最新有效 info 快照（drain 线程整体替换 immutable 实例；@Volatile 只保证引用可见）。 */
    @Volatile
    private var currentInfo: EngineInfo? = null

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
                LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "首次启动：拷贝 NNUE 权重到 filesDir")
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
                    LogLevel.INFO,
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
     * 发送局面并返回 EngineResult；无着法（终局）move = null。
     * 局面 = [baselineFen] + [moves] 全列表（R3）：App 不逐手拼完整 FEN，
     * halfmove / 重复局面由引擎按 moves 自算；moves 为空时省略 moves 段。
     * 主搜发 `go movetime <TARGET>`（R1，UCI 空格分隔，勿写等号），引擎到点自交 bestmove。
     */
    fun bestMove(
        context: Context,
        baselineFen: String,
        moves: List<String>,
        movetimeMs: Int? = null,
    ): EngineResult {
        val outcome = go(context, baselineFen, moves, movetimeMs)
        return buildResult(outcome.snapshot)
    }

    /**
     * 快照 → EngineResult（主搜与 ponder 收割共用口径）：
     * 取深度最深的有效 info 行（R4：不做任何可信度判断）→ 分 / 层 / mate；
     * 无有效行则 scoreCp/depth 为 null（不填占位 0，避免把「引擎沉默」显示成「评估 0」）。
     * matePly 仅在非 bound 时外泄（bound 行是「至少 N 步杀」边界值）。
     */
    private fun buildResult(snapshot: List<String>): EngineResult {
        val info = EngineInfoPick.pickFinalInfo(snapshot)
        val score = info?.scoreCp
        val depth = info?.depth
        val matePly = if (info != null && !info.hasBound) info.matePly else null

        // bestmove 永远为最终着法（引擎到点自停 / stop 后做最终整理）；pvFirst 仅观测告警
        val best = parseBestMove(snapshot)
        if (info?.pvFirst != null && best?.first != null && info.pvFirst != best.first) {
            LogBus.log(
                LogLevel.WARN, LogTag.ENGINE,
                "bestmove(${best.first}) 与 pv 首着(${info.pvFirst}) 不一致 " +
                        "(depth=${info.depth} seldepth=${info.seldepth})"
            )
        }
        LogBus.log(
            LogLevel.DEBUG, LogTag.ENGINE,
            "最终 info：depth=${depth ?: "-"} seldepth=${info?.seldepth ?: 0} " +
                    "nodes=${info?.nodes ?: 0} score=${score ?: "-"} mate=${matePly ?: "-"}"
        )
        return EngineResult(
            best?.first, score, depth, matePly, best?.second,
            info?.seldepth ?: 0, info?.nodes ?: 0L,
        )
    }

    /**
     * 异步开始 ponder：`go ponder movetime T`（R2）。
     * Stockfish 语义下 movetime 是**累积搜索时长**（`elapsed = now - startTime`，ponder 期不检查、
     * 命令一发出即计时），因此：
     * - 命中：`ponderhit` 转正式搜索后 check_time 立即满足 → 即刻交 bestmove；
     * - 未命中：App 发 `stop` → 引擎自清 ponder 旗标 → 常规 bestMove 自带 movetime，实耗恰为 T。
     * 位置 = 基线 FEN + moves 全列表 + 预测敌着追加尾部（R3）→ 引擎回放至「对方行棋」局面。
     */
    fun startPonder(
        context: Context,
        baselineFen: String,
        moves: List<String>,
        predictedEnemyMove: String,
    ) {
        ensureStarted(context)
        synchronized(lock) {
            synchronized(lines) { lines.clear() }
            val ponderMoves = moves + predictedEnemyMove
            writeLine("position fen $baselineFen moves ${ponderMoves.joinToString(" ")}")
            val targetMs = com.chess.bot.data.BotConfig.data.movetimeMs
            writeLine("go ponder movetime $targetMs")
            currentInfo = null
            ponderStartNs = System.nanoTime()
            ponderMovetimeMs = targetMs
            pondering = true
        }
    }

    /**
     * go ponder 已持续毫秒数（保留备查，D3）。非 ponder 态返回 -1。
     */
    fun ponderElapsedMs(): Long = synchronized(lock) {
        if (!pondering) -1L else (System.nanoTime() - ponderStartNs) / 1_000_000L
    }

    /** 是否处 ponder 态（App 侧记录）：调用方据此决定走 [ponderHit] 还是常规 `go`。 */
    fun isPondering(): Boolean = synchronized(lock) { pondering }

    /**
     * ponder 命令送达自检（A 项，见 BotSessionEnemy.PONDER_GUARD_MS）：
     * 发 `go ponder` 后超过 [limitMs] 仍无任何 info 行 → 判定命令未被引擎接受
     *（引擎重启 / 管道异常；App 侧 pondering 与引擎状态不一致）。
     * 此时后续 ponderhit 会被引擎忽略、收割白等超时、stop 也等不到 bestmove（触发进程重建），
     * 故主动 stop 复位，让调用方走常规搜索。
     *
     * @return true = 已复位（命令丢失，调用方放弃本次预搜）；false = 引擎确有反应 / 未超时
     */
    fun abortPonderIfUnacknowledged(context: Context, limitMs: Long): Boolean {
        synchronized(lock) {
            if (!pondering) return false
            val elapsedMs = (System.nanoTime() - ponderStartNs) / 1_000_000L
            if (elapsedMs < limitMs) return false
            if (currentInfo != null) return false // 有 info = 引擎确在算，正常 ponder
            LogBus.log(
                LogLevel.WARN, LogTag.ENGINE,
                "ponder 发出 ${elapsedMs}ms 无任何 info 行，判定命令未送达引擎，stop 复位"
            )
            writeLine("stop")
            pondering = false
            // 引擎若真在 ponder，stop 后回 bestmove（错误局面，读掉即可）；没在搜则无输出。
            // 不阻塞：残留行只影响 waitFor("bestmove") 的最早命中者，而 go 前会 lines.clear()。
            synchronized(lines) { lines.clear() }
            currentInfo = null
            ponderMovetimeMs = 0
            return true
        }
    }

    /**
     * 收割 ponder 预搜：`ponderhit` 转正式搜索。`go ponder movetime T` 的 T 在 ponder 期不检查，
     * 命中后 check_time 立即满足 → 引擎几乎立刻交 bestmove，故 [monitorSearch] 以
     * `hasBestmove` 为第一退出条件；harvestDeadlineMs 与硬顶仅作兜底。
     */
    fun ponderHit(): EngineResult {
        val targetMs = com.chess.bot.data.BotConfig.data.movetimeMs
        synchronized(lock) { writeLine("ponderhit") }
        LogBus.log(
            LogLevel.DEBUG, LogTag.ENGINE,
            "ponderhit 转正式搜索，按守卫收割（预搜 movetime=${ponderMovetimeMs}ms）"
        )
        val outcome = monitorSearch(
            GoMode(targetMs),
            capStartNs = System.nanoTime(),
            harvestDeadlineMs = targetMs,
        )
        pondering = false
        return buildResult(outcome.snapshot)
    }

    /**
     * 中止 ponder（敌方未走预测着 / 中断 / 新局；ponderhit 前后均可用——后者引擎处正式搜索态，
     * stop 照常收尾）。引擎回的 bestmove 属错误局面，丢弃。
     */
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
                    LogLevel.WARN, LogTag.ENGINE,
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

    /** 单次 go 的返回：输出行快照。 */
    private class GoOutcome(val snapshot: List<String>)

    /** go 模式参数：targetMs 为主搜 movetime（也是硬顶基准）。 */
    private class GoMode(val targetMs: Int)

    private fun resolveGoMode(movetimeMs: Int?): GoMode =
        GoMode(movetimeMs ?: com.chess.bot.data.BotConfig.data.movetimeMs)

    private fun go(
        context: Context,
        baselineFen: String,
        moves: List<String>,
        movetimeMs: Int?,
    ): GoOutcome {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                // 防御：若上一次 ponder 未干净停止（stop 超时残留），先强制清掉，
                // 否则在 ponder 状态下发 go 会让引擎不发 bestmove → 等 bestmove 超时
                if (pondering) {
                    LogBus.log(LogLevel.WARN, LogTag.ENGINE, "bestMove 前引擎仍处 ponder，强制 stopPonder")
                    stopPonder()
                }
                ensureStarted(context)
                synchronized(lock) {
                    synchronized(lines) { lines.clear() }
                    val positionLine = if (moves.isEmpty()) {
                        "position fen $baselineFen"
                    } else {
                        "position fen $baselineFen moves ${moves.joinToString(" ")}"
                    }
                    writeLine(positionLine)
                    return goMovetime(resolveGoMode(movetimeMs))
                }
            } catch (e: EngineError) {
                lastError = e
                restart()
                if (attempt < 2) Thread.sleep(500)
            }
        }
        throw EngineError("引擎异常：${lastError?.message}")
    }

    /**
     * `go movetime <TARGET>` + 守卫收尾（R1）。皮卡鱼 check_time 到点自停交 bestmove，无需 App 掐表。
     * 注意：UCI 参数是**空格分隔**（`go movetime 500`），不可写 `movetime=500`——引擎按 token 解析，
     * 等号形式不识别该关键字 → 等价于无限制 `go`，永不自停。
     */
    private fun goMovetime(mode: GoMode): GoOutcome {
        currentInfo = null
        writeLine("go movetime ${mode.targetMs}")
        return monitorSearch(mode, capStartNs = System.nanoTime())
    }

    /**
     * 守卫监听循环（主搜与 ponder ponderhit 收割共用）。运行在调用方线程，5ms 轮询 currentInfo。
     * 退出条件（按序检查）：
     * ⓪ 引擎已自发 bestmove → 直接退出（引擎自停，无需 stop）；
     * ① 收割时限兜底（仅 ponderhit 段）→ stop；
     * ② 硬顶 `target + ENGINE_HARD_CAP_APPEND_MS` → stop（引擎假死 / 管道堵塞兜底）。
     *
     * ⚠️ R5：**不再有 nearMate 提前停**——info 出现 mate 一律不打断，等 movetime 走完。
     * ⚠️ DV7：本次未引入中断守卫（待批复 E）。
     */
    private fun monitorSearch(
        mode: GoMode,
        capStartNs: Long,
        harvestDeadlineMs: Int? = null,
    ): GoOutcome {
        val target = mode.targetMs
        val capMs = EngineInfoPick.hardCapMs(target)
        while (true) {
            val capElapsed = ((System.nanoTime() - capStartNs) / 1_000_000L).toInt()
            val info = currentInfo
            if (hasBestmove()) {
                LogBus.log(
                    LogLevel.DEBUG, LogTag.ENGINE,
                    "引擎 movetime 自停（${capElapsed}ms）depth=${info?.depth ?: 0}"
                )
                break
            }
            if (harvestDeadlineMs != null && capElapsed >= harvestDeadlineMs) {
                LogBus.log(
                    LogLevel.DEBUG, LogTag.ENGINE,
                    "收割时限到（${capElapsed}ms ≥ ${harvestDeadlineMs}ms）depth=${info?.depth ?: 0}"
                )
                writeLine("stop")
                break
            }
            if (capElapsed >= capMs) {
                LogBus.log(
                    LogLevel.WARN, LogTag.ENGINE,
                    "硬顶触发（${capElapsed}ms ≥ ${capMs}ms）depth=${info?.depth ?: 0} " +
                            "seldepth=${info?.seldepth ?: 0} nodes=${info?.nodes ?: 0}"
                )
                writeLine("stop")
                break
            }
            Thread.sleep(5)
        }
        waitFor("bestmove", Const.ENGINE_STOP_BESTMOVE_TIMEOUT)
        // 收到 bestmove = 引擎侧本次搜索已彻底结束：若 App 仍记为 ponder 态（命令丢失 / ponderhit
        // 被忽略），必须复位，否则后续 bestMove 的 go 会被连锁影响。主搜路径本就是 false，无副作用。
        pondering = false
        ponderMovetimeMs = 0
        val snapshot = synchronized(lines) { lines.toList() }
        return GoOutcome(snapshot)
    }

    /** 快照里是否已有 bestmove 行（引擎已结束本次搜索）。 */
    private fun hasBestmove(): Boolean = synchronized(lines) {
        lines.any { it.trim().startsWith("bestmove") }
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
                    // 同步维护 currentInfo（整体替换 immutable 实例）；解析失败/非 multipv 1 → 丢弃。
                    // R4：不再做伪影过滤/采样，引擎给什么就是什么。
                    val info = EngineInfoPick.parseInfoLine(line.trim())
                    if (info != null) currentInfo = info
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

    /** 解析 bestmove 行：返回 (move, ponderMove)；无 bestmove 行返回 null。 */
    private fun parseBestMove(snapshot: List<String>): Pair<String?, String?>? {
        for (line in snapshot) {
            if (line.startsWith("bestmove")) {
                val tokens = line.split(" ")
                if (tokens.size < 2) return null to null
                val move = tokens[1]
                val ponder = if (tokens.size >= 4 && tokens[2] == "ponder") tokens[3] else null
                return (if (move == "(none)") null else move) to ponder
            }
        }
        return null
    }

    companion object {
        private const val MAX_LINES = 2000

        @Volatile
        private var instance: PikafishEngine? = null

        fun get(): PikafishEngine =
            instance ?: synchronized(this) {
                instance ?: PikafishEngine().also { instance = it }
            }

        // 注：legacy parseScore/parseDepth/parseMatePly 已随 F2/F3 净化删除——
        // 所有分/层/mate 读取统一走 EngineInfoPick.pickFinalInfo（只取最深有效行）。
    }
}
