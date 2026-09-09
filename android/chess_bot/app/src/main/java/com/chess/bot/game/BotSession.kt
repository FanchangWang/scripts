package com.chess.bot.game

import android.content.Context
import com.chess.bot.book.ObkBook
import com.chess.bot.data.BotConfig
import com.chess.bot.engine.EngineError
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
import com.chess.bot.vision.TextMatcher
import com.chess.bot.vision.VisionInit
import kotlinx.coroutines.delay
import org.opencv.core.Mat
import kotlin.math.roundToInt

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
 */
class BotSession(private val context: Context) {

    private val engine = PikafishEngine.get()

    @Volatile
    var running = false
        private set

    @Volatile
    private var interrupted = false

    @Volatile
    private var autoNextFlag = false

    /**
     * 绝杀探测（Option A）：主搜 go 已返回 mate+1 时，本步着法即杀着，
     * 应用 + 截屏验证后直接终局、跳过二次引擎调用。computeMove 引擎路径写入，verify 消费后清零。
     * verify 中有两条终局信号会消费它：① SELF_DONE（走棋成功且棋盘已落定）② RESIGN_SUSPECT
     * （检测到对局结束画面，双方将/帥缺失）——只要命中其一即判「我方绝杀」并终局，
     * 阻断 doMove 重复点击（见 2026-08-29 走子后卡在重试的修复）。
     */
    private var selfMatePending = false

    /**
     * Y 方案 mate 记账（2026-09-08）：本轮着法来自引擎且其 info 质量达标、matePly 非空且非 1
     * （≥2 = 我方尚需 N 步杀；负值 = 我方被将死序列中）→ 引擎确证对方必有应手、对局必续，
     * endgameHook 跳过 200ms 绝杀二次探测。matePly==1 走 selfMatePending；
     * 无 mate / TT 盲区（picked==null）/ 开局库着法 → false，探测照常（保困毙 + 盲区漏判兜底）。
     * 每轮 computeMove 重置；由 [recordMateInfo] 在主搜与 ponderHit 两条引擎路径写入。
     */
    private var mateInfoSolid = false

    /**
     * 绝杀探测上次「跳过原因」（日志降噪，2026-09-09 D3）：常态每步都走同一条跳过路径，
     * 仅当原因变化（子数跨越探测窗口阈值 / mate 记账翻转 / 探测真正执行）时打一条 DEBUG。
     */
    private var lastProbeSkip: String? = null

    /** grabBoard「变化行」上次记录的内容（内容相同则静默，2026-09-09 日志拆分 D1=A）。 */
    private var lastGrabLogKey: String? = null

    /** grabBoard「异常行」上次事件指纹（非慢帧部分连续相同只打首条，变化行打印时重置；2026-09-09 R5=A）。 */
    private var lastAnomalyKey: String? = null

    /** grabBoard 异常行上次漂移打点时刻（单调 ms）；漂移限频 GRAB_LOG_DRIFT_INTERVAL_MS 内不重复打（R7）。 */
    private var lastDriftLogMs = 0L

    /** 我方走子时引擎返回的预测敌着（ponder）；用于敌方思考期启动 ponder 预搜。 */
    private var pendingPonderMove: String? = null

    /** 敌方走子命中预测后，ponderHit 取回的我方预搜结果；下一轮 computeMove 直接消费（省一次引擎调用）。 */
    private var pendingPonderResult: EngineResult? = null

    /**
     * F2-A（2026-09-08）：CAP 提前收割标志——敌方思考超 Const.ENGINE_PONDER_CAP_MS 时已在
     * waitForEnemyMove 轮询里 ponderHit 收割并缓存进 [pendingPonderResult]。敌着提交时：
     * 命中预测 → 直接消费缓存；未命中 → 作废缓存（结果属「Q=我方走子+预测敌着」局面）。
     */
    private var prematurePonderHarvested = false

    /** 当前状态机阶段（BotRuntime.status 的本地镜像，emit 时推送悬浮窗）。 */
    @Volatile
    private var status: BotStatus = BotStatus.PAUSED

    val state = GameState()
    val autoNextEnabled: () -> Boolean = { BotRuntime.autoNext.value }
    val statusLine: () -> String = { BotRuntime.statusLine.value }

    /** 会话级单例：homography 等缓存必须跨调用保留（对齐 python 单一 Capture）。 */
    private val capture: Capture by lazy {
        Capture(
            context,
            shouldContinue = { running && !interrupted && !state.gameOver },
        ) {
            decideDraw()
        }
    }

