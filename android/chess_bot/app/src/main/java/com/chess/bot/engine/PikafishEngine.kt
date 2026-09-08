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
 * 用于绝杀探测：matePly==1 时返回的着法本身就是杀着，无需走完再二次调用引擎验证。
 *
 * 2026-09-08 质量门控新增：seldepth/nodes（调参采样用）、qualityReached（硬顶触发时 false，
 * 上层可感知结果质量）、scoreUnreliable（全部候选 info 均带 bound，分数不可作为决策依据）。
 */
data class EngineResult(
    val move: String?,
    val scoreCp: Int,
    val depth: Int,
    val matePly: Int? = null,
    /** UCI `bestmove X ponder Y` 中的 Y：引擎预测对手的应手（ponder 预搜起点）。 */
    val ponderMove: String? = null,
    /** 选择性搜索深度（真实思考层次信号，D6-A 采样用）。 */
    val seldepth: Int = 0,
    /** 本次搜索节点数（硬计算量指标）。 */
    val nodes: Long = 0L,
    /** 结果是否质量达标（false = 硬顶兜底触发，引擎未搜透）。 */
    val qualityReached: Boolean = true,
    /** true = 最终分来自 bound 行（边界值），不可作为决策依据（和棋判断等）。 */
    val scoreUnreliable: Boolean = false,
)

/**
 * pikafish UCI 长进程客户端（移植 python engine.py）。
 *
 * - 二进制：nativeLibraryDir/libpikafish.so（APK jniLibs 方案，安装后可 exec）
 * - NNUE：assets 首启拷贝到 filesDir/pikafish.nnue；启动参数显式指定 EvalFile
 * - `quit` 只在 close() 时发送；无响应/进程退出自动重建并重试（共 3 次）
 *
 * 2026-09-08 交互重构（go infinite + 持续监听 + 质量门控 + stop）：
 * - 一律发 `go infinite`（2026-09-08 设置简化：思考模式/层数删除，DEPTH 引擎自停路径移除），
 *   App 侧监听 currentInfo：时间到且质量达标 → stop；
 *   近杀（精确 mate≤3）提前停；盲区/停更止损；硬顶（分段倍率 + 追加上限）强制停兜底；
 * - drain 线程逐行解析 info，伪影行（A1/A2 双规则）直接丢弃不更新 currentInfo；
 * - ponder 路径同步改造（2026-09-08 F1-A）：裸 `go ponder` 无限预搜（Stockfish 系 ponder 阶段
 *   不做时间检查），时长控制上移 App 侧双触发——敌方命中预测时 ponderhit，或思考超
 *   ENGINE_PONDER_CAP_MS 提前 ponderhit；两条路都进与主搜同一套 monitorSearch 质量门控
 *   （近杀提前停/盲区止损/硬顶）后 stop，qualityReached 等全字段透传回上层。
 */
class PikafishEngine private constructor() {

    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    private val lines = ArrayDeque<String>()
    private val lock = Any()

    /** 是否处于 ponder 预搜中（go ponder 已发、尚未 ponderhit/stop）。串行调用方据此决定走 ponderHit 还是重新 go。 */
    private var pondering = false

    /** go ponder 发出时刻（纳秒）：F3-A 最短总思考时长与 F2-A CAP 判断共用时钟。 */
    @Volatile
    private var ponderStartNs = 0L

    /** 最新有效 info 快照（drain 线程整体替换 immutable 实例；@Volatile 只保证引用可见）。 */
    @Volatile
    private var currentInfo: EngineInfo? = null

    /** 当前搜索的 TARGET 时长（drain 线程伪影 A1 动态阈值用；0 = 无搜索/取基线 100ms）。 */
    @Volatile
    private var searchTargetMs = 0

    /**
     * 当前搜索相位（D6 采样标注用，不影响任何判定逻辑）：
     * M=主搜 / PROBE=绝杀探测 / PONDER=go ponder 预搜段 / HIT=ponderhit 后正式搜索段。
     */
    @Volatile
    private var searchPhase = "M"

