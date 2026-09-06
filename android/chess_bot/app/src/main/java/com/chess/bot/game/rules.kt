package com.chess.bot.game

/**
 * 伪合法着法校验（纯函数，2026-09-06 P0 修复）。
 *
 * 背景：走子动画中间帧可能被 inferMove 推断成非法着法——黑象 c9->e7 飞行途经 d8 时，
 * 某帧「c9 空 + d8 有象」被推断为「象 c9->d8」（象走一格斜线，非法）并提交，
 * 一次真实敌着被消费成两次 → 轮次永久错位一拍，后续我方点击全被吞、靠重试硬撑。
 * 故所有从画面变化推断出的着法（inferMove/反吃/N3/N4）提交前必须过本校验，非法即拒、等干净帧。
 *
 * 只做几何/约束校验（不含将帅照面——committed board 若有识别误差，照面检查会误杀真实着法；
 * 动画中间帧伪着法均栽在基本走法几何上，几何校验已足够）。
 *
 * 屏幕方位约定（2026-09-06 01:53 事故修正）：本库网格恒定「我方在屏幕下半区（rows 5..9）」，
 * 敌方在上半区（rows 0..4）——与我方执红执黑无关（执黑时红子识别为 r_ 前缀且位于 rows 0..4）。
 * 故半场/九宫/兵方向一律按「该子颜色是否等于 mySide」推屏幕方位，禁止按红黑写死绝对方向。
 * （事故：我方执黑时敌方红相 c0(0,6)->e2(2,4) 全部落定帧被写死的「红=rows 5..9」半场检查误杀，
 * 连续 3 帧 NOISY → 误暂停。）
 *
 * @param board 走子前的已提交棋盘（state.board），用于蹩腿/象眼/炮架/路径判断
 * @param mySide 我方阵营（屏幕方位基准，必传不给默认值——调用方必须显式声明视角）
 */
fun isPseudoLegal(board: Board, move: Move, mySide: Side): Boolean {
    val (r1, c1) = move.src
    val (r2, c2) = move.dst
    if (r1 !in 0 until ROWS || c1 !in 0 until COLS) return false
    if (r2 !in 0 until ROWS || c2 !in 0 until COLS) return false
    if (r1 == r2 && c1 == c2) return false
    if (board[r1][c1] != move.piece) return false // 起点须为该子（防陈旧棋盘）
    val dst = board[r2][c2]
    if (dst != null && pieceColor(dst) == pieceColor(move.piece)) return false // 不吃己方
    val dr = r2 - r1
    val dc = c2 - c1
    // 棋子 ID 第三字符区分兵种，但黑方为小写（b_r）、红方为大写（r_R）→ 统一转大写分派
    return when (move.piece[2].uppercaseChar()) {
        'R' -> rookLegal(board, r1, c1, dr, dc, capture = dst != null)
        'N' -> knightLegal(r1, c1, dr, dc, board)
        'B' -> elephantLegal(r1, c1, dr, dc, board, move.piece, mySide)
        'A' -> advisorLegal(r1, c1, dr, dc, move.piece, mySide)
        'K' -> generalLegal(r1, c1, dr, dc, move.piece, mySide)
        'C' -> cannonLegal(board, r1, c1, dr, dc, capture = dst != null)
        'P' -> pawnLegal(r1, c1, dr, dc, move.piece, mySide)
        else -> false
    }
}

/** 車：直线，路径中间（不含起终点）无子。 */
private fun rookLegal(board: Board, r1: Int, c1: Int, dr: Int, dc: Int, capture: Boolean): Boolean {
    if (dr != 0 && dc != 0) return false
    return pathClear(board, r1, c1, dr, dc, screens = 0)
}

/** 炮：不吃子走法同車；吃子须恰有一个炮架（路径中间恰一子）。 */
private fun cannonLegal(
    board: Board,
    r1: Int,
    c1: Int,
    dr: Int,
    dc: Int,
    capture: Boolean,
): Boolean {
    if (dr != 0 && dc != 0) return false
    return pathClear(board, r1, c1, dr, dc, screens = if (capture) 1 else 0)
}

