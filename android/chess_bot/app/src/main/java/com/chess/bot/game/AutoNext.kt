package com.chess.bot.game

import android.content.Context
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.chess.bot.service.Capture
import com.chess.bot.vision.BoardGeometryGuard
import com.chess.bot.vision.PieceClsModel
import com.chess.bot.vision.Recognizer
import com.chess.bot.vision.TextMatcher
import kotlinx.coroutines.delay
import org.opencv.core.Mat

/**
 * 结算画面交互 + 等待摆棋（移植 python auto_next.py scan_and_wait）。
 *
 * 单循环：先扫结算文字（按钮点击 / 遮罩发返回键，同一文字重试上限）；
 * 无文字时分析棋盘，摆棋稳定判定复用共享 SettleWaiter
 * （我方提子确认后交恢复流程 / 敌方提子等待落子 / 32 子开局形态校验 / 其余逐值稳定计数）。
 */

/** 我方提子恢复流程结果（BotSession.recoverOwnLift 返回）。 */
enum class LiftRecovery {
    /** 提子身份识别失败等：继续等待，下帧重试。 */
    RETRY,

    /** 恢复走子已完成并接管状态（已初始化、已走恢复着）——调用方跳过重新初始化。 */
    ADOPTED,

    /** 恢复走子失败：中止启动（调用方按失败收尾）。 */
    ABORT,
}

/** scanAndWait 结果：null = 中断/超时/失败中止。 */
sealed interface ScanResult {
    /** 摆棋稳定就绪，corrected 所有权归调用方（走正常 initialize 流程）。 */
    data class Settled(val corrected: Mat) : ScanResult

    /** 我方提子恢复走子已完成并接管状态——调用方跳过 state.reset/initialize/轮次判定。 */
    data object Adopted : ScanResult
}

