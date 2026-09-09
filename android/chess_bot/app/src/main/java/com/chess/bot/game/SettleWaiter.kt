package com.chess.bot.game

import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag

/**
 * 摆棋稳定等待器（开始棋局与自动下一局共用）。
 *
 * 2026-09-08 lift 感知改造（方案 game_start_lift_recovery_plan.md）：
 * - 我方半区 lift（此前走棋失败棋子在手）→ **将帅同现门控**（2026-09-09 21:53 真机误判修复，
 *   对称 R2=A：遮罩消除过渡帧黑将默认位被误读为 lift，无佐证直接确认 2 帧触发无意义恢复流程；
 *   真实提子必发生在对局中）→ 连续同位置 [Const.LIFT_CONFIRM_FRAMES] 帧确认
 *   （过滤飞行途经瞬态伪影）后返回 [Feed.OwnLift]，交 BotSession 恢复流程；
 * - 敌方半区 lift（敌方提子未落）→ 等待落子，不计数不就绪
 *   （D3=A 无超时：走棋超时后游戏播放超时动画，自然触发 OCR 结束扫描）；
 * - 32 子分支加布局校验（Q1 修复）：开局形态（默认位或红方走一子，D2=A）才立即就绪，
 *   其余（重摆动画瞬态/上局残留，偏差超 1 子）走 3 帧稳定计数；
 * - 31 子：全在初始位置（lift 格按「棋子仍在原格」计，Q2 修复）→ 提子过渡态继续等；
 *   有子离位 → 残局走稳定计数；
 * - 将帅门控：其余子数下将/帥缺任一格 → 过渡帧，不计稳定；
 * - 其余子数：与上一帧逐值相等累加稳定计数，连续 BOARD_STABLE_THRESHOLD 帧即返回；
 * - G1=A/B（2026-09-08）：我方提子确认超 LIFT_STALL_FRAMES 帧仍未恢复 → OwnLiftStalled
 *   触发调用方 det 几何诊断（缩小棋盘误读为提子防护）；MISMATCH → blockLift 封锁该提子格
 *   （静止棋盘重置无效，换位/消失自动解除），PASS → ackStall 放行恢复；
 *   确认/敌方提子日志封顶防刷屏。
 *
 * Kotlin 数组 == 是引用比较，必须 contentDeepEquals（M6 补丁① 结论）。
 */
class SettleWaiter(private val tag: LogTag = LogTag.NEXT) {

    sealed interface Feed {
        data object Waiting : Feed
        data class Ready(val count: Int, val openingForm: Boolean = false) : Feed

        /** 我方提子未落已确认（liftPos 为提子格）：调用方执行恢复流程。 */
        data class OwnLift(val liftPos: Pair<Int, Int>) : Feed

        /** 提子卡死诊断（G1=A）：确认次数达 [Const.LIFT_STALL_FRAMES] 仍未完成恢复 →
         *  调用方做一次 det 几何诊断（缩小棋盘误读为提子防护）。每个提子 episode 至多一次。 */
        data class OwnLiftStalled(val liftPos: Pair<Int, Int>) : Feed
    }

    private var prevBoard: Board? = null
    private var stableCount = 0
    private var prevOwnLift: Pair<Int, Int>? = null
    private var ownLiftStable = 0

    /** 卡死事件是否已被调用方处理（log2.txt 复审修复：发事件时不置位，由调用方
     *  真正执行了诊断（未节流）时 ackStall()——否则 10s 节流吞掉事件后门永久失效）。 */
    private var stallFired = false

    /** 卡死诊断 INFO 日志每 episode 只打一次（节流期内事件每帧重发，不能每帧刷日志）。 */
    private var stallLogged = false

    /** 几何诊断判 MISMATCH 后封锁的提子格：同位置不再确认/恢复（静止缩小棋盘重置无效，
     *  只能等棋盘变化/提子换位自动解除）。 */
    private var blockedLiftPos: Pair<Int, Int>? = null

    /** 敌方提子日志每 episode 只打一次（B：避免长动画期间每帧刷屏）。 */
    private var enemyLiftLogged = false

