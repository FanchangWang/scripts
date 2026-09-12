package com.chess.bot.game

import android.content.Context
import com.chess.bot.engine.EngineResult
import com.chess.bot.engine.PikafishEngine
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.chess.bot.overlay.BotRuntime
import com.chess.bot.service.Capture
import com.chess.bot.vision.CornerDetModel
import com.chess.bot.vision.PieceClsModel
import com.chess.bot.vision.Recognizer
import com.chess.bot.vision.VisionInit
import org.opencv.core.Mat

/**
 * 对局状态机（移植 python session.py，薄控制层）。
 *
 * 单工作线程串行执行；interrupt() 可从其它线程安全调用。
 * 日志经 LogBus 推送；状态行/引擎行/棋盘快照经 BotRuntime 同步悬浮窗。
 *
 * 2026-08-28 审计改造（R3/R4/R5）：
 * - 走棋无限重试 + 指数退避（1s→10s 封顶），退出条件=对弈结束判断；
 *   内置「连续 N 整步画面零变化→暂停」守卫（防弹窗遮挡盲点误触）
 * - verify 首帧按走子动画公式等待（400+dist×60+50），后续帧 150ms 兜底；tapHold 固定 250ms
 * - 开始棋局走共享 SettleWaiter 摆棋等待（31 子持续等待 / 32 子新开局 / 其余稳定计数）；
 *   轮次确认弹窗移除：detectSide 失败→暂停+布局落盘，排局默认红先
 * - 开局库（OBK）优先：启用时全程先查书，未命中回落引擎
 *
 * 2026-08-29 性能改造（T1）：和棋弹窗检查事件化（异常帧/终局确认前才查，不再每帧全图 matchTemplate）
 * 2026-08-30 识别提速（方案 A 变种）：
 * - 维护 state.cellImgs（已提交棋盘 90 格 10x10 中心灰度小图，与 board 严格对齐）；
 *   snapshotPrev 时克隆为 prevCellImgs 作为逐格 diff 基线
 * - recognizeBoardChanged 仅对 diff 变化的格子跑模板匹配，未变格沿用 board；recog ~466ms→~20ms
 * - 提交点（initialize/verify/敌方 Moved）与 board 同步局部更新 cellImgs（仅改 changes 格子），
 *   避免「敌方提子未落子」等中间帧污染基线（全量更新会波及无关格，已弃用）
 *
 * 2026-09-06 敌着两帧一致确认（T-D）：所有敌着提交点（waitForEnemyMove MOVED / 噪声复判 /
 * verify N3/N4（SELF_THEN_ENEMY）/ 吞点击恢复）统一走 reconfirmEnemyMoved（立即复抓复判，不加显式
 * 延时——单次 grabBoard ~70-100ms 已越过半格飞行窗口，防几何合法中途帧如車 C0→C9 途经 C5 误提交）+ commitEnemyMove
 * （ponder 处理 + cellImgs 更新 + applyEnemyMove 公共路径）。
 *
 * 2026-09-09 拆分（方案 6 / D1=A，纯移动不改逻辑）：本类保留核心粘合——字段+生命周期+悬浮窗推送（emit），
 * 逻辑分组拆至同 package 扩展函数文件（类成员 private 已放宽 internal）：
 * - BotSessionFlow.kt    主循环+我方走子+ponder+评估显示+自动下一局
 * - BotSessionVerify.kt  verify v3 校验链（多帧校验/提交/吞点击恢复）
 * - BotSessionEnemy.kt   敌方链（提子恢复/敌方检测/ponder 收割/敌着提交）
 * - BotSessionEndgame.kt 终局判定（认输/OCR 结算/和棋决策；绝杀二次探测已取消）
 */
class BotSession(internal val context: Context) {

    internal val engine = PikafishEngine.get()

    @Volatile
    var running = false
        internal set // waitForEnemyMove 噪声上限暂停需跨文件写（B2）

    @Volatile
    internal var interrupted = false

    @Volatile
    internal var autoNextFlag = false