/** 直线路径中间棋子数是否恰为 screens（不含起终点）。 */
private fun pathClear(
    board: Board,
    r1: Int,
    c1: Int,
    dr: Int,
    dc: Int,
    screens: Int,
): Boolean {
    val stepR = if (dr != 0) dr / kotlin.math.abs(dr) else 0
    val stepC = if (dc != 0) dc / kotlin.math.abs(dc) else 0
    var r = r1 + stepR
    var c = c1 + stepC
    var count = 0
    while (r != r1 + dr || c != c1 + dc) {
        if (board[r][c] != null) count++
        r += stepR
        c += stepC
    }
    return count == screens
}

/** 马：日字，蹩腿格（长腿方向相邻格）须为空。 */
private fun knightLegal(r1: Int, c1: Int, dr: Int, dc: Int, board: Board): Boolean {
    val adr = kotlin.math.abs(dr)
    val adc = kotlin.math.abs(dc)
    if (!((adr == 1 && adc == 2) || (adr == 2 && adc == 1))) return false
    val legR = if (adr == 2) r1 + dr / 2 else r1
    val legC = if (adc == 2) c1 + dc / 2 else c1
    return board[legR][legC] == null
}

/** 相/象：田字，象眼（中点）须为空，不得过河（本方半区：我方 rows 5..9 / 敌方 rows 0..4）。 */
private fun elephantLegal(
    r1: Int,
    c1: Int,
    dr: Int,
    dc: Int,
    board: Board,
    piece: String,
    mySide: Side,
): Boolean {
    if (kotlin.math.abs(dr) != 2 || kotlin.math.abs(dc) != 2) return false
    val r2 = r1 + dr
    // 屏幕方位按「该子是否我方」推：我方子活动于下半区，敌方子上半区（禁止按红黑写死）
    val inHalf = { r: Int -> if (pieceColor(piece) == mySide) r >= 5 else r <= 4 }
    if (!inHalf(r1) || !inHalf(r2)) return false
    return board[r1 + dr / 2][c1 + dc / 2] == null
}

/** 士/仕：斜线一步，且起终点均在九宫（col 3..5；我方 rows 7..9 / 敌方 rows 0..2）。 */
private fun advisorLegal(
    r1: Int,
    c1: Int,
    dr: Int,
    dc: Int,
    piece: String,
    mySide: Side,
): Boolean {
    if (kotlin.math.abs(dr) != 1 || kotlin.math.abs(dc) != 1) return false
    return inPalace(r1, c1, piece, mySide) && inPalace(r1 + dr, c1 + dc, piece, mySide)
}

/** 将/帥：横竖一步，且起终点均在九宫。 */
private fun generalLegal(
    r1: Int,
    c1: Int,
    dr: Int,
    dc: Int,
    piece: String,
    mySide: Side,
): Boolean {
    if (kotlin.math.abs(dr) + kotlin.math.abs(dc) != 1) return false
    return inPalace(r1, c1, piece, mySide) && inPalace(r1 + dr, c1 + dc, piece, mySide)
}

private fun inPalace(r: Int, c: Int, piece: String, mySide: Side): Boolean {
    if (c !in 3..5) return false
    // 我方九宫在屏幕下方 rows 7..9，敌方九宫在上方 rows 0..2
    return if (pieceColor(piece) == mySide) r in 7..9 else r in 0..2
}

/** 兵/卒：向前一格；过河后可横走一格，永不后退。
 *  屏幕方位：我方子在下半区向上进（dr=-1），敌方子在上半区向下进（dr=+1）；
 *  过河判定同理（我方子 r1<=4 为已过河，敌方子 r1>=5 为已过河）。 */
private fun pawnLegal(
    r1: Int,
    c1: Int,
    dr: Int,
    dc: Int,
    piece: String,
    mySide: Side,
): Boolean {
    val isMySide = pieceColor(piece) == mySide
    return if (dr == 0 && kotlin.math.abs(dc) == 1) {
        // 横走：须已过河
        if (isMySide) r1 <= 4 else r1 >= 5
    } else {
        dr == (if (isMySide) -1 else 1) && dc == 0
    }
}
