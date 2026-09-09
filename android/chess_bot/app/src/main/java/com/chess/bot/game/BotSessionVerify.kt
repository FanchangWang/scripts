package com.chess.bot.game

import com.chess.bot.data.BotConfig
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.chess.bot.service.Capture
import kotlinx.coroutines.delay

/**
 * BotSession verify v3 校验链（B1 拆分自 BotSession.kt，2026-09-09 方案 6 / D1=A）。
 *
 * 职责：我方走子多帧校验（diff 数量分流）+ 我方/双着提交 + 基线白名单刷新 + 吞点击恢复。
 * 纯移动不改逻辑：函数体逐字搬运，类成员 private 放宽为 internal 后经扩展函数访问
 * （单 module 内可见性实际不变宽）。
 */

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

internal suspend fun BotSession.verifyForSelfMove(
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
internal fun BotSession.commitSelfSettled(
    grab: Grabbed,
    selfMove: Move,
    cells: Set<Pair<Int, Int>>
) {
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
internal fun BotSession.commitSelfThenEnemy(
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
internal suspend fun BotSession.tryCommitSelfThenEnemy(
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
internal fun BotSession.refreshBaselineCells(grab: Grabbed, cells: Set<Pair<Int, Int>>) {
    val refresh = cells.map { Change(it.first, it.second, null, null) }
    state.updateCellImgs(grab.corrected, refresh, grab.scan.driftCells)
}

/** v3 verify 内终局/和棋检查（触发条件：diff cell > VERIFY_OCR_DIFF_CELLS 的大面积遮挡帧，
 *  或稳定未知模式超 T3）。和棋弹窗关闭 → RETRY_BOTH（点击多半被吞）；终局确认 → DONE_END。
 *  OCR 类检查自带节流（confirmEndByOcr / lastVerifyDrawScanAt），updateResign 为纯格子信号不节流。 */
internal suspend fun BotSession.verifyEndgameCheck(grabbed: Grabbed): VerifyOutcome? {
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
internal suspend fun BotSession.tryRecoverSwallowedTap(
    changes: List<Change>,
    expected: Move
): Boolean {
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