    /**
     * 绝杀提前终局（二次引擎探测已取消）：主搜 / ponder 收割的 info 判定 mate+1 时，
     * 本步着法即杀着，应用 + 截屏验证后直接终局。computeMove 写入，verify 消费后清零。
     * verify 中有两条终局信号会消费它：① SELF_DONE（走棋成功且棋盘已落定）② RESIGN_SUSPECT
     * （检测到对局结束画面，双方将/帥缺失）——只要命中其一即判「我方绝杀」并终局，
     * 阻断 doMove 重复点击（见 2026-08-29 走子后卡在重试的修复）。
     */
    internal var selfMatePending = false

    /** grabBoard「变化行」上次记录的内容（内容相同则静默，2026-09-09 日志拆分 D1=A）。
     *  2026-09-12 Q1：异常行整套状态已随性能行收敛一并删除（`lastAnomalyKey` / `lastDriftLogMs`
     *  / `GRAB_LOG_DRIFT_INTERVAL_MS` 漂移限频），本类不再有异常行记账字段。 */
    internal var lastGrabLogKey: String? = null

    /** 我方走子时引擎返回的预测敌着（ponder）；用于敌方思考期启动 ponder 预搜。 */
    internal var pendingPonderMove: String? = null

    /** 敌方走子命中预测后，ponderHit 取回的我方预搜结果；下一轮 computeMove 直接消费（省一次引擎调用）。 */
    internal var pendingPonderResult: EngineResult? = null

    /**
     * 本轮 ponder 收割出的结果是否已被采纳（A 项）：置位时机 = 命中路径的 ponderhit 收割、
     * 或 checkPonderHealth 的终止即时收割。用途：敌着提交时命中预测 → 直接消费已收割结果，不重复收割。
     */
    internal var prematurePonderHarvested = false

    /** 当前状态机阶段（BotRuntime.status 的本地镜像，emit 时推送悬浮窗）。 */
    @Volatile
    private var status: BotStatus =
        BotStatus.PAUSED // 保持 private：成员 setStatus/emit 可访问；internal 会与 fun setStatus 产生 JVM setter 签名冲突

    val state = GameState()
    val autoNextEnabled: () -> Boolean = { BotRuntime.autoNext.value }
    val statusLine: () -> String = { BotRuntime.statusLine.value }

    /** 会话级单例：homography 等缓存必须跨调用保留（对齐 python 单一 Capture）。 */
    internal val capture: Capture by lazy {
        Capture(
            context,
            shouldContinue = { running && !interrupted && !state.gameOver },
        ) {
            decideDraw()
        }
    }

    /** 视觉预热：OpenCV/校准 JSON 注入 + cls 会话懒加载（棋子识别已由 YOLO cls 替代模板）。 */
    internal fun visionWarmup() {
        VisionInit.init(context)
        PieceClsModel.ensure(context)
        // Q1（2026-09-08）：det 四角模型随启动整体预加载——几何守卫已进入正常对弈路径
        // （摆棋接受门 + 提子卡死诊断），懒加载会让首局扫描多等一次 ~700ms 推理+加载
        CornerDetModel.ensure(context)
    }
    // ---------- 公共接口 ----------

    /** 线程安全中断：打断自动对弈循环与摆棋等待。 */
    fun interrupt() {
        interrupted = true
        engine.stopPonder()
        BotRuntime.running.value = false
        status = BotStatus.PAUSED
        BotRuntime.status.value = status
    }

    fun close() {
        interrupt()
        engine.close()
    }

    // ---------- 启动 ----------

