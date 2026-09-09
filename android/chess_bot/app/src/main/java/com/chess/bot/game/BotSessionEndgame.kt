package com.chess.bot.game

import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.chess.bot.vision.TextMatcher

/**
 * BotSession 终局判定（B3 拆分自 BotSession.kt，2026-09-09 方案 6 / D1=A）。
 *
 * 职责：认输/清盘信号判定（updateResign）+ T-OCR 结算扫描（confirmEndByOcr）+
 * 绝杀二次探测（checkmateProbe，Y 方案质量门控跳过）+ 跳过原因打点（logProbeSkipChange）+
 * 和棋决策（decideDraw）。被 Flow/Verify/Enemy 三侧调用，独立内聚。
 * 字段 lastOcrEndScanAt/lastVerifyDrawScanAt 留守 BotSession 类内（纯移动约束）。
 */

/**
 * T-OCR（2026-09-06）：「疑似对局结束画面」（updateResign SUSPECT）时做一次整屏 OCR 扫描
 * （≥OCR_SUSPECT_SCAN_THROTTLE_MS 节流），命中结算词表（按钮/遮罩任一词）→ 立即 finishGame
 * 返回 true，省掉连续 RESIGN_CONFIRM_COUNT 帧确认等待；未命中返回 false，回落原兜底逻辑
 * （结算动画尚无文字时 OCR 命中不了，仍靠连续棋盘信号确认，二者互补）。
 */
internal suspend fun BotSession.confirmEndByOcr(): Boolean {
    val now = System.nanoTime()
    if (now - lastOcrEndScanAt < Const.OCR_SUSPECT_SCAN_THROTTLE_MS * 1_000_000) return false
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
    // 提速后游戏结束动画渐进遮盖将帅，单帧「两将缺失」信号会抖动；改用更稳定的清盘信号：
    // 单帧 >6 个已提交棋子变为空（changes 中 old!=null && new==null），与「两将缺失」取 OR 判疑似结束。
    val emptyDrop = changes.count { it.old != null && it.new == null }
    val suspect =
        isResignSuspect(newBoard, state.mySide) || emptyDrop > Const.RESIGN_EMPTY_DROP_MAX
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

internal suspend fun BotSession.checkmateProbe(): Boolean {
    // Y 方案：本轮着法的引擎 info 质量达标且带明确 mate 值（非杀）→ 对方必有应手，跳过二次探测
    if (mateInfoSolid) {
        logProbeSkipChange("mate 明确", "mate 明确（质量达标）")
        return false
    }
    // Option A：仅终局附近（子少）才二次调用引擎验证，常规中局主搜已覆盖将死，跳过以减少引擎开销
    val count = pieceCount(state.board)
    if (count > Const.ENDGAME_PROBE_PIECE_MAX) {
        // 常态路径：仅原因变化时打点（每步一条曾占单局日志 66 行）。
        // 注意 key 用稳定类别「非终局」，文案才可携带实时子数——key 内嵌子数会永不去重（2026-09-09 复审 R1 修复）
        logProbeSkipChange("非终局", "非终局（$count 子 > ${Const.ENDGAME_PROBE_PIECE_MAX}）")
        return false
    }
    setStatus(BotStatus.GAMEOVER_CHECK)
    lastProbeSkip = null // 本轮真正执行了探测，下轮跳过原因需重新打点
    val opp = state.mySide.opponent
    val fen = fenOfBoard(state.board, state.mySide, opp, state.halfmoveClock)
    LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "绝杀探测 FEN（${opp.cn}方行棋）：$fen")
    val mated = try {
        engine.isMate(context, fen)
    } catch (e: Exception) {
        LogBus.log(
            LogLevel.WARN,
            LogTag.ENGINE,
            "引擎绝杀探测失败，当作未绝杀继续：${e::class.java.simpleName}: ${e.message}"
        )
        return false
    }
    if (!mated) {
        LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "未绝杀，继续对局")
        return false
    }
    finishGame("我方绝杀，${opp.cn}方无路可走")
    return true
}

/** 绝杀探测「跳过原因」变化时才打 DEBUG（常态每步同因跳过不再刷屏）。key 用稳定类别，text 可携带实时数值。 */
internal fun BotSession.logProbeSkipChange(key: String, text: String) {
    if (lastProbeSkip != key) {
        lastProbeSkip = key
        LogBus.log(LogLevel.DEBUG, LogTag.ENGINE, "跳过绝杀二次探测：$text")
    }
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
