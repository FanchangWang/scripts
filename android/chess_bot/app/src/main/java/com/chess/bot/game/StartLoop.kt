package com.chess.bot.game

import android.content.Context
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.chess.bot.overlay.BotRuntime
import com.chess.bot.service.Capture
import com.chess.bot.vision.BoardGeometryGuard
import com.chess.bot.vision.PieceClsModel
import com.chess.bot.vision.Recognizer
import com.chess.bot.vision.TextMatcher
import kotlinx.coroutines.delay
import org.opencv.core.Mat

/**
 * 统一启动循环（2026-09-09 U1=A，用户提议方案落地）：
 * 「开始对弈」（手动）与「自动下一局」共用同一套 OCR 交互 + 摆棋检测循环，替代原
 * BotSession.waitForBoardSettled（仅摆棋、无 OCR 交互）与 AutoNext.scanAndWait（OCR 先行
 * 但 OCR 0 命中即落入摆棋分支——log.txt 事故入口）两份相似循环。
 *
 * 每轮顺序：头部检查 → OCR 结算交互（终止词/遮罩/按钮）→ det 四角 + 棋子识别 + 稳定判定。
 * OCR 交互先行保证任何弹窗（结算/遮罩）在屏时不会误判摆棋；交互成功产生摆棋准入证据
 * [StartExpectation.endgameNeedsResetEvidence] 所需的 allowSettle。
 *
 * 摆棋准入证据门（log.txt 事故修复；V2 按三游戏模式建模 2026-09-09 D1=A）：自动下一局场景下，
 * 非 32 子开局形态的 Ready（三模式中唯一合法来源=模式③残局结算页点击按钮后摆出的预设残局）
 * 需「结算交互成功（allowSettle）」才接受，否则按疑似上局残盘/过关重演画面拒绝、继续等待；
 * 手动开始由用户当面摆棋，免证据。清盘帧证据已废弃——模式③结算页中部的缩小棋盘会被
 * cls 读出 0 子，「连续 0 子」≠真清盘（17:00 循环实证：resetSeen 被污染放行缩小棋盘重演的上局残盘）。
 * OCR 交互成功后延时 [Const.OCR_INTERACTION_SETTLE_MS]，等待页面切换动画帧播完再恢复检测。
 *
 * 两路径差异经 [StartExpectation] 注入（逻辑共用、策略分离）：
 * - 残局准入：手动免证据 / 自动需证据；
 * - 超时：手动无超时（手动停止为止）/ 自动 [Const.AUTO_NEXT_TIMEOUT_S]；
 * - 我方提子恢复：手动有（棋局中途点开始恢复提子）/ 自动无——新局理论上不存在我方提子，
 *   且敌方为红方手速快时两帧可能截到红方侧（下半区）提子帧，此时按过渡帧等待红方落子，
 *   不触发恢复流程（敌方提子等待落子由 SettleWaiter 统一处理，两路径一致）。
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

/** 启动循环结果：null = 中断/超时/失败中止。 */
sealed interface StartLoopResult {
    /** 摆棋稳定就绪，corrected 所有权归调用方（走正常 initialize 流程）。 */
    data class Ready(val corrected: Mat) : StartLoopResult

    /** 我方提子恢复走子已完成并接管状态——调用方跳过 state.reset/initialize/轮次判定。 */
    data object Adopted : StartLoopResult
}

/**
 * 启动循环策略注入：逻辑共用、路径差异参数化（2026-09-09 U1-U3=A）。
 */
data class StartExpectation(
    /** 日志 tag：手动 PLAY / 自动 NEXT。 */
    val tag: LogTag,
    /** 进入循环时设置的状态；null = 调用方已自行设置（自动下一局设 AUTO_NEXT）。 */
    val entryStatus: BotStatus?,
    /** 下半区提子是否触发我方提子恢复流程（手动 true / 自动 false）。 */
    val allowOwnLiftRecovery: Boolean,
    /** 非 32 子开局形态的 Ready 是否需要准入证据（手动 false / 自动 true）。 */
    val endgameNeedsResetEvidence: Boolean,
    /** 总超时秒数；null = 无超时（手动停止为止）。 */
    val timeoutS: Int?,
    /** 自动下一局开关检查；null = 不检查（手动开始不判断，2026-09-09 用户修正）。 */
    val autoNextEnabled: (() -> Boolean)? = null,
    /**
     * OCR 交互态退出后回切的「等待摆棋」子状态（2026-09-09 用户修正）：OCR 命中按钮/遮罩时
     * 状态切为 NEXT_BUTTON/NEXT_MASK，无 OCR 命中回到棋盘检测时应切回本状态——原实现
     * 交互后状态一直卡在按钮/遮罩提示不回切。手动=WAIT_PLACEMENT，自动=AUTO_NEXT。
     */
    val waitingStatus: BotStatus? = null,
) {
    companion object {
        /** 手动开始策略：无超时、提子恢复有、残局免证据、不检查自动下一局开关。 */
        val MANUAL = StartExpectation(
            tag = LogTag.PLAY,
            entryStatus = BotStatus.WAIT_PLACEMENT,
            allowOwnLiftRecovery = true,
            endgameNeedsResetEvidence = false,
            timeoutS = null,
            waitingStatus = BotStatus.WAIT_PLACEMENT,
        )
    }
}

