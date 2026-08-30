package com.chess.bot.game

import android.content.Context
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
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
 * 无文字时分析棋盘，摆棋稳定判定复用共享 SettleWaiter
 * （31 子提子未落过渡态跳过 / 32 子按新开局快速返回 / 其余逐值稳定计数）。
 */
class AutoNext(
    private val context: Context,
    private val capture: Capture,
    private val shouldContinue: () -> Boolean,
    private val autoNextEnabled: () -> Boolean,
) {

    /** 返回摆棋完毕的矫正帧；中断/超时/失败返回 null。 */
    suspend fun scanAndWait(): Mat? {
        LogBus.log(LogKind.INFO, LogTag.NEXT, "开始扫描结算画面")
        var lastWord: String? = null
        var retryCount = 0
        val waiter = SettleWaiter(LogTag.NEXT)
        val startAt = System.nanoTime()
        val templates = VisionInit.loadPieceTemplates(context)

        while (true) {
            if (!shouldContinue()) return null
            if (!autoNextEnabled()) {
                LogBus.log(LogKind.WARN, LogTag.NEXT, "自动下一局已关闭，中止扫描")
                return null
            }
            if (elapsedSeconds(startAt) > Const.AUTO_NEXT_TIMEOUT_S) {
                LogBus.log(
                    LogKind.WARN,
                    LogTag.NEXT,
                    "${Const.AUTO_NEXT_TIMEOUT_S}秒未完成结算交互与摆棋，中止自动下一局，请手动处理",
                )
                return null
            }

            delay(Const.GAMEOVER_SCAN_INTERVAL_MS)

            // ---------- 扫结算文字 ----------
            val raw = capture.screenshot()
            val hit = raw?.let { TextMatcher.findGameoverScan(context, it) }
            if (hit != null) {
                val (word, x, y) = hit
                val isButton = !isBackWord(word)
                if (word != lastWord) {
                    lastWord = word
                    retryCount = 0
                }
                retryCount++
                if (!isButton) {
                    if (retryCount > Const.GAMEOVER_RETRY_MAX) {
                        LogBus.log(
                            LogKind.ERROR,
                            LogTag.NEXT,
                            "遮罩「$word」发送返回键 ${Const.GAMEOVER_RETRY_MAX} 次仍无响应，中止自动下一局，请手动处理",
                        )
                        return null
                    }
                    LogBus.log(
                        LogKind.INFO,
                        LogTag.NEXT,
                        "识别到遮罩文字「$word」，发送返回键（第 $retryCount/${Const.GAMEOVER_RETRY_MAX} 次）",
                    )
                    if (!capture.back()) {
                        LogBus.log(LogKind.ERROR, LogTag.NEXT, "自动下一局交互失败（返回键）")
                        return null
                    }
                } else {
                    if (retryCount > Const.GAMEOVER_RETRY_MAX) {
                        LogBus.log(
                            LogKind.ERROR,
                            LogTag.NEXT,
                            "结算按钮「$word」点击 ${Const.GAMEOVER_RETRY_MAX} 次仍无响应，中止自动下一局，请手动处理",
                        )
                        return null
                    }
                    LogBus.log(
                        LogKind.INFO,
                        LogTag.NEXT,
                        "识别到结算按钮「$word」，点击进入下一局（第 $retryCount/${Const.GAMEOVER_RETRY_MAX} 次）",
                    )
                    if (!capture.tapXy(x, y)) {
                        LogBus.log(LogKind.ERROR, LogTag.NEXT, "自动下一局交互失败（点击结算按钮）")
                        return null
                    }
                }
                continue
            }

            // ---------- 分析棋盘（稳定判定复用 SettleWaiter） ----------
            val corrected = capture.grab() ?: continue
            // 所有权移交：命中返回路径时 Mat 归调用方释放；其余路径本层 finally 立即释放
            var handOffToCaller = false
            try {
                val board = Recognizer.analyzeBoard(corrected, templates)
                val count = board.sumOf { row -> row.count { it != null } }
                if (count == 0) {
                    LogBus.log(LogKind.DEBUG, LogTag.NEXT, "未识别到结算文字，棋盘为空")
                } else {
                    // 棋子出现 = 操作已生效，清空重试状态
                    lastWord = null
                    retryCount = 0
                    if (waiter.feed(board) is SettleWaiter.Feed.Ready) {
                        handOffToCaller = true
                        return corrected
                    }
                }
            } finally {
                if (!handOffToCaller) corrected.release()
            }
        }
    }

    private fun elapsedSeconds(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000_000L

    companion object {
        fun isBackWord(word: String): Boolean = word in Const.GAMEOVER_BACK_WORDS
    }
}