    suspend fun start() {
        // 防抖：上一次 start 未结束前忽略重复点击（单线程队列会串行执行两次全量同步）
        if (!startGuard.compareAndSet(false, true)) {
            LogBus.log(LogLevel.WARN, LogTag.PLAY, "启动流程进行中，忽略重复点击")
            return
        }
        // E 批复（2026-09-11）：这两个标志的复位必须在**拿到守卫之后**——否则「点停止 → 立刻点开始」
        // 会在上一会话仍在收尾时把 interrupted 清成 false，让正在退出的主循环误以为要继续而继续走棋
        //（emit 还会把操控条按钮打回运行态）。重复点击直接丢弃；旧会话收尾后按钮回到「开始」。
        interrupted = false
        running = true
        try {
            visionWarmup()
            state.reset()
            adoptedByRecovery = false
            engineAlreadyReset = false // U-1：新一轮启动，重置 ucinewgame 已发标志
            pendingPonderMove = null
            pendingPonderResult = null
            prematurePonderHarvested = false
            emit()
            // 统一启动等待循环（2026-09-09 U1=A）：手动开始与自动下一局共用
            // StartLoop（OCR 结算交互先行 + det 四角几何守卫 + SettleWaiter 摆棋判定内核），
            // 手动路径差异经 StartExpectation.MANUAL 注入（无超时/提子恢复有/残局免证据/
            // 不检查自动下一局开关）
            val loop = StartLoop(
                context,
                capture,
                shouldContinue = { !interrupted },
                interruptSession = { interrupt() },
                onPhase = { setStatus(it) },
                onOwnLift = { corrected, liftPos -> recoverOwnLift(corrected, liftPos) },
            )
            val ready = loop.run(StartExpectation.MANUAL)
            val corrected = when (ready) {
                null -> null
                is StartLoopResult.Adopted -> {
                    adoptedByRecovery = true
                    null
                }

                is StartLoopResult.Ready -> ready.corrected
            }
            if (corrected == null) {
                if (adoptedByRecovery) {
                    // 我方提子恢复流程已接管棋局（已初始化、已走恢复着法、轮到敌方），
                    // corrected 已由恢复流程释放，跳过 initialize/decideStartTurn 直接进入主循环
                    LogBus.log(LogLevel.INFO, LogTag.PLAY, "提子恢复已接管棋局，跳过重新初始化")
                    startFlow()
                } else {
                    running = false
                    emit()
                }
                return
            }
            try {
                if (!initialize(corrected)) {
                    running = false
                    emit()
                    return
                }
                decideStartTurn()
                emit()
            } finally {
                corrected.release()
            }
            startFlow()
        } catch (e: Exception) {
            LogBus.log(
                LogLevel.ERROR,
                LogTag.PLAY,
                "启动棋局异常：${e::class.java.simpleName}: ${e.message}"
            )
            running = false
            setStatus(BotStatus.ABNORMAL_PAUSED)
        } finally {
            startGuard.set(false)
        }
    }

    internal val startGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 提子恢复流程已接管棋局（StartLoop 返回 Adopted 时置位，start 据此跳过重新初始化）。 */
    internal var adoptedByRecovery = false

    /**
     * U-1（2026-09-11）：本次启动流程内 ucinewgame 是否已发过——提子恢复 [recoverOwnLift] 已复位 TT，
     * 紧随其后的 [startFlow] 不再重复发（同一启动流程内 ucinewgame 只发一次）。
     * [start] 入口复位，防跨次启动残留。
     */
    internal var engineAlreadyReset = false

    // waitForBoardSettled 与几何守卫助手已于 2026-09-09 U1=A 迁入统一启动循环 StartLoop.kt

    /** recoverOwnLift「将帅不在上半区」伪影告警节流（保留在 BotSession，恢复流程专用）。 */
    internal var lastLiftArtifactWarnAt = 0L

    // ---------- 初始化 ----------

