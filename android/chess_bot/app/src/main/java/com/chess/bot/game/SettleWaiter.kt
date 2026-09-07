package com.chess.bot.game

import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag

/**
 * 摆棋稳定等待器（开始棋局与自动下一局共用，规则与 python scan_and_wait 一致）：
 * - 31 子：终局残留（被吃一将）/ 动画中间帧 / 敌方提子未落 → 跳过（不重置稳定计数旁的状态）
 * - 32 子：按新开局快速返回
 * - 将帅门控：其余子数下将/帥缺任一格 → 过渡帧，不计稳定（2026-09-06 真机日志 bug 修复）
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
        val count = pieceCount(board)
        lastCount = count
        return when {
            count == 31 -> {
                // 区分提子过渡态（全部在初始位置 → 继续等 32 子）与残局（有子已离初始
                // 位置 → 轮到我方走，按稳定计数等待后返回，避免残局开局卡在等待摆棋）
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
                    // 残局：与上一帧逐值相等累加稳定计数（同「其余子数」分支），连续达阈值即返回
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
                    if (stableCount >= Const.BOARD_STABLE_THRESHOLD) Feed.Ready(count) else Feed.Waiting
                }
            }

            count == 32 -> {
                LogBus.log(LogLevel.INFO, tag, "识别到 32 个棋子，按新开局处理")
                Feed.Ready(count)
            }

            count < 32 && !bothGeneralsPresent(board) -> {
                // 将帅同现门控（2026-09-06 真机日志 bug）：大厅/过渡/清盘残留可能只识别出
                // 2 个「棋子」却进入稳定计数（01:18:58「识别到 2 个棋子（稳定 1/3）」）。
                // 将/帥缺任一格一律视为过渡帧：不计数、不返回 Ready，必须等到两将同时在盘。
                // （31 子残局分支在上文已处理——终局残留被吃一将属合法落定场景，不受此门控约束。）
                prevBoard = board
                stableCount = 0
                LogBus.log(
                    LogLevel.DEBUG, tag,
                    "等待摆棋：识别到 $count 个棋子但将帅未同时存在，继续等待",
                )
                Feed.Waiting
            }

            prevBoard != null && boardEquals(prevBoard, board) -> {
                stableCount++
                LogBus.log(
                    LogLevel.DEBUG, tag,
                    "等待摆棋：识别到 $count 个棋子（稳定 $stableCount/${Const.BOARD_STABLE_THRESHOLD}）",
                )
                if (stableCount >= Const.BOARD_STABLE_THRESHOLD) Feed.Ready(count) else Feed.Waiting
            }

            else -> {
                prevBoard = board
                stableCount = 0
                LogBus.log(LogLevel.DEBUG, tag, "等待摆棋：识别到 $count 个棋子，重新计稳定")
                Feed.Waiting
            }
        }
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
