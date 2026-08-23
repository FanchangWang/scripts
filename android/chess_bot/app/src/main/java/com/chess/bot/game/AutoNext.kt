package com.chess.bot.game

import android.content.Context
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.service.Capture
import com.chess.bot.vision.Recognizer
import com.chess.bot.vision.TextMatcher
import com.chess.bot.vision.VisionInit
import kotlinx.coroutines.delay
import org.opencv.core.Mat

/**
 * 结算画面交互 + 等待摆棋（移植 python auto_next.py scan_and_wait）。
 *
 * 单循环：先扫结算文字（按钮点击 / 遮罩发返回键，同一文字重试上限）；
 * 无文字时分析棋盘：32 子直接返回，否则连续 BOARD_STABLE_THRESHOLD 帧相同返回。
 */
class AutoNext(
    private val context: Context,
    private val capture: Capture,
    private val shouldContinue: () -> Boolean,
    private val autoNextEnabled: () -> Boolean,
) {

    /** 返回摆棋完毕的矫正帧；中断/超时/失败返回 null。 */
    suspend fun scanAndWait(): Mat? {
        LogBus.log(LogKind.INFO, "开始扫描结算文字……")
        var lastWord: String? = null
        var retryCount = 0
        var prevBoard: Board? = null
        var stableCount = 0
        var nonTypicalLogged: Int? = null
        val startAt = System.nanoTime()
        val templates = VisionInit.loadPieceTemplates(context)

        while (true) {
            if (!shouldContinue()) return null
            if (!autoNextEnabled()) {
                LogBus.log(LogKind.WARN, "自动下一局已关闭，中止扫描")
                return null
            }
            if (elapsedSeconds(startAt) > Const.AUTO_NEXT_TIMEOUT_S) {
                LogBus.log(LogKind.WARN, "${Const.AUTO_NEXT_TIMEOUT_S}秒未完成结算交互+摆棋，中止自动下一局")
                return null
            }

            delay(Const.GAMEOVER_SCAN_INTERVAL_MS)

            val raw = capture.screenshot()
            val hit = raw?.let { TextMatcher.findGameoverScan(context, it) }
            if (hit == null) {
                val corrected = capture.grab() ?: continue
                // 所有权移交模式：命中返回路径时把 Mat 交给调用方（由其负责 release），
                // 其余路径在本层 finally 立即释放——严禁对已移交帧重复 release
                var handOffToCaller = false
                try {
                    val board = Recognizer.analyzeBoard(corrected, templates)
                    val count = board.sumOf { row -> row.count { it != null } }
                    if (count > 0) {
                        // 棋子出现 = 操作已生效，清空重试状态
                        lastWord = null
                        retryCount = 0
                        when {
                            count == 31 -> {
                                LogBus.log(LogKind.INFO, "识别到 31 个棋子，暂不处理")
                                prevBoard = board
                                stableCount = 0
                            }
                            count == 32 && plausibleNewGame(board) -> {
                                LogBus.log(LogKind.INFO, "识别到 32 个棋子，当做开局处理")
                                handOffToCaller = true
                                return corrected
                            }
                            prevBoard != null && boardEquals(prevBoard, board) -> {
                                // 合理新开局 = 满员合法开局(32) 或 残局面(子数<24 且双方将/帥俱在)；
                                // 无将帅的画面（选关预览等）与 24~30 子中间态一样继续等待
                                val hasBothKings =
                                    board.any { row -> row.any { it == "r_K" } } &&
                                        board.any { row -> row.any { it == "b_k" } }
                                val plausible = (count == 32 && plausibleNewGame(board)) ||
                                    (count < Const.ENDGAME_PIECE_COUNT && hasBothKings)
                                if (!plausible) {
                                    if (nonTypicalLogged != count) {
                                        nonTypicalLogged = count
                                        val kings = buildString {
                                            if (!board.any { row -> row.any { it == "r_K" } }) append("缺红帥 ")
                                            if (!board.any { row -> row.any { it == "b_k" } }) append("缺黑將")
                                        }.trim()
                                        LogBus.log(
                                            LogKind.INFO,
                                            "等待摆棋：$count 子非典型${if (kings.isEmpty()) "" else "（$kings）"}，继续等待",
                                        )
                                    }
                                    stableCount = 0
                                } else {
                                    stableCount++
                                    LogBus.log(
                                        LogKind.INFO,
                                        "等待摆棋完毕：识别到 $count 个棋子（稳定 $stableCount/${Const.BOARD_STABLE_THRESHOLD}）",
                                    )
                                    if (stableCount >= Const.BOARD_STABLE_THRESHOLD) {
                                        handOffToCaller = true
                                        return corrected
                                    }
                                }
                            }
                            else -> {
                                prevBoard = board
                                stableCount = 0
                                nonTypicalLogged = null
                                LogBus.log(LogKind.INFO, "等待摆棋：识别到 $count 个棋子，重新计稳定")
                            }
                        }
                    } else {
                        LogBus.log(LogKind.INFO, "未识别到结算文字，棋盘为空")
                    }
                } finally {
                    if (!handOffToCaller) corrected.release()
                }
                continue
            }

            // 结算交互：遮罩发返回键 / 按钮点击
            val (word, x, y) = hit
            val isButton = !isBackWord(word)
            if (word != lastWord) {
                lastWord = word
                retryCount = 0
            }
            retryCount++
            if (!isButton) {
                if (retryCount > Const.GAMEOVER_RETRY_MAX) {
                    LogBus.log(LogKind.ERROR, "遮罩「$word」发送返回键 ${Const.GAMEOVER_RETRY_MAX} 次仍无响应，中止自动下一局")
                    return null
                }
                LogBus.log(LogKind.INFO, "识别到文字「$word」，发送返回键（第 $retryCount/${Const.GAMEOVER_RETRY_MAX} 次）")
                if (!capture.back()) {
                    LogBus.log(LogKind.ERROR, "自动下一局交互失败")
                    return null
                }
            } else {
                if (retryCount > Const.GAMEOVER_RETRY_MAX) {
                    LogBus.log(LogKind.ERROR, "结算按钮「$word」点击 ${Const.GAMEOVER_RETRY_MAX} 次仍无响应，中止自动下一局")
                    return null
                }
                LogBus.log(LogKind.MOVE, "识别到结算按钮「$word」，点击 ($x,$y) 开始下一局（第 $retryCount 次）")
                if (!capture.tapXy(x, y)) {
                    LogBus.log(LogKind.ERROR, "自动下一局交互失败")
                    return null
                }
            }
        }
    }

    private fun elapsedSeconds(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000_000L

    /** 逐值比较两布局（Kotlin 数组 == 是引用比较，必须用 contentDeepEquals）。 */
    private fun boardEquals(a: Board?, b: Board): Boolean = a != null && a.contentDeepEquals(b)

    /** 32 子且能推断为合法开局（全默认位或恰一方走一步）才算真开局。 */
    private fun plausibleNewGame(board: Board): Boolean {
        val mySide = detectSide(board) ?: return false
        if (detectPhase(board, mySide) != Phase.OPENING) return false
        return inferTurn(board, mySide, Phase.OPENING) != null
    }

    companion object {
        fun isBackWord(word: String): Boolean = word in Const.GAMEOVER_BACK_WORDS
    }
}