    internal fun initialize(corrected: Mat): Boolean {
        setStatus(BotStatus.INITIALIZING)
        val board = Recognizer.analyzeBoard(corrected)
        val mySide = detectSide(board)
        if (mySide == null) {
            LogBus.log(
                LogLevel.ERROR,
                LogTag.VISION,
                "无法判断我方红黑方（未识别到将/帥），已暂停；请检查棋盘画面后重新同步"
            )
            // 布局落盘（替代原轮次弹窗的兜底），便于定位误识别
            val count = pieceCount(board)
            LogBus.log(LogLevel.WARN, LogTag.PLAY, "失败帧诊断：识别到 $count 个棋子")
            Recognizer.formatLayout(board)
                .forEach { LogBus.log(LogLevel.WARN, LogTag.PLAY, "识别布局 $it") }
            status = BotStatus.PAUSED
            return false
        }
        val phase = detectPhase(board, mySide)
        state.replaceBoard(board)
        state.resetCellImgs(corrected) // 开局全量重建 90 格中心小图（无动画中间帧风险）
        state.markInitialized(mySide, phase)
        LogBus.log(LogLevel.INFO, LogTag.PLAY, "我方为${mySide.cn}方，当前棋盘为${phase.cn}")
        // 摆棋布局无条件落日志（2026-09-08 意见1：Q1 类问题直接从布局定位，不再靠猜）
        LogBus.log(LogLevel.INFO, LogTag.VISION, "摆棋布局（${pieceCount(board)} 子）")
        Recognizer.formatLayout(board, mySide)
            .forEach { LogBus.log(LogLevel.DEBUG, LogTag.VISION, "摆棋布局 $it") }
        val lifts = liftCells(board)
        if (lifts.isNotEmpty()) {
            LogBus.log(LogLevel.WARN, LogTag.VISION, "摆棋帧含提子格：$lifts（不应出现，请排查）")
        }
        return true
    }

    /** 走子成功后的终局联动：仅 mate+1 → 本步即杀着直接终局（绝杀判断只认 info mate，R4/绝杀口径）。 */
    internal suspend fun endgameHook() {
        if (selfMatePending) {
            selfMatePending = false
            finishGame("我方绝杀，${state.mySide.opponent.cn}方无路可走")
        }
    }

    // ---------- 认输 / 绝杀 / 和棋 ----------

    internal var lastOcrEndScanAt = 0L
    internal var lastVerifyDrawScanAt =
        0L // verify 内和棋弹窗 OCR 检查节流（v3，复用 OCR_SUSPECT_SCAN_THROTTLE_MS）

    // ---------- 工具 ----------

    internal suspend fun grabBoard(cap: Capture): Grabbed? {
        // 统一计时日志：所有截屏识别入口（敌方检测 / 我方校验 / 重试稳判 / 认输复检）共用，便于一处查看 grab+recog 耗时。
        val tGrab = System.nanoTime()
        val corrected = cap.grab() ?: return null
        val grabMs = (System.nanoTime() - tGrab) / 1_000_000
        val tRecog = System.nanoTime()
        val scan = recognizeBoardChanged(corrected, state.prevCellImgs, state.board, state.mySide)
        val recogMs = (System.nanoTime() - tRecog) / 1_000_000
        // 行1「性能行」（2026-09-12 Q1 收敛）：仅慢帧打印，且只带时间——原事件明细
        //   （慢帧 / diff N / 暂缓 N / 剔除 N / 漂移 N）整块删除：剔除是空格被 cls 误读成 lift 的
        //   飞行途经伪影、漂移只是光效导致基线自愈，均与棋子变更无关；未确认明细改由识别侧
        //   「识别明细」开关日志承载。grep grabBoard = 纯性能异常流。
        if (grabMs > Const.GRAB_LOG_SLOW_MS) {
            LogBus.log(
                LogLevel.DEBUG,
                LogTag.VISION,
                "grabBoard grab=${grabMs}ms recog=${recogMs}ms"
            )
        }
        // 行2「变化行」（2026-09-12 Q1 收敛）：只打明确变更的棋子——置信度不足的未确认格不再并入
        //   本行；内容与上一条相同才静默。grep 棋盘变化 = 走子过程回放。
        val changeLine = scan.changes.joinToString(", ") {
            changeText(it.r, it.c, it.old, it.new, it.top1Prob, state.mySide)
        }
        if (changeLine.isNotEmpty() && changeLine != lastGrabLogKey) {
            lastGrabLogKey = changeLine
            LogBus.log(LogLevel.DEBUG, LogTag.VISION, "棋盘变化：$changeLine")
        }
        return Grabbed(corrected, scan)
    }

