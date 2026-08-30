package com.chess.bot.game

import android.content.Context
import com.chess.bot.book.ObkBook
import com.chess.bot.data.BotConfig
import com.chess.bot.engine.EngineError
import com.chess.bot.engine.EngineResult
import com.chess.bot.engine.PikafishEngine
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import com.chess.bot.overlay.BotRuntime
import com.chess.bot.service.Capture
import com.chess.bot.vision.Recognizer
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
 * - 开局库（OBK）优先：bookEnabled 且未超最大步数时先查书，未命中回落引擎
 *
 * 2026-08-29 性能改造（T1）：和棋弹窗检查事件化（异常帧/终局确认前才查，不再每帧全图 matchTemplate）
 * 2026-08-30 识别提速（方案 A 变种）：
 * - 维护 state.cellImgs（已提交棋盘 90 格 10x10 中心灰度小图，与 board 严格对齐）；
 *   snapshotPrev 时克隆为 prevCellImgs 作为逐格 diff 基线
 * - recognizeBoardChanged 仅对 diff 变化的格子跑模板匹配，未变格沿用 board；recog ~466ms→~20ms
 * - 提交点（initialize/verify/敌方 Moved）与 board 同步局部更新 cellImgs（仅改 changes 格子），
 *   避免「敌方提子未落子」等中间帧污染基线（全量更新会波及无关格，已弃用）
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

    /** 我方走子时引擎返回的预测敌着（ponder）；用于敌方思考期启动 ponder 预搜。 */
    private var pendingPonderMove: String? = null

    /** 敌方走子命中预测后，ponderHit 取回的我方预搜结果；下一轮 computeMove 直接消费（省一次引擎调用）。 */
    private var pendingPonderResult: EngineResult? = null

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

    private fun templates() = VisionInit.loadPieceTemplates(context)

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
            LogBus.log(LogKind.WARN, LogTag.PLAY, "启动流程进行中，忽略重复点击")
            return
        }
        try {
            state.reset()
            pendingPonderMove = null
            pendingPonderResult = null
            emit()
            val corrected = waitForBoardSettled()
            if (corrected == null) {
                running = false
                emit()
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
                LogKind.ERROR,
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

    /**
     * 摆棋稳定等待（绝杀探测改造同期）：
     * - 检测不到棋盘（0 子）→ 持续等待（周期日志）
     * - 32 子 → 新开局快速返回；31 子 → 持续等待（不降级，天然吸收提子中间帧）
     * - 其余子数 → SettleWaiter 逐值稳定计数（连续 3 帧相同）
     * - 不设超时：开始之后一直等摆棋直到手动停止（⌂ 返回 / 长按中断）。
     *
     * 返回摆棋完毕的矫正帧（所有权归调用方）；中断返回 null。
     */
    private suspend fun waitForBoardSettled(): Mat? {
        val waiter = SettleWaiter(LogTag.PLAY)
        val startAt = System.nanoTime()
        var lastLogAt = startAt
        setStatus(BotStatus.WAIT_PLACEMENT)
        LogBus.log(LogKind.INFO, LogTag.PLAY, "等待棋盘就绪（开始棋局，无超时，手动停止为止）")
        while (true) {
            if (interrupted) return null
            val corrected = capture.grab()
            if (corrected == null) {
                delay(Const.GAMEOVER_SCAN_INTERVAL_MS)
                continue
            }
            var handOff = false
            try {
                val board = Recognizer.analyzeBoard(corrected, templates())
                val count = board.sumOf { row -> row.count { it != null } }
                // 外抛等待态信息到悬浮窗（已等待秒数 + 子数/稳定摘要）
                BotRuntime.waitElapsedS.value =
                    ((System.nanoTime() - startAt) / 1_000_000_000L).toInt()
                BotRuntime.waitDetail.value = when {
                    count == 31 -> "子数 31（判定提子/残局）"
                    count == 0 -> "未识别到棋盘"
                    else -> "子数 $count · 稳定 ${waiter.stableProgress}/${waiter.threshold}"
                }
                if (count > 0) {
                    if (waiter.feed(board) is SettleWaiter.Feed.Ready) {
                        handOff = true
                        LogBus.log(LogKind.INFO, LogTag.PLAY, "棋盘已就绪（$count 子）")
                        return corrected
                    }
                }
                val now = System.nanoTime()
                if ((now - lastLogAt) / 1_000_000_000L >= Const.WAIT_BOARD_LOG_INTERVAL_S) {
                    lastLogAt = now
                    LogBus.log(LogKind.INFO, LogTag.PLAY, "等待摆棋：当前识别到 $count 个棋子")
                }
            } finally {
                if (!handOff) corrected.release()
            }
            delay(Const.GAMEOVER_SCAN_INTERVAL_MS)
        }
    }

    /** 首局轮次判定（审计 §二.E 三路径；轮次确认弹窗已删除）。 */
    private fun decideStartTurn() {
        val count = state.board.sumOf { row -> row.count { it != null } }
        if (count == 32 && plausibleNewGame(state.board, state.mySide)) {
            state.turn = Side.RED
            LogBus.log(LogKind.GAME, LogTag.PLAY, "完整新开局（32 子默认位），红方先走")
            return
        }
        val inferred = inferTurn(state.board, state.mySide, state.phase)
        if (inferred != null) {
            state.turn = inferred
            LogBus.log(LogKind.GAME, LogTag.PLAY, "轮次推断：轮到${inferred.cn}方走棋")
        } else {
            // 闯关排局 / 残局：无法静态推断轮次，默认我方（玩家）先行
            state.turn = state.mySide
            LogBus.log(
                LogKind.GAME,
                LogTag.PLAY,
                "无法推断轮次（${state.phase.cn}），默认我方（${state.mySide.cn}）先走"
            )
            Recognizer.formatLayout(state.board)
                .forEach { LogBus.log(LogKind.DEBUG, LogTag.VISION, "开局布局 $it") }
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
                LogKind.ERROR,
                LogTag.PLAY,
                "自动对弈异常终止：${e::class.java.simpleName}: ${e.message}"
            )
            setStatus(BotStatus.ABNORMAL_PAUSED)
        } finally {
            running = false
            setStatus(BotStatus.PAUSED)
        }
        LogBus.log(LogKind.DEBUG, LogTag.PLAY, "对弈主循环已退出")
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
                        LogBus.log(LogKind.DEBUG, LogTag.PLAY, "我方走棋阶段检测到对局结束")
                    } else if (running) {
                        // 走棋失败但未结束：doMove 内部已按守卫/中断处理并落日志
                        LogBus.log(
                            LogKind.WARN,
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
                    LogBus.log(LogKind.WARN, LogTag.NEXT, "自动下一局未开启")
                    break
                }
            }
        }
        emit()
    }

    // ---------- 初始化 ----------

    private fun initialize(corrected: Mat): Boolean {
        setStatus(BotStatus.INITIALIZING)
        val board = Recognizer.analyzeBoard(corrected, templates())
        val mySide = detectSide(board)
        if (mySide == null) {
            LogBus.log(
                LogKind.ERROR,
                LogTag.VISION,
                "无法判断我方红黑方（未识别到将/帥），已暂停；请检查棋盘画面后重新同步"
            )
            // 布局落盘（替代原轮次弹窗的兜底），便于定位误识别
            val count = board.sumOf { row -> row.count { it != null } }
            LogBus.log(LogKind.ERROR, LogTag.VISION, "失败帧诊断：识别到 $count 个棋子")
            Recognizer.formatLayout(board)
                .forEach { LogBus.log(LogKind.ERROR, LogTag.VISION, "识别布局 $it") }
            status = BotStatus.PAUSED
            return false
        }
        val phase = detectPhase(board, mySide)
        state.replaceBoard(board)
        state.resetCellImgs(corrected) // 开局全量重建 90 格中心小图（无动画中间帧风险）
        state.markInitialized(mySide, phase)
        LogBus.log(LogKind.OK, LogTag.PLAY, "我方为${mySide.cn}方，当前棋盘为${phase.cn}")
        return true
    }

    // ---------- 我方走棋（无限重试 + 守卫） ----------

    private suspend fun doMove(): Boolean {
        val pending = computeMove() ?: return false
        val unpacked = unpackMove(pending.move) ?: return false
        val (r1, c1, r2, c2, piece) = unpacked
        state.resignStreak = 0
        var zeroChange = 0
        var isLifted = false
        var attempt = 0
        while (true) {
            attempt++
            if (!running || interrupted) return false
            if (state.gameOver) {
                LogBus.log(LogKind.DEBUG, LogTag.SELF, "对局已结束，停止走棋重试")
                return false
            }
            // 上一轮若仅「提起未落」(LIFTED) 则本次只点目标格补落，避免重点头格把已提起的子又点下；
            // 否则正常走子（重试时 attemptMove 自带稳判：已落定则不再点、源空格则只点目标格）。
            val tapped = if (isLifted) {
                capture?.tap(r2, c2) ?: false
            } else {
                attemptMove(r1, c1, r2, c2, attempt > 1)
            }
            if (!tapped) {
                // 点按注入失败：保留一个固定延迟兜底，避免零延迟自旋占满 CPU；
                // 正常走子路径由 verifyForSelfMove 首帧 delay(firstWaitMs) 约 700ms+ 节流，无需额外退避。
                LogBus.log(
                    LogKind.WARN,
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

                VerifyOutcome.LIFTED -> {
                    // 棋子提起未落：标记 isLifted，下一轮只点目标格补落（verifyForSelfMove 首帧会等落定，无需额外 delay）。
                    LogBus.log(LogKind.INFO, LogTag.SELF, "棋子提起未落，补点落子")
                    isLifted = true
                    zeroChange = 0
                    continue
                }

                // 画面零变化(SILENT) / 无法归类(NOISY)：连续 N 步都未确认走棋成功 → 累计守卫计数（不 continue，落到下方守卫判定）。
                VerifyOutcome.SILENT, VerifyOutcome.NOISY -> {
                    isLifted = false
                    zeroChange++
                }
            }
            if (zeroChange >= Const.SELF_MOVE_ZERO_CHANGE_MAX) {
                LogBus.log(
                    LogKind.ERROR,
                    LogTag.SELF,
                    "连续 ${Const.SELF_MOVE_ZERO_CHANGE_MAX} 个整步画面零变化，疑似弹窗遮挡，自动对弈已暂停（处理后点「开始」续弈）",
                )
                Recognizer.formatLayout(state.board)
                    .forEach { LogBus.log(LogKind.ERROR, LogTag.VISION, "守卫触发布局 $it") }
                running = false
                setStatus(BotStatus.ABNORMAL_PAUSED)
                return false
            }
            LogBus.log(
                LogKind.DEBUG,
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
        // 当前 state.board 已含我方走子、轮到敌方 → 取「我方走子后」局面 FEN，叠加预测敌着 Y 作为 ponder 起点
        val fenAfterMyMove =
            fenOfBoard(state.board, state.mySide, state.turn, state.halfmoveClock)
        engine.startPonder(context, fenAfterMyMove, predicted)
        LogBus.log(
            LogKind.DEBUG,
            LogTag.ENGINE,
            "已启动 ponder（预测敌着 $predicted），敌方思考期预搜我方应手"
        )
    }

    /** computeMove 产物：着法（ICCS）——来源经 state.lastMoveSource 传递给悬浮窗引擎行。 */
    private data class PendingMove(val move: String)

    private suspend fun computeMove(): PendingMove? {
        if (!state.initialized) {
            LogBus.log(LogKind.WARN, LogTag.PLAY, "棋盘未初始化，无法生成着法")
            return null
        }
        setStatus(BotStatus.THINKING)
        selfMatePending = false
        // 敌方走子命中预判：直接消费 ponderHit 预搜结果，省去一次完整引擎搜索（仅当着法有效）
        val pre = pendingPonderResult
        pendingPonderMove = null
        if (pre != null) {
            pendingPonderResult = null
            if (pre.move != null) {
                state.lastMoveSource = MoveSource.ENGINE
                state.lastMoveDepth = pre.depth
                state.lastEvalScore = pre.scoreCp
                selfMatePending = pre.matePly == 1
                BotRuntime.bookWinRate.value = 0f
                emit()
                LogBus.log(
                    LogKind.DEBUG, LogTag.ENGINE,
                    "命中预判：直接使用 ponder 预搜着法 ${pre.move}（评估 ${pre.scoreCp}，depth ${pre.depth}）",
                )
                return PendingMove(pre.move!!)
            }
            // 预搜无着法（极罕见）→ 丢弃，退回常规搜索
        }
        val cfg = BotConfig.data

        // ---------- 开局库优先 ----------
        if (cfg.bookEnabled && state.moveCount < cfg.bookMaxMoves) {
            // 书库 vkey 按 ICCS 标准方向（黑上红下、a 列在左）计算；执黑时屏幕棋盘需先 180° 旋转归一化，
            // 返回的 ICCS 着法再经 unpackMove 的 squareToGrid(iccs, mySide) 转回屏幕网格（两条链路对称）
            val bookBoard =
                if (state.mySide == Side.BLACK) rotateBoard180(state.board) else state.board
            val hit =
                runCatching { ObkBook.get(context).queryBest(bookBoard, state.turn == Side.RED) }
                    .onFailure { e ->
                        LogBus.log(
                            LogKind.WARN,
                            LogTag.PLAY,
                            "开局库查询异常：${e::class.java.simpleName}: ${e.message}"
                        )
                    }
                    .getOrNull()
            if (hit != null) {
                state.lastMoveSource = MoveSource.BOOK
                state.lastMoveDepth = 0
                selfMatePending = false
                state.lastEvalScore = hit.vscore
                BotRuntime.bookWinRate.value = hit.winRate
                emit()
                LogBus.log(
                    LogKind.INFO,
                    LogTag.PLAY,
                    "开局库命中：${hit.iccs}（vkey=${hit.vkey}，vscore=${hit.vscore}，" +
                            "胜率${(hit.winRate * 100).roundToInt()}%）",
                )
                return PendingMove(hit.iccs)
            }
            LogBus.log(
                LogKind.DEBUG,
                LogTag.PLAY,
                "开局库未命中（已走 ${state.moveCount} 步），回落引擎"
            )
        }

        // ---------- 引擎 ----------
        val fen = fenOfBoard(state.board, state.mySide, state.turn, state.halfmoveClock)
        LogBus.log(LogKind.DEBUG, LogTag.ENGINE, "生成 FEN：$fen")
        LogBus.log(LogKind.DEBUG, LogTag.ENGINE, "计算着法中…")
        var result: EngineResult
        try {
            result = engine.bestMove(context, fen)
        } catch (e: EngineError) {
            LogBus.log(LogKind.ERROR, LogTag.ENGINE, "引擎错误：${e.message}")
            return null
        }
        // 记录引擎预测敌着，供我方走子后启动 ponder 预搜（仅引擎来源；开局库无预测）
        pendingPonderMove = result.ponderMove
        if (result.move == null) {
            val shortTime = Const.ENGINE_MOVETIME_MS * 2 / 3
            LogBus.log(LogKind.WARN, LogTag.ENGINE, "引擎无可用着法，改用 $shortTime ms 短时限重试")
            try {
                result = engine.bestMove(context, fen, movetimeMs = shortTime)
            } catch (e: EngineError) {
                LogBus.log(LogKind.ERROR, LogTag.ENGINE, "重试引擎错误：${e.message}")
                return null
            }
        }
        if (result.move == null) {
            LogBus.log(LogKind.WARN, LogTag.ENGINE, "引擎无可用着法（对局可能已结束）")
            finishGame("引擎判定我方无路可走，对局结束")
            return null
        }
        state.lastMoveSource = MoveSource.ENGINE
        state.lastMoveDepth = result.depth
        state.lastEvalScore = result.scoreCp
        // 主搜已声明 mate+1 = 本步着法即杀着；标记后 verify 直接终局，省去二次引擎调用
        selfMatePending = result.matePly == 1
        if (selfMatePending) {
            LogBus.log(LogKind.GAME, LogTag.ENGINE, "引擎判定本步绝杀（mate+1）：${result.move}")
        }
        BotRuntime.bookWinRate.value = 0f
        emit() // 引擎返回后立即刷新悬浮窗引擎行
        LogBus.log(
            LogKind.DEBUG,
            LogTag.ENGINE,
            "引擎着法：${result.move}（评估 ${result.scoreCp}，depth ${result.depth}）",
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
                LogKind.WARN,
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
        val evalNote = state.lastEvalScore.let { if (it > 0) "（评估 +$it）" else "（评估 $it）" }
        LogBus.log(
            LogKind.MOVE,
            LogTag.SELF,
            "走棋 $move：${pieceLabel(piece)} " +
                    "${gridToSquare(from.first, from.second, state.mySide)} -> " +
                    "${gridToSquare(to.first, to.second, state.mySide)}$capturedNote$evalNote",
        )
        return Unpacked(from.first, from.second, to.first, to.second, piece)
    }

    private suspend fun attemptMove(
        r1: Int,
        c1: Int,
        r2: Int,
        c2: Int,
        isRetry: Boolean = false
    ): Boolean {
        setStatus(BotStatus.TAPPING)
        val cap = capture ?: return false
        // 重试判稳（Fix 2，2026-08-30）：敌方与我方走棋动画重叠时，我方棋可能早在首点就落定，
        // 但 verify 因敌方中途格未 settle 而 TRANSIENT→进入重试。重试前先确认我方棋是否真已落定：
        //   - 源格空 且 落点已是己方棋子 → 已落定，不再点击（否则二次点击会把已落定的子重新提起，永不落稳）
        //   - 源格空（落点非我子）→ 子真浮在半空，仅点目标格落子
        //   - 否则 → 正常「先点源、再点目标」
        if (isRetry) {
            val grabbed = grabBoard(cap) ?: return false
            val srcEmpty = grabbed.scan.board[r1][c1] == null
            val dstMine =
                grabbed.scan.board[r2][c2]?.let { pieceColor(it) == state.mySide } ?: false
            val chg = grabbed.scan.changes.joinToString(", ") {
                "${gridToSquare(it.r, it.c, state.mySide)} ${it.old ?: "空"}->${it.new ?: "空"}"
            }
            LogBus.log(
                LogKind.DEBUG,
                LogTag.SELF,
                "重试识别：源($r1,$c1)${gridToSquare(r1, c1, state.mySide)}=" +
                        "${grabbed.scan.board[r1][c1] ?: "空"} 落点($r2,$c2)${
                            gridToSquare(
                                r2,
                                c2,
                                state.mySide
                            )
                        }=" +
                        "${grabbed.scan.board[r2][c2] ?: "空"} 变化${grabbed.scan.changes.size}格: $chg"
            )
            grabbed.corrected.release()
            if (srcEmpty && dstMine) {
                LogBus.log(
                    LogKind.DEBUG,
                    LogTag.SELF,
                    "重试：源格已空且落点已是己方棋子（走子已落定），无需重试"
                )
                return true
            }
            if (srcEmpty) {
                LogBus.log(LogKind.DEBUG, LogTag.SELF, "重试：源格已空（子已提起），仅点目标格落子")
                return cap.tap(r2, c2)
            }
        }
        if (!cap.tap(r1, c1)) return false
        delay(BotConfig.data.tapHoldMs.toLong())
        return cap.tap(r2, c2)
    }

    // ---------- 多帧校验 verifyForSelfMove（首帧等走子动画落定，之后按用户设置间隔，累计超 firstWaitMs+300ms 即跳出） ----------

    private suspend fun verifyForSelfMove(
        r1: Int,
        c1: Int,
        r2: Int,
        c2: Int,
        piece: String
    ): VerifyOutcome {
        setStatus(BotStatus.VERIFYING)
        val expected = Move(r1 to c1, r2 to c2, piece)
        val cap = capture
        // 首帧等待 = 走子动画公式（提起+飞一格+落下最低 400ms，每多飞一格 +60ms）；
        // 后续帧按用户设置间隔兜底。总检测窗口 = firstWaitMs + 300ms（与 verifyNextFrameMs 解耦，
        // 不论该间隔多小都能保证至少 300ms 复检）。VERIFY_ANIM_REDUNDANCY_MS 已废弃（由 +300ms 兜底覆盖）。
        val dist = maxOf(kotlin.math.abs(r2 - r1), kotlin.math.abs(c2 - c1))
        val firstWaitMs =
            BotConfig.data.verifyAnimBaseMs.toLong() + dist * Const.VERIFY_ANIM_PER_CELL_MS
        val maxWaitMs = firstWaitMs + 300L
        val tStart = System.nanoTime()
        var lastFc: SelfFrame? = null

        while (running && !interrupted && !state.gameOver) {
            val elapsed = (System.nanoTime() - tStart) / 1_000_000
            if (elapsed >= maxWaitMs) break
            // 首帧等动画落定；后续帧按用户设置间隔（时间窗由 maxWaitMs 控制，与 nextFrameMs 解耦）
            delay(if (lastFc == null) firstWaitMs else BotConfig.data.verifyNextFrameMs.toLong())
            if (!running || interrupted || state.gameOver) return VerifyOutcome.DONE_END

            val grabbed = grabBoard(cap) ?: continue
            try {
                val changes = grabbed.scan.changes
                val fc = classifySelfFrame(changes, grabbed.scan.board, expected, state.mySide)
                lastFc = fc
                val changeDetail = changes.joinToString(", ") {
                    "${gridToSquare(it.r, it.c, state.mySide)} ${it.old ?: "空"}->${it.new ?: "空"}"
                }
                LogBus.log(
                    LogKind.DEBUG,
                    LogTag.SELF,
                    "校验帧 result=${fc.result} 变化 ${changes.size} 格: $changeDetail"
                )

                when (fc.result) {
                    SelfFrameResult.SELF_DONE -> {
                        fc.selfMove?.let { state.applySelfMove(it) }
                        // 方案 A（对齐原始 python classifier）：我方走棋提交时，把「敌方棋子提起（enemy piece->空）」
                        // 这类 side-change 格排除出 updateCellImgs 的基线刷新——它们只是并发动画的临时格，
                        // 若把 baseline 刷成「空」，敌方整步会被拆两段：g5 离场被我提前冻结为「空」，
                        // 之后敌方车落到 e5 时只剩 e5 一格变化→判 NOISY（见 2026-08-30 17:49:50 日志误暂停）。
                        // 保留敌方子 baseline，待 waitForEnemyMove 看到「g5 离场 + e5 落子」两格才识别为 MOVED。
                        // 注：state.board 只由 applySelfMove 改（本步仅 f4->e6），g5 仍=黑車，无需额外处理。
                        val commitChanges = changes.filterNot { ch ->
                            ch.old != null && ch.new == null && pieceColor(ch.old) != state.mySide
                        }
                        state.updateCellImgs(
                            grabbed.corrected,
                            commitChanges,
                            grabbed.scan.driftCells
                        )
                        state.resignStreak = 0
                        emit()
                        // Option A：主搜已声明 mate+1 → 本步即杀着，直接终局，省去二次引擎调用
                        if (selfMatePending) {
                            selfMatePending = false
                            finishGame("我方绝杀，${state.mySide.opponent.cn}方无路可走")
                        } else {
                            checkmateProbe()
                        }
                        return VerifyOutcome.DONE_OK
                    }

                    SelfFrameResult.SELF_THEN_ENEMY -> {
                        fc.selfMove?.let { s ->
                            fc.enemyMove?.let { e ->
                                state.applySelfThenEnemy(s, e)
                                // 我方走棋动画与敌方走棋重叠（SELF_THEN_ENEMY）：敌方这一步也打印走棋日志（对齐 waitForEnemyMove 的 applyEnemyMove）
                                LogBus.log(LogKind.ENEMY, LogTag.ENEMY, formatMove(e, state.mySide))
                            }
                        }
                        state.updateCellImgs(grabbed.corrected, changes, grabbed.scan.driftCells)
                        state.resignStreak = 0
                        emit()
                        return VerifyOutcome.DONE_OK
                    }

                    // LIFTED / SILENT / NOISY 均非落定结论，继续等到超时；末帧再判定返回。
                    SelfFrameResult.LIFTED -> {
                        state.resignStreak = 0
                    }

                    SelfFrameResult.SILENT -> {
                        state.resignStreak = 0
                    }

                    SelfFrameResult.NOISY -> {
                        // 循环内不校验结束画面：末帧为 NOISY 时由循环外（下方）连续校验结束画面，
                        // 对齐「异常才校验」原则，不占用逐帧循环。
                    }
                }
            } finally {
                grabbed.corrected.release()
            }
        }

        // 末帧为 NOISY（无法判断）时循环外处理：和棋弹窗与终局都会产生大量棋子变动（NOISY），
        // 且会遮挡棋盘 → 仅在此分支校验一次和棋弹窗 + 连续校验结束画面（对齐「异常才校验」）。
        // SILENT（零变化=静止非遮挡）/LIFTED（我方棋子刚提起、明显在动画中）不需要校验。
        if (lastFc?.result == SelfFrameResult.NOISY) {
            // 1) 连续校验结束画面：逐帧 grab 调 updateResign，连续 RESIGN_CONFIRM_COUNT 次确认才终局
            //    （终局也有遮挡动画、棋子变动多，必落在 NOISY 区间，故放此处）。
            repeat(Const.RESIGN_CONFIRM_COUNT) {
                if (!running || interrupted || state.gameOver) return VerifyOutcome.DONE_END
                val grabbed = grabBoard(cap) ?: return@repeat
                try {
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

                        else -> {} // SUSPECT/NONE 继续下一次；streak 由 updateResign 维护
                    }
                } finally {
                    grabbed.corrected.release()
                }
            }
            // 2) 和棋弹窗：整屏 OCR，仅 NOISY 末帧触发一次（SILENT/LIFTED 不触发，因弹窗必产生大量变动=NOISY）。
            val tDraw = System.nanoTime()
            if (cap.dismissDrawDialog()) {
                LogBus.log(
                    LogKind.INFO,
                    LogTag.SELF,
                    "verifyForSelfMove 超时检出并关闭和棋弹窗，交由重试重新核验"
                )
                state.resignStreak = 0
            } else {
                val drawMs = (System.nanoTime() - tDraw) / 1_000_000
                if (drawMs > 100) LogBus.log(
                    LogKind.DEBUG,
                    LogTag.SELF,
                    "verify 异常校验 drawDialog 耗时 ${drawMs}ms（整屏 OCR，仅 NOISY 超时异常时触发一次）"
                )
            }
        }

        return when (lastFc?.result ?: SelfFrameResult.SILENT) {
            SelfFrameResult.LIFTED -> VerifyOutcome.LIFTED
            SelfFrameResult.SILENT -> VerifyOutcome.SILENT
            // NOISY 已在循环外末帧统一校验过结束画面；到此处仍未确认则交由 doMove 重试（零变化守卫计数）。
            else -> VerifyOutcome.NOISY
        }
    }

    // ---------- 敌方走棋检测 ----------

    // 棋盘识别提速（方案 A 变种，2026-08-30）：recognizeBoardChanged 每轮逐格 10x10 中心小图 diff，
    // 仅变化格跑模板匹配，未变格沿用 board；不再做全量 90 格匹配（原 ~466ms/帧 → ~20ms/帧）。

    private suspend fun waitForEnemyMove() {
        setStatus(BotStatus.WAIT_ENEMY)
        state.resignStreak = 0
        state.noisyCount = 0
        state.liftLogged = false
        LogBus.log(LogKind.INFO, LogTag.PLAY, "等待对方走棋")
        val cap = capture
        while (running && !interrupted && !state.gameOver) {
            val grabbed = grabBoard(cap) ?: continue
            try {
                val newBoard = grabbed.scan.board
                val changes = grabbed.scan.changes
                if (!running || interrupted || state.gameOver) break
                val frame = classifyEnemyFrame(changes, state.mySide)
                when (frame.result) {
                    EnemyFrameResult.MOVED -> {
                        frame.enemyMove?.let { move ->
                            setStatus(BotStatus.ENEMY_CONFIRM)
                            // 敌方走子是否命中预测：命中→ponderHit 取预搜结果，未命中→stop 丢弃
                            val enemyIccs =
                                gridToSquare(
                                    move.src.first,
                                    move.src.second,
                                    state.mySide
                                ) +
                                        gridToSquare(
                                            move.dst.first,
                                            move.dst.second,
                                            state.mySide
                                        )
                            if (pendingPonderMove != null && enemyIccs == pendingPonderMove) {
                                pendingPonderResult = engine.ponderHit()
                                LogBus.log(
                                    LogKind.DEBUG,
                                    LogTag.ENGINE,
                                    "敌方走子命中预测（$enemyIccs），ponderHit 取回预搜结果"
                                )
                            } else if (pendingPonderMove != null) {
                                engine.stopPonder()
                                LogBus.log(
                                    LogKind.DEBUG,
                                    LogTag.ENGINE,
                                    "敌方走子未命中预测（$enemyIccs≠${pendingPonderMove}），丢弃 ponder"
                                )
                            }
                            pendingPonderMove = null
                            // 与 board 同步局部更新（Moved 帧已落定）；driftCells 一并自愈白点/高亮漂移
                            state.updateCellImgs(
                                grabbed.corrected,
                                changes,
                                grabbed.scan.driftCells
                            )
                            applyEnemyMove(move)
                            return
                        }
                    }

                    EnemyFrameResult.LIFTED -> {
                        if (!state.liftLogged) {
                            state.liftLogged = true
                            LogBus.log(LogKind.INFO, LogTag.ENEMY, "对方提起棋子")
                        }
                        state.noisyCount = 0
                    }

                    EnemyFrameResult.SILENT -> {
                        state.liftLogged = false
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
                                continue
                            }

                            ResignResult.NONE -> {}
                        }
                        state.liftLogged = false
                        state.noisyCount++
                        formatChanges(changes, state.mySide).forEach {
                            LogBus.log(
                                LogKind.DEBUG,
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
                                    val rf = classifyEnemyFrame(reMove.scan.changes, state.mySide)
                                    if (rf.result == EnemyFrameResult.MOVED && rf.enemyMove != null) {
                                        val move = rf.enemyMove
                                        setStatus(BotStatus.ENEMY_CONFIRM)
                                        val enemyIccs =
                                            gridToSquare(
                                                move.src.first,
                                                move.src.second,
                                                state.mySide
                                            ) +
                                                    gridToSquare(
                                                        move.dst.first,
                                                        move.dst.second,
                                                        state.mySide
                                                    )
                                        if (pendingPonderMove != null && enemyIccs == pendingPonderMove) {
                                            pendingPonderResult = engine.ponderHit()
                                            LogBus.log(
                                                LogKind.DEBUG,
                                                LogTag.ENGINE,
                                                "敌方走子命中预测（$enemyIccs），ponderHit 取回预搜结果",
                                            )
                                        } else if (pendingPonderMove != null) {
                                            engine.stopPonder()
                                            LogBus.log(
                                                LogKind.DEBUG,
                                                LogTag.ENGINE,
                                                "敌方走子未命中预测（$enemyIccs≠${pendingPonderMove}），丢弃 ponder",
                                            )
                                        }
                                        pendingPonderMove = null
                                        state.updateCellImgs(
                                            reMove.corrected,
                                            reMove.scan.changes,
                                            reMove.scan.driftCells,
                                        )
                                        applyEnemyMove(move)
                                        return
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

                                        else -> {}
                                    }
                                } finally {
                                    re.corrected.release()
                                }
                                if (ended) return@repeat
                            }
                            if (ended) return
                            LogBus.log(
                                LogKind.WARN,
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
                // 每轮全量识别后短暂让步，避免单工作线程被识别独占（识别本身已 ~250ms，此延迟仅节流）
                delay(Const.ENEMY_IDLE_POLL_MS)
            } finally {
                grabbed.corrected.release()
            }
        }
        LogBus.log(LogKind.DEBUG, LogTag.PLAY, "已中断等待对方走棋")
    }

    // ---------- 认输 / 绝杀 / 和棋 ----------

    private fun updateResign(newBoard: Board, changes: List<Change>): ResignResult {
        // 提速后游戏结束动画渐进遮盖将帅，单帧「两将缺失」信号会抖动；改用更稳定的清盘信号：
        // 单帧 >6 个已提交棋子变为空（changes 中 old!=null && new==null），与「两将缺失」取 OR 判疑似结束。
        val emptyDrop = changes.count { it.old != null && it.new == null }
        val suspect =
            isResignSuspect(newBoard, state.mySide) || emptyDrop > Const.RESIGN_EMPTY_DROP_MAX
        if (suspect) {
            state.resignStreak++
            LogBus.log(
                LogKind.DEBUG,
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

    private suspend fun checkmateProbe(): Boolean {
        // Option A：仅终局附近（子少）才二次调用引擎验证，常规中局主搜已覆盖将死，跳过以减少引擎开销
        val count = state.board.sumOf { row -> row.count { it != null } }
        if (count > Const.ENDGAME_PROBE_PIECE_MAX) {
            LogBus.log(LogKind.DEBUG, LogTag.ENGINE, "非终局（${count} 子），跳过绝杀二次探测")
            return false
        }
        setStatus(BotStatus.GAMEOVER_CHECK)
        val opp = state.mySide.opponent
        val fen = fenOfBoard(state.board, state.mySide, opp, state.halfmoveClock)
        LogBus.log(LogKind.DEBUG, LogTag.ENGINE, "绝杀探测 FEN（${opp.cn}方行棋）：$fen")
        val mated = try {
            engine.isMate(context, fen)
        } catch (e: Exception) {
            LogBus.log(
                LogKind.WARN,
                LogTag.ENGINE,
                "引擎绝杀探测失败，当作未绝杀继续：${e::class.java.simpleName}: ${e.message}"
            )
            return false
        }
        if (!mated) {
            LogBus.log(LogKind.DEBUG, LogTag.ENGINE, "未绝杀，继续对局")
            return false
        }
        finishGame("我方绝杀，${opp.cn}方无路可走")
        return true
    }

    private fun decideDraw(): Boolean {
        val score = state.lastEvalScore
        val reject = decideDraw(score, Const.DRAW_REJECT_CP)
        LogBus.log(
            LogKind.INFO,
            LogTag.PLAY,
            if (reject) "和棋决策：我方占优（${score}cp），拒绝和棋" else "和棋决策：均势或劣势（${score}cp），同意和棋",
        )
        return reject
    }

    // ---------- 自动下一局 ----------

    private suspend fun autoNextGame(): Boolean {
        if (interrupted) return false
        LogBus.log(LogKind.INFO, LogTag.NEXT, "开始自动下一局")
        autoNextFlag = true
        setStatus(BotStatus.AUTO_NEXT)
        emit()
        try {
            val autoNext = AutoNext(
                context,
                capture,
                shouldContinue = { running && !interrupted },
                autoNextEnabled = autoNextEnabled,
            )
            val corrected = autoNext.scanAndWait() ?: return false
            try {
                // state.reset() 不触碰 running（对齐最终版 python：无 keepRunning 过渡）
                state.reset()
                pendingPonderMove = null
                pendingPonderResult = null
                if (!initialize(corrected)) return false
                engine.newGame(context)
                if (state.phase == Phase.ENDGAME) {
                    state.turn = state.mySide
                    LogBus.log(
                        LogKind.GAME,
                        LogTag.NEXT,
                        "残局模式：轮到${state.mySide.cn}方（我方）走棋"
                    )
                } else {
                    val inferred = inferTurn(state.board, state.mySide, state.phase)
                    if (inferred != null) {
                        state.turn = inferred
                        LogBus.log(LogKind.GAME, LogTag.NEXT, "下一局开始：轮到${inferred.cn}方走棋")
                    } else {
                        // 闯关排局（如 24 子中局形态）：无法静态推断轮次，默认我方（玩家）先行
                        state.turn = state.mySide
                        LogBus.log(
                            LogKind.GAME,
                            LogTag.NEXT,
                            "排局模式：${state.phase.cn}，默认轮到${state.mySide.cn}方（我方）走棋"
                        )
                        Recognizer.formatLayout(state.board)
                            .forEach { LogBus.log(LogKind.DEBUG, LogTag.VISION, "排局布局 $it") }
                    }
                }
                return true
            } finally {
                corrected.release()
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
        val scan = recognizeBoardChanged(corrected, templates(), state.prevCellImgs, state.board)
        val recogMs = (System.nanoTime() - tRecog) / 1_000_000
        LogBus.log(
            LogKind.DEBUG,
            LogTag.VISION,
            "grabBoard 耗时拆解 grab=${grabMs}ms recog=${recogMs}ms（diff 命中 ${scan.diffFires} 格 / 变化 ${scan.changes.size} 格）"
        )
        return Grabbed(corrected, scan)
    }

    private fun finishGame(reason: String) {
        state.markGameOver()
        LogBus.log(LogKind.GAME, LogTag.PLAY, reason)
        emit()
    }

    private fun applyEnemyMove(move: Move) {
        state.applyEnemyMove(move)
        state.lastMove = "${gridToSquare(move.src.first, move.src.second, state.mySide)}-" +
                gridToSquare(move.dst.first, move.dst.second, state.mySide)
        LogBus.log(LogKind.ENEMY, LogTag.ENEMY, formatMove(move, state.mySide))
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
