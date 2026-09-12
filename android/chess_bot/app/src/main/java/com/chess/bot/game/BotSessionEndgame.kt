package com.chess.bot.game

import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.chess.bot.vision.TextMatcher

/**
 * BotSession 终局判定（B3 拆分自 BotSession.kt，2026-09-09 方案 6 / D1=A）。
 *
 * 职责：认输/清盘信号判定（updateResign）+ T-OCR 结算扫描（confirmEndByOcr）+
 * 和棋决策（decideDraw）。（checkmateProbe / logProbeSkipChange 已随二次探测取消删除。）
 * 被 Flow/Verify/Enemy 三侧调用，独立内聚。
 * 字段 lastOcrEndScanAt/lastVerifyDrawScanAt 留守 BotSession 类内（纯移动约束）。
 */

/**
 * T-OCR（2026-09-06）：「疑似对局结束画面」（updateResign SUSPECT）时做一次整屏 OCR 扫描
 * （≥OCR_SUSPECT_SCAN_THROTTLE_MS 节流），命中结算词表（按钮/遮罩任一词）→ 立即 finishGame
 * 返回 true，省掉连续 RESIGN_CONFIRM_COUNT 帧确认等待；未命中返回 false，回落原兜底逻辑
 * （结算动画尚无文字时 OCR 命中不了，仍靠连续棋盘信号确认，二者互补）。
 *
 * force=true（2026-09-10 waitForEnemyMove 重构 D2=A）：绕内层节流强扫一次——供稳定未知兜底
 * （外层已按 ENEMY_STABLE_SCAN_THROTTLE_MS 节流）与总超时中止前使用；时间戳仍更新保持全局一致。
 */
internal suspend fun BotSession.confirmEndByOcr(force: Boolean = false): Boolean {
    val now = System.nanoTime()
    if (!force && now - lastOcrEndScanAt < Const.OCR_SUSPECT_SCAN_THROTTLE_MS * 1_000_000) return false
    lastOcrEndScanAt = now
    val cap = capture ?: return false
    val img = cap.screenshot() ?: return false
    val hit = TextMatcher.findGameoverScan(context, img)
    img.recycle() // 2026-09-07 D2=A：raw Bitmap（~10MB）用完即还，hit 已含词文本与坐标
    if (hit == null) {
        LogBus.log(
            LogLevel.DEBUG,
            LogTag.PLAY,
            "疑似结束画面 OCR 扫描无结算词命中，走连续确认兜底"
        )
        return false
    }
    LogBus.log(
        LogLevel.INFO,
        LogTag.PLAY,
        "OCR 命中结算文字「${hit.word}」（置信度 ${"%.2f".format(hit.score)}），立即终局"
    )
    if (selfMatePending) {
        selfMatePending = false
        finishGame("我方绝杀，${state.mySide.opponent.cn}方无路可走")
    } else {
        finishGame("检测到对局结束画面")
    }
    return true
}

internal fun BotSession.updateResign(newBoard: Board, changes: List<Change>): ResignResult {
    // 疑似结束双路（2026-09-12 用户批复）：
    // ① 任一将/帥离盘——「变空」才算，本格读到 lift（提起）算仍在盘上（口径见 isResignSuspect）。
    //    原口径要求「两将同时缺失」，残局遮罩下常只有一侧读空（2026-09-12 log2.txt：黑將 d8 确认 ->空
    //    [1.00]，红帥 d0 仅 0.65~0.80 未确认）→ 判不出结束；
    // ② 单帧 >6 个已提交棋子变为空（changes 中 old!=null && new==null）——满盘清盘动画信号。
    // 提速后游戏结束动画渐进遮盖将帅，单帧信号会抖动，故仍需连续 RESIGN_CONFIRM_COUNT 帧确认。
    val emptyDrop = changes.count { it.old != null && it.new == null }
    val suspect =
        isResignSuspect(newBoard, state.board, state.mySide) ||
                emptyDrop > Const.RESIGN_EMPTY_DROP_MAX
    if (suspect) {
        state.resignStreak++
        LogBus.log(
            LogLevel.DEBUG,
            LogTag.PLAY,
            "疑似对局结束画面（${state.resignStreak}/${Const.RESIGN_CONFIRM_COUNT}，清盘空格 $emptyDrop）",
        )
        return if (state.resignStreak >= Const.RESIGN_CONFIRM_COUNT) {
            ResignResult.CONFIRMED
        } else {
            ResignResult.SUSPECT
        }
    }
    state.resignStreak = 0
    return ResignResult.NONE
}

internal fun BotSession.decideDraw(): Boolean {
    setStatus(BotStatus.DRAW_HANDLING)
    val score = state.lastEvalScore
    val reject = decideDraw(score, Const.DRAW_REJECT_CP)
    LogBus.log(
        LogLevel.INFO,
        LogTag.PLAY,
        if (reject) "和棋决策：我方占优（${score}cp），拒绝和棋" else "和棋决策：均势或劣势（${score}cp），同意和棋",
    )
    return reject
}