    internal fun finishGame(reason: String) {
        state.markGameOver()
        setStatus(BotStatus.GAME_OVER)
        LogBus.log(LogLevel.INFO, LogTag.PLAY, reason)
    }

    internal fun setStatus(next: BotStatus) {
        status = next
        BotRuntime.status.value = next
        // 离开等待摆棋态：清空等待信息，避免收起小窗残留旧计时/子数
        if (next != BotStatus.WAIT_PLACEMENT) {
            BotRuntime.waitElapsedS.value = 0
            BotRuntime.waitDetail.value = ""
        }
        emit()
    }

    /**
     * C5 批复（2026-09-11）：主循环退出时把状态收敛为「已暂停」，但**不覆盖** [BotStatus.ABNORMAL_PAUSED]。
     * 异常暂停（走棋总超时 / 等待敌方超时 / 主循环异常）代表「需人工介入」，此前被 startFlow 的 finally
     * 无条件打回 PAUSED，用户无法从状态行区分两种退出原因；保留后状态行/悬浮窗（均走 status.cn）可直接
     * 显示「异常暂停」。仍保证 emit()：running=false 必须推送出去，否则 UI 会停留在运行态。
     */
    internal fun pauseIfNotAbnormal() {
        if (status == BotStatus.ABNORMAL_PAUSED) emit() else setStatus(BotStatus.PAUSED)
    }

    internal fun emit() {
        BotRuntime.running.value = running
        BotRuntime.status.value = status
        val sideCn = if (state.initialized) "${state.mySide.cn}方" else "-"
        val phaseCn = if (state.initialized) state.phase.cn else "-"
        val stateCn = when {
            state.gameOver -> "已结束"
            !state.initialized && !running && status == BotStatus.PAUSED -> "未同步"
            else -> status.cn
        }
        // 前三段进状态行；评估分单独走数据流，悬浮窗据此着色（正=绿 负=红 0=白）
        BotRuntime.statusLine.value = "$phaseCn · $sideCn · $stateCn"
        // 等待摆棋态：状态行直接展示子数/稳定摘要（与悬浮窗等待态一致，设计稿 §二②）
        if (status == BotStatus.WAIT_PLACEMENT && BotRuntime.waitDetail.value.isNotEmpty()) {
            BotRuntime.statusLine.value = "等待摆棋 · ${BotRuntime.waitDetail.value}"
        }
        BotRuntime.evalScore.value = state.lastEvalScore
        BotRuntime.evalText.value = evalOverlayText()
        BotRuntime.moveSource.value = state.lastMoveSource
        BotRuntime.moveDepth.value = state.lastMoveDepth
        BotRuntime.lastMoveIccs.value = state.lastMove
        // 棋盘快照（防御性拷贝：悬浮窗线程与 bot 线程并发读）
        BotRuntime.board.value = copyBoard(state.board)
        // 红/黑方各保留各自最近一步：分别驱动「我方箭头 / 敌方箭头」（见棋盘小窗绘制）
        BotRuntime.mySideIsRed.value = state.mySide == Side.RED
        BotRuntime.lastSelfMovePlanned.value = state.selfPlanned
        BotRuntime.lastSelfMoveCells.value =
            if (state.selfHighlight.size == 2) state.selfHighlight[0] to state.selfHighlight[1] else null
        BotRuntime.lastEnemyMoveCells.value =
            if (state.enemyHighlight.size == 2) state.enemyHighlight[0] to state.enemyHighlight[1] else null
    }
}

/** 截屏 + 识别；持有 corrected（Mat，调用方负责 release）与 scan（BoardScan 富结构）。
 *  B1 提取为顶层（原 BotSession 嵌套类）：Verify/Enemy 等扩展函数文件需跨文件引用。 */
internal data class Grabbed(
    val corrected: Mat,
    val scan: BoardScan,
)