    /** 最近一次喂入帧的棋子总数（供悬浮窗等待态显示「子数 N」）。 */
    var lastCount = 0
        private set

    /** 连续稳定帧数（供悬浮窗等待态显示「稳定 X/${threshold}」）。 */
    val stableProgress: Int get() = stableCount

    /** 稳定判定阈值（与 Const.BOARD_STABLE_THRESHOLD 一致）。 */
    val threshold: Int get() = Const.BOARD_STABLE_THRESHOLD

    /** 清空稳定计数（几何守卫拒绝后调用：强制重新计 3 帧，拉开两次 det 校验的间隔）。 */
    fun resetStable() {
        prevBoard = null
        stableCount = 0
    }

    /** 调用方真正执行了卡死诊断（未节流）后调用：此后同 episode 不再重发诊断事件。 */
    fun ackStall() {
        stallFired = true
    }

    /** 封锁该提子格（几何诊断 MISMATCH 后调用）：同位置不再确认/恢复，
     *  提子换位或消失自动解除；静止缩小棋盘重置确认计数无效，只能等棋盘变化。 */
    fun blockLift(pos: Pair<Int, Int>) {
        blockedLiftPos = pos
        resetOwnLift()
        stallFired = false
    }

    /** 喂入一帧全量识别布局。 */
    fun feed(board: Board): Feed {
        val count = pieceCount(board)
        lastCount = count
        val lifts = liftCells(board)
        val ownLift = lifts.firstOrNull { it.first >= Const.OWN_HALF_MIN_ROW }
        val enemyLift = lifts.firstOrNull { it.first < Const.OWN_HALF_MIN_ROW }
        if (enemyLift == null) enemyLiftLogged = false // 敌方提子 episode 结束
        // 封锁的提子格已换位或消失 → 自动解除封锁
        if (ownLift != blockedLiftPos) blockedLiftPos = null
        return when {
            // 几何诊断已判不符的同一提子格：忽略（等棋盘变化），不再确认/恢复
            ownLift != null && ownLift == blockedLiftPos -> {
                resetOwnLift()
                prevBoard = board
                stableCount = 0
                Feed.Waiting
            }

            ownLift != null && bothGeneralsPresent(board) -> {
                // 我方提子未落（此前我方走棋失败，棋子被提起悬停原格上半部）：
                // 连续同位置确认 K 帧后交恢复流程，不参与普通稳定计数
                if (prevOwnLift == ownLift) {
                    ownLiftStable++
                } else {
                    prevOwnLift = ownLift
                    ownLiftStable = 1
                    stallFired = false // 换位 = 新 episode，卡死诊断可再次触发
                    stallLogged = false
                }
                prevBoard = board
                // B（G1=B）：确认帧日志封顶——达到 N 帧后不再逐帧刷（缩小棋盘误读时曾连刷 10+ 条）
                if (ownLiftStable <= Const.LIFT_STALL_FRAMES) {
                    LogBus.log(
                        LogLevel.DEBUG, tag,
                        "我方提子未落(r=${ownLift.first},c=${ownLift.second})，" +
                                "确认 $ownLiftStable/${Const.LIFT_CONFIRM_FRAMES}",
                    )
                }
                // G1=A（N=2）：确认次数已达 N 帧仍未完成恢复（首次恢复 RETRY 后的下帧）→
                // 发卡死诊断事件。log2.txt 复审修复：不在此处置位 stallFired——调用方
                // 节流跳过诊断时不 ack，下帧重发（10s 到点自动重检）；诊断后 ack/block
                if (ownLiftStable >= Const.LIFT_CONFIRM_FRAMES &&
                    ownLiftStable > Const.LIFT_STALL_FRAMES &&
                    !stallFired
                ) {
                    if (!stallLogged) {
                        stallLogged = true
                        LogBus.log(
                            LogLevel.INFO, tag,
                            "我方提子未落确认 ${ownLiftStable} 帧仍未恢复，触发几何诊断" +
                                    "（疑似结束动画缩小棋盘被误读为提子）",
                        )
                    }
                    return Feed.OwnLiftStalled(ownLift)
                }
                return if (ownLiftStable >= Const.LIFT_CONFIRM_FRAMES) {
                    Feed.OwnLift(ownLift)
                } else {
                    Feed.Waiting
                }
            }

            ownLift != null -> {
                // R2 对称门控（2026-09-09 21:53 真机误判修复）：遮罩消除/大厅等过渡画面
                // cls 误读 lift（无将帅佐证）→ episode 复位按过渡帧处理，不确认不恢复。
                // 真实我方提子必发生在对局中（将帅同现）；本例 lift 源恰为黑将默认位被读空，
                // 该帧将帅必然缺一 → 门控零成本拦截。G1=A 卡死诊断链不受影响：
                // 缩小棋盘帧读出将帅同现仍走原链，读不出则被此处忽略（本就该忽略）。
                // 日志不打（对齐 2026-09-09 精简，等待态由悬浮窗 waitDetail 呈现）。
                resetOwnLift()
                prevBoard = board
                stableCount = 0
                Feed.Waiting
            }

            enemyLift != null && bothGeneralsPresent(board) -> {
                // 敌方提子未落（敌方走棋中途）：等待落子（无超时，D3=A）
                // B：日志每 episode 一次（首局启动等待期曾连刷 20 秒）
                resetOwnLift()
                prevBoard = board
                stableCount = 0
                if (!enemyLiftLogged) {
                    enemyLiftLogged = true
                    LogBus.log(
                        LogLevel.DEBUG, tag,
                        "敌方提子未落(r=${enemyLift.first},c=${enemyLift.second})，等待落子",
                    )
                }
                Feed.Waiting
            }

            enemyLift != null -> {
                // R2=A（2026-09-09）：真实提子必然发生在对局中（将帅同现，count=31/32）；
                // 大厅/过渡画面 cls 误读 lift（无将帅佐证）时 episode 复位，按普通过渡帧
                // 处理并落入下方将帅门控分支——不再打「敌方提子未落」误导日志。
                // （我方 lift 分支 2026-09-09 21:53 起加对称门控，见上方 ownLift 分支；
                // G1=A 的 OwnLiftStalled→det 诊断链保留在将帅同现的确认分支内。）
                enemyLiftLogged = false
                resetOwnLift()
                prevBoard = board
                stableCount = 0
                Feed.Waiting
            }

            count == 31 -> {
                // 区分提子过渡态（全在初始位置 → 继续等 32 子）与残局（有子离初始
                // 位置 → 按稳定计数等待后返回，避免残局开局卡在等待摆棋）
                resetOwnLift()
                val side = detectSide(board)
                if (side != null && allOnInitialSquares(board, side)) {
                    // 提子过渡态（31 子全初始位）：语义已由「敌方提子未落，等待落子」episode
                    // 日志覆盖，此处不再逐帧打日志（2026-09-09 日志精简：只留关键点）
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
                    // openingForm=true：32 子完整开局形态是上局残盘不可能伪造的强信号，
                    // 统一 StartLoop 证据门（endgameNeedsResetEvidence）对此直通
                    Feed.Ready(count, openingForm = true)
                } else {
                    // 非开局形态（偏差超 1 子：重摆动画瞬态/上局残留）→ 3 帧稳定（Q1 修复）
                    stabilityFeed(board, count, "32 子非开局形态（偏差>1子）")
                }
            }

            count < 32 && !bothGeneralsPresent(board) -> {
                // 将帅同现门控（2026-09-06 真机日志 bug）：大厅/过渡/清盘残留可能只识别出
                // 2 个「棋子」却进入稳定计数。将/帥缺任一格一律视为过渡帧：不计数、不返回
                // Ready。（31 子残局分支在上文已处理，不受此门控约束。）
                // 状态维护保留、日志已删（2026-09-09 精简：大厅等待期此分支曾 1.2s 一条刷屏
                // 且语义误导，等待态由悬浮窗 waitDetail 实时呈现）
                resetOwnLift()
                prevBoard = board
                stableCount = 0
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