class AutoNext(
    private val context: Context,
    private val capture: Capture,
    private val shouldContinue: () -> Boolean,
    private val autoNextEnabled: () -> Boolean,
    private val onPhase: (BotStatus) -> Unit = {},
    /** 命中终止类词（如「体力x2」）时的自动中断回调（BotSession 传入 interrupt()，与用户点停止等价）。 */
    private val interruptSession: () -> Unit = {},
    /** 我方提子恢复回调（BotSession.recoverOwnLift）；corrected 所有权移交回调内部释放。 */
    private val onOwnLift: suspend (corrected: Mat, liftPos: Pair<Int, Int>) -> LiftRecovery =
        { _, _ -> LiftRecovery.RETRY },
) {

    /** 返回摆棋结果；null = 中断/超时/失败。 */
    suspend fun scanAndWait(): ScanResult? {
        LogBus.log(LogLevel.INFO, LogTag.NEXT, "开始扫描结算画面")
        var lastWord: String? = null
        var retryCount = 0
        val waiter = SettleWaiter(LogTag.NEXT)
        val startAt = System.nanoTime()
        PieceClsModel.ensure(context)

        while (true) {
            if (!shouldContinue()) return null
            if (!autoNextEnabled()) {
                LogBus.log(LogLevel.WARN, LogTag.NEXT, "自动下一局已关闭，中止扫描")
                return null
            }
            if (elapsedSeconds(startAt) > Const.AUTO_NEXT_TIMEOUT_S) {
                LogBus.log(
                    LogLevel.WARN,
                    LogTag.NEXT,
                    "${Const.AUTO_NEXT_TIMEOUT_S}秒未完成结算交互与摆棋，中止自动下一局，请手动处理",
                )
                return null
            }

            delay(Const.GAMEOVER_SCAN_INTERVAL_MS)

            // ---------- 扫结算文字 ----------
            val raw = capture.screenshot()
            val hit = raw?.let { TextMatcher.findGameoverScan(context, it) }
            raw?.recycle() // 2026-09-07 D2=A：raw Bitmap（~10MB）用完即还，hit 已含词文本与坐标
            if (hit != null) {
                // ---------- 终止类词（2026-09-07）：识别到即自动中断对弈（等价用户点停止） ----------
                if (hit.word in Const.GAMEOVER_INTERRUPT_WORDS) {
                    // 设计内流程（等价用户点停止），INFO 与「OCR 命中结算文字」同级（2026-09-09 D2）
                    LogBus.log(
                        LogLevel.INFO,
                        LogTag.NEXT,
                        "识别到终止弹窗「${hit.word}」，自动中断对弈"
                    )
                    interruptSession()
                    return null
                }
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
                            LogLevel.ERROR,
                            LogTag.NEXT,
                            "遮罩「$word」发送返回键 ${Const.GAMEOVER_RETRY_MAX} 次仍无响应，中止自动下一局，请手动处理",
                        )
                        return null
                    }
                    LogBus.log(
                        LogLevel.INFO,
                        LogTag.NEXT,
                        "识别到遮罩文字「$word」，发送返回键（第 $retryCount/${Const.GAMEOVER_RETRY_MAX} 次）",
                    )
                    onPhase(BotStatus.NEXT_MASK)
                    if (!capture.back()) {
                        LogBus.log(LogLevel.ERROR, LogTag.NEXT, "自动下一局交互失败（返回键）")
                        return null
                    }
                } else {
                    if (retryCount > Const.GAMEOVER_RETRY_MAX) {
                        LogBus.log(
                            LogLevel.ERROR,
                            LogTag.NEXT,
                            "结算按钮「$word」点击 ${Const.GAMEOVER_RETRY_MAX} 次仍无响应，中止自动下一局，请手动处理",
                        )
                        return null
                    }
                    LogBus.log(
                        LogLevel.INFO,
                        LogTag.NEXT,
                        "识别到结算按钮「$word」，点击进入下一局（第 $retryCount/${Const.GAMEOVER_RETRY_MAX} 次）",
                    )
                    onPhase(BotStatus.NEXT_BUTTON)
                    if (!capture.tapXy(x, y)) {
                        LogBus.log(LogLevel.ERROR, LogTag.NEXT, "自动下一局交互失败（点击结算按钮）")
                        return null
                    }
                }
                continue
            }

            // ---------- 分析棋盘（稳定判定复用 SettleWaiter） ----------
            val corrected = capture.grab() ?: continue
            // 所有权移交：命中返回路径时归调用方/恢复回调释放；其余路径本层 finally 立即释放
            var handOffToCaller = false
            try {
                val board = Recognizer.analyzeBoard(corrected)
                val count = pieceCount(board)
                if (count == 0) {
                    LogBus.log(LogLevel.DEBUG, LogTag.NEXT, "未识别到结算文字，棋盘为空")
                } else {
                    // 棋子出现 = 操作已生效，清空重试状态
                    lastWord = null
                    retryCount = 0
                    when (val f = waiter.feed(board)) {
                        is SettleWaiter.Feed.Ready -> {
                            // 几何守卫（2026-09-08）：稳定≠完整尺寸棋盘。结束动画末期棋盘缩至
                            // ~80% 且静止，内容级校验全过但校准四角采样全错位。det 重定位四角
                            // 比对通过才接受；拒绝则重新计稳定（拉开两次 det 校验间隔）
                            if (geometryOk()) {
                                handOffToCaller = true
                                return ScanResult.Settled(corrected)
                            }
                            waiter.resetStable()
                        }

                        is SettleWaiter.Feed.OwnLift -> {
                            // 我方提子确认：Mat 所有权移交恢复回调（内部释放），恢复走子
                            // 成功则状态已接管（返回 Adopted，调用方跳过重新初始化）
                            handOffToCaller = true
                            when (onOwnLift(corrected, f.liftPos)) {
                                LiftRecovery.ADOPTED -> return ScanResult.Adopted
                                LiftRecovery.ABORT -> return null
                                LiftRecovery.RETRY -> {} // 继续等待，提子仍在会再次触发
                            }
                        }

                        is SettleWaiter.Feed.OwnLiftStalled -> {
                            // 提子卡死诊断门（G1=A，N=2）：确认超 N 帧仍未恢复 → det 几何诊断。
                            // MISMATCH（缩小棋盘被误读为提子）→ 封锁该提子格回摆棋等待——
                            // 静止缩小棋盘重置确认计数无效（log2.txt 复审：重置后 2 帧又复原），
                            // 同位置不再走恢复流程，等棋盘变化/「下一关」交互自动解除；
                            // PASS/NO_DETECT → ack 放行，真提子恢复流程继续；
                            // 节流期内不 ack（10s 到点重检），诊断事件每帧重发期间不跑恢复
                            if (!stallCheckThrottled()) {
                                when (geometryVerify().verdict) {
                                    BoardGeometryGuard.Verdict.MISMATCH -> {
                                        warnThrottled(
                                            "提子卡死诊断：棋盘几何与校准不符（疑似结束动画缩小棋盘），" +
                                                    "封锁提子格，回摆棋等待棋盘变化"
                                        )
                                        waiter.resetStable()
                                        waiter.blockLift(f.liftPos)
                                    }

                                    else -> waiter.ackStall()
                                }
                            }
                        }

                        SettleWaiter.Feed.Waiting -> {}
                    }
                }
            } finally {
                if (!handOffToCaller) corrected.release()
            }
        }
    }

    private fun elapsedSeconds(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000_000L

    /** 几何守卫拒绝日志节流（避免长时间停留在缩小棋盘时刷屏）。 */
    private var lastGeomWarnAt = 0L

    /** 提子卡死诊断节流：det 推理较重（首局实测 ~500ms），缩小棋盘持续期间限频。 */
    private var lastStallCheckAt = 0L

    private fun stallCheckThrottled(): Boolean {
        val now = System.nanoTime()
        if (now - lastStallCheckAt < Const.LIFT_STALL_CHECK_INTERVAL_MS * 1_000_000) return true
        lastStallCheckAt = now
        return false
    }

    /** det 四角重定位校验（截屏→verify→回收），返回完整结论供分支判断。 */
    private fun geometryVerify(): BoardGeometryGuard.Result {
        val raw = capture.screenshot()
        if (raw == null) {
            warnThrottled("几何校验截屏不可用，按未通过处理，继续等待")
            return BoardGeometryGuard.Result(BoardGeometryGuard.Verdict.NO_DETECT)
        }
        return try {
            BoardGeometryGuard.verify(context, raw)
        } finally {
            raw.recycle()
        }
    }

    /**
     * 摆棋接受前的几何一致性校验（det 重定位四角 vs 校准四角，容差 Const.BOARD_GEOMETRY_TOL_PX）。
     * 未通过打节流 WARN 并返回 false（调用方继续等待；「下一关」等结算交互不受影响）。
     */
    private fun geometryOk(): Boolean {
        val result = geometryVerify()
        return when (result.verdict) {
            BoardGeometryGuard.Verdict.PASS -> true
            BoardGeometryGuard.Verdict.MISMATCH ->
                warnThrottled(
                    "棋盘几何与校准不符（最大偏差 %.0fpx > ${Const.BOARD_GEOMETRY_TOL_PX}px，" +
                            "疑似结束动画/缩放棋盘），拒绝接受，继续等待".format(result.maxDevPx ?: -1.0)
                )

            BoardGeometryGuard.Verdict.NO_DETECT ->
                warnThrottled("det 四角未检出（过渡帧/遮挡），拒绝接受，继续等待")
        }
    }

    private fun warnThrottled(msg: String): Boolean {
        val now = System.nanoTime()
        if (now - lastGeomWarnAt > 3_000_000_000L) {
            lastGeomWarnAt = now
            LogBus.log(LogLevel.WARN, LogTag.NEXT, msg)
        }
        return false
    }

    companion object {
        fun isBackWord(word: String): Boolean = word in Const.GAMEOVER_BACK_WORDS
    }
}