    /**
     * 本次搜索是否观测到伪深度洪泛行（d≥ENGINE_BLIND_PSEUDO_DEPTH 且 sd≤ENGINE_BLIND_PSEUDO_SELDEPTH，
     * drain 线程置位）——TT 饱和盲区的指纹，盲区提前止损用（D6 数据实证，2026-09-08）。
     */
    @Volatile
    private var pseudoFloodSeen = false

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
     * 发送局面并返回 EngineResult；无着法（终局）move=null。
     * movetimeMs 为显式覆盖（短时限重试、绝杀探测用）；
     * 缺省时按设置页「思考时间」构造 go 命令。
     *
     * 2026-09-08 设置简化：思考模式/思考层数删除（DEPTH 模式 waitFor 超时隐患 + BOTH 层数
     * 与质量门控触发时序重叠），固定走 go infinite + 质量门控 + stop。
     */
    fun bestMove(
        context: Context,
        fen: String,
        movetimeMs: Int? = null,
        phase: String = "M",
    ): EngineResult {
        val outcome = go(context, fen, movetimeMs, phase)
        return buildResult(outcome.snapshot, outcome.targetMs, outcome.qualityReached)
    }

    /** 对方在该局面是否无路可走（绝杀/困毙）。 */
    fun isMate(
        context: Context,
        fen: String,
        movetimeMs: Int = Const.ENGINE_MATE_PROBE_MS
    ): Boolean =
        bestMove(context, fen, movetimeMs = movetimeMs, phase = "PROBE").move == null