    /** 视觉预热：OpenCV/校准 JSON 注入 + cls 会话懒加载（棋子识别已由 YOLO cls 替代模板）。 */
    private fun visionWarmup() {
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
        interrupted = false
        running = true
        // 防抖：上一次 start 未结束前忽略重复点击（单线程队列会串行执行两次全量同步）
        if (!startGuard.compareAndSet(false, true)) {
            LogBus.log(LogLevel.WARN, LogTag.PLAY, "启动流程进行中，忽略重复点击")
            return
        }
        try {
            visionWarmup()
            state.reset()
            adoptedByRecovery = false
            pendingPonderMove = null
            pendingPonderResult = null
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

    private val startGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 提子恢复流程已接管棋局（StartLoop 返回 Adopted 时置位，start 据此跳过重新初始化）。 */
    private var adoptedByRecovery = false

    // waitForBoardSettled 与几何守卫助手已于 2026-09-09 U1=A 迁入统一启动循环 StartLoop.kt

    /** recoverOwnLift「将帅不在上半区」伪影告警节流（保留在 BotSession，恢复流程专用）。 */
    private var lastLiftArtifactWarnAt = 0L

    /** 首局轮次判定（审计 §二.E 三路径；轮次确认弹窗已删除）。 */
    private fun decideStartTurn() {
        val count = pieceCount(state.board)
        if (count == 32 && plausibleNewGame(state.board, state.mySide)) {
            state.turn = Side.RED
            LogBus.log(LogLevel.INFO, LogTag.PLAY, "完整新开局（32 子默认位），红方先走")
            return
        }
        val inferred = inferTurn(state.board, state.mySide, state.phase)
        if (inferred != null) {
            state.turn = inferred
            LogBus.log(
                LogLevel.INFO, LogTag.PLAY,
                if (inferred == state.mySide) "轮次推断：轮到${inferred.cn}方（我方）走棋"
                else "轮次推断：轮到${inferred.cn}方走棋"
            )
        } else {
            // 闯关排局 / 残局：无法静态推断轮次，默认我方（玩家）先行
            state.turn = state.mySide
            LogBus.log(
                LogLevel.INFO,
                LogTag.PLAY,
                "无法推断轮次（${state.phase.cn}），默认我方（${state.mySide.cn}）先走"
            )
            // 布局不在此打印：initialize 已无条件落「摆棋布局」（2026-09-08 Q1 去重）
        }
    }

    // ---------- 我方提子恢复（2026-09-08 game_start_lift_recovery_plan） ----------

    /**
     * 摆棋等待中检测到我方半区 lift（此前我方走棋失败，棋子被提起未落）时的恢复流程。
     * 仅手动开始路径触发（StartLoop 手动策略 allowOwnLiftRecovery=true；自动下一局新局
     * 理论上不存在我方提子，下半区提子帧按过渡等待，见 StartLoop）。
     *
     * 步骤：
     * 1. 重新接管会话语义（state.reset；StartLoop 入口此前的旧局状态一并清除）；
     * 2. 向上逐 px cls 扫描识别提起子身份（悬浮棋子位于格子上半部，无需先验猜测，D1）；
     * 3. 恢复棋盘（提起子落回原格）并按 initialize 语义接管状态（resetCellImgs 基线、
     *    turn=我方；恢复着法计入 moveCount，D4=A）；
     * 4. 复用 doMove 主链路走恢复着法（开局库→皮卡鱼）：源格==提子格时子已在手，
     *    首击直接补落目标格（dstOnlySrc）；源格为其他棋子时常规两击——点源格会令提起的
     *    棋子被 App 自动落回原格，其 diff 为「lift 外观→棋子」但读数==已提交值 →
     *    走 driftCells 自愈刷新基线，不进 changes、不干扰帧分类（v3 白名单天然兼容）。
     *
     * Mat 所有权归本函数，所有路径 finally 释放。
     */
    private suspend fun recoverOwnLift(correctedOwned: Mat, liftPos: Pair<Int, Int>): LiftRecovery {
        try {
            state.reset()
            pendingPonderMove = null
            pendingPonderResult = null
            val (lr, lc) = liftPos
            val raw = Recognizer.analyzeBoard(correctedOwned)
            val abovePiece = if (lr > 0) raw[lr - 1][lc] else null
            val piece = Recognizer.identifyLiftedPiece(correctedOwned, lr, lc, abovePiece)
            if (piece == null || piece == Const.LIFT) {
                LogBus.log(
                    LogLevel.WARN, LogTag.PLAY,
                    "提子身份识别失败（r$lr c$lc 向上扫描无有效棋子读数），下帧重试"
                )
                return LiftRecovery.RETRY
            }
            val mySide = pieceColor(piece)
            // 合理性校验：对方将/帅须在敌方半区（防飞行棋子途经瞬态伪触发 OwnLift）
            val enemyGeneral = if (mySide == Side.RED) "b_k" else "r_K"
            val enemyGeneralTop = (0 until Const.OWN_HALF_MIN_ROW).any { r ->
                (0 until COLS).any { c -> raw[r][c] == enemyGeneral }
            }
            if (!enemyGeneralTop) {
                // B（G1=B）：该告警在缩小棋盘误读为提子时会逐帧重试刷屏（真机日志 10+ 条），
                // 加 3s 节流；根因由提子卡死诊断门（OwnLiftStalled → det）兜底
                val now = System.nanoTime()
                if (now - lastLiftArtifactWarnAt > 3_000_000_000L) {
                    lastLiftArtifactWarnAt = now
                    LogBus.log(
                        LogLevel.WARN, LogTag.PLAY,
                        "提子识别为${pieceLabel(piece)}但对方将帅不在上半区，疑似动画伪影，下帧重试"
                    )
                }
                return LiftRecovery.RETRY
            }
            // 恢复棋盘并接管状态（对齐 initialize 语义：replaceBoard 归一化 lift 后写回提起子）
            raw[lr][lc] = piece // analyzeBoard 每次返回新分配数组，可原地修改
            state.replaceBoard(raw) // 其余残留 lift 格（若有瞬态伪影）一并归一化为 null
            state.board[lr][lc] = piece
            state.markInitialized(mySide, detectPhase(state.board, mySide))
            state.turn = mySide
            state.resetCellImgs(correctedOwned)
            LogBus.log(
                LogLevel.INFO, LogTag.PLAY,
                "我方提子识别为${pieceLabel(piece)}（r$lr c$lc），棋盘已恢复，开始走恢复着法"
            )
            Recognizer.formatLayout(state.board, mySide)
                .forEach { LogBus.log(LogLevel.DEBUG, LogTag.VISION, "恢复布局 $it") }
            emit()
            engine.newGame(context)
            if (!doMove(dstOnlySrc = liftPos)) {
                LogBus.log(
                    LogLevel.ERROR, LogTag.PLAY,
                    "提子恢复走子未完成（doMove 失败），中止启动；处理后可重新点「开始」"
                )
                return LiftRecovery.ABORT
            }
            LogBus.log(
                LogLevel.INFO, LogTag.PLAY,
                "提子恢复走子完成，轮到${state.turn.cn}方，对弈继续"
            )
            return LiftRecovery.ADOPTED
        } finally {
            correctedOwned.release()
        }
    }

    // ---------- 自动对弈主循环 ----------

    private suspend fun startFlow() {
        running = true
        emit()
        try {
            engine.newGame(context)
            flowLoop()
        } catch (e: Exception) {
            LogBus.log(
                LogLevel.ERROR,
                LogTag.PLAY,
                "自动对弈异常终止：${e::class.java.simpleName}: ${e.message}"
            )
            setStatus(BotStatus.ABNORMAL_PAUSED)
        } finally {
            running = false
            setStatus(BotStatus.PAUSED)
        }
        LogBus.log(LogLevel.DEBUG, LogTag.PLAY, "对弈主循环已退出")
    }

    private suspend fun flowLoop() {
        while (true) {
            if (!running || interrupted) break
            state.snapshotPrev()
            if (state.turn != state.mySide) {
                setStatus(BotStatus.WAIT_ENEMY)
                waitForEnemyMove()
            } else {
                setStatus(BotStatus.WAIT_SELF)
                if (!doMove()) {
                    if (state.gameOver) {
                        LogBus.log(LogLevel.DEBUG, LogTag.PLAY, "我方走棋阶段检测到对局结束")
                    } else if (running) {
                        // 走棋失败但未结束：doMove 内部已按守卫/中断处理并落日志
                        LogBus.log(
                            LogLevel.WARN,
                            LogTag.PLAY,
                            "走棋中止，自动对弈已暂停，可点击「开始」续弈"
                        )
                    }
                    break
                } else {
                    // 我方走子成功：若引擎给出预测敌着，启动 ponder 在敌方思考期预搜我方应手
                    maybeStartPonder()
                }
            }
            if (state.gameOver) {
                setStatus(BotStatus.GAMEOVER_CHECK)
                if (autoNextEnabled()) {
                    if (!autoNextGame()) break
                } else {
                    LogBus.log(LogLevel.WARN, LogTag.NEXT, "自动下一局未开启")
                    break
                }
            }
        }
        emit()
    }

    // ---------- 初始化 ----------

    private fun initialize(corrected: Mat): Boolean {
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

    // ---------- 我方走棋（无限重试 + 守卫） ----------

    /**
     * 我方走棋（无限重试 + 守卫）。
     * @param dstOnlySrc 提子恢复场景（2026-09-08）传提子格坐标：着法源格==该格时棋子已在手，
     * 首击直接补落目标格（跳过点源格——对提着中的棋子再点源格行为不可控）。
     */
    private suspend fun doMove(dstOnlySrc: Pair<Int, Int>? = null): Boolean {
        val pending = computeMove() ?: return false
        val unpacked = unpackMove(pending.move) ?: return false
        val (r1, c1, r2, c2, piece) = unpacked
        state.resignStreak = 0
        var zeroChange = 0
        // 提子恢复：源格==提子格 → 棋子已在手，首轮直接只点目标格
        var dstOnly = dstOnlySrc != null && r1 == dstOnlySrc.first && c1 == dstOnlySrc.second
        var attempt = 0
        while (true) {
            attempt++
            if (!running || interrupted) return false
            if (state.gameOver) {
                LogBus.log(LogLevel.DEBUG, LogTag.SELF, "对局已结束，停止走棋重试")
                return false
            }
            // v3：attemptMove 纯点击（稳判职责已移入 verify）；仅 RETRY_DST（提起未落）时只点目标格补落
            val tapped = if (dstOnly) {
                capture?.tap(r2, c2) ?: false
            } else {
                attemptMove(r1, c1, r2, c2)
            }
            if (!tapped) {
                // 点按注入失败：保留一个固定延迟兜底，避免零延迟自旋占满 CPU；
                // 正常走子路径由 verifyForSelfMove 首帧 delay(firstWaitMs) 约 700ms+ 节流，无需额外退避。
                LogBus.log(
                    LogLevel.WARN,
                    LogTag.SELF,
                    "走棋注入失败（第 $attempt 次），${Const.RETRY_BACKOFF_START_MS}ms 后重试",
                )
                delay(Const.RETRY_BACKOFF_START_MS)
                continue
            }
            val outcome = verifyForSelfMove(r1, c1, r2, c2, piece)
            when (outcome) {
                VerifyOutcome.DONE_OK -> {
                    state.moveCount++
                    return true
                }

                VerifyOutcome.DONE_END -> {
                    // 绝杀/认输导致对局结束：本步着法已成功落子（结束画面出现即证据），
                    // 视为成功走棋返回 true，让 flowLoop 继续走到 autoNext 自动下一局流程；
                    // 仅当对局并非因本步结束（外部中断/停止导致 gameOver 仍 false）时才返回 false 终止。
                    if (state.gameOver) {
                        state.moveCount++
                        return true
                    }
                    return false
                }

                VerifyOutcome.RETRY_DST -> {
                    // 我方提子未落：补点目标格。首轮为进度态清零守卫；连续两轮补点仍未落
                    // = 无进展，计入零变化守卫（防「点目标格始终无效」死循环）
                    if (dstOnly) zeroChange++ else zeroChange = 0
                    dstOnly = true
                }

                VerifyOutcome.RETRY_AFTER_ENEMY -> {
                    // 点击被吞、敌方已先走（敌着已在 verify 恢复分支提交，轮到我方）：
                    // 立即重试本步走子，不计零变化守卫（2026-09-06 T-B）。
                    dstOnly = false
                    zeroChange = 0
                    attempt = 0
                    continue
                }

                // 两次点击均未生效（RETRY_BOTH，含稳定未知兜底）：累计守卫计数
                //（不 continue，落到下方守卫判定）。
                VerifyOutcome.RETRY_BOTH -> {
                    dstOnly = false
                    zeroChange++
                }
            }
            if (zeroChange >= Const.SELF_MOVE_ZERO_CHANGE_MAX) {
                LogBus.log(
                    LogLevel.ERROR,
                    LogTag.SELF,
                    "连续 ${Const.SELF_MOVE_ZERO_CHANGE_MAX} 轮走棋重试无进展（RETRY），疑似弹窗遮挡或着法被拒，自动对弈已暂停（处理后点「开始」续弈）",
                )
                Recognizer.formatLayout(state.board)
                    .forEach { LogBus.log(LogLevel.WARN, LogTag.VISION, "守卫触发布局 $it") }
                running = false
                setStatus(BotStatus.ABNORMAL_PAUSED)
                return false
            }
            LogBus.log(
                LogLevel.DEBUG,
                LogTag.SELF,
                "走棋未确认（第 $attempt 次，$outcome，zeroChange=$zeroChange）",
            )
        }
    }

    /** 我方走子成功后启动 ponder（需引擎提供预测敌着）；敌方思考期预搜我方应手以加速走棋。 */
    private fun maybeStartPonder() {
        // 仅在轮到敌方时启动 ponder：正常 SELF_DONE 后敌方思考期预搜我方应手；
        // 若已 SELF_THEN_ENEMY（敌方与本方走子动画重叠、敌方已落子），轮到我方，ponder 无意义且会被紧接着的 bestMove 强制 stopPonder 浪费。
        if (state.turn != state.mySide.opponent) return
        val predicted = pendingPonderMove ?: return
        prematurePonderHarvested = false // 新一轮预搜开始，清除上一轮 CAP 收割标志
        // 当前 state.board 已含我方走子、轮到敌方 → 取「我方走子后」局面 FEN，叠加预测敌着 Y 作为 ponder 起点
        val fenAfterMyMove =
            fenOfBoard(state.board, state.mySide, state.turn, state.halfmoveClock)
        engine.startPonder(context, fenAfterMyMove, predicted)
        // 预测敌着中文化（2026-09-09 D6）：从「我方走子后」局面取起点格棋子名（黑马 b9 -> c7）
        val (pr, pc) = squareToGrid(predicted.take(2), state.mySide)
        val predictedLabel = state.board.getOrNull(pr)?.getOrNull(pc)?.let(::pieceLabel) ?: "未知子"
        LogBus.log(
            LogLevel.DEBUG,
            LogTag.ENGINE,
            "已启动 ponder（预测敌着 $predictedLabel ${predicted.take(2)} -> ${predicted.drop(2)}），敌方思考期预搜我方应手"
        )
    }

    /** computeMove 产物：着法（ICCS）——来源经 state.lastMoveSource 传递给悬浮窗引擎行。 */
    private data class PendingMove(val move: String)

    private suspend fun computeMove(): PendingMove? {
        if (!state.initialized) {
            LogBus.log(LogLevel.WARN, LogTag.PLAY, "棋盘未初始化，无法生成着法")
            return null
        }
        setStatus(BotStatus.THINKING)
        selfMatePending = false
        mateInfoSolid = false
        // 敌方走子命中预判：直接消费 ponderHit 预搜结果，省去一次完整引擎搜索（仅当着法有效）
        val pre = pendingPonderResult
        pendingPonderMove = null
        if (pre != null) {
            pendingPonderResult = null
            // 无评估佐证（ponder 停止取回时尚未输出任何 info 行 → depth=0/eval=0）→ 丢弃预搜，退回常规搜索
            if (pre.move != null && !(pre.depth == 0 && pre.scoreCp == 0)) {
                state.lastMoveSource = MoveSource.ENGINE
                state.lastMoveDepth = pre.depth
                state.lastEvalScore = pre.scoreCp
                state.lastEvalScoreUnreliable = pre.scoreUnreliable
                state.lastMatePly = pre.matePly
                recordMateInfo(pre)
                BotRuntime.bookWinRate.value = 0f
                emit()
                LogBus.log(
                    LogLevel.DEBUG, LogTag.ENGINE,
                    "命中预判：直接使用 ponder 预搜着法 ${pre.move}（${evalDetail(pre)}）",
                )
                return PendingMove(pre.move!!)
            }
            if (pre.move != null) {
                LogBus.log(
                    LogLevel.DEBUG, LogTag.ENGINE,
                    "预搜着法 ${pre.move} 无评估佐证（depth=0, eval=0），丢弃预搜，常规搜索",
                )
            }
            // 预搜无着法（极罕见）→ 丢弃，退回常规搜索
        }
        val cfg = BotConfig.data

        // ---------- 开局库优先（启用即全程生效，命中走书、未命中回落引擎） ----------
        if (cfg.bookEnabled) {
            // 书库 vkey 按 ICCS 标准方向（黑上红下、a 列在左）计算；执黑时屏幕棋盘需先 180° 旋转归一化，
            // 返回的 ICCS 着法再经 unpackMove 的 squareToGrid(iccs, mySide) 转回屏幕网格（两条链路对称）
            val bookBoard =
                if (state.mySide == Side.BLACK) rotateBoard180(state.board) else state.board
            val hit =
                runCatching { ObkBook.get(context).queryBest(bookBoard, state.turn == Side.RED) }
                    .onFailure { e ->
                        LogBus.log(
                            LogLevel.WARN,
                            LogTag.ENGINE,
                            "开局库查询异常：${e::class.java.simpleName}: ${e.message}"
                        )
                    }
                    .getOrNull()
            if (hit != null) {
                state.lastMoveSource = MoveSource.BOOK
                state.lastMoveDepth = 0
                selfMatePending = false
                mateInfoSolid = false // 开局库着法无引擎 info，探测按子数门控照常
                state.lastEvalScore = hit.vscore
                state.lastEvalScoreUnreliable = false // 开局库分是真实统计值，非占位
                state.lastMatePly = null
                BotRuntime.bookWinRate.value = hit.winRate
                emit()
                LogBus.log(
                    LogLevel.INFO,
                    LogTag.ENGINE,
                    "开局库命中：${hit.iccs}（vkey=${hit.vkey}，vscore=${hit.vscore}，" +
                            "胜率${(hit.winRate * 100).roundToInt()}%）",
                )
                return PendingMove(hit.iccs)
            }
            // 未命中回落引擎为常态路径，不打日志（命中已有 INFO；降噪 2026-09-09 D3）
        }

        // ---------- 引擎 ----------
        val fen = fenOfBoard(state.board, state.mySide, state.turn, state.halfmoveClock)
        LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "计算着法中：$fen")
        var result: EngineResult
        try {
            result = engine.bestMove(context, fen)
        } catch (e: EngineError) {
            LogBus.log(LogLevel.ERROR, LogTag.ENGINE, "引擎错误：${e.message}")
            return null
        }
        // 记录引擎预测敌着，供我方走子后启动 ponder 预搜（仅引擎来源；开局库无预测）
        pendingPonderMove = result.ponderMove
        if (result.move == null) {
            val shortTime = Const.ENGINE_MOVETIME_MS * 2 / 3
            LogBus.log(LogLevel.WARN, LogTag.ENGINE, "引擎无可用着法，改用 $shortTime ms 短时限重试")
            try {
                result = engine.bestMove(context, fen, movetimeMs = shortTime)
            } catch (e: EngineError) {
                LogBus.log(LogLevel.ERROR, LogTag.ENGINE, "重试引擎错误：${e.message}")
                return null
            }
        }
        if (result.move == null) {
            LogBus.log(LogLevel.WARN, LogTag.ENGINE, "引擎无可用着法（对局可能已结束）")
            finishGame("引擎判定我方无路可走，对局结束")
            return null
        }
        state.lastMoveSource = MoveSource.ENGINE
        state.lastMoveDepth = result.depth
        state.lastEvalScore = result.scoreCp
        state.lastEvalScoreUnreliable = result.scoreUnreliable
        state.lastMatePly = result.matePly
        // 主搜已声明 mate+1 = 本步着法即杀着；标记后 verify 直接终局，省去二次引擎调用
        recordMateInfo(result)
        if (selfMatePending) {
            LogBus.log(LogLevel.INFO, LogTag.ENGINE, "引擎判定本步绝杀（mate+1）：${result.move}")
        } else if (mateInfoSolid) {
            LogBus.log(
                LogLevel.DEBUG, LogTag.ENGINE,
                "info mate=${result.matePly}（质量达标）→ 对局确证仍将继续，本轮跳过绝杀探测"
            )
        }
        BotRuntime.bookWinRate.value = 0f
        emit() // 引擎返回后立即刷新悬浮窗引擎行
        LogBus.log(
            LogLevel.DEBUG,
            LogTag.ENGINE,
            "引擎着法：${result.move}（${evalDetail(result)}）",
        )
        return PendingMove(result.move)
    }

    private data class Unpacked(
        val r1: Int, val c1: Int, val r2: Int, val c2: Int,
        val piece: String,
    )

    private fun unpackMove(move: String): Unpacked? {
        val from = squareToGrid(move.substring(0, 2), state.mySide)
        val to = squareToGrid(move.substring(2, 4), state.mySide)
        val piece = state.boardAt(from.first, from.second)
        if (piece == null) {
            LogBus.log(
                LogLevel.WARN,
                LogTag.PLAY,
                "着法 $move 起点无我方棋子，棋盘数据可能已过期，请点击「开始」重同步"
            )
            return null
        }
        state.selfHighlight = listOf(from, to)
        state.selfPlanned = true
        state.lastMove = move
        // 棋盘动画先行（#6）：开局库/引擎出着法后立即推悬浮棋盘箭头，再执行点击走子
        emit()
        val capturedNote = state.boardAt(to.first, to.second)?.let { "（吃${pieceLabel(it)}）" } ?: ""
        val matePly = state.lastMatePly
        val evalNote = when {
            state.lastEvalScoreUnreliable -> "（评估 -）"
            matePly != null && matePly > 0 -> "（绝杀 $matePly）"
            matePly != null -> "（被绝杀 ${-matePly}）"
            else -> state.lastEvalScore.let { if (it > 0) "（评估 +$it）" else "（评估 $it）" }
        }
        LogBus.log(
            LogLevel.INFO,
            LogTag.SELF,
            "走棋 $move：${pieceLabel(piece)} " +
                    "${gridToSquare(from.first, from.second, state.mySide)} -> " +
                    "${gridToSquare(to.first, to.second, state.mySide)}$capturedNote$evalNote",
        )
        return Unpacked(from.first, from.second, to.first, to.second, piece)
    }

    /** 纯点击走子（v3 简化 2026-09-06）：先点源格再点目标格。
     *  原 isRetry 稳判预检（「已落定不再点 / 源空仅点目标」）已移入 verifyForSelfMove——
     *  v3 verify 无全局时间窗，落定态必被 n==2 快速成功路径观测到后才返回 RETRY_*，
     *  盲点重试不会再出现「把已落定的子重新提起」问题（原 2026-08-30 Fix 2 的场景已消除）。 */
    private suspend fun attemptMove(
        r1: Int,
        c1: Int,
        r2: Int,
        c2: Int
    ): Boolean {
        setStatus(BotStatus.TAPPING)
        val cap = capture ?: return false
        if (!cap.tap(r1, c1)) return false
        delay(BotConfig.data.tapHoldMs.toLong())
        return cap.tap(r2, c2)
    }

    // ---------- 多帧校验 verifyForSelfMove（v3 重构 2026-09-06：diff 数量分流，无全局时间窗） ----------
    // 每帧 grabBoard 一次，n = changes.size，已知结果先行：
    //   (a) n==2 且恰为本步两格 → 直接成功免两帧校验（dst→我子落定 / dst→敌子=落子即被反吃）；
    //   (b) n ≤ 4 → classifySelfFrame 明确归类（SELF_DONE / SELF_THEN_ENEMY[稳定 + T-D 复判，
    //       敌着就地提交] / LIFTED[超 T2 补点] / SILENT[稳定 K1 帧重试两格] / NOISY[T-B 吞点击恢复]）；
    //   (c) n ∈ 5..VERIFY_OCR_DIFF_CELLS → 动画/噪声灰区，静默继续；
    //   (d) diffCells > VERIFY_OCR_DIFF_CELLS → 大面积遮挡（弹窗/遮罩/结束画面）→ OCR/终局检查（节流）；
    //   (e) 稳定（变化格子集合与上帧逐格相同）且不可行动持续超 T3 → 终局检查 + RETRY_BOTH 兜底；
    //   liveness 硬顶 VERIFY_HARD_CAP_MS（防非稳定的持续动画模式永久悬挂）。
    // 基线白名单：提交只刷新被提交着法覆盖的格子 + driftCells，其余变化格（敌方仅提起/伪影）
    // 一律留 diff 管线（防 17:59 类「无关格进基线 → 敌着两格对被拆散 → 误暂停」污染）。

    private suspend fun verifyForSelfMove(
        r1: Int,
        c1: Int,
        r2: Int,
        c2: Int,
        piece: String,
    ): VerifyOutcome {
        setStatus(BotStatus.VERIFYING)
        val expected = Move(r1 to c1, r2 to c2, piece)
        val cap = capture
        // 防御性入口检查（02:27 事故语义保留）：本步若已在别处提交过，不再看画面——我方走子必然
        // 成功，此后画面差异（敌方回复 / 动画残留 / 弹窗）一律交回 waitForEnemyMove 处理。
        if (state.board[r1][c1] == null && state.board[r2][c2] == piece) {
            LogBus.log(LogLevel.DEBUG, LogTag.SELF, "我方走子已提交过，跳过校验直接确认")
            endgameHook()
            return VerifyOutcome.DONE_OK
        }
        val dist = maxOf(kotlin.math.abs(r2 - r1), kotlin.math.abs(c2 - c1))
        // T2 = 走子动画公式（提起+飞 dist 格+落下）：LIFTED（提起未落）持续超过它 → 补点目标格
        val firstWaitMs =
            BotConfig.data.verifyAnimBaseMs.toLong() + dist * Const.VERIFY_ANIM_PER_CELL_MS
        val srcCell = r1 to c1
        val dstCell = r2 to c2
        val verifyStartMs = System.nanoTime() / 1_000_000
        var prevChanges: List<Change>? = null // 上一帧变化格子集合（null=首帧，视为不稳定）
        var stillCount = 0 // n==0 静止连续帧数（SILENT 稳定判定）
        var liftSinceMs = -1L // LIFTED（我子提起未落）起始时刻
        var stableUnknownSinceMs = -1L // 稳定未知模式起始时刻

        while (running && !interrupted && !state.gameOver) {
            // 首帧等走子动画落定；后续帧按用户设置间隔
            delay(if (prevChanges == null) firstWaitMs else BotConfig.data.verifyNextFrameMs.toLong())
            if (!running || interrupted || state.gameOver) return VerifyOutcome.DONE_END
            // liveness 硬顶：正常路径必在已知结果/灰区/遮挡/稳定兜底中收敛，仅病态持续动画会触达
            val nowCap = System.nanoTime() / 1_000_000
            if (nowCap - verifyStartMs > Const.VERIFY_HARD_CAP_MS) {
                LogBus.log(
                    LogLevel.WARN,
                    LogTag.SELF,
                    "verify 超过硬顶 ${Const.VERIFY_HARD_CAP_MS}ms，放弃本轮重试"
                )
                return VerifyOutcome.RETRY_BOTH
            }

            val grabbed = grabBoard(cap) ?: continue
            try {
                val changes = grabbed.scan.changes
                val n = changes.size
                // 帧间一致性（v3）：变化格子集合逐格相同（同格同 old/new；两帧皆空也算稳定）
                val stable = prevChanges != null && changes == prevChanges
                prevChanges = changes
                val nowMs = System.nanoTime() / 1_000_000

                // ── (a) n==2 且恰为本步两格：直接成功，免两帧校验 ──
                // （dst=敌子的「落子即被反吃」形状已删：干净 diff 下不可达，见 isSelfPairSettled 注释）
                if (isSelfPairSettled(changes, expected)) {
                    val dstChg = changes.first { it.r to it.c == dstCell }
                    val captured = dstChg.old?.takeIf { pieceColor(it) != state.mySide }
                    commitSelfSettled(
                        grabbed,
                        expected.copy(captured = captured),
                        setOf(srcCell, dstCell)
                    )
                    endgameHook()
                    return if (state.gameOver) VerifyOutcome.DONE_END else VerifyOutcome.DONE_OK
                }

                // ── (b) n ≤ 4：classifySelfFrame 明确归类 ──
                if (n <= 4) {
                    val fc = classifySelfFrame(
                        changes,
                        grabbed.scan.board,
                        expected,
                        state.mySide,
                        state.board
                    )
                    LogBus.log(
                        LogLevel.DEBUG,
                        LogTag.SELF,
                        "校验帧 n=$n stable=$stable result=${fc.result}"
                    )

                    when (fc.result) {
                        SelfFrameResult.SELF_DONE -> {
                            // 我方走子成功；可能夹带敌方仅提起格（n==3 情况1，如 17:59 的 e9 b_k→空）
                            // ——不完整敌着一律不进基线，留管线给 waitForEnemyMove 拼完整两格对
                            commitSelfSettled(
                                grabbed,
                                fc.selfMove ?: expected,
                                setOf(srcCell, dstCell)
                            )
                            endgameHook()
                            return if (state.gameOver) VerifyOutcome.DONE_END else VerifyOutcome.DONE_OK
                        }

                        SelfFrameResult.SELF_THEN_ENEMY -> {
                            val selfM = fc.selfMove
                            val enemyM = fc.enemyMove
                            if (selfM == null || enemyM == null) {
                                LogBus.log(
                                    LogLevel.WARN,
                                    LogTag.SELF,
                                    "SELF_THEN_ENEMY 缺走子数据，按未确认处理"
                                )
                            } else if (!stable) {
                                // 敌着两格对尚未稳定（可能为动画中途拼对）→ 继续观察
                            } else {
                                // T-D 两帧一致确认（复抓重推敌着；不加显式延时——单次 grabBoard
                                // ~70-100ms 已越半格飞行窗）。一致 → 就地提交双着（省一轮敌方截图分析）
                                if (!running || interrupted || state.gameOver) return VerifyOutcome.DONE_END
                                if (tryCommitSelfThenEnemy(cap, expected, selfM, enemyM)) {
                                    endgameHook()
                                    return if (state.gameOver) VerifyOutcome.DONE_END else VerifyOutcome.DONE_OK
                                }
                                LogBus.log(
                                    LogLevel.DEBUG, LogTag.SELF,
                                    "两帧确认未通过（敌着未复现，疑似动画中途帧），丢弃本帧继续校验",
                                )
                            }
                        }

                        SelfFrameResult.LIFTED -> {
                            // 第一点击生效、第二点击未注册：持续超过动画时长 → 补点目标格
                            if (liftSinceMs < 0) liftSinceMs = nowMs
                            if (nowMs - liftSinceMs > firstWaitMs) {
                                LogBus.log(
                                    LogLevel.INFO,
                                    LogTag.SELF,
                                    "我方提子未落（${nowMs - liftSinceMs}ms），补点落子"
                                )
                                return VerifyOutcome.RETRY_DST
                            }
                        }

                        SelfFrameResult.SILENT -> {
                            // n==0：两次点击均未生效 → 稳定 K1 帧后重试两格
                            stillCount++
                            if (stillCount >= Const.VERIFY_SILENT_K1) {
                                return VerifyOutcome.RETRY_BOTH
                            }
                        }

                        SelfFrameResult.NOISY -> {
                            // T-B 吞点击恢复：changes 恰为一步完整合法敌着且我方未执行 → 提交敌着重试本步
                            if (tryRecoverSwallowedTap(changes, expected)) {
                                return VerifyOutcome.RETRY_AFTER_ENEMY
                            }
                        }
                    }
                    // 本帧未行动 → 维护计时器（模式切换即重置）
                    if (fc.result != SelfFrameResult.LIFTED) liftSinceMs = -1L
                    if (fc.result != SelfFrameResult.SILENT) stillCount = 0
                } else {
                    liftSinceMs = -1L
                    stillCount = 0
                }

                // ── (c) n ∈ 5..VERIFY_OCR_DIFF_CELLS：动画/噪声灰区，静默继续（不 OCR、不重试）──

                // ── (d) diffCells > VERIFY_OCR_DIFF_CELLS：大面积遮挡 → OCR/终局检查（节流）──
                if (grabbed.scan.diffCells > Const.VERIFY_OCR_DIFF_CELLS) {
                    verifyEndgameCheck(grabbed)?.let { return it }
                }

                // ── (e) 稳定未知模式兜底：与上帧变化格子相同且不可行动，持续超 T3 →
                //     终局/和棋检查 + RETRY_BOTH（计入 doMove 零变化守卫）──
                if (stable) {
                    if (stableUnknownSinceMs < 0) stableUnknownSinceMs = nowMs
                    if (nowMs - stableUnknownSinceMs > Const.VERIFY_UNKNOWN_STABLE_MS) {
                        LogBus.log(
                            LogLevel.DEBUG, LogTag.SELF,
                            "稳定未知模式持续超 ${Const.VERIFY_UNKNOWN_STABLE_MS}ms，执行终局/和棋检查后重试",
                        )
                        verifyEndgameCheck(grabbed)?.let { return it }
                        return VerifyOutcome.RETRY_BOTH
                    }
                } else {
                    stableUnknownSinceMs = -1L
                }
            } finally {
                grabbed.corrected.release()
            }
        }
        // 循环退出 = 对局结束或运行中断（doMove 按 gameOver/running 分流）
        return VerifyOutcome.DONE_END
    }

    /** v3 我方走子成功提交：board 提交 selfMove，基线白名单只刷 cells（被提交着法覆盖格）+ driftCells。 */
    private fun commitSelfSettled(grab: Grabbed, selfMove: Move, cells: Set<Pair<Int, Int>>) {
        state.applySelfMove(selfMove)
        refreshBaselineCells(grab, cells)
        state.resignStreak = 0
        emit()
        LogBus.log(
            LogLevel.DEBUG, LogTag.SELF,
            "我方走子确认 ${
                gridToSquare(
                    selfMove.src.first,
                    selfMove.src.second,
                    state.mySide
                )
            }->" +
                    "${
                        gridToSquare(
                            selfMove.dst.first,
                            selfMove.dst.second,
                            state.mySide
                        )
                    }（基线刷 ${cells.size} 格）",
        )
    }

