package com.chess.bot.game

import android.content.Context
import com.chess.bot.accessibility.BotAccessibilityServiceHolder
import com.chess.bot.engine.EngineError
import com.chess.bot.engine.PikafishEngine
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import com.chess.bot.service.Capture
import com.chess.bot.vision.Recognizer
import com.chess.bot.vision.VisionInit
import com.chess.bot.overlay.BotRuntime
import kotlinx.coroutines.delay
import org.opencv.core.Mat

/**
 * 对局状态机（移植 python session.py，薄控制层）。
 *
 * 单工作线程串行执行；interrupt() 可从其它线程安全调用。
 * 日志经 LogBus 推送；状态行经 BotRuntime 同步悬浮窗。
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

    /** 轮次确认答案：null=待答；true=我方先走；false=暂不开始。UI 经 answerTurn 写入。 */
    @Volatile
    var turnAnswer: Boolean? = null
        private set

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

    /** 线程安全中断：打断自动对弈循环与轮次确认等待。 */
    fun interrupt() {
        interrupted = true
        BotRuntime.running.value = false
    }

    /** 网页弹窗等价：确认/拒绝我方先走。 */
    fun answerTurn(answer: Boolean?) {
        turnAnswer = answer
    }

    fun close() {
        interrupt()
        engine.close()
    }

    // ---------- 启动 ----------

    suspend fun start() {
        interrupted = false
        // 防抖：上一次 start 未结束前忽略重复点击（单线程队列会串行执行两次全量同步）
        if (!startGuard.compareAndSet(false, true)) {
            LogBus.log(LogKind.WARN, LogTag.PLAY, "启动流程进行中，忽略重复点击")
            return
        }
        try {
            val corrected = capture.grab()
            if (corrected == null) {
                emit()
                return
            }
            try {
                state.reset()
                if (!initialize(corrected)) {
                    emit()
                    return
                }

                var startNow = false
                val inferred = inferTurn(state.board, state.mySide, state.phase)
                if (inferred != null) {
                    state.turn = inferred
                } else {
                    startNow = confirmStart()
                    if (startNow) state.turn = state.mySide else {
                        LogBus.log(LogKind.OK, LogTag.PLAY, "我方为${state.mySide.cn}方，当前棋盘为${state.phase.cn}，未开始对弈")
                        emit()
                        return
                    }
                }

                emit()
                if (startNow || state.phase == Phase.OPENING) startFlow()
            } finally {
                corrected.release()
            }
        } catch (e: Exception) {
            LogBus.log(LogKind.ERROR, LogTag.PLAY, "启动棋局异常：${e::class.java.simpleName}: ${e.message}")
            running = false
            emit()
        } finally {
            startGuard.set(false)
        }
    }

    private val startGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    // ---------- 自动对弈主循环 ----------

    private suspend fun startFlow() {
        running = true
        emit()
        try {
            engine.newGame(context)
            flowLoop()
        } catch (e: Exception) {
            LogBus.log(LogKind.ERROR, LogTag.PLAY, "自动对弈异常终止：${e::class.java.simpleName}: ${e.message}")
        } finally {
            running = false
            emit()
        }
        LogBus.log(LogKind.DEBUG, LogTag.PLAY, "对弈主循环已退出")
    }

    private suspend fun flowLoop() {
        while (true) {
            if (!running || interrupted) break
            state.snapshotPrev()
            if (state.turn != state.mySide) {
                waitForEnemyMove()
            } else {
                if (!doMove()) {
                    if (state.gameOver) {
                        LogBus.log(LogKind.DEBUG, LogTag.PLAY, "我方走棋阶段检测到对局结束")
                    } else {
                        running = false
                        LogBus.log(LogKind.WARN, LogTag.PLAY, "走棋失败，自动对弈已暂停，可点击「开始棋局」重试")
                        break
                    }
                }
            }
            if (state.gameOver) {
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

    private suspend fun initialize(corrected: Mat): Boolean {
        val board = Recognizer.analyzeBoard(corrected, templates())
        val mySide = detectSide(board)
        if (mySide == null) {
            LogBus.log(LogKind.ERROR, LogTag.VISION, "无法判断我方红黑方（未识别到将/帥），请检查棋盘画面后重新同步")
            // 打印当前识别布局，便于定位：是将/帥被误识别成其他棋子，还是该画面本就无可识别将帅
            val count = board.sumOf { row -> row.count { it != null } }
            LogBus.log(LogKind.DEBUG, LogTag.VISION, "失败帧诊断：识别到 $count 个棋子")
            Recognizer.formatLayout(board).forEach { LogBus.log(LogKind.DEBUG, LogTag.VISION, "识别布局 $it") }
            return false
        }
        val phase = detectPhase(board, mySide)
        state.replaceBoard(board)
        state.markInitialized(mySide, phase)
        LogBus.log(LogKind.OK, LogTag.PLAY, "我方为${mySide.cn}方，当前棋盘为${phase.cn}")
        return true
    }

    // ---------- 我方走棋 ----------

    private suspend fun doMove(): Boolean {
        val pending = computeMove() ?: return false
        val unpacked = unpackMove(pending.first, pending.second) ?: return false
        val (r1, c1, r2, c2, piece) = unpacked
        state.resignStreak = 0
        for (attempt in 0 until Const.SELF_MOVE_ATTEMPTS) {
            if (!attemptMove(r1, c1, r2, c2)) continue
            val outcome = verify(r1, c1, r2, c2, piece)
            when (outcome) {
                VerifyOutcome.DONE_OK -> return true
                VerifyOutcome.DONE_END -> return false
                VerifyOutcome.LIFTED_ONLY -> {
                    LogBus.log(LogKind.INFO, LogTag.SELF, "棋子提起未落，补点落子")
                    capture.tap(r2, c2)
                    delay(Const.TAP_HOLD_INTERVAL_MS)
                    when (verify(r1, c1, r2, c2, piece)) {
                        VerifyOutcome.DONE_OK -> return true
                        VerifyOutcome.DONE_END -> return false
                        else -> {} // 未成功：交给下一轮整步重走
                    }
                }
                VerifyOutcome.TRANSIENT -> break // 有变动但没分类，不建议重走
                VerifyOutcome.STATIONARY -> {} // 建议外层重走
            }
        }
        LogBus.log(LogKind.WARN, LogTag.SELF, "走棋尝试失败，未检测到走棋成功")
        return false
    }

    private suspend fun computeMove(): Pair<String, String>? {
        if (!state.initialized) {
            LogBus.log(LogKind.WARN, LogTag.PLAY, "棋盘未初始化，无法生成着法")
            return null
        }
        val fen = fenOfBoard(state.board, state.mySide, state.turn, state.halfmoveClock)
        LogBus.log(LogKind.DEBUG, LogTag.ENGINE, "生成 FEN：$fen")
        LogBus.log(LogKind.DEBUG, LogTag.ENGINE, "计算着法中…")
        var result: Pair<String?, Int>
        try {
            result = engine.bestMove(context, fen)
        } catch (e: EngineError) {
            LogBus.log(LogKind.ERROR, LogTag.ENGINE, "引擎错误：${e.message}")
            return null
        }
        if (result.first == null) {
            val shortTime = Const.ENGINE_MOVETIME_MS * 2 / 3
            LogBus.log(LogKind.WARN, LogTag.ENGINE, "引擎无可用着法，改用 $shortTime ms 短时限重试")
            try {
                result = engine.bestMove(context, fen, shortTime)
            } catch (e: EngineError) {
                LogBus.log(LogKind.ERROR, LogTag.ENGINE, "重试引擎错误：${e.message}")
                return null
            }
        }
        if (result.first == null) {
            LogBus.log(LogKind.WARN, LogTag.ENGINE, "引擎无可用着法（对局可能已结束）")
            finishGame("引擎判定我方无路可走，对局结束")
            return null
        }
        state.lastEvalScore = result.second
        emit() // 引擎返回评估分后立即刷新悬浮窗状态行
        LogBus.log(LogKind.DEBUG, LogTag.ENGINE, "引擎着法：${result.first}（评估分 ${result.second}）")
        return fen to result.first!!
    }

    private data class Unpacked(
        val r1: Int, val c1: Int, val r2: Int, val c2: Int,
        val piece: String,
    )

    private fun unpackMove(fen: String, move: String): Unpacked? {
        val from = squareToGrid(move.substring(0, 2), state.mySide)
        val to = squareToGrid(move.substring(2, 4), state.mySide)
        val piece = state.boardAt(from.first, from.second)
        if (piece == null) {
            LogBus.log(LogKind.WARN, LogTag.PLAY, "引擎着法 $move 起点无我方棋子，棋盘数据可能已过期，请点击「开始棋局」重同步")
            return null
        }
        state.highlight = listOf(from, to)
        state.lastMove = move
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

    private suspend fun attemptMove(r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        val cap = capture
        if (!cap.tap(r1, c1)) return false
        delay(Const.TAP_HOLD_INTERVAL_MS)
        return cap.tap(r2, c2)
    }

    // ---------- 多帧校验 ----------

    private suspend fun verify(r1: Int, c1: Int, r2: Int, c2: Int, piece: String): VerifyOutcome {
        val expected = Move(r1 to c1, r2 to c2, piece)
        var stationary = true
        var liftedOnLast = false
        val cap = capture

        repeat(Const.MOVE_VERIFY_COUNT) { idx ->
            delay(Const.MOVE_SETTLE_MS)
            val grabbed = grabBoard(cap) ?: return@repeat
            if (!running || interrupted || state.gameOver) return VerifyOutcome.DONE_END
            val (newBoard, changes) = grabbed
            val fc = classifySelfFrame(changes, newBoard, expected, state.mySide, idx == Const.MOVE_VERIFY_COUNT - 1)

            when (fc.result) {
                FrameResult.SELF_DONE -> {
                    fc.selfMove?.let { state.applySelfMove(it) }
                    state.resignStreak = 0
                    emit()
                    checkmateProbe()
                    return VerifyOutcome.DONE_OK
                }
                FrameResult.SELF_THEN_ENEMY -> {
                    fc.selfMove?.let { s -> fc.enemyMove?.let { e -> state.applySelfThenEnemy(s, e) } }
                    state.resignStreak = 0
                    emit()
                    return VerifyOutcome.DONE_OK
                }
                FrameResult.LIFTED_ONLY -> {
                    liftedOnLast = true
                    state.resignStreak = 0
                }
                FrameResult.RESIGN_SUSPECT -> {
                    when (updateResign(newBoard)) {
                        ResignResult.CONFIRMED -> {
                            finishGame("检测到对局结束画面")
                            return VerifyOutcome.DONE_END
                        }
                        else -> {}
                    }
                }
                FrameResult.TRANSIENT -> {
                    stationary = false
                    state.resignStreak = 0
                }
                FrameResult.STATIONARY -> {}
            }
        }

        // 认输续帧确认（仅 streak>0 时进入）
        while (
            state.resignStreak > 0 && running && !interrupted && !state.gameOver
        ) {
            delay(Const.MOVE_SETTLE_MS)
            val grabbed = grabBoard(cap) ?: continue
            if (!running || interrupted || state.gameOver) return VerifyOutcome.DONE_END
            val (newBoard, _) = grabbed
            when (updateResign(newBoard)) {
                ResignResult.CONFIRMED -> {
                    finishGame("检测到对局结束画面")
                    return VerifyOutcome.DONE_END
                }
                ResignResult.SUSPECT -> delay(Const.RESIGN_SUSPECT_WAIT_MS)
                ResignResult.NONE -> break
            }
        }

        return if (liftedOnLast) VerifyOutcome.LIFTED_ONLY
        else if (stationary) VerifyOutcome.STATIONARY else VerifyOutcome.TRANSIENT
    }

    // ---------- 敌方走棋检测 ----------

    private suspend fun waitForEnemyMove() {
        state.resignStreak = 0
        state.noisyCount = 0
        state.liftLogged = false
        LogBus.log(LogKind.INFO, LogTag.PLAY, "等待对方走棋")
        val cap = capture
        while (running && !interrupted && !state.gameOver) {
            val grabbed = grabBoard(cap) ?: continue
            if (!running || interrupted || state.gameOver) break
            val (newBoard, changes) = grabbed
            when (val frame = classifyEnemyFrame(changes, state.mySide)) {
                is EnemyFrame.Moved -> {
                    applyEnemyMove(frame.move)
                    return
                }
                EnemyFrame.Lifted -> {
                    if (!state.liftLogged) {
                        state.liftLogged = true
                        LogBus.log(LogKind.INFO, LogTag.ENEMY, "对方提起棋子")
                    }
                    state.noisyCount = 0
                }
                EnemyFrame.Silent -> {
                    state.liftLogged = false
                    state.noisyCount = 0
                }
                EnemyFrame.Noisy -> {
                    when (updateResign(newBoard)) {
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
                    formatChanges(changes, state.mySide).forEach { LogBus.log(LogKind.DEBUG, LogTag.VISION, "识别变动 $it") }
                    if (state.noisyCount >= Const.ENEMY_NOISY_MAX) {
                        LogBus.log(
                            LogKind.WARN,
                            LogTag.PLAY,
                            "连续 ${Const.ENEMY_NOISY_MAX} 帧无法推断对方完整走法，暂停自动对弈",
                        )
                        running = false
                        emit()
                        return
                    }
                    delay(Const.ENEMY_RECHECK_WAIT_MS)
                }
            }
        }
        LogBus.log(LogKind.DEBUG, LogTag.PLAY, "已中断等待对方走棋")
    }

    // ---------- 认输 / 绝杀 / 和棋 ----------

    private fun updateResign(newBoard: Board): ResignResult {
        if (isResignSuspect(newBoard, state.mySide)) {
            state.resignStreak++
            LogBus.log(
                LogKind.DEBUG,
                LogTag.PLAY,
                "疑似对局结束画面（${state.resignStreak}/${Const.RESIGN_CONFIRM_COUNT}）",
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
        val opp = state.mySide.opponent
        val fen = fenOfBoard(state.board, state.mySide, opp, state.halfmoveClock)
        LogBus.log(LogKind.DEBUG, LogTag.ENGINE, "绝杀探测 FEN（${opp.cn}方行棋）：$fen")
        val mated = try {
            engine.isMate(context, fen)
        } catch (e: Exception) {
            LogBus.log(LogKind.WARN, LogTag.ENGINE, "引擎绝杀探测失败，当作未绝杀继续：${e::class.java.simpleName}: ${e.message}")
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
                if (!initialize(corrected)) return false
                engine.newGame(context)
                if (state.phase == Phase.ENDGAME) {
                    state.turn = Side.RED
                    LogBus.log(LogKind.GAME, LogTag.NEXT, "残局模式：轮到红方走棋")
                } else {
                    val inferred = inferTurn(state.board, state.mySide, state.phase)
                    if (inferred != null) {
                        state.turn = inferred
                        LogBus.log(LogKind.GAME, LogTag.NEXT, "下一局开始：轮到${inferred.cn}方走棋")
                    } else {
                        // 闯关排局（如 24 子中局形态）：无法静态推断轮次，
                        // 按 JJ 平台规则默认玩家（红方）先行；对齐 ENDGAME 固定红先规则
                        state.turn = Side.RED
                        LogBus.log(LogKind.GAME, LogTag.NEXT, "排局模式：${state.phase.cn}，默认轮到红方走棋")
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

    // ---------- 工具 / 交互 ----------

    private suspend fun grabBoard(cap: Capture): Pair<Board, List<Change>>? {
        val corrected = cap.grab() ?: return null
        try {
            return recognizeBoard(corrected, templates(), state.prevBoard)
        } finally {
            corrected.release()
        }
    }

    private suspend fun confirmStart(): Boolean {
        BotRuntime.pendingTurnConfirm.value = true
        turnAnswer = null
        LogBus.log(LogKind.INFO, LogTag.PLAY, "无法自动判断当前轮到哪一方，请在弹窗中选择是否由我方先走")
        while (turnAnswer == null) {
            if (interrupted) {
                BotRuntime.pendingTurnConfirm.value = false
                return false
            }
            delay(200)
        }
        BotRuntime.pendingTurnConfirm.value = false
        return turnAnswer == true
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

    private fun statusCn(): String = when {
        autoNextFlag -> "auto_next"
        state.gameOver -> "over"
        !state.initialized -> "idle"
        !running -> "stopped"
        else -> if (state.turn == Side.RED) "red" else "black"
    }

    private fun emit() {
        BotRuntime.running.value = running
        val sideCn = if (state.initialized) "${state.mySide.cn}方" else "-"
        val phaseCn = if (state.initialized) state.phase.cn else "-"
        val stateCn = when {
            autoNextFlag -> "自动下一局"
            state.gameOver -> "已结束"
            !state.initialized -> "未同步"
            running -> "轮到${state.turn.cn}方"
            else -> "已暂停"
        }
        // 前三段进状态行；评估分单独走数据流，悬浮窗据此着色（正=绿 负=红 0=白）
        BotRuntime.statusLine.value = "$phaseCn · $sideCn · $stateCn"
        BotRuntime.evalScore.value = state.lastEvalScore
    }
}
