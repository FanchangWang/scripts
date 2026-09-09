package com.chess.bot.game

import com.chess.bot.data.BotConfig
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.chess.bot.vision.Recognizer
import kotlinx.coroutines.delay
import org.opencv.core.Mat

/**
 * BotSession 敌方链（B2 拆分自 BotSession.kt，2026-09-09 方案 6 / D1=A）。
 *
 * 职责：我方提子恢复 + 敌方走棋检测（T-D 两帧一致确认）+ ponder CAP 收割（F2-A/F3-A）+
 * 敌着提交公共路径（ponder 命中取预搜/未命中丢弃 + cellImgs 局部更新）+ 敌方局面应用。
 * 纯移动不改逻辑：函数体逐字搬运，类成员 private 已在 B1 放宽为 internal 后经扩展函数访问
 * （字段 lastLiftArtifactWarnAt 留守类内：顶层 var 会改变多实例语义）。
 *
 * waitForEnemyMove 重构（2026-09-10，参考 verifyForSelfMove v3）：n 分流（0 静默 / ≤2 classify /
 * 3..30 灰区）+ (d) diffCells>30 大面积遮挡 verifyEndgameCheck + (e) 稳定未知 2s 兜底（OCR 强扫）+
 * (f) 32 子新局摆棋检测（终局漏检兜底，2026-09-10 D3=A）+ 180s 总超时；
 * 废除旧「连续噪声帧暂停」体系（noisyCount/ENEMY_NOISY_MAX，见 Const.kt 注释）。
 */

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
internal suspend fun BotSession.recoverOwnLift(
    correctedOwned: Mat,
    liftPos: Pair<Int, Int>
): LiftRecovery {
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

// ---------- 敌方走棋检测 ----------

// 棋盘识别提速（方案 A 变种，2026-08-30）：recognizeBoardChanged 每轮逐格 10x10 中心小图 diff，
// 仅变化格跑模板匹配，未变格沿用 board；不再做全量 90 格匹配（原 ~466ms/帧 → ~20ms/帧）。

internal suspend fun BotSession.waitForEnemyMove() {
    setStatus(BotStatus.WAIT_ENEMY)
    state.resignStreak = 0
    state.liftLogged = false
    var silentStreak = 0 // Q4-3：连续静默帧计数（单帧 SILENT 常为误读，防「对方提子」双打）
    // 节奏信息降 DEBUG（2026-09-09 D3）：敌着事件本身有 INFO（对方提子 / X方走炮），避免每步重复
    LogBus.log(LogLevel.DEBUG, LogTag.PLAY, "等待对方走棋")
    val cap = capture
    val startMs = System.nanoTime() / 1_000_000
    var prevChanges: List<Change>? = null // 帧间一致性（参考 verifyForSelfMove v3）：上一帧变化格子集合（null=首帧视为不稳定）
    var stableUnknownSinceMs = -1L // 稳定未知模式起始时刻（仅 NOISY/灰区等不可行动帧累计，已知进度帧重置）
    var lastStableScanMs = -1L // (e) 兜底外层节流时间戳：稳定未知触发终局检查+OCR 强扫的最小间隔
    while (running && !interrupted && !state.gameOver) {
        // D1=A 总超时（2026-09-10）：对方单步限时 ≤120s + 动画余量；超时先 OCR 强扫
        //（对方超时判负的结算页可能已渲染而格子信号未达标），命中直接终局，未命中再暂停
        if (System.nanoTime() / 1_000_000 - startMs > Const.ENEMY_WAIT_TOTAL_TIMEOUT_MS) {
            if (confirmEndByOcr(force = true)) return
            LogBus.log(
                LogLevel.WARN,
                LogTag.PLAY,
                "等待对方走棋超 ${Const.ENEMY_WAIT_TOTAL_TIMEOUT_MS / 1000}s，暂停自动对弈",
            )
            running = false
            setStatus(BotStatus.ABNORMAL_PAUSED)
            return
        }
        // F2-A：敌方思考超 CAP 仍未走子 → 提前 ponderhit 按质量门控收割预搜（裸 go ponder 唯一闸门）。
        // 收割阻塞通常 <200ms（elapsed 已 ≥ TARGET，质量多半已达标），期间不取帧——敌方尚未走子无信息损失
        maybeHarvestPonderCap()
        val grabbed = grabBoard(cap) ?: continue
        try {
            val changes = grabbed.scan.changes
            val n = changes.size
            if (!running || interrupted || state.gameOver) break
            val nowMs = System.nanoTime() / 1_000_000
            // 帧间一致性（参考 verifyForSelfMove v3）：变化格子集合逐格相同（同格同 old/new；两帧皆空也算稳定）
            val stable = prevChanges != null && changes == prevChanges
            prevChanges = changes

            // ── n==0：静默帧（伪代码 changes==0 continue；S1：真静默重置稳定未知计数，防误入 (e) 兜底）──
            if (n == 0) {
                silentStreak++
                if (silentStreak >= 2) state.liftLogged = false
                stableUnknownSinceMs = -1L
                delay(BotConfig.data.enemyPollMs.toLong())
                continue
            }

            // ── n ≤ 2：classifyEnemyFrame 明确归类（伪代码主分流）──
            if (n <= 2) {
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
                                // S5：敌方在动=已知进度（动画中途帧），重置稳定未知计数
                                stableUnknownSinceMs = -1L
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
                        // S5：提子=已知进度（真人提子思考可 >2s），不参与稳定未知兜底
                        stableUnknownSinceMs = -1L
                    }

                    EnemyFrameResult.SILENT -> {
                        // 不可达（n==0 已在上方 continue，classify 的 SILENT 仅 n==0 产生）；防御性保留静默语义
                        silentStreak++
                        if (silentStreak >= 2) state.liftLogged = false
                        stableUnknownSinceMs = -1L
                    }

                    EnemyFrameResult.NOISY -> {
                        // D4=A：保留「识别变动」DEBUG 日志（诊断可见性）；旧「连续噪声帧暂停」体系废除（S2/D3），
                        // 不再累计暂停计数——终局/遮挡交 (d)(e) 兜底，总时长由循环顶部 180s 总超时封顶
                        formatChanges(changes, state.mySide).forEach {
                            LogBus.log(
                                LogLevel.DEBUG,
                                LogTag.VISION,
                                "识别变动 $it"
                            )
                        }
                        state.liftLogged = false
                        silentStreak = 0
                        // NOISY=未知模式：不重置稳定未知计数，交 (e) 稳定兜底
                    }
                }
            }
            // n ∈ 3..VERIFY_OCR_DIFF_CELLS：动画/噪声灰区，静默继续（参考 verifyForSelfMove (c)），交 (e) 稳定兜底

            // ── (f) 新局摆棋检测（2026-09-10 D3=A 简化版）：已提交棋盘 <32 子而扫描板恢复
            //     32 子 → 上一局终局信号漏检后游戏已自动摆好下一局（32 子连读模式），
            //     此时 n 落灰区、(d) 的 OCR 已错过结算页，原逻辑只能空等到 180s 总超时。
            //     对局中棋子只减不增，32 子不可能在局中出现；开局等敌手时 committed==32
            //     不满足 <32，零误触发。标记本局结束交 flowLoop 走 autoNextGame——
            //     StartLoop 对 32 子开局形态免准入证据（openingForm 直通），摆棋若仍在
            //     动画中由其稳定判定 + det 几何守卫兜底。
            if (pieceCount(grabbed.scan.board) == 32 && pieceCount(state.board) < 32) {
                finishGame(
                    "检测到棋盘恢复 32 子（原 ${pieceCount(state.board)} 子），" +
                            "判定本局已结束，进入自动下一局"
                )
                return
            }

            // ── (d) diffCells > VERIFY_OCR_DIFF_CELLS：大面积遮挡（弹窗/遮罩/结算画面）→
            //     OCR/终局检查（updateResign / confirmEndByOcr 节流 / dismissDrawDialog 内聚于 verifyEndgameCheck）──
            if (grabbed.scan.diffCells > Const.VERIFY_OCR_DIFF_CELLS) {
                verifyEndgameCheck(grabbed)?.let {
                    if (it == VerifyOutcome.DONE_END) return
                    // RETRY_BOTH（和棋弹窗已关闭）：继续等待
                }
                // S5：大动画帧=已知进度，重置稳定未知计数
                stableUnknownSinceMs = -1L
                silentStreak = 0
                state.liftLogged = false
            }

            // ── (e) 稳定未知模式兜底：变化格子集合与上帧逐格相同且不可行动（NOISY/灰区卡死），
            //     持续超 VERIFY_UNKNOWN_STABLE_MS → 终局/和棋检查 + OCR 强扫（外层节流 S3）──
            if (stable) {
                if (stableUnknownSinceMs < 0) stableUnknownSinceMs = nowMs
                if (nowMs - stableUnknownSinceMs > Const.VERIFY_UNKNOWN_STABLE_MS &&
                    nowMs - lastStableScanMs > Const.ENEMY_STABLE_SCAN_THROTTLE_MS
                ) {
                    LogBus.log(
                        LogLevel.DEBUG,
                        LogTag.PLAY,
                        "稳定未知模式持续超 ${Const.VERIFY_UNKNOWN_STABLE_MS}ms，执行终局/和棋检查",
                    )
                    lastStableScanMs = nowMs
                    verifyEndgameCheck(grabbed)?.let {
                        if (it == VerifyOutcome.DONE_END) return
                    }
                    if (confirmEndByOcr(force = true)) return // S4b：绕内层 1s 节流的 OCR 强扫
                }
            } else {
                stableUnknownSinceMs = -1L // 模式切换即重置
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

/**
 * T-D 敌着两帧一致确认（2026-09-06）：立即复抓复判（不加显式延时——单次 grabBoard 本身
 * ~70-100ms，已足够越过半格飞行窗口），敌着在复判帧再次推断出才视为落定——动画中途帧
 * （如車 C0→C9 途经 C5，几何合法、伪合法校验拦截不了）的 cls 读数必已变化，两帧同着法即排除。
 *
 * @return 复抓帧（确认时供提交用，调用方负责 release）；未确认/中断/对局结束返回 null
 */
internal suspend fun BotSession.reconfirmEnemyMoved(move: Move): Grabbed? {
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
internal fun BotSession.maybeHarvestPonderCap() {
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
internal fun BotSession.commitEnemyMove(move: Move, grab: Grabbed) {
    val enemyIccs = gridToSquare(move.src.first, move.src.second, state.mySide) +
            gridToSquare(move.dst.first, move.dst.second, state.mySide)
    val predicted = pendingPonderMove
    if (predicted != null) {
        // 着法中文化（2026-09-09 D3=A）：commit 时 board 尚未更新（updateCellImgs 在后），
        // 起点格仍是实际/预测的敌方棋子，取棋名拼「黑車 e7 -> g7」与 ponder 行风格统一
        val actualCn =
            (state.boardAt(move.src.first, move.src.second)?.let(::pieceLabel) ?: "未知子") +
                    " ${enemyIccs.take(2)} -> ${enemyIccs.drop(2)}"
        val (pr, pc) = squareToGrid(predicted.take(2), state.mySide)
        val predictedCn = (state.boardAt(pr, pc)?.let(::pieceLabel) ?: "未知子") +
                " ${predicted.take(2)} -> ${predicted.drop(2)}"
        if (enemyIccs == predicted) {
            if (prematurePonderHarvested) {
                LogBus.log(
                    LogLevel.DEBUG,
                    LogTag.ENGINE,
                    "敌方走子命中预测（$actualCn），直接消费 CAP 提前收割的预搜结果"
                )
            } else {
                // F1-A：ponderHit 内部走主搜同款质量门控（elapsed<target 等满、质量达标才 stop）
                pendingPonderResult = engine.ponderHit()
                LogBus.log(
                    LogLevel.DEBUG,
                    LogTag.ENGINE,
                    "敌方走子命中预测（$actualCn），ponderHit 按质量门控取回预搜结果"
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
                "敌方走子未命中预测（$actualCn ≠ $predictedCn），丢弃 ponder"
            )
        }
        pendingPonderMove = null
        prematurePonderHarvested = false
    }
    state.updateCellImgs(grab.corrected, grab.scan.changes, grab.scan.driftCells)
    applyEnemyMove(move)
}

internal fun BotSession.applyEnemyMove(move: Move) {
    state.applyEnemyMove(move)
    state.lastMove = "${gridToSquare(move.src.first, move.src.second, state.mySide)}-" +
            gridToSquare(move.dst.first, move.dst.second, state.mySide)
    LogBus.log(LogLevel.INFO, LogTag.ENEMY, formatMove(move, state.mySide))
    emit()
}
