package com.chess.bot.engine

import android.content.Context
import com.chess.bot.data.BotConfig
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
 * - 主搜/ponder「思考时间过短」闸门（2026-09-11 04:24 引入，12:37 修正）：比较最终 info 的引擎自报 `time`
 *   与 `target*ENGINE_MIN_THINK_RATIO`；**仅当 mate 为空且 time 远低于 target** 才视为瞬时故障
 *   （管道残留/异常早退/bestmove 缓冲串扰）重搜最多 ENGINE_MIN_THINK_RETRIES 次。
 *   依据：引擎**恒思考到 movetime 才自交 bestmove**（用户实机复验 go movetime 500→info time 500 /
 *   go ponder 2000+ponderhit→info time 2000），故「mate 空 + time 远低于设定」即异常；
 *   但**强制绝杀/被将死（mate 非空）的秒回是合法行为**（log2.txt：score 99985→99999、mate 15→2
 *   每个 mate 步都秒回），闸门对 mate 非空一律放行，不误杀赢棋着法。
 *   ponder 同样受本闸门约束（用 info time 而非 App 墙钟，因墙钟仅含 ponderhit→bestmove 一小段、
 *   且 ponder 的 info time 含累积时长）；重试封顶避免死循环；interrupted（E 守卫）整体作废不重试。
 * - position 改「基线 FEN + moves 全列表」（R3）：App 不逐手拼完整 FEN，
 *   halfmove / 重复局面由引擎按 moves 自算。
 * - ponder 改 `go ponder movetime T`（R2）：movetime 是累积搜索时长（ponder 期不检查），
 *   命中 ponderhit 即刻交手；硬顶 = TARGET + ENGINE_HARD_CAP_APPEND_MS 仅作兜底（R6）。
 *   命令送达由 [abortPonderIfUnacknowledged] 自检（A 项）。
 * - drain 线程只解析 info 维护 currentInfo，不再做任何过滤/采样。
 * - D4（2026-09-11）：writeLine/drain 两处埋点记录 UCI 收发，由运行时开关
 *   `BotConfig.data.debugUciTrace` 控制（设置页「调试日志」分组，默认关闭）。
 * - E（2026-09-11）：`bestMove` 支持 `interrupted` 回调——[monitorSearch] **最优先**轮询到中断即 `stop`，
 *   用户点「停止」后不再空转至 movetime / 硬顶；且该判定先于 bestmove 检查，结果由调用方整体丢弃。
 */
class PikafishEngine private constructor() {

    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    private val lines = ArrayDeque<String>()
    private val lock = Any()

    /** 是否处于 ponder 预搜中（go ponder 已发、尚未 ponderhit/stop）。串行调用方据此决定走 ponderHit 还是重新 go。 */
    private var pondering = false

    /** go ponder 发出时刻（纳秒）：命令送达自检 [abortPonderIfUnacknowledged] 的计时基准。 */
    @Volatile
    private var ponderStartNs = 0L

    /** 本次 ponder 的 movetime（ms），仅日志用。 */
    @Volatile
    private var ponderMovetimeMs = 0

    /** ponder 局面快照（startPonder 时存，供 ponderHit 异常早退重搜，2026-09-11 04:24 修正）：基线 FEN / 我方 moves / 预测敌着。 */
    private var ponderFen: String? = null
    private var ponderMovesList: List<String>? = null
    private var ponderPredicted: String? = null

