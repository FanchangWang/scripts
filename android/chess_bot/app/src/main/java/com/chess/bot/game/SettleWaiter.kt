package com.chess.bot.game

import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag

/**
 * 摆棋稳定等待器（开始棋局与自动下一局共用）。
 *
 * 2026-09-08 lift 感知改造（方案 game_start_lift_recovery_plan.md）：
 * - 我方半区 lift（此前走棋失败棋子在手）→ 连续同位置 [Const.LIFT_CONFIRM_FRAMES] 帧确认
 *   （过滤飞行途经瞬态伪影）后返回 [Feed.OwnLift]，交 BotSession 恢复流程；
 * - 敌方半区 lift（敌方提子未落）→ 等待落子，不计数不就绪
 *   （D3=A 无超时：走棋超时后游戏播放超时动画，自然触发 OCR 结束扫描）；
 * - 32 子分支加布局校验（Q1 修复）：开局形态（默认位或红方走一子，D2=A）才立即就绪，
 *   其余（重摆动画瞬态/上局残留，偏差超 1 子）走 3 帧稳定计数；
 * - 31 子：全在初始位置（lift 格按「棋子仍在原格」计，Q2 修复）→ 提子过渡态继续等；
 *   有子离位 → 残局走稳定计数；
 * - 将帅门控：其余子数下将/帥缺任一格 → 过渡帧，不计稳定；
 * - 其余子数：与上一帧逐值相等累加稳定计数，连续 BOARD_STABLE_THRESHOLD 帧即返回。
 *
 * Kotlin 数组 == 是引用比较，必须 contentDeepEquals（M6 补丁① 结论）。
 */
class SettleWaiter(private val tag: LogTag = LogTag.NEXT) {

    sealed interface Feed {
        data object Waiting : Feed
        data class Ready(val count: Int) : Feed

        /** 我方提子未落已确认（liftPos 为提子格）：调用方执行恢复流程。 */
        data class OwnLift(val liftPos: Pair<Int, Int>) : Feed
    }

    private var prevBoard: Board? = null
    private var stableCount = 0
    private var prevOwnLift: Pair<Int, Int>? = null
    private var ownLiftStable = 0

    /** 最近一次喂入帧的棋子总数（供悬浮窗等待态显示「子数 N」）。 */
    var lastCount = 0
        private set

    /** 连续稳定帧数（供悬浮窗等待态显示「稳定 X/${threshold}」）。 */
    val stableProgress: Int get() = stableCount

    /** 稳定判定阈值（与 Const.BOARD_STABLE_THRESHOLD 一致）。 */
    val threshold: Int get() = Const.BOARD_STABLE_THRESHOLD