    /** v3 我方+敌方双着就地提交（verify 帧内敌着信息完整且两帧确认通过，省一轮 waitForEnemyMove 截图分析）。 */
    private fun commitSelfThenEnemy(
        grab: Grabbed,
        selfMove: Move,
        enemyMove: Move,
        cells: Set<Pair<Int, Int>>
    ) {
        state.applySelfThenEnemy(selfMove, enemyMove)
        refreshBaselineCells(grab, cells)
        LogBus.log(LogLevel.INFO, LogTag.ENEMY, formatMove(enemyMove, state.mySide))
        state.resignStreak = 0
        emit()
    }

    /** T-D 两帧一致确认（v3 方案A 自 verifyForSelfMove 抽取，2026-09-07）：复抓一帧重推敌着——
     *  敌着一致 → 就地提交我方+敌方双着（基线白名单四格）；未复现（首帧为动画瞬时态）→
     *  仅提交我方走子（基线两格，敌着交回敌方检测）；其余 → 返回 false 交 verify 继续校验。 */
    private suspend fun tryCommitSelfThenEnemy(
        cap: Capture,
        expected: Move,
        selfM: Move,
        enemyM: Move,
    ): Boolean {
        val reGrab = grabBoard(cap) ?: return false
        try {
            val reFc = classifySelfFrame(
                reGrab.scan.changes,
                reGrab.scan.board,
                expected,
                state.mySide,
                state.board,
            )
            return when {
                reFc.result == SelfFrameResult.SELF_THEN_ENEMY && reFc.enemyMove == enemyM -> {
                    // 敌着两帧一致：就地提交我方+敌方（基线白名单只刷四格）
                    commitSelfThenEnemy(
                        reGrab, selfM, enemyM,
                        setOf(expected.src, expected.dst, enemyM.src, enemyM.dst),
                    )
                    true
                }

                reFc.result == SelfFrameResult.SELF_DONE -> {
                    // 敌着未复现（首帧敌变为瞬时态）→ 仅提交我方走子，敌着交回敌方检测
                    commitSelfSettled(reGrab, selfM, setOf(expected.src, expected.dst))
                    LogBus.log(
                        LogLevel.DEBUG, LogTag.SELF,
                        "敌着未复现（首帧为动画瞬时态），仅提交我方走子",
                    )
                    true
                }

                else -> false
            }
        } finally {
            reGrab.corrected.release()
        }
    }