    /** 最近一次 `go` / `go ponder` 发出时刻（纳秒）：UCI 埋点据此把 bestmove 到达配对成真实耗时（D4）。 */
    @Volatile
    private var lastGoSentNs = 0L

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
     *
     * @param interrupted E 中断守卫（2026-09-11）：返回 true 时立刻 `stop` 并收尾返回，
     *   不再等满 movetime —— 用户点「停止」/ 会话中断时不再空转最多 `TARGET + 1000ms`。
     *   E 批复（2026-09-11）：优先级最高（先于 bestmove），结果是「已作废」的，调用方必须丢弃。
     */
    fun bestMove(
        context: Context,
        baselineFen: String,
        moves: List<String>,
        movetimeMs: Int? = null,
        interrupted: (() -> Boolean)? = null,
    ): EngineResult {
        val outcome = go(context, baselineFen, moves, movetimeMs, interrupted)
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
                    "nodes=${info?.nodes ?: 0} score=${score ?: "-"} mate=${matePly ?: "-"} time=${info?.timeMs ?: "-"}"
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
     * - 命中：`ponderhit` 转正式搜索后引擎继续算满 T 才自交 bestmove（info time=2000=T，
     *   非「即刻交手」；实机复验见 2026-09-11 04:24），故收割走 bestmove 第一退出条件；
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
            // 存局面供 ponderHit 异常早退时重搜（2026-09-11 04:24 修正：ponder 也受「思考时间过短」闸门约束）
            ponderFen = baselineFen
            ponderMovesList = moves
            ponderPredicted = predictedEnemyMove
        }
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
     * 收割 ponder 预搜：`ponderhit` 转正式搜索。`go ponder movetime T` 的 T 是累积搜索时长
     *（ponder 期即计时，命中后引擎继续算满 T 才自交 bestmove；用户 04:24 实机复验 info time=2000=T），
     * 故 [monitorSearch] 以 `hasBestmove` 为第一退出条件；harvestDeadlineMs 与硬顶仅作兜底。
     */
    fun ponderHit(): EngineResult {
        val targetMs = com.chess.bot.data.BotConfig.data.movetimeMs
        // 「思考时间过短」闸门同样适用于 ponder（2026-09-11 04:24 引入，12:37 修正）：引擎恒思考到 movetime，
        // ponderhit 后 info time 仍≈target（用户实机复验 go ponder 2000+ponderhit→info time 2000）；
        // 异常早退（mate 空且 info time 远低于 target）则重搜，mate 非空的强制绝杀秒回合法放行。
        // - 首次：引擎仍在 ponder，发 ponderhit 收割；
        // - 重试：引擎已交 bestmove 并复位，改从已知局面（moves + 预测敌着 = 敌方实际已走子）
        //   发正常 go movetime 重搜（对手着法此刻已知，正常搜索口径正确）。
        val outcome = searchGuarded(targetMs, null) {
            synchronized(lock) {
                if (pondering) {
                    writeLine("ponderhit")
                } else {
                    // 捕获到局部 val，规避 var 在 lambda 内无法智能转换
                    val fen = ponderFen
                    val pmoves = ponderMovesList
                    val pred = ponderPredicted
                    if (fen != null && pmoves != null && pred != null) {
                        synchronized(lines) { lines.clear() }
                        currentInfo = null
                        val pos = "position fen $fen moves ${(pmoves + pred).joinToString(" ")}"
                        writeLine(pos)
                        writeLine("go movetime $targetMs")
                    } else {
                        LogBus.log(
                            LogLevel.ERROR, LogTag.ENGINE,
                            "ponderHit 重试时局面丢失，无法重搜（info time 异常但跳过）"
                        )
                    }
                }
            }
            // 首次走 ponder 收割时限；重试已是正常搜索，仅硬顶兜底（harvestDeadlineMs=null）
            monitorSearch(
                GoMode(targetMs),
                capStartNs = System.nanoTime(),
                harvestDeadlineMs = if (pondering) targetMs else null,
            )
        }
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

    /** 单次 go 的返回：输出行快照 + 实测思考耗时（自 go 发出到 bestmove 到达，ms）。 */
    private class GoOutcome(val snapshot: List<String>, val elapsedMs: Int)

    /** go 模式参数：targetMs 为主搜 movetime（也是硬顶基准）。 */
    private class GoMode(val targetMs: Int)

    private fun resolveGoMode(movetimeMs: Int?): GoMode =
        GoMode(movetimeMs ?: com.chess.bot.data.BotConfig.data.movetimeMs)

    private fun go(
        context: Context,
        baselineFen: String,
        moves: List<String>,
        movetimeMs: Int?,
        interrupted: (() -> Boolean)?,
    ): GoOutcome {
        val mode = resolveGoMode(movetimeMs)
        var engineAttempt = 0 // 进程崩溃重启计数（EngineError 兜底，最多 2 次）
        while (true) {
            try {
                // 防御：若上一次 ponder 未干净停止（stop 超时残留），先强制清掉，
                // 否则在 ponder 状态下发 go 会让引擎不发 bestmove → 等 bestmove 超时
                if (pondering) {
                    LogBus.log(
                        LogLevel.WARN,
                        LogTag.ENGINE,
                        "bestMove 前引擎仍处 ponder，强制 stopPonder"
                    )
                    stopPonder()
                }
                ensureStarted(context)
                // 「思考时间过短」闸门（2026-09-11 04:24 修正）：用 searchGuarded 包裹，
                // 比较引擎 info 自报 time 与 target*ratio；过短则重搜（详见 searchGuarded）。
                return searchGuarded(mode.targetMs, interrupted) {
                    synchronized(lock) {
                        synchronized(lines) { lines.clear() }
                        currentInfo = null
                        val positionLine = if (moves.isEmpty()) {
                            "position fen $baselineFen"
                        } else {
                            "position fen $baselineFen moves ${moves.joinToString(" ")}"
                        }
                        writeLine(positionLine)
                        goMovetime(mode, interrupted)
                    }
                }
            } catch (e: EngineError) {
                if (engineAttempt >= 2) throw EngineError("引擎异常：${e.message}")
                engineAttempt++
                restart()
                Thread.sleep(500)
            }
        }
    }

    /**
     * 带「思考时间过短」闸门的搜索（主搜与 ponder 收割共用，2026-09-11 04:24 引入，12:37 修正）：
     * 比较最终 info 的引擎自报 `time`（UCI 字段，= 引擎真实搜索耗时，含 ponder 累积时长）
     * 与 `targetMs * ENGINE_MIN_THINK_RATIO`；**仅当 mate 为空且**过短且未中断且未达重试上限
     * → 重发搜索（[issue] 重跑）。
     *
     * 依据（用户 04:24 实机复验，推翻原「判和棋早退」猜测）：引擎**恒思考到 movetime 才自交 bestmove**——
     * `go movetime 500` → info time 500；`go ponder movetime 2000` + ponderhit → info time 2000。
     * 故「mate 空 + time 远低于设定」即属**瞬时故障**（管道残留 / 异常早退 / bestmove 缓冲串扰），重搜可自愈
     *（本例 15ms 的 `e2g0` 重搜即得正确的 `c0a2`）。
     * - 用引擎自报 `time`（非 App 墙钟）：ponder 段墙钟仅含 ponderhit→bestmove 一小段，
     *   但 `time` 含整个 ponder 累积时长，故闸门对 ponder 同样有效（不忽略 ponder）。
     * - **mate 非空（强制绝杀/被将死）秒回合法**：log2.txt 中 score 99985→99999、mate 15→2 每个 mate 步
     *   都秒回，引擎已得确定结论，不判异常、不重搜（修正前误把赢棋着法标「不可靠」，见 12:37 复盘）；
     * - 唯一着法等合法瞬时返回：引擎仍思考满 movetime，info time≈target，不误触；
     * - 重试封顶 `ENGINE_MIN_THINK_RETRIES`：避免死循环；
     * - `interrupted` 为真（E 中断守卫）结果整体作废，不重试。
     * - info time 缺失（无有效 info 行，极端终局）→ 无法判定，不重搜。
     */
    private fun searchGuarded(
        targetMs: Int,
        interrupted: (() -> Boolean)?,
        issue: () -> GoOutcome,
    ): GoOutcome {
        var thinkRetry = 0 // 「思考时间过短」重搜计数（最多 ENGINE_MIN_THINK_RETRIES 次）
        while (true) {
            val outcome = issue()
            val finalInfo = EngineInfoPick.pickFinalInfo(outcome.snapshot)
            val infoTime = finalInfo?.timeMs ?: -1
            // 强制绝杀/被将死（matePly 非空）的秒回是引擎合法行为（log2.txt：score 99985→99999、
            // mate 15→2，每个 mate 步都秒回），不能判异常；只有「mate 为空 且 info time 远低于
            // target」才属瞬时故障（管道残留/异常早退/bestmove 缓冲串扰），重搜可自愈。
            // 依据（2026-09-11 12:37 用户实机复盘）：绝杀/少数子局面合法秒回，纯靠 time 比对会误杀
            // 赢棋着法；mate 非空即视为已有确定结论，秒回也信任。
            val mateResolved = finalInfo?.matePly != null
            val tooFast =
                !mateResolved && infoTime > 0 && infoTime < targetMs * Const.ENGINE_MIN_THINK_RATIO
            val notInterrupted = interrupted == null || !interrupted()
            if (tooFast && notInterrupted && thinkRetry < Const.ENGINE_MIN_THINK_RETRIES) {
                thinkRetry++
                LogBus.log(
                    LogLevel.WARN, LogTag.ENGINE,
                    "引擎提前返回（info time ${infoTime}ms < ${targetMs}ms*${Const.ENGINE_MIN_THINK_RATIO}），重搜" +
                            "（$thinkRetry/${Const.ENGINE_MIN_THINK_RETRIES}）" +
                            " depth=${finalInfo?.depth ?: "-"} seldepth=${finalInfo?.seldepth ?: 0}" +
                            " score=${finalInfo?.scoreCp ?: "-"} nodes=${finalInfo?.nodes ?: 0}"
                )
                continue
            }
            if (tooFast) {
                LogBus.log(
                    LogLevel.ERROR, LogTag.ENGINE,
                    "引擎连续 ${thinkRetry + 1} 次提前返回（info time ${infoTime}ms，mate 空），疑似瞬时故障/管道串扰，着法可能不可靠"
                )
            }
            return outcome
        }
    }

    /**
     * `go movetime <TARGET>` + 守卫收尾（R1）。皮卡鱼 check_time 到点自停交 bestmove，无需 App 掐表。
     * 注意：UCI 参数是**空格分隔**（`go movetime 500`），不可写 `movetime=500`——引擎按 token 解析，
     * 等号形式不识别该关键字 → 等价于无限制 `go`，永不自停。
     * [interrupted] 见 [monitorSearch]（E 中断守卫）。
     */
    private fun goMovetime(mode: GoMode, interrupted: (() -> Boolean)?): GoOutcome {
        currentInfo = null
        writeLine("go movetime ${mode.targetMs}")
        return monitorSearch(mode, capStartNs = System.nanoTime(), interrupted = interrupted)
    }

    /**
     * 守卫监听循环（主搜与 ponder ponderhit 收割共用）。运行在调用方线程，5ms 轮询 currentInfo。
     * 退出条件（按序检查）：
     * ⓪ 中断请求（[interrupted]）→ stop 收尾（E 中断守卫，**优先于 bestmove**）；
     * ① 引擎已自发 bestmove → 直接退出（引擎自停，无需 stop）；
     * ② 收割时限兜底（仅 ponderhit 段）→ stop；
     * ③ 硬顶 `target + ENGINE_HARD_CAP_APPEND_MS` → stop（引擎假死 / 管道堵塞兜底）。
     *
     * ⚠️ R5：**不再有 nearMate 提前停**——info 出现 mate 一律不打断，等 movetime 走完。
     *
     * @param interrupted E 中断守卫（2026-09-11，主搜专用）：非空且返回 true 时立即 stop。
     *   E 批复（2026-09-11）：判定**置于 bestmove 之前**——用户主动点「停止」即整体作废本次搜索，
     *   即便引擎刚好算完也不再采用该结果；调用方按中断语义丢弃（见 BotSessionFlow.computeMove）。
     */
    private fun monitorSearch(
        mode: GoMode,
        capStartNs: Long,
        harvestDeadlineMs: Int? = null,
        interrupted: (() -> Boolean)? = null,
    ): GoOutcome {
        val target = mode.targetMs
        val capMs = EngineInfoPick.hardCapMs(target)
        while (true) {
            val capElapsed = ((System.nanoTime() - capStartNs) / 1_000_000L).toInt()
            val info = currentInfo
            // E 中断守卫（2026-09-11，E 批复前置）：外部要求立即停（用户点「停止」/ 会话中断）→
            // 最优先判定，即使引擎此刻刚交出 bestmove 也不采用（结果整体作废）。
            // 引擎收到 stop 会尽快回 bestmove；若已自停则 bestmove 已在 lines 中，waitFor 立即命中。
            if (interrupted != null && interrupted()) {
                LogBus.log(
                    LogLevel.DEBUG, LogTag.ENGINE,
                    "中断请求，stop 引擎搜索（已搜 ${capElapsed}ms）depth=${info?.depth ?: 0}"
                )
                writeLine("stop")
                break
            }
            // 正常自停（引擎思考到 movetime 自交 bestmove）不打印日志（2026-09-11 13:24 Q2）；
            // 异常自停（思考时间过短）由 searchGuarded 的「重搜」WARN / 连续超限 ERROR 体现
            if (hasBestmove()) break
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
        val elapsedMs = ((System.nanoTime() - capStartNs) / 1_000_000L).toInt()
        val snapshot = synchronized(lines) { lines.toList() }
        return GoOutcome(snapshot, elapsedMs)
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
            // D4 埋点：逐行记录发出的 UCI 命令；`go`/`go ponder` 另记时刻，供 bestmove 到达配对算真实耗时
            if (BotConfig.data.debugUciTrace) {
                if (line.startsWith("go ")) lastGoSentNs = System.nanoTime()
                LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "UCI→ $line")
            }
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
                    // D4 埋点：bestmove 到达 + 自最近一次 go 起的耗时（观察点 1 直接取证；配对失败记 "-"）
                    if (BotConfig.data.debugUciTrace && line.startsWith("bestmove")) {
                        val sent = lastGoSentNs
                        val dt = if (sent == 0L) -1L else (System.nanoTime() - sent) / 1_000_000L
                        LogBus.log(
                            LogLevel.DEBUG, LogTag.ENGINE,
                            "UCI← ${line.trim()}（自最近一次 go 起 ${if (dt < 0) "-" else "${dt}ms"}）"
                        )
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