    /** 喂入一帧全量识别布局。 */
    fun feed(board: Board): Feed {
        val count = pieceCount(board)
        lastCount = count
        val lifts = liftCells(board)
        val ownLift = lifts.firstOrNull { it.first >= Const.OWN_HALF_MIN_ROW }
        val enemyLift = lifts.firstOrNull { it.first < Const.OWN_HALF_MIN_ROW }
        return when {
            ownLift != null -> {
                // 我方提子未落（此前我方走棋失败，棋子被提起悬停原格上半部）：
                // 连续同位置确认 K 帧后交恢复流程，不参与普通稳定计数
                if (prevOwnLift == ownLift) {
                    ownLiftStable++
                } else {
                    prevOwnLift = ownLift
                    ownLiftStable = 1
                }
                prevBoard = board
                LogBus.log(
                    LogLevel.DEBUG, tag,
                    "我方提子未落(r=${ownLift.first},c=${ownLift.second})，" +
                            "确认 $ownLiftStable/${Const.LIFT_CONFIRM_FRAMES}",
                )
                if (ownLiftStable >= Const.LIFT_CONFIRM_FRAMES) {
                    Feed.OwnLift(ownLift)
                } else {
                    Feed.Waiting
                }
            }

            enemyLift != null -> {
                // 敌方提子未落（敌方走棋中途）：等待落子（无超时，D3=A）
                resetOwnLift()
                prevBoard = board
                stableCount = 0
                LogBus.log(
                    LogLevel.DEBUG, tag,
                    "敌方提子未落(r=${enemyLift.first},c=${enemyLift.second})，等待落子",
                )
                Feed.Waiting
            }

            count == 31 -> {
                // 区分提子过渡态（全在初始位置 → 继续等 32 子）与残局（有子离初始
                // 位置 → 按稳定计数等待后返回，避免残局开局卡在等待摆棋）
                resetOwnLift()
                val side = detectSide(board)
                if (side != null && allOnInitialSquares(board, side)) {
                    LogBus.log(
                        LogLevel.DEBUG, tag,
                        "识别到 31 子且全在初始位置（提子过渡态），继续等待 32 子",
                    )
                    prevBoard = board
                    stableCount = 0
                    Feed.Waiting
                } else {
                    // 残局：与上一帧逐值相等累加稳定计数（首帧即计 1，连续达阈值即返回）
                    if (prevBoard != null && boardEquals(prevBoard, board)) {
                        stableCount++
                    } else {
                        stableCount = 1
                    }
                    prevBoard = board
                    LogBus.log(
                        LogLevel.DEBUG, tag,
                        "识别到 31 子残局（有子离初始位置），等待稳定 $stableCount/${Const.BOARD_STABLE_THRESHOLD}",
                    )
                    if (stableCount >= Const.BOARD_STABLE_THRESHOLD) {
                        Feed.Ready(count)
                    } else {
                        Feed.Waiting
                    }
                }
            }

            count == 32 -> {
                resetOwnLift()
                val side = detectSide(board)
                if (side != null && isEarlyOpeningForm(board, side)) {
                    LogBus.log(
                        LogLevel.INFO, tag,
                        "识别到 32 子开局形态（默认位或红方走一子），直接就绪",
                    )
                    Feed.Ready(count)
                } else {
                    // 非开局形态（偏差超 1 子：重摆动画瞬态/上局残留）→ 3 帧稳定（Q1 修复）
                    stabilityFeed(board, count, "32 子非开局形态（偏差>1子）")
                }
            }

            count < 32 && !bothGeneralsPresent(board) -> {
                // 将帅同现门控（2026-09-06 真机日志 bug）：大厅/过渡/清盘残留可能只识别出
                // 2 个「棋子」却进入稳定计数。将/帥缺任一格一律视为过渡帧：不计数、不返回
                // Ready。（31 子残局分支在上文已处理，不受此门控约束。）
                resetOwnLift()
                prevBoard = board
                stableCount = 0
                LogBus.log(
                    LogLevel.DEBUG, tag,
                    "等待摆棋：识别到 $count 个棋子但将帅未同时存在，继续等待",
                )
                Feed.Waiting
            }

            else -> stabilityFeed(board, count, "等待摆棋：识别到 $count 个棋子")
        }
    }

    /** 逐值相等累加稳定计数（32 子非开局形态 / 其余子数共用；保持旧口径：首帧记录 prevBoard
     *  且计数归零，第 2..4 帧计 1..3，第 4 帧连续相同才 Ready——与 python scan_and_wait 一致）。 */
    private fun stabilityFeed(board: Board, count: Int, reason: String): Feed {
        if (prevBoard != null && boardEquals(prevBoard, board)) {
            stableCount++
            LogBus.log(
                LogLevel.DEBUG, tag,
                "$reason（稳定 $stableCount/${Const.BOARD_STABLE_THRESHOLD}）",
            )
            return if (stableCount >= Const.BOARD_STABLE_THRESHOLD) Feed.Ready(count) else Feed.Waiting
        }
        prevBoard = board
        stableCount = 0
        LogBus.log(LogLevel.DEBUG, tag, "$reason，重新计稳定")
        return Feed.Waiting
    }

    private fun resetOwnLift() {
        prevOwnLift = null
        ownLiftStable = 0
    }

    private fun boardEquals(a: Board?, b: Board): Boolean = a != null && a.contentDeepEquals(b)

    /** 将（b_k）与帅（r_K）是否同时在盘。 */
    private fun bothGeneralsPresent(board: Board): Boolean {
        var red = false
        var black = false
        for (row in board) for (p in row) {
            if (p == "r_K") red = true
            if (p == "b_k") black = true
        }
        return red && black
    }
}