    /** v3 基线白名单刷新（核心原则 3）：只刷新被提交着法覆盖的格子 + driftCells；
     *  其余变化格（敌方仅提起/伪影）一律不进基线，留给 diff 管线。用合成 Change 仅携带格子坐标
     *  （updateCellImgs 只取 r,c），保证落定格基线必被刷新（不受该帧 drift 漏触发影响）。 */
    private fun refreshBaselineCells(grab: Grabbed, cells: Set<Pair<Int, Int>>) {
        val refresh = cells.map { Change(it.first, it.second, null, null) }
        state.updateCellImgs(grab.corrected, refresh, grab.scan.driftCells)
    }

    /** 走子成功后的终局联动（与原实现一致）：主搜已声明 mate+1 → 本步即杀着直接终局；否则绝杀二次探测。 */
    private suspend fun endgameHook() {
        if (selfMatePending) {
            selfMatePending = false
            finishGame("我方绝杀，${state.mySide.opponent.cn}方无路可走")
        } else {
            checkmateProbe()
        }
    }

    /** v3 verify 内终局/和棋检查（触发条件：diff cell > VERIFY_OCR_DIFF_CELLS 的大面积遮挡帧，
     *  或稳定未知模式超 T3）。和棋弹窗关闭 → RETRY_BOTH（点击多半被吞）；终局确认 → DONE_END。
     *  OCR 类检查自带节流（confirmEndByOcr / lastVerifyDrawScanAt），updateResign 为纯格子信号不节流。 */
    private suspend fun verifyEndgameCheck(grabbed: Grabbed): VerifyOutcome? {
        when (updateResign(grabbed.scan.board, grabbed.scan.changes)) {
            ResignResult.CONFIRMED -> {
                if (selfMatePending) {
                    selfMatePending = false
                    finishGame("我方绝杀，${state.mySide.opponent.cn}方无路可走")
                } else {
                    finishGame("检测到对局结束画面")
                }
                return VerifyOutcome.DONE_END
            }

            ResignResult.SUSPECT -> if (confirmEndByOcr()) return VerifyOutcome.DONE_END

            ResignResult.NONE -> {}
        }
        val now = System.nanoTime()
        if (now - lastVerifyDrawScanAt >= Const.OCR_SUSPECT_SCAN_THROTTLE_MS * 1_000_000) {
            lastVerifyDrawScanAt = now
            val cap = capture
            if (cap.dismissDrawDialog()) {
                LogBus.log(LogLevel.INFO, LogTag.SELF, "verify 检出并关闭和棋弹窗，交由重试重新核验")
                setStatus(BotStatus.DRAW_HANDLING)
                state.resignStreak = 0
                return VerifyOutcome.RETRY_BOTH
            }
        }
        return null
    }