    /**
     * 快照 → EngineResult（主搜与 ponder 收割共用口径，F1-A 2026-09-08）：
     * pickFinalInfo 过滤伪影/bound 行 → 分/层/mate；bound 行的 mate 不可靠（「至少 N 步杀」
     * 边界值）不外泄；TT 盲区（picked==null）一律 0，不回退 legacy 直读（真机实证伪 mate 分污染）。
     * qualityReached 全字段透传——ponder 路径旧实现缺失此字段，Y 方案 mateInfoSolid 的
     * 「质量达标」判定在 ponder 路径形同虚设，本次修复。
     */
    private fun buildResult(snapshot: List<String>, targetMs: Int, qualityReached: Boolean): EngineResult {
        val picked = EngineInfoPick.pickFinalInfo(snapshot, targetMs)
        val info = picked?.info
        val score = info?.scoreCp ?: 0
        val depth = info?.depth ?: 0
        val matePly = if (picked != null && !picked.scoreUnreliable) info?.matePly else null
        val scoreUnreliable = picked == null || picked.scoreUnreliable

        // bestmove 永远为最终着法（引擎收到 stop 后做最终整理）；pvFirst 仅观测告警
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
            "最终 info：depth=$depth seldepth=${info?.seldepth ?: 0} " +
                    "nodes=${info?.nodes ?: 0} score=$score mate=${matePly ?: "-"} " +
                    "quality=$qualityReached" +
                    (if (scoreUnreliable) " scoreUnreliable(${if (picked == null) "无有效info" else "全bound"})" else "")
        )
        return EngineResult(
            best?.first, score, depth, matePly, best?.second,
            info?.seldepth ?: 0, info?.nodes ?: 0L,
            qualityReached, scoreUnreliable,
        )
    }

    /**
     * 异步开始 ponder（UCI 裸 `go ponder`，F1-A 2026-09-08）：在「我方走子 + 预测敌着 Y」局面
     * 无限预搜我方应手——Stockfish 系 ponder 阶段不做时间检查，时长完全由 App 侧控制。
     * 收割两个触发点（均调 [ponderHit]）：① 敌方真走 Y；② 思考超 ENGINE_PONDER_CAP_MS。
     * 敌方走别的时调 [stopPonder] 丢弃并另行常规搜索。
     */
    fun startPonder(context: Context, fenAfterMyMove: String, predictedEnemyMove: String) {
        ensureStarted(context)
        synchronized(lock) {
            synchronized(lines) { lines.clear() }
            writeLine("position fen $fenAfterMyMove moves $predictedEnemyMove")
            writeLine("go ponder")
            currentInfo = null // 丢弃主搜残留评估，ponder 期由 drain 重建（收割时读到的一定是 Q 局面 info）
            searchTargetMs = com.chess.bot.data.BotConfig.data.movetimeMs // 伪影 A1 阈值对齐主搜 TARGET
            searchPhase = "PONDER"
            pseudoFloodSeen = false
            if (Const.ENGINE_INFO_SAMPLE) {
                LogBus.log(
                    LogLevel.DEBUG, LogTag.ENGINE,
                    "INFO样本 ph=PONDER tgt=$searchTargetMs event=START"
                )
            }
            ponderStartNs = System.nanoTime()
            pondering = true
        }
    }

    /**
     * go ponder 已持续毫秒数（F2-A CAP 判断用）。非 ponder 态返回 -1（< CAP，调用方自然跳过）。
     */
    fun ponderElapsedMs(): Long = synchronized(lock) {
        if (!pondering) -1L else (System.nanoTime() - ponderStartNs) / 1_000_000L
    }

    /**
     * 收割 ponder 预搜（F1-A 2026-09-08，两个触发点共用）：发 ponderhit 转正式搜索后，
     * 走与主搜同一套 [monitorSearch] 质量门控（近杀提前停/质量达标停/盲区止损/停更止损/硬顶）。
     * F3-A：最短总思考时长从 go ponder 起算（elapsed 含 ponder 段），不足 TARGET 继续等满，
     * 质量达标才 stop——杜绝旧实现「干等 bestmove、info 无质量判断」的缺口。
     * 硬顶时钟从 ponderhit 起算（capStartNs），保证收割等待本身有界（≤hardCapMs(TARGET)）。
     */
    fun ponderHit(): EngineResult {
        val targetMs = com.chess.bot.data.BotConfig.data.movetimeMs
        val startNs = synchronized(lock) {
            writeLine("ponderhit")
            searchPhase = "HIT" // D6 采样：ponderhit 后的 info 行按正式搜索段标注
            ponderStartNs
        }
        LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "ponderhit 转正式搜索，按质量门控收割（target=$targetMs）")
        val outcome = monitorSearch(GoInfiniteMode(targetMs, null), startNs, capStartNs = System.nanoTime())
        pondering = false
        return buildResult(outcome.snapshot, outcome.targetMs, outcome.qualityReached)
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

    /** 单次 go 的返回：输出行快照 + 本次的 TARGET 时长（伪影 A1 动态阈值/pickFinalInfo 用）+ 质量是否达标。 */
    private class GoOutcome(
        val snapshot: List<String>,
        val targetMs: Int,
        val qualityReached: Boolean,
    )

    /** go 模式参数：Infinite=go infinite + 监听循环 + 质量门控 + stop（主搜与 ponder 收割共用）。 */
    private class GoInfiniteMode(
        val targetMs: Int,
        val depthTarget: Int?,
    )

    /** 按显式覆盖解析 go 形态：一律 Infinite；depthTarget 仅双限显式覆盖时使用。 */
    private fun resolveGoMode(movetimeMs: Int?): GoInfiniteMode {
        val cfg = com.chess.bot.data.BotConfig.data
        return GoInfiniteMode(movetimeMs ?: cfg.movetimeMs, null)
    }

    private fun go(
        context: Context,
        fen: String,
        movetimeMs: Int?,
        phase: String = "M"
    ): GoOutcome {
        var lastError: Exception? = null
        repeat(3) { attempt ->
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
                synchronized(lock) {
                    synchronized(lines) { lines.clear() }
                    writeLine("position fen $fen")
                    return goInfinite(resolveGoMode(movetimeMs), phase)
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

    /**
     * go infinite + 质量门控收尾（主搜入口）：重置评估快照与伪影阈值后进入 [monitorSearch]。
     */
    private fun goInfinite(mode: GoInfiniteMode, phase: String = "M"): GoOutcome {
        currentInfo = null
        searchTargetMs = mode.targetMs
        searchPhase = phase
        pseudoFloodSeen = false
        if (Const.ENGINE_INFO_SAMPLE) {
            LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "INFO样本 ph=$phase tgt=${mode.targetMs} event=START")
        }
        writeLine("go infinite")
        return monitorSearch(mode, startNs = System.nanoTime())
    }

    /**
     * 质量监听循环（主搜 go infinite 与 ponder ponderhit 收割共用，F1-A 2026-09-08）。
     * 运行在调用方线程，5ms 轮询 currentInfo，无新增线程；drain 线程并行解析并维护 currentInfo
     * （伪影行已在其侧丢弃）。近杀提前停 / 质量达标停 / 盲区止损 / 停更止损 / 硬顶兜底 → stop → 等快照。
     *
     * @param startNs   TARGET 时长与盲区/停更判定的计时起点（主搜=go 发出时刻；ponder=go ponder
     *                  发出时刻，F3-A 最短总思考时长含 ponder 段）
     * @param capStartNs 硬顶计时起点（主搜=startNs；ponder=ponderhit 时刻，收割等待本身有界）
     */
    private fun monitorSearch(mode: GoInfiniteMode, startNs: Long, capStartNs: Long = startNs): GoOutcome {
        val target = mode.targetMs
        var qualityReached = false
        var stopReason = ""
        // F4 停更检测：记录 currentInfo 最近一次「换新」（引用变化）的时刻
        var lastInfoNs = startNs
        var lastSeenInfo: EngineInfo? = null
        while (true) {
            val elapsed = ((System.nanoTime() - startNs) / 1_000_000L).toInt()
            val capElapsed = ((System.nanoTime() - capStartNs) / 1_000_000L).toInt()
            val info = currentInfo
            if (info != null && info !== lastSeenInfo) {
                lastSeenInfo = info
                lastInfoNs = System.nanoTime()
            }
            // 近杀提前停：仅精确 mate（bound 态 mate 是「至少 N 步」边界值，不可靠）
            if (EngineInfoPick.nearMate(info)) {
                qualityReached = true
                stopReason = "近杀提前停 mate=${info?.matePly}"
                LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, stopReason)
                writeLine("stop")
                break
            }
            // 盲区提前止损（D6 数据实证 2026-09-08）：TT 饱和时引擎 <100ms 内冲到伪深度后
            // 整个搜索期沉默（盲区段 200ms 后 0 新 info，55 段实证）→ 无需等满 TARGET。
            // 条件 = 到点仍无有效 info + 伪深度洪泛指纹：晚 KEEP 正常段 14/14 无指纹，零误伤；
            // 无指纹的盲区段（5/55）退回下方 TARGET 原时点止损。
            if (info == null && pseudoFloodSeen &&
                elapsed >= minOf(Const.ENGINE_BLIND_EARLY_MS, target)
            ) {
                qualityReached = false
                stopReason = "盲区提前止损（${elapsed}ms 无有效 info + 伪深度洪泛指纹）"
                LogBus.log(LogLevel.WARN, LogTag.ENGINE, stopReason)
                writeLine("stop")
                break
            }
            if (elapsed >= target) {
                val depthHit = mode.depthTarget != null && info != null &&
                        info.depth >= mode.depthTarget
                val quality = info != null && EngineInfoPick.qualityOk(info, target)
                if (quality || depthHit) {
                    qualityReached = true
                    stopReason = if (depthHit && !quality) {
                        "层数达标提前停 depth=${info?.depth ?: 0}"
                    } else {
                        "质量达标停 depth=${info.depth} seldepth=${info.seldepth} " +
                                "nodes=${info.nodes} time=${info.timeMs}"
                    }
                    LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, stopReason)
                    writeLine("stop")
                    break
                }
                // F1 TT 饱和盲区止损：TARGET 已到却始终无有效 info（伪影行全被 drain 丢弃，
                // 引擎只发 currmove 沉默）→ 立即 stop 取 bestmove。bestmove 永远为准（TT 现成
                // 好棋），qualityReached=false 如实标记；硬顶 3×TARGET 收回 1×TARGET。
                if (info == null) {
                    qualityReached = false
                    stopReason = "TT 饱和盲区止损（TARGET=${target}ms 内无有效 info）"
                    LogBus.log(LogLevel.WARN, LogTag.ENGINE, stopReason)
                    writeLine("stop")
                    break
                }
                // F4 info 停更止损（防御性）：曾有有效 info 但 >1s 无更新且已过 TARGET——
                // 引擎沉默不再产出评估，继续等只会滑向硬顶
                val staleMs = (System.nanoTime() - lastInfoNs) / 1_000_000L
                if (staleMs >= Const.ENGINE_INFO_STALE_MS) {
                    qualityReached = false
                    stopReason = "info 停更止损（${staleMs}ms 无新评估行）depth=${info.depth}"
                    LogBus.log(LogLevel.WARN, LogTag.ENGINE, stopReason)
                    writeLine("stop")
                    break
                }
            }
            // 硬顶兜底（分段倍率 + 追加上限）：go infinite / go ponder 引擎都不会自停，这是唯一防无限思考的闸门
            if (capElapsed >= EngineInfoPick.hardCapMs(target)) {
                qualityReached = false
                stopReason = "硬顶触发（质量未达标）depth=${info?.depth ?: 0} " +
                        "seldepth=${info?.seldepth ?: 0} nodes=${info?.nodes ?: 0}"
                LogBus.log(LogLevel.WARN, LogTag.ENGINE, stopReason)
                writeLine("stop")
                break
            }
            Thread.sleep(5)
        }
        // 停止原因已在各分支各自落笔（质量/近杀 DEBUG，盲区/停更/硬顶 WARN），此处不再重复
        waitFor("bestmove", Const.ENGINE_STOP_BESTMOVE_TIMEOUT)
        searchTargetMs = 0
        val snapshot = synchronized(lines) { lines.toList() }
        return GoOutcome(snapshot, target, qualityReached)
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
                    // 同步维护 currentInfo（整体替换 immutable 实例）：
                    // 解析失败 / 伪影（A1/A2 双规则）/ 非 multipv 1 → 丢弃，不污染状态快照
                    val info = EngineInfoPick.parseInfoLine(line.trim())
                    if (info != null) {
                        // 盲区指纹：伪深度行（无论后续 A1/A2 判定如何）——提前止损信号，
                        // 安全性由「止损条件同时要求 currentInfo==null」兜底（真 KEEP 一到即免疫）
                        if (info.depth >= Const.ENGINE_BLIND_PSEUDO_DEPTH &&
                            info.seldepth <= Const.ENGINE_BLIND_PSEUDO_SELDEPTH
                        ) {
                            pseudoFloodSeen = true
                        }
                        val reason = EngineInfoPick.artifactReason(info, searchTargetMs)
                        if (reason == null) currentInfo = info
                        // D6 采样（临时）：每条可解析 info 行的完整五元组 + 伪影判定结果，
                        // 供离线分析动态阈值（ph=M/PROBE/PONDER/HIT 区分搜索段）
                        if (Const.ENGINE_INFO_SAMPLE) {
                            LogBus.log(
                                LogLevel.DEBUG, LogTag.ENGINE,
                                "INFO样本 ph=$searchPhase tgt=$searchTargetMs " +
                                        "d=${info.depth} sd=${info.seldepth} " +
                                        "gap=${info.depth - info.seldepth} n=${info.nodes} t=${info.timeMs} " +
                                        "sc=${info.scoreCp} mate=${info.matePly ?: "-"} " +
                                        "b=${if (info.hasBound) 1 else 0} v=${reason ?: "KEEP"}"
                            )
                        }
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
        // 它们「无伪影过滤直读快照最后一条 info」的口径正是本次要消灭的污染源，
        // 所有分/层/mate 读取统一走 EngineInfoPick.pickFinalInfo（伪影 + bound 过滤）。
    }
}
