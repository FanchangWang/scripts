package com.chess.bot.game

import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag

/**
 * 摆棋稳定等待器（开始棋局与自动下一局共用，规则与 python scan_and_wait 一致）：
 * - 31 子：终局残留（被吃一将）/ 动画中间帧 / 敌方提子未落 → 跳过（不重置稳定计数旁的状态）
 * - 32 子：按新开局快速返回
 * - 其余子数：与上一帧逐值相等累加稳定计数，连续 BOARD_STABLE_THRESHOLD 帧即返回
 *
 * Kotlin 数组 == 是引用比较，必须 contentDeepEquals（M6 补丁① 结论）。
 */
class SettleWaiter(private val tag: LogTag = LogTag.NEXT) {

    sealed interface Feed {
        data object Waiting : Feed
        data class Ready(val count: Int) : Feed
    }

    private var prevBoard: Board? = null
    private var stableCount = 0

    /** 最近一次喂入帧的棋子总数（供悬浮窗等待态显示「子数 N」）。 */
    var lastCount = 0
        private set

    /** 连续稳定帧数（供悬浮窗等待态显示「稳定 X/${threshold}」）。 */
    val stableProgress: Int get() = stableCount

    /** 稳定判定阈值（与 Const.BOARD_STABLE_THRESHOLD 一致）。 */
    val threshold: Int get() = Const.BOARD_STABLE_THRESHOLD

    /** 喂入一帧全量识别布局。 */
    fun feed(board: Board): Feed {
        val count = board.sumOf { row -> row.count { it != null } }
        lastCount = count
        return when {
            count == 31 -> {
                // 区分提子过渡态（全部在初始位置 → 继续等 32 子）与残局（有子已离初始
                // 位置 → 轮到我方走，按稳定计数等待后返回，避免残局开局卡在等待摆棋）
                val side = detectSide(board)
                if (side != null && allOnInitialSquares(board, side)) {
                    LogBus.log(
                        LogKind.DEBUG, tag,
                        "识别到 31 子且全在初始位置（提子过渡态），继续等待 32 子",
                    )
                    prevBoard = board
                    stableCount = 0
                    Feed.Waiting
                } else {
                    // 残局：与上一帧逐值相等累加稳定计数（同「其余子数」分支），连续达阈值即返回
                    if (prevBoard != null && boardEquals(prevBoard, board)) {
                        stableCount++
                    } else {
                        stableCount = 1
                    }
                    prevBoard = board
                    LogBus.log(
                        LogKind.DEBUG, tag,
                        "识别到 31 子残局（有子离初始位置），等待稳定 $stableCount/${Const.BOARD_STABLE_THRESHOLD}",
                    )
                    if (stableCount >= Const.BOARD_STABLE_THRESHOLD) Feed.Ready(count) else Feed.Waiting
                }
            }

            count == 32 -> {
                LogBus.log(LogKind.INFO, tag, "识别到 32 个棋子，按新开局处理")
                Feed.Ready(count)
            }

            prevBoard != null && boardEquals(prevBoard, board) -> {
                stableCount++
                LogBus.log(
                    LogKind.DEBUG, tag,
                    "等待摆棋：识别到 $count 个棋子（稳定 $stableCount/${Const.BOARD_STABLE_THRESHOLD}）",
                )
                if (stableCount >= Const.BOARD_STABLE_THRESHOLD) Feed.Ready(count) else Feed.Waiting
            }

            else -> {
                prevBoard = board
                stableCount = 0
                LogBus.log(LogKind.DEBUG, tag, "等待摆棋：识别到 $count 个棋子，重新计稳定")
                Feed.Waiting
            }
        }
    }

    private fun boardEquals(a: Board?, b: Board): Boolean = a != null && a.contentDeepEquals(b)
}