    /**
     * T-B 恢复分支（2026-09-06）：verify 期间 NOISY 帧若恰好构成一步「合法敌方走子」且我方 expected
     * 尚未执行 → 判「我方点击被吞、对方先走了」。提交敌着（含 cellImgs/ponder 清理）后由
     * doMove 以 RETRY_AFTER_ENEMY 重试本步走子，阻断「轮次错位 → 点击全被吞 → NOISY 空转」连锁。
     * T-D 补充：NOISY 帧推断的敌着同样可能是动画中途帧（几何合法、伪合法校验拦截不了），
     * 走 reconfirmEnemyMoved 两帧一致确认，未复现则返回 false 交 verify 继续观察。
     */
    private suspend fun tryRecoverSwallowedTap(changes: List<Change>, expected: Move): Boolean {
        if (changes.size != 2) return false
        val moved = inferMove(changes) ?: return false
        if (pieceColor(moved.piece) == state.mySide) return false
        // 伪合法校验（动画中间帧的非法推断在此被拒）
        if (!isPseudoLegal(state.board, moved, state.mySide)) return false
        // 我方 expected 确实未执行：committed 起点仍应有我方棋子
        if (state.board[expected.src.first][expected.src.second] == null) return false
        // 两帧一致确认后再提交（对称：与 waitForEnemyMove 的 MOVED 路径同一确认/提交链路）
        val confirmGrab = reconfirmEnemyMoved(moved) ?: return false
        try {
            setStatus(BotStatus.ENEMY_CONFIRM)
            commitEnemyMove(moved, confirmGrab)
        } finally {
            confirmGrab.corrected.release()
        }
        LogBus.log(
            LogLevel.WARN,
            LogTag.SELF,
            "我方点击被吞（对方先走了）：已记录敌着 ${
                gridToSquare(
                    moved.src.first,
                    moved.src.second,
                    state.mySide
                )
            }->" +
                    "${gridToSquare(moved.dst.first, moved.dst.second, state.mySide)}，重试我方走子"
        )
        return true
    }

    // ---------- 敌方走棋检测 ----------

    // 棋盘识别提速（方案 A 变种，2026-08-30）：recognizeBoardChanged 每轮逐格 10x10 中心小图 diff，
    // 仅变化格跑模板匹配，未变格沿用 board；不再做全量 90 格匹配（原 ~466ms/帧 → ~20ms/帧）。

    private suspend fun waitForEnemyMove() {
        setStatus(BotStatus.WAIT_ENEMY)
        state.resignStreak = 0
        state.noisyCount = 0
        state.liftLogged = false
        var silentStreak = 0 // Q4-3：连续静默帧计数（单帧 SILENT 常为误读，防「对方提子」双打）
        // 节奏信息降 DEBUG（2026-09-09 D3）：敌着事件本身有 INFO（对方提子 / X方走炮），避免每步重复
        LogBus.log(LogLevel.DEBUG, LogTag.PLAY, "等待对方走棋")
        val cap = capture
        while (running && !interrupted && !state.gameOver) {
            // F2-A：敌方思考超 CAP 仍未走子 → 提前 ponderhit 按质量门控收割预搜（裸 go ponder 唯一闸门）。
            // 收割阻塞通常 <200ms（elapsed 已 ≥ TARGET，质量多半已达标），期间不取帧——敌方尚未走子无信息损失
            maybeHarvestPonderCap()
            val grabbed = grabBoard(cap) ?: continue
            try {
                val newBoard = grabbed.scan.board
                val changes = grabbed.scan.changes
                if (!running || interrupted || state.gameOver) break
                val frame = classifyEnemyFrame(changes, state.mySide, state.board)
                when (frame.result) {
                    EnemyFrameResult.MOVED -> {
                        frame.enemyMove?.let { move ->
                            // T-D 两帧一致确认（2026-09-06）：首帧 MOVED 可能是动画中途帧
                            //（如車 C0→C9 途经 C5，几何合法、伪合法校验拦截不了），复抓复判
                            //（不加显式延时，单次 grabBoard 已够），敌着两帧一致才提交。
                            val confirmGrab = reconfirmEnemyMoved(move)
                            if (confirmGrab == null) {
                                if (running && !interrupted && !state.gameOver) {
                                    LogBus.log(
                                        LogLevel.DEBUG,
                                        LogTag.ENEMY,
                                        "两帧确认未通过（敌着未复现，疑似动画中途帧），丢弃本帧继续等待",
                                    )
                                }
                                // 丢弃本帧：不计噪声，回循环重新识别（落定后会再次 MOVED 并通过确认）
                            } else {
                                try {
                                    setStatus(BotStatus.ENEMY_CONFIRM)
                                    commitEnemyMove(move, confirmGrab)
                                    return
                                } finally {
                                    confirmGrab.corrected.release()
                                }
                            }
                        }
                    }

                    EnemyFrameResult.LIFTED -> {
                        silentStreak = 0
                        if (!state.liftLogged) {
                            state.liftLogged = true
                            setStatus(BotStatus.ENEMY_LIFTED)
                            LogBus.log(LogLevel.INFO, LogTag.ENEMY, "对方提子")
                        }
                        state.noisyCount = 0
                    }

                    EnemyFrameResult.SILENT -> {
                        // Q4-3（log 1432-1433 双打实证）：提子悬停期 LIFTED/SILENT 交替，
                        // 单帧 SILENT 是 cls 误读——连续 2 帧静默才视为提子结束并重置日志门
                        silentStreak++
                        if (silentStreak >= 2) state.liftLogged = false
                        state.noisyCount = 0
                    }

                    EnemyFrameResult.NOISY -> {
                        // 异常帧：可能是和棋弹窗盖盘（T1），先查一次；处理过则重置噪声计数
                        if (cap.dismissDrawDialog()) {
                            state.liftLogged = false
                            state.noisyCount = 0
                            continue
                        }
                        when (updateResign(newBoard, changes)) {
                            ResignResult.CONFIRMED -> {
                                finishGame("检测到对局结束画面")
                                return
                            }

                            ResignResult.SUSPECT -> {
                                delay(Const.RESIGN_SUSPECT_WAIT_MS)
                                // T-OCR：疑似即扫一次结算文字，命中立即终局
                                if (confirmEndByOcr()) return
                                continue
                            }

                            ResignResult.NONE -> {}
                        }
                        state.liftLogged = false
                        silentStreak = 0
                        state.noisyCount++
                        formatChanges(changes, state.mySide).forEach {
                            LogBus.log(
                                LogLevel.DEBUG,
                                LogTag.VISION,
                                "识别变动 $it"
                            )
                        }
                        if (state.noisyCount >= Const.ENEMY_NOISY_MAX) {
                            // 连续噪声帧上限命中：先给动画落定时间，再复判一次。
                            delay(Const.RESIGN_SUSPECT_WAIT_MS)
                            // 复判优先级 1：可能只是「敌方一步慢落子」被前几帧噪声拖垮，
                            // 延时后应能识别为 MOVED —— 走正常敌方走子流程，避免误暂停。
                            val reMove = grabBoard(cap)
                            if (reMove != null) {
                                try {
                                    val rf = classifyEnemyFrame(
                                        reMove.scan.changes,
                                        state.mySide,
                                        state.board
                                    )
                                    if (rf.result == EnemyFrameResult.MOVED && rf.enemyMove != null) {
                                        val move = rf.enemyMove
                                        // T-D：复判帧同样过两帧一致确认（与首个 MOVED 路径对称共用提交链路）
                                        val confirmGrab = reconfirmEnemyMoved(move)
                                        if (confirmGrab == null) {
                                            LogBus.log(
                                                LogLevel.DEBUG,
                                                LogTag.ENEMY,
                                                "噪声复判帧两帧确认未通过，按噪声流程继续",
                                            )
                                        } else {
                                            try {
                                                setStatus(BotStatus.ENEMY_CONFIRM)
                                                commitEnemyMove(move, confirmGrab)
                                                return
                                            } finally {
                                                confirmGrab.corrected.release()
                                            }
                                        }
                                    }
                                } finally {
                                    reMove.corrected.release()
                                }
                            }
                            // 复判优先级 2：仍非敌方走棋 → 走认输连续校验（清盘动画落定后），
                            // 避免「清盘动画未稳、将帅仍可见」的窗口被误判为无法推断而暂停。
                            var ended = false
                            repeat(Const.RESIGN_CONFIRM_COUNT) {
                                if (!running || interrupted || state.gameOver) {
                                    ended = state.gameOver
                                    return@repeat
                                }
                                val re = grabBoard(cap) ?: return@repeat
                                try {
                                    when (updateResign(re.scan.board, re.scan.changes)) {
                                        ResignResult.CONFIRMED -> {
                                            finishGame("检测到对局结束画面")
                                            ended = true
                                        }

                                        ResignResult.SUSPECT -> {
                                            // T-OCR：疑似即扫一次结算文字，命中立即终局
                                            if (confirmEndByOcr()) ended = true
                                        }

                                        else -> {}
                                    }
                                } finally {
                                    re.corrected.release()
                                }
                                if (ended) return@repeat
                            }
                            if (ended) return
                            LogBus.log(
                                LogLevel.WARN,
                                LogTag.PLAY,
                                "连续 ${Const.ENEMY_NOISY_MAX} 帧无法推断对方完整走法，暂停自动对弈",
                            )
                            running = false
                            setStatus(BotStatus.ABNORMAL_PAUSED)
                            return
                        }
                        delay(Const.ENEMY_RECHECK_WAIT_MS)
                    }
                }
                // 每轮全量识别后短暂让步，避免单工作线程被识别独占（识别本身已 ~250ms，此延迟仅节流）；
                // 间隔可调（设置页「敌方走棋」分组，默认 Const.ENEMY_IDLE_POLL_MS=50）
                delay(BotConfig.data.enemyPollMs.toLong())
            } finally {
                grabbed.corrected.release()
            }
        }
        LogBus.log(LogLevel.DEBUG, LogTag.PLAY, "已中断等待对方走棋")
    }

    // ---------- 认输 / 绝杀 / 和棋 ----------

    private var lastOcrEndScanAt = 0L
    private var lastVerifyDrawScanAt =
        0L // verify 内和棋弹窗 OCR 检查节流（v3，复用 OCR_SUSPECT_SCAN_THROTTLE_MS）

    /**
     * T-OCR（2026-09-06）：「疑似对局结束画面」（updateResign SUSPECT）时做一次整屏 OCR 扫描
     * （≥OCR_SUSPECT_SCAN_THROTTLE_MS 节流），命中结算词表（按钮/遮罩任一词）→ 立即 finishGame
     * 返回 true，省掉连续 RESIGN_CONFIRM_COUNT 帧确认等待；未命中返回 false，回落原兜底逻辑
     * （结算动画尚无文字时 OCR 命中不了，仍靠连续棋盘信号确认，二者互补）。
     */
    private suspend fun confirmEndByOcr(): Boolean {
        val now = System.nanoTime()
        if (now - lastOcrEndScanAt < Const.OCR_SUSPECT_SCAN_THROTTLE_MS * 1_000_000) return false
        lastOcrEndScanAt = now
        val cap = capture ?: return false
        val img = cap.screenshot() ?: return false
        val hit = TextMatcher.findGameoverScan(context, img)
        img.recycle() // 2026-09-07 D2=A：raw Bitmap（~10MB）用完即还，hit 已含词文本与坐标
        if (hit == null) {
            LogBus.log(
                LogLevel.DEBUG,
                LogTag.PLAY,
                "疑似结束画面 OCR 扫描无结算词命中，走连续确认兜底"
            )
            return false
        }
        LogBus.log(
            LogLevel.INFO,
            LogTag.PLAY,
            "OCR 命中结算文字「${hit.word}」（置信度 ${"%.2f".format(hit.score)}），立即终局"
        )
        if (selfMatePending) {
            selfMatePending = false
            finishGame("我方绝杀，${state.mySide.opponent.cn}方无路可走")
        } else {
            finishGame("检测到对局结束画面")
        }
        return true
    }

    private fun updateResign(newBoard: Board, changes: List<Change>): ResignResult {
        // 提速后游戏结束动画渐进遮盖将帅，单帧「两将缺失」信号会抖动；改用更稳定的清盘信号：
        // 单帧 >6 个已提交棋子变为空（changes 中 old!=null && new==null），与「两将缺失」取 OR 判疑似结束。
        val emptyDrop = changes.count { it.old != null && it.new == null }
        val suspect =
            isResignSuspect(newBoard, state.mySide) || emptyDrop > Const.RESIGN_EMPTY_DROP_MAX
        if (suspect) {
            state.resignStreak++
            LogBus.log(
                LogLevel.DEBUG,
                LogTag.PLAY,
                "疑似对局结束画面（${state.resignStreak}/${Const.RESIGN_CONFIRM_COUNT}，清盘空格 $emptyDrop）",
            )
            return if (state.resignStreak >= Const.RESIGN_CONFIRM_COUNT) {
                ResignResult.CONFIRMED
            } else {
                ResignResult.SUSPECT
            }
        }
        state.resignStreak = 0
        return ResignResult.NONE
    }

    /** 引擎结果评估详情（2026-09-09 Q2/Q3）：无有效 info（盲区止损时引擎全程沉默）时 depth/score
     *  是占位值 0 非引擎评分，显示「评估 -，depth -」避免误导；matePly 正=我方 N 步内绝杀 /
     *  负=被绝杀（EngineInfoPick 视角：行棋方），显示「绝杀 N」更直观（用户要求）；
     *  mate 时评估分（100000-N）与绝杀并列显示（2026-09-09 用户要求三字段齐显，scoreCp 与 matePly 同源皆真实）。 */
    private fun evalDetail(r: EngineResult): String = when {
        r.scoreUnreliable -> "评估 -，depth -"
        r.matePly != null && r.matePly > 0 -> "绝杀 ${r.matePly}，评估 ${"%+d".format(r.scoreCp)}，depth ${r.depth}"
        r.matePly != null -> "被绝杀 ${-r.matePly}，评估 ${"%+d".format(r.scoreCp)}，depth ${r.depth}"
        else -> "评估 ${"%+d".format(r.scoreCp)}，depth ${r.depth}"
    }

    /** 信息框第四行评估文本（2026-09-09 Q2）：mate 优先「绝杀 N/被绝杀 N」（与 evalDetail 同语义），
     *  盲区步「评估 -」（占位 0 不再显示为评分），普通局面「+N/N」；着色由 evalScore 正负决定，无需额外传色。 */
    private fun evalOverlayText(): String = when {
        state.lastEvalScoreUnreliable -> "评估 -"
        else -> {
            val matePly = state.lastMatePly
            when {
                matePly != null && matePly > 0 -> "绝杀 $matePly"
                matePly != null -> "被绝杀 ${-matePly}"
                state.lastEvalScore > 0 -> "+${state.lastEvalScore}"
                else -> "${state.lastEvalScore}"
            }
        }
    }

    /** Y 方案记账入口（主搜 / ponderHit 两条引擎路径共用）：写 selfMatePending 与 mateInfoSolid。 */
    private fun recordMateInfo(result: EngineResult) {
        selfMatePending = result.matePly == 1
        // matePly 非 null 已隐含「有效非 bound info」（bound 行的 mate 在引擎层即被滤除）；
        // 再叠加 qualityReached 排除硬顶兜底场景，双重保险
        mateInfoSolid = !selfMatePending && result.matePly != null && result.qualityReached
    }

    private suspend fun checkmateProbe(): Boolean {
        // Y 方案：本轮着法的引擎 info 质量达标且带明确 mate 值（非杀）→ 对方必有应手，跳过二次探测
        if (mateInfoSolid) {
            logProbeSkipChange("mate 明确", "mate 明确（质量达标）")
            return false
        }
        // Option A：仅终局附近（子少）才二次调用引擎验证，常规中局主搜已覆盖将死，跳过以减少引擎开销
        val count = pieceCount(state.board)
        if (count > Const.ENDGAME_PROBE_PIECE_MAX) {
            // 常态路径：仅原因变化时打点（每步一条曾占单局日志 66 行）。
            // 注意 key 用稳定类别「非终局」，文案才可携带实时子数——key 内嵌子数会永不去重（2026-09-09 复审 R1 修复）
            logProbeSkipChange("非终局", "非终局（$count 子 > ${Const.ENDGAME_PROBE_PIECE_MAX}）")
            return false
        }
        setStatus(BotStatus.GAMEOVER_CHECK)
        lastProbeSkip = null // 本轮真正执行了探测，下轮跳过原因需重新打点
        val opp = state.mySide.opponent
        val fen = fenOfBoard(state.board, state.mySide, opp, state.halfmoveClock)
        LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "绝杀探测 FEN（${opp.cn}方行棋）：$fen")
        val mated = try {
            engine.isMate(context, fen)
        } catch (e: Exception) {
            LogBus.log(
                LogLevel.WARN,
                LogTag.ENGINE,
                "引擎绝杀探测失败，当作未绝杀继续：${e::class.java.simpleName}: ${e.message}"
            )
            return false
        }
        if (!mated) {
            LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "未绝杀，继续对局")
            return false
        }
        finishGame("我方绝杀，${opp.cn}方无路可走")
        return true
    }

    /** 绝杀探测「跳过原因」变化时才打 DEBUG（常态每步同因跳过不再刷屏）。key 用稳定类别，text 可携带实时数值。 */
    private fun logProbeSkipChange(key: String, text: String) {
        if (lastProbeSkip != key) {
            lastProbeSkip = key
            LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "跳过绝杀二次探测：$text")
        }
    }

    private fun decideDraw(): Boolean {
        setStatus(BotStatus.DRAW_HANDLING)
        val score = state.lastEvalScore
        val reject = decideDraw(score, Const.DRAW_REJECT_CP)
        LogBus.log(
            LogLevel.INFO,
            LogTag.PLAY,
            if (reject) "和棋决策：我方占优（${score}cp），拒绝和棋" else "和棋决策：均势或劣势（${score}cp），同意和棋",
        )
        return reject
    }

    // ---------- 自动下一局 ----------

    private suspend fun autoNextGame(): Boolean {
        if (interrupted) return false
        LogBus.log(LogLevel.INFO, LogTag.NEXT, "开始自动下一局")
        autoNextFlag = true
        setStatus(BotStatus.AUTO_NEXT)
        emit()
        try {
            // 统一启动等待循环（2026-09-09 U1=A）：自动策略注入——需准入证据/有超时/
            // 检查开关/下半区提子不触发恢复（敌方红方提子帧按过渡等待）
            val loop = StartLoop(
                context,
                capture,
                shouldContinue = { running && !interrupted },
                interruptSession = { interrupt() },
                onPhase = { setStatus(it) },
                onOwnLift = { corrected, liftPos -> recoverOwnLift(corrected, liftPos) },
            )
            val expectation = StartExpectation(
                tag = LogTag.NEXT,
                entryStatus = null, // 调用方已设 AUTO_NEXT
                allowOwnLiftRecovery = false,
                endgameNeedsResetEvidence = true,
                timeoutS = Const.AUTO_NEXT_TIMEOUT_S,
                autoNextEnabled = autoNextEnabled,
                waitingStatus = BotStatus.AUTO_NEXT, // OCR 交互态退出后回切
            )
            val settledCorrected = when (val result = loop.run(expectation)) {
                null -> return false
                is StartLoopResult.Adopted -> {
                    // 我方提子恢复走子已完成并接管状态（已初始化、轮到敌方）：
                    // 跳过 state.reset/initialize/轮次判定，直接回主循环等敌方走子
                    LogBus.log(LogLevel.INFO, LogTag.NEXT, "提子恢复已接管棋局，跳过重新初始化")
                    return true
                }

                is StartLoopResult.Ready -> result.corrected
            }
            try {
                // state.reset() 不触碰 running（对齐最终版 python：无 keepRunning 过渡）
                state.reset()
                pendingPonderMove = null
                pendingPonderResult = null
                if (!initialize(settledCorrected)) return false
                engine.newGame(context)
                if (state.phase == Phase.ENDGAME) {
                    state.turn = state.mySide
                    LogBus.log(
                        LogLevel.INFO,
                        LogTag.NEXT,
                        "残局模式：轮到${state.mySide.cn}方（我方）走棋"
                    )
                } else {
                    val inferred = inferTurn(state.board, state.mySide, state.phase)
                    if (inferred != null) {
                        state.turn = inferred
                        LogBus.log(
                            LogLevel.INFO,
                            LogTag.NEXT,
                            if (inferred == state.mySide) "下一局开始：轮到${inferred.cn}方（我方）走棋"
                            else "下一局开始：轮到${inferred.cn}方走棋"
                        )
                    } else {
                        // 闯关排局（如 24 子中局形态）：无法静态推断轮次，默认我方（玩家）先行
                        state.turn = state.mySide
                        LogBus.log(
                            LogLevel.INFO,
                            LogTag.NEXT,
                            "排局模式：${state.phase.cn}，默认轮到${state.mySide.cn}方（我方）走棋"
                        )
                        // 布局不在此打印：initialize 已无条件落「摆棋布局」（2026-09-08 Q1 去重）
                    }
                }
                return true
            } finally {
                settledCorrected.release()
            }
        } finally {
            autoNextFlag = false
            emit()
        }
    }

    // ---------- 工具 ----------

    /** 截屏 + 识别；持有 corrected（Mat，调用方负责 release）与 scan（BoardScan 富结构）。 */
    private data class Grabbed(
        val corrected: Mat,
        val scan: BoardScan,
    )

    private suspend fun grabBoard(cap: Capture): Grabbed? {
        // 统一计时日志：所有截屏识别入口（敌方检测 / 我方校验 / 重试稳判 / 认输复检）共用，便于一处查看 grab+recog 耗时。
        val tGrab = System.nanoTime()
        val corrected = cap.grab() ?: return null
        val grabMs = (System.nanoTime() - tGrab) / 1_000_000
        val tRecog = System.nanoTime()
        val scan = recognizeBoardChanged(corrected, state.prevCellImgs, state.board, state.mySide)
        val recogMs = (System.nanoTime() - tRecog) / 1_000_000
        // 两行拆分（2026-09-09 D1=A，替代原「耗时拆解」单行混装；R4=A 收紧漂移触发）：
        // 行1「异常行」：仅慢帧/暂缓/剔除/漂移事件必打，安静期全静默——grep grabBoard 即性能与识别异常流；
        //   漂移仅在「无变化帧」（静止棋盘白点/高亮自愈）时才报——过渡期内已变格持续 diff 且识别值==提交值
        //   必然计为漂移，属物理必然（log3.txt 实测 611 次/2 局曾把门控击穿），不进异常行；
        //   且静止漂移本身高频（UI 光效逐帧像素漂移，log3 实测 468 条无变化帧漂移；指纹去重 R5 对
        //   逐帧波动噪音基本无效，467 行）→ 漂移限频打点（R7）：GRAB_LOG_DRIFT_INTERVAL_MS(3s) 内
        //   同因最多 1 条，log3 模拟 641 → 109 行；
        // 行2「变化行」：变化+未确认中文明细并一行，内容与上一条相同才静默——grep 棋盘变化 即走子过程回放
        val slow = grabMs > Const.GRAB_LOG_SLOW_MS
        val nowMs = System.nanoTime() / 1_000_000
        val driftReport = scan.driftCells.isNotEmpty() && scan.changes.isEmpty() &&
                nowMs - lastDriftLogMs >= Const.GRAB_LOG_DRIFT_INTERVAL_MS
        if (slow || scan.transitLifts > 0 || scan.unconfirmedCells > 0 || driftReport) {
            val parts = buildList {
                add("diff ${scan.diffCells}")
                if (scan.unconfirmedCells > 0) add("暂缓 ${scan.unconfirmedCells}")
                if (scan.transitLifts > 0) add("剔除 ${scan.transitLifts}")
                if (driftReport) add("漂移 ${scan.driftCells.size}")
            }
            val eventKey = parts.joinToString(" / ")
            if (slow || driftReport || eventKey != lastAnomalyKey) {
                lastAnomalyKey = eventKey
                if (driftReport) lastDriftLogMs = nowMs
                val prefix = if (slow) "慢帧 / " else ""
                LogBus.log(
                    LogLevel.DEBUG,
                    LogTag.VISION,
                    "grabBoard grab=${grabMs}ms recog=${recogMs}ms（$prefix$eventKey）"
                )
            }
        }
        // 行2：变化项「格 红X->黑Y[top1]」（D5 中文化；lift 概率仅显著时附注，D2=A）
        val items = scan.changes.mapTo(mutableListOf()) { ch ->
            val liftNote =
                if (ch.liftProb > Const.GRAB_LOG_LIFT_NOTE_MIN) ",lift${"%.2f".format(ch.liftProb)}" else ""
            "${gridToSquare(ch.r, ch.c, state.mySide)} ${ch.old?.let(::pieceLabel) ?: "空"}->" +
                    "${ch.new?.let(::pieceLabel) ?: "空"}[${"%.2f".format(ch.top1Prob)}$liftNote]"
        }
        scan.unconfirmedDetail?.let { items.add(it) } // 未确认格并入变化行（D4=A）
        val changeLine = items.joinToString(", ")
        if (changeLine.isNotEmpty() && changeLine != lastGrabLogKey) {
            lastGrabLogKey = changeLine
            lastAnomalyKey = null // 真实变化发生：异常行指纹重置，下一节拍的漂移/暂缓可重新首打
            LogBus.log(LogLevel.DEBUG, LogTag.VISION, "棋盘变化：$changeLine")
        }
        return Grabbed(corrected, scan)
    }

    /**
     * T-D 敌着两帧一致确认（2026-09-06）：立即复抓复判（不加显式延时——单次 grabBoard 本身
     * ~70-100ms，已足够越过半格飞行窗口），敌着在复判帧再次推断出才视为落定——动画中途帧
     * （如車 C0→C9 途经 C5，几何合法、伪合法校验拦截不了）的 cls 读数必已变化，两帧同着法即排除。
     *
     * @return 复抓帧（确认时供提交用，调用方负责 release）；未确认/中断/对局结束返回 null
     */
    private suspend fun reconfirmEnemyMoved(move: Move): Grabbed? {
        if (!running || interrupted || state.gameOver) return null
        val reGrab = grabBoard(capture) ?: return null
        val rf = classifyEnemyFrame(reGrab.scan.changes, state.mySide, state.board)
        return if (rf.result == EnemyFrameResult.MOVED && rf.enemyMove == move) reGrab else {
            reGrab.corrected.release()
            null
        }
    }

    /**
     * F2-A/F3-A（2026-09-08）：敌方思考超 Const.ENGINE_PONDER_CAP_MS 仍未走子 → 提前 ponderhit
     * 收割 ponder 预搜（裸 go ponder 无限预搜的唯一时间闸门，防慢敌手无限占 CPU）。
     * 结果缓存进 [pendingPonderResult]：Q 局面的我方应手——敌随后走 Y 直接消费，走 Z 作废重搜。
     * 非 ponder 态（ponderElapsedMs=-1 < CAP）自然跳过。
     */
    private fun maybeHarvestPonderCap() {
        if (pendingPonderMove == null || prematurePonderHarvested) return
        if (engine.ponderElapsedMs() < Const.ENGINE_PONDER_CAP_MS) return
        prematurePonderHarvested = true
        pendingPonderResult = engine.ponderHit()
        LogBus.log(
            LogLevel.DEBUG,
            LogTag.ENGINE,
            "敌方思考超 ${Const.ENGINE_PONDER_CAP_MS}ms，提前 ponderHit 收割预搜结果" +
                    "（${pendingPonderResult?.move ?: "无"}，depth ${pendingPonderResult?.depth ?: 0}）"
        )
    }

    /**
     * 敌着提交公共路径（三处提交点对称复用：waitForEnemyMove MOVED / 噪声复判 / 吞点击恢复）：
     * ponder 命中取预搜、未命中丢弃 → cellImgs 与 board 局部同步更新 → applyEnemyMove。
     */
    private fun commitEnemyMove(move: Move, grab: Grabbed) {
        val enemyIccs = gridToSquare(move.src.first, move.src.second, state.mySide) +
                gridToSquare(move.dst.first, move.dst.second, state.mySide)
        if (pendingPonderMove != null) {
            if (enemyIccs == pendingPonderMove) {
                if (prematurePonderHarvested) {
                    LogBus.log(
                        LogLevel.DEBUG,
                        LogTag.ENGINE,
                        "敌方走子命中预测（$enemyIccs），直接消费 CAP 提前收割的预搜结果"
                    )
                } else {
                    // F1-A：ponderHit 内部走主搜同款质量门控（elapsed<target 等满、质量达标才 stop）
                    pendingPonderResult = engine.ponderHit()
                    LogBus.log(
                        LogLevel.DEBUG,
                        LogTag.ENGINE,
                        "敌方走子命中预测（$enemyIccs），ponderHit 按质量门控取回预搜结果"
                    )
                }
            } else {
                engine.stopPonder()
                if (prematurePonderHarvested) {
                    // CAP 提前收割的结果属「Q=我方走子+预测敌着」局面，敌走了别的 → 作废
                    pendingPonderResult = null
                }
                LogBus.log(
                    LogLevel.DEBUG,
                    LogTag.ENGINE,
                    "敌方走子未命中预测（$enemyIccs≠${pendingPonderMove}），丢弃 ponder"
                )
            }
            pendingPonderMove = null
            prematurePonderHarvested = false
        }
        state.updateCellImgs(grab.corrected, grab.scan.changes, grab.scan.driftCells)
        applyEnemyMove(move)
    }

    private fun finishGame(reason: String) {
        state.markGameOver()
        setStatus(BotStatus.GAME_OVER)
        LogBus.log(LogLevel.INFO, LogTag.PLAY, reason)
    }

    private fun applyEnemyMove(move: Move) {
        state.applyEnemyMove(move)
        state.lastMove = "${gridToSquare(move.src.first, move.src.second, state.mySide)}-" +
                gridToSquare(move.dst.first, move.dst.second, state.mySide)
        LogBus.log(LogLevel.INFO, LogTag.ENEMY, formatMove(move, state.mySide))
        emit()
    }

    private fun setStatus(next: BotStatus) {
        status = next
        BotRuntime.status.value = next
        // 离开等待摆棋态：清空等待信息，避免收起小窗残留旧计时/子数
        if (next != BotStatus.WAIT_PLACEMENT) {
            BotRuntime.waitElapsedS.value = 0
            BotRuntime.waitDetail.value = ""
        }
        emit()
    }

    private fun emit() {
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