/**
 * 统一启动循环（SettleWaiter 为摆棋判定内核，本类只做外壳：OCR 交互 + 证据门 + 门控编排）。
 * 每次调用新建实例（节流状态随调用生命周期）。
 */
class StartLoop(
    private val context: Context,
    private val capture: Capture,
    private val shouldContinue: () -> Boolean,
    /** 命中终止类词（如「体力x2」）时的自动中断回调（BotSession 传入 interrupt()，与用户点停止等价）。 */
    private val interruptSession: () -> Unit = {},
    private val onPhase: (BotStatus) -> Unit = {},
    /** 我方提子恢复回调（BotSession.recoverOwnLift）；corrected 所有权移交回调内部释放。 */
    private val onOwnLift: suspend (corrected: Mat, liftPos: Pair<Int, Int>) -> LiftRecovery =
        { _, _ -> LiftRecovery.RETRY },
) {

    /** 返回启动结果；null = 中断/超时/失败。 */
    suspend fun run(expectation: StartExpectation): StartLoopResult? {
        val waiter = SettleWaiter(expectation.tag)
        val startAt = System.nanoTime()
        var lastWord: String? = null
        var retryCount = 0
        // 摆棋准入证据（仅 endgameNeedsResetEvidence=true 路径消费）：
        var allowSettle = false // 唯一证据：本循环内结算按钮/遮罩交互成功（V2 三模式建模，清盘帧证据已废弃）
        var ownLiftNoticeAt = 0L // 自动路径下半区提子读数告警节流
        var inInteraction = false // 当前处于 OCR 交互子状态（NEXT_BUTTON/NEXT_MASK）
        expectation.entryStatus?.let(onPhase)
        // 注意：字符串拼接须显式括号分组——原写法 if/else 表达式吞掉尾部 "）"，
        // 手动路径日志输出为「…手动停止为止」缺右括号（16:21 真机日志实证）
        LogBus.log(
            LogLevel.INFO, expectation.tag,
            "启动等待循环（" +
                    (if (expectation.timeoutS == null) "无超时，手动停止为止" else "总超时 ${expectation.timeoutS}s") +
                    "；残局准入" +
                    (if (expectation.endgameNeedsResetEvidence) "需清盘/交互证据" else "免证据") +
                    "）"
        )
        PieceClsModel.ensure(context)

        while (true) {
            if (!shouldContinue()) return null
            expectation.autoNextEnabled?.let { enabled ->
                if (!enabled()) {
                    LogBus.log(LogLevel.WARN, expectation.tag, "自动下一局已关闭，中止等待循环")
                    return null
                }
            }
            expectation.timeoutS?.let { timeoutS ->
                if (elapsedSeconds(startAt) > timeoutS) {
                    LogBus.log(
                        LogLevel.WARN, expectation.tag,
                        "${timeoutS}秒未完成结算交互与摆棋，中止自动下一局，请手动处理",
                    )
                    return null
                }
            }
            delay(Const.GAMEOVER_SCAN_INTERVAL_MS)

            // ---------- OCR 结算交互（每轮先行：有弹窗先处理弹窗，再看棋盘） ----------
            val raw = capture.screenshot()
            val hit = raw?.let { TextMatcher.findGameoverScan(context, it) }
            raw?.recycle() // raw Bitmap（~10MB）用完即还，hit 已含词文本与坐标
            if (hit != null) {
                // 终止类词：识别到即自动中断对弈（等价用户点停止），优先级高于遮罩/按钮
                if (hit.word in Const.GAMEOVER_INTERRUPT_WORDS) {
                    LogBus.log(
                        LogLevel.INFO, expectation.tag,
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
                inInteraction = true // 子状态切至交互态（退出交互态时回切 waitingStatus）
                onPhase(if (isButton) BotStatus.NEXT_BUTTON else BotStatus.NEXT_MASK)
                val ok = if (!isButton) {
                    if (retryCount > Const.GAMEOVER_RETRY_MAX) {
                        LogBus.log(
                            LogLevel.ERROR, expectation.tag,
                            "遮罩「$word」发送返回键 ${Const.GAMEOVER_RETRY_MAX} 次仍无响应，中止，请手动处理",
                        )
                        return null
                    }
                    LogBus.log(
                        LogLevel.INFO, expectation.tag,
                        "识别到遮罩文字「$word」，发送返回键（第 $retryCount/${Const.GAMEOVER_RETRY_MAX} 次）",
                    )
                    capture.back()
                } else {
                    if (retryCount > Const.GAMEOVER_RETRY_MAX) {
                        LogBus.log(
                            LogLevel.ERROR, expectation.tag,
                            "结算按钮「$word」点击 ${Const.GAMEOVER_RETRY_MAX} 次仍无响应，中止，请手动处理",
                        )
                        return null
                    }
                    LogBus.log(
                        LogLevel.INFO, expectation.tag,
                        "识别到结算按钮「$word」，点击进入下一局（第 $retryCount/${Const.GAMEOVER_RETRY_MAX} 次）",
                    )
                    capture.tapXy(x, y)
                }
                if (!ok) {
                    LogBus.log(
                        LogLevel.ERROR,
                        expectation.tag,
                        "启动等待循环交互失败，中止，请手动处理"
                    )
                    return null
                }
                allowSettle = true // 唯一证据：结算交互已发生（V2：模式③残局准入仅认此项）
                // 交互后延时（2026-09-09 D1=A 用户要求）：页面切换动画帧需要时间，
                // 立即恢复检测会读到过渡画面（原 300ms 间隔不够，循环#1 点击后 3 秒即放行过渡帧）
                delay(Const.OCR_INTERACTION_SETTLE_MS)
                continue
            }

            // ---------- 棋盘检测（det 四角几何守卫 + SettleWaiter 稳定判定内核） ----------
            // 子状态回切（2026-09-09 用户修正）：无 OCR 命中回到棋盘检测时，若此前处于
            // OCR 交互态（NEXT_BUTTON/NEXT_MASK），切回「等待摆棋」态——原实现交互后
            // 状态一直卡在按钮/遮罩提示
            if (inInteraction) {
                inInteraction = false
                expectation.waitingStatus?.let(onPhase)
            }
            val corrected = capture.grab() ?: continue
            // 所有权移交：命中返回路径时归调用方/恢复回调释放；其余路径本层 finally 立即释放
            var handOff = false
            try {
                val board = Recognizer.analyzeBoard(corrected)
                val count = pieceCount(board)
                // 外抛等待态信息到悬浮窗（已等待秒数 + 子数/稳定摘要）
                BotRuntime.waitElapsedS.value =
                    ((System.nanoTime() - startAt) / 1_000_000_000L).toInt()
                BotRuntime.waitDetail.value = when {
                    count == 31 -> "子数 31（判定提子/残局）"
                    count == 0 -> "未识别到棋盘"
                    else -> "子数 $count · 稳定 ${waiter.stableProgress}/${waiter.threshold}"
                }
                // 证据前置预检（2026-09-09 用户提议+裁定）：自动路径无证据时，1..31 子帧注定被拒
                // ——静默跳过稳定计数，不打日志：结算动画期拒绝是必现常态，节流 WARN 零信息量
                // （log 17:58:41/45 两条被裁无效），等待态由悬浮窗呈现，「识别到结算按钮」INFO
                // 才是关键点。仅点击后仍被拒（下方 Ready 后置 else 分支）才是异常，保留告警。
                // 32 子帧不前置（真实新局/重摆瞬态仍需 waiter 判 openingForm）。
                if (count in 1 until 32 && expectation.endgameNeedsResetEvidence && !allowSettle) {
                    BotRuntime.waitDetail.value = "等待结算交互 · 识别到 $count 子"
                } else if (count > 0) {
                    when (val f = waiter.feed(board)) {
                        is SettleWaiter.Feed.Ready -> {
                            // 证据门（V2 三模式建模 D1=A）：非 32 子开局形态的 Ready 在自动路径
                            // 仅认「结算交互成功」——残局/排局只可能来自模式③按钮点击后；
                            // 清盘帧证据已废弃（缩小棋盘读 0 子会伪造清盘）
                            val evidenceOk =
                                f.openingForm || !expectation.endgameNeedsResetEvidence ||
                                        allowSettle
                            if (evidenceOk) {
                                // 几何守卫（2026-09-08）：稳定≠完整尺寸棋盘（缩小棋盘内容级校验全过）。
                                // det 重定位四角通过才接受；拒绝则重新计稳定继续等待
                                if (geometryOk(expectation.tag)) {
                                    handOff = true
                                    LogBus.log(
                                        LogLevel.INFO, expectation.tag,
                                        "棋盘已就绪（${f.count} 子）"
                                    )
                                    return StartLoopResult.Ready(corrected)
                                }
                                waiter.resetStable()
                            } else {
                                waiter.resetStable()
                                warnThrottled(
                                    expectation.tag,
                                    "摆棋准入证据不足（未点过结算按钮），" +
                                            "疑似上局残盘/过关重演画面，拒绝接受 ${f.count} 子，继续等待"
                                )
                            }
                        }

                        is SettleWaiter.Feed.OwnLift -> {
                            if (expectation.allowOwnLiftRecovery) {
                                // 我方提子确认：Mat 所有权移交恢复回调（内部释放）；
                                // ADOPTED → 恢复走子完成、状态已接管（返回 Adopted，调用方跳过重新初始化）；
                                // ABORT → 恢复走子失败中止启动；RETRY → 继续等待下帧重试
                                handOff = true
                                when (onOwnLift(corrected, f.liftPos)) {
                                    LiftRecovery.ADOPTED -> return StartLoopResult.Adopted
                                    LiftRecovery.ABORT -> return null
                                    LiftRecovery.RETRY -> {} // 继续等待，提子仍在会再次触发
                                }
                            } else {
                                // 自动下一局：新局不应有我方（下半区）提子；敌方为红方手速快时
                                // 两帧可能截到红方侧提子帧——按过渡帧等待红方落子（落子后自然
                                // 消失），不触发恢复流程；若为缩小棋盘误读，OwnLiftStalled
                                // 诊断门会兜底封锁（与手动路径同机制）
                                val now = System.nanoTime()
                                if (now - ownLiftNoticeAt > 5_000_000_000L) {
                                    ownLiftNoticeAt = now
                                    LogBus.log(
                                        LogLevel.DEBUG, expectation.tag,
                                        "新局等待期出现下半区提子读数(r=${f.liftPos.first},c=${f.liftPos.second})，" +
                                                "按过渡帧等待落子（不触发我方提子恢复）"
                                    )
                                }
                            }
                        }

                        is SettleWaiter.Feed.OwnLiftStalled -> {
                            // 提子卡死诊断门（G1=A，N=2）：确认超 N 帧仍未恢复 → det 几何诊断。
                            // MISMATCH（缩小棋盘被误读为提子）→ 封锁该提子格回摆棋等待——
                            // 静止缩小棋盘重置确认计数无效（log2.txt 复审：重置后 2 帧又复原），
                            // 同位置不再走恢复流程，等棋盘变化/结算交互自动解除；
                            // PASS/NO_DETECT → ack 放行，真提子恢复流程继续；
                            // 节流期内不 ack（10s 到点重检），诊断事件每帧重发期间不跑恢复
                            if (!stallCheckThrottled()) {
                                when (geometryVerify(expectation.tag).verdict) {
                                    BoardGeometryGuard.Verdict.MISMATCH -> {
                                        warnThrottled(
                                            expectation.tag,
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
                // 周期性「当前识别到 N 个棋子」日志已删（2026-09-09 日志精简：等待态
                // 由悬浮窗 waitDetail 实时呈现，日志只留关键点——OCR 命中/稳定 123/就绪）
            } finally {
                if (!handOff) corrected.release()
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
    private fun geometryVerify(tag: LogTag): BoardGeometryGuard.Result {
        val raw = capture.screenshot()
        if (raw == null) {
            warnThrottled(tag, "几何校验截屏不可用，按未通过处理，继续等待")
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
     * 未通过打节流 WARN 并返回 false（调用方继续等待；结算交互不受影响）。
     */
    private fun geometryOk(tag: LogTag): Boolean {
        val result = geometryVerify(tag)
        return when (result.verdict) {
            BoardGeometryGuard.Verdict.PASS -> true
            BoardGeometryGuard.Verdict.MISMATCH ->
                warnThrottled(
                    tag,
                    "棋盘几何与校准不符（最大偏差 %.0fpx > ${Const.BOARD_GEOMETRY_TOL_PX}px，" +
                            "疑似结束动画/缩放棋盘），拒绝接受，继续等待".format(
                                result.maxDevPx ?: -1.0
                            )
                )

            BoardGeometryGuard.Verdict.NO_DETECT ->
                warnThrottled(tag, "det 四角未检出（过渡帧/遮挡），拒绝接受，继续等待")
        }
    }

    private fun warnThrottled(tag: LogTag, msg: String): Boolean {
        val now = System.nanoTime()
        if (now - lastGeomWarnAt > 3_000_000_000L) {
            lastGeomWarnAt = now
            LogBus.log(LogLevel.WARN, tag, msg)
        }
        return false
    }

    companion object {
        fun isBackWord(word: String): Boolean = word in Const.GAMEOVER_BACK_WORDS
    }
}
