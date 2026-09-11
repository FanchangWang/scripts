package com.chess.bot.game

import com.chess.bot.book.ObkBook
import com.chess.bot.data.BotConfig
import com.chess.bot.engine.EngineError
import com.chess.bot.engine.EngineResult
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.chess.bot.overlay.BotRuntime
import com.chess.bot.vision.Recognizer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * BotSession 主循环+我方走子（B4 拆分自 BotSession.kt，2026-09-09 方案 6 / D1=A）。
 *
 * 职责：首局轮次判定 + 自动对弈主循环（flowLoop）+ 我方走棋（doMove 无限重试+守卫）+
 * 开局库/引擎着法计算（computeMove）+ ponder 启动与预搜消费 + 着法解包与点击（unpackMove/attemptMove）+
 * 评估显示文本（evalDetail/evalOverlayText）+ mate 记账（recordMateInfo）+ 自动下一局（autoNextGame）。
 * 嵌套类 PendingMove/Unpacked 随主消费者顶层化（已验证无 BotSession. 限定引用）。
 */

/** 首局轮次判定（审计 §二.E 三路径；轮次确认弹窗已删除）。 */
internal fun BotSession.decideStartTurn() {
    val count = pieceCount(state.board)
    if (count == 32 && plausibleNewGame(state.board, state.mySide)) {
        state.turn = Side.RED
        LogBus.log(LogLevel.INFO, LogTag.PLAY, "完整新开局（32 子默认位），红方先走")
    } else {
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
    // R3 配套：轮次已定 → 拍引擎 position 基线（32 子局 = 初始局面，其后全部着法进 movesList）
    state.ensureEngineBaseline()
}

// ---------- 自动对弈主循环 ----------

internal suspend fun BotSession.startFlow() {
    running = true
    emit()
    try {
        // U-1（2026-09-11）：提子恢复路径（recoverOwnLift）已发过 ucinewgame 复位 TT，
        // 此处不重复发——同一次启动流程内 ucinewgame 只发一次；正常启动路径（标志为 false）照常发。
        if (engineAlreadyReset) engineAlreadyReset = false else engine.newGame(context)
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
        // C5 批复（2026-09-11）：异常暂停（走棋/等敌总超时、主循环异常）保留给用户看，不再被无条件
        // 打回「已暂停」——两种退出原因在状态行上可辨识
        pauseIfNotAbnormal()
    }
    LogBus.log(LogLevel.DEBUG, LogTag.PLAY, "对弈主循环已退出")
}

internal suspend fun BotSession.flowLoop() {
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
                } else if (interrupted) {
                    // E 批复（2026-09-11）：用户主动点「停止」——本步搜索/结果整体作废，主循环结束。
                    // 再次点「开始」一律按**新一局**处理（重新截屏全量同步；已走棋即视为残局），
                    // 不续接中断前的局面与着法记录（state.reset 已清基线/moves）。
                    LogBus.log(
                        LogLevel.INFO,
                        LogTag.PLAY,
                        "已按用户请求停止，本步结果已作废；重新点「开始」将按新一局处理",
                    )
                } else if (running) {
                    // 走棋失败但未结束：doMove 内部已按总超时处理并落日志
                    LogBus.log(
                        LogLevel.WARN,
                        LogTag.PLAY,
                        "走棋中止，自动对弈已暂停，可点击「开始」重新开始（按新一局处理）"
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

// ---------- 我方走棋（无限重试 + 总超时） ----------

/**
 * 我方走棋（无限重试 + 总超时）。
 * @param dstOnlySrc 提子恢复场景（2026-09-08）传提子格坐标：着法源格==该格时棋子已在手，
 * 首击直接补落目标格（跳过点源格——对提着中的棋子再点源格行为不可控）。
 */
internal suspend fun BotSession.doMove(dstOnlySrc: Pair<Int, Int>? = null): Boolean {
    // C4 批复（2026-09-11）：总超时起点提前到入口——computeMove 的引擎思考时长一并计入
    // SELF_MOVE_TOTAL_TIMEOUT_MS(60s) 预算，避免「思考久 + 走棋重试」两段各自计时而叠加越界。
    val startMs = System.nanoTime() / 1_000_000
    val pending = computeMove() ?: return false
    // E 中断守卫（2026-09-11）着子前复检：computeMove 期间用户可能已点「停止」——
    // 此刻立刻放弃，不再向棋盘注入点击（避免「已停止却仍落子」）
    if (!running || interrupted) return false
    val unpacked = unpackMove(pending.move) ?: return false
    val (r1, c1, r2, c2, piece) = unpacked
    state.resignStreak = 0
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
        // D1=A 总超时（2026-09-10；C4 2026-09-11 起点提前到入口含思考时间）：无限重试由总时长封顶。
        // 设备卡顿（点击排队延迟）场景数秒内自行恢复后下一次补点即成功；真遮挡/着法被拒场景 60s 后暂停交用户处理。
        if (System.nanoTime() / 1_000_000 - startMs > Const.SELF_MOVE_TOTAL_TIMEOUT_MS) {
            LogBus.log(
                LogLevel.ERROR,
                LogTag.SELF,
                "走棋总超时 ${Const.SELF_MOVE_TOTAL_TIMEOUT_MS / 1000}s（共尝试 $attempt 次），" +
                        "疑似设备卡顿或弹窗遮挡，自动对弈已暂停（点「开始」重新开始，按新一局处理）",
            )
            Recognizer.formatLayout(state.board)
                .forEach { LogBus.log(LogLevel.WARN, LogTag.VISION, "守卫触发布局 $it") }
            running = false
            setStatus(BotStatus.ABNORMAL_PAUSED)
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
                // 我方提子未落：补点目标格（无限重试，总超时兜底；2026-09-10 D1=A 删零变化守卫）
                dstOnly = true
            }

            VerifyOutcome.RETRY_AFTER_ENEMY -> {
                // 点击被吞、敌方已先走（敌着已在 verify 恢复分支提交，轮到我方）：
                // 立即重试本步走子（2026-09-06 T-B；D2=A 不走重试冷却）
                dstOnly = false
                attempt = 0
                continue
            }

            // 两次点击均未生效（RETRY_BOTH，含稳定未知兜底）：落循环末尾冷却后重试
            VerifyOutcome.RETRY_BOTH -> {
                dstOnly = false
            }
        }
        LogBus.log(
            LogLevel.DEBUG,
            LogTag.SELF,
            "走棋未确认（第 $attempt 次，$outcome）",
        )
        delay(Const.SELF_RETRY_COOLDOWN_MS) // D2=A：重试点击冷却，防卡顿设备点击事件堆积
    }
}

/** 我方走子成功后启动 ponder（需引擎提供预测敌着）；敌方思考期预搜我方应手以加速走棋。 */
internal fun BotSession.maybeStartPonder() {
    // 仅在轮到敌方时启动 ponder：正常 SELF_DONE 后敌方思考期预搜我方应手；
    // 若已 SELF_THEN_ENEMY（敌方与本方走子动画重叠、敌方已落子），轮到我方，ponder 无意义且会被紧接着的 bestMove 强制 stopPonder 浪费。
    if (state.turn != state.mySide.opponent) return
    val predicted = pendingPonderMove ?: return
    prematurePonderHarvested = false // 新一轮预搜开始，清除上一轮收割标志
    // position = 基线 FEN + moves 全列表（movesList 已含我方刚走的这步）+ 预测敌着追加尾部
    engine.startPonder(context, state.ensureEngineBaseline(), state.movesList, predicted)
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
internal data class PendingMove(val move: String)

internal suspend fun BotSession.computeMove(): PendingMove? {
    if (!state.initialized) {
        LogBus.log(LogLevel.WARN, LogTag.PLAY, "棋盘未初始化，无法生成着法")
        return null
    }
    setStatus(BotStatus.THINKING)
    selfMatePending = false
    // E 中断守卫（2026-09-11；E 批复 2026-09-11）：用户点「停止」→ 本次着法计算**整体作废**，
    // 开局库 / ponder 预搜 / 引擎三条来源一律不再出着法（中断就是中断，不落盘任何中间态：
    // 不写 pendingPonderMove 与 state.lastMove*，也不落入「引擎无着法 → 终局」误判分支）。
    // computeMove 与 doMove 同线程，此处只读会话标志、无竞态；doMove 在点击前另有一道复检兜底。
    val shouldAbort: () -> Boolean = { !running || interrupted }
    fun droppedByInterrupt(): Boolean {
        if (!shouldAbort()) return false
        LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "已中断，丢弃本次着法计算结果，不产生着法")
        return true
    }
    if (droppedByInterrupt()) return null
    // 敌方走子命中预判：直接消费 ponderHit 预搜结果，省去一次完整引擎搜索（仅当着法有效）
    val pre = pendingPonderResult
    pendingPonderMove = null
    if (pre != null) {
        pendingPonderResult = null
        // 局部捕获快照：判空后跨多语句访问属性无法智能转换，快照值也消除中途变化风险
        val preMove = pre.move
        // 不再用 qualityReached 判断；只要给出有效着法（且有评估佐证）就直接用
        if (preMove != null && !(pre.depth == null && pre.scoreCp == null)) {
            state.lastMoveSource = MoveSource.ENGINE
            state.lastMoveDepth = pre.depth ?: 0
            state.lastEvalScore = pre.scoreCp ?: 0
            state.lastEvalScoreUnreliable = pre.scoreCp == null
            state.lastMatePly = pre.matePly
            recordMateInfo(pre)
            BotRuntime.bookWinRate.value = 0f
            emit()
            LogBus.log(
                LogLevel.DEBUG, LogTag.ENGINE,
                "命中预判：采用 ponder 预搜着法 $preMove（${evalDetail(pre)}），预测敌着 ${pre.ponderMove ?: "-"}",
            )
            return PendingMove(preMove)
        }
        if (preMove != null) {
            LogBus.log(
                LogLevel.DEBUG, LogTag.ENGINE,
                "预搜着法 $preMove 无评估佐证（无有效 info），丢弃预搜，常规搜索",
            )
        }
        // 预搜无着法（极罕见）→ 丢弃，退回常规搜索
    }
    val cfg = BotConfig.data

    // ---------- 开局库优先（启用即全程生效，命中走书、未命中回落引擎） ----------
    // E2（2026-09-11）：引擎一旦返回 mate，本局挂起书（state.bookSuspendedByMate），直接走引擎
    if (cfg.bookEnabled && !state.bookSuspendedByMate) {
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
    if (droppedByInterrupt()) return null // 开局库查询/预搜消费期间发生的中断
    val baselineFen = state.ensureEngineBaseline() // 轮次判定已拍，此处防御兜底
    LogBus.log(
        LogLevel.DEBUG, LogTag.ENGINE,
        "计算着法中：基线局面 + moves ${state.movesList.size} 手（position 演进制）"
    )
    var result: EngineResult
    try {
        result = engine.bestMove(context, baselineFen, state.movesList, interrupted = shouldAbort)
    } catch (e: EngineError) {
        LogBus.log(LogLevel.ERROR, LogTag.ENGINE, "引擎错误：${e.message}")
        return null
    }
    if (droppedByInterrupt()) return null
    if (result.move == null) {
        val shortTime = Const.ENGINE_MOVETIME_MS * 2 / 3
        LogBus.log(LogLevel.WARN, LogTag.ENGINE, "引擎无可用着法，改用 $shortTime ms 短时限重试")
        try {
            result = engine.bestMove(
                context, baselineFen, state.movesList,
                movetimeMs = shortTime, interrupted = shouldAbort,
            )
        } catch (e: EngineError) {
            LogBus.log(LogLevel.ERROR, LogTag.ENGINE, "重试引擎错误：${e.message}")
            return null
        }
        if (droppedByInterrupt()) return null
    }
    // 记录引擎预测敌着，供我方走子后启动 ponder 预搜（仅引擎来源；开局库无预测）
    pendingPonderMove = result.ponderMove
    if (result.move == null) {
        LogBus.log(LogLevel.WARN, LogTag.ENGINE, "引擎无可用着法（对局可能已结束）")
        finishGame("引擎判定我方无路可走，对局结束")
        return null
    }
    state.lastMoveSource = MoveSource.ENGINE
    state.lastMoveDepth = result.depth ?: 0
    state.lastEvalScore = result.scoreCp ?: 0
    state.lastEvalScoreUnreliable = result.scoreCp == null
    state.lastMatePly = result.matePly
    // 主搜已声明 mate+1 = 本步着法即杀着；标记后 verify 直接终局
    recordMateInfo(result)
    if (selfMatePending) {
        LogBus.log(LogLevel.INFO, LogTag.ENGINE, "引擎判定本步绝杀（mate+1）：${result.move}")
    }
    BotRuntime.bookWinRate.value = 0f
    emit() // 引擎返回后立即刷新悬浮窗引擎行
    LogBus.log(
        LogLevel.DEBUG,
        LogTag.ENGINE,
        "引擎着法：${result.move}（${evalDetail(result)}），预测敌着 ${result.ponderMove ?: "-"}",
    )
    return PendingMove(result.move)
}

internal data class Unpacked(
    val r1: Int, val c1: Int, val r2: Int, val c2: Int,
    val piece: String,
)

internal fun BotSession.unpackMove(move: String): Unpacked? {
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
    // B-3（2026-09-11）：点击前对已提交棋盘做伪合法断言 —— R3「基线/moves 与 board 静默分叉」的检出器。
    // 我方着法来自引擎/开局库，正常必然合法；一旦在 board 上非法，说明发给引擎的 position 与已提交
    // board 已不是同一局面（引擎在错误局面上算棋）→ 立刻中止报错，而不是盲点 + 把错误着法写进 movesList。
    if (!isPseudoLegal(state.board, Move(from, to, piece), state.mySide)) {
        LogBus.log(
            LogLevel.ERROR,
            LogTag.PLAY,
            "着法 $move（${pieceLabel(piece)}）在已提交棋盘上伪合法校验失败，" +
                    "疑似棋盘状态与引擎局面分叉，中止走棋；请检查局面后点「开始」重同步"
        )
        Recognizer.formatLayout(state.board, state.mySide)
            .forEach { LogBus.log(LogLevel.WARN, LogTag.VISION, "分叉诊断布局 $it") }
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
internal suspend fun BotSession.attemptMove(
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

/** 引擎结果评估详情：无有效 info（scoreCp/depth 为 null）→ 显示「评估 -，depth -」，
 *  不把占位 0 伪装成真实评分；matePly 正=我方 N 步内绝杀 / 负=被绝杀（EngineInfoPick 视角：行棋方），
 *  显示「绝杀 N」更直观（用户要求）；mate 时评估分（100000-N）与绝杀并列显示
 *（2026-09-09 用户要求三字段齐显，scoreCp 与 matePly 同源皆真实）。 */
internal fun BotSession.evalDetail(r: EngineResult): String {
    val score = r.scoreCp
    val depth = r.depth
    if (score == null || depth == null) return "评估 -，depth -"
    return when {
        r.matePly != null && r.matePly > 0 -> "绝杀 ${r.matePly}，评估 ${"%+d".format(score)}，depth $depth"
        r.matePly != null -> "被绝杀 ${-r.matePly}，评估 ${"%+d".format(score)}，depth $depth"
        else -> "评估 ${"%+d".format(score)}，depth $depth"
    }
}

/** 信息框第四行评估文本（2026-09-09 Q2）：mate 优先「绝杀 N/被绝杀 N」（与 evalDetail 同语义），
 *  无有效 info 步「评估 -」（占位 0 不再显示为评分），普通局面「+N/N」；着色由 evalScore 正负决定，无需额外传色。 */
internal fun BotSession.evalOverlayText(): String = when {
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

/** mate 记账：matePly == 1 = 本步即杀着 → 走完验证后直接终局（绝杀只认 info mate）。
 *  E2（2026-09-11）：此处亦是「引擎已返回 mate」的唯一漏斗（主搜 / ponder 两路共用），
 *  matePly 非空（正=我方 N 步杀 / 负=被绝杀）即挂起本局开局库，后续直接走引擎。 */
internal fun BotSession.recordMateInfo(result: EngineResult) {
    selfMatePending = result.matePly == 1
    suspendBookIfMate(result.matePly)
}

/** E2：引擎 info 返回 mate → 本局不再查开局库（幂等，仅首次打点）。新局 [GameState.reset] 自动解除。 */
internal fun BotSession.suspendBookIfMate(matePly: Int?) {
    if (matePly == null || state.bookSuspendedByMate) return
    state.suspendBookByMate()
    LogBus.log(
        LogLevel.INFO, LogTag.ENGINE,
        "皮卡鱼 info 已返回 mate（$matePly），本局后续改用引擎、不再查开局库（自动下一局恢复）"
    )
}

// ---------- 自动下一局 ----------

internal suspend fun BotSession.autoNextGame(): Boolean {
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
            // R3 配套：轮次已定 → 拍引擎 position 基线
            state.ensureEngineBaseline()
            return true
        } finally {
            settledCorrected.release()
        }
    } finally {
        autoNextFlag = false
        emit()
    }
}
