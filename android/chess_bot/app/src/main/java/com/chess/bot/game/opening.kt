package com.chess.bot.game

/** 开局局面分析：判阵营、判阶段、推断轮次（纯函数，移植 python opening.py）。 */

/** 将/帥在屏幕下方（行 6..9）则该方为我方；识别不到返回 null。 */
fun detectSide(board: Board): Side? {
    val redBottom = (6 until ROWS).any { r -> (0 until COLS).any { c -> board[r][c] == "r_K" } }
    val blackBottom = (6 until ROWS).any { r -> (0 until COLS).any { c -> board[r][c] == "b_k" } }
    return when {
        redBottom -> Side.RED
        blackBottom -> Side.BLACK
        else -> null
    }
}

/** 判断对局阶段：开局（32 子未走或恰一方走一步）/ 残局（其余一切）。
 *  count 口径为真实棋子数（排除 lift，2026-09-08 Q2 修复；lift 格在 colorDeviates 中按
 *  「棋子仍在原格」处理，不再被 pieceColor("lift")→BLACK 误参与红黑偏差统计）。 */
fun detectPhase(board: Board, mySide: Side): Phase {
    if (pieceCount(board) != 32) return Phase.ENDGAME

    val redDev = colorDeviates(board, mySide, Side.RED)
    val blackDev = colorDeviates(board, mySide, Side.BLACK)
    if (!redDev && !blackDev) return Phase.OPENING
    if (redDev != blackDev) {
        val moved = if (redDev) Side.RED else Side.BLACK
        if (singlePieceMoved(board, mySide, moved)) return Phase.OPENING
    }
    return Phase.ENDGAME
}

/** 推断轮次：仅开局可判（全默认位红先；对方刚走一步则轮到我方），其余返回 null。 */
fun inferTurn(board: Board, mySide: Side, phase: Phase): Side? {
    if (phase != Phase.OPENING) return null
    val redDev = colorDeviates(board, mySide, Side.RED)
    val blackDev = colorDeviates(board, mySide, Side.BLACK)
    if (!redDev && !blackDev) return Side.RED
    if (redDev != blackDev) {
        val moved = if (redDev) Side.RED else Side.BLACK
        if (singlePieceMoved(board, mySide, moved)) return moved.opponent
    }
    return null
}

/** 32 子全部位于开局默认格（完整新开局，2026-08-28 审计 §二.E 的 32 子分支校验）。 */
fun plausibleNewGame(board: Board, mySide: Side): Boolean =
    board.contentDeepEquals(fullStartBoard(mySide))

/**
 * 31 子是否全部位于开局默认格（即「标准开局缺 1 子」）。
 * 用于在 SettleWaiter（统一 StartLoop 摆棋判定内核）区分两种 31 子局面：
 * - 全在初始位置 → 提子过渡态（对方刚提子，棋盘暂时少 1 子），应继续等待 32 子；
 * - 有子已离初始位置 → 残局（已开下），应走稳定计数后返回、轮到我方走，不应无限等待。
 * lift 格按「棋子仍在原格（提起中）」处理（2026-09-08 Q2 修复：此前 lift 非空且 ≠ 初始位
 * 棋子，把提子过渡态自己击穿成「有子离初始位置」→ 残局分支误判）。
 */
fun allOnInitialSquares(board: Board, mySide: Side): Boolean {
    val start = fullStartBoard(mySide)
    for (r in 0 until ROWS) {
        for (c in 0 until COLS) {
            val p = board[r][c]
            if (p != null && p != Const.LIFT && p != start[r][c]) return false
        }
    }
    return true
}

/**
 * 32 子「开局形态」判定（2026-09-08 D2=A 严格版，SettleWaiter 32 子分支布局校验用）：
 * 全部位于初始位，或仅红方恰走一子（黑方全在初始位）——满足即视为开局形态，
 * SettleWaiter 立即就绪（黑方走棋阶段由 decideStartTurn/inferTurn 判定轮次）；
 * 其余（偏差超 1 子，如重摆动画瞬态/上局残留）走 3 帧稳定计数。
 */
fun isEarlyOpeningForm(board: Board, mySide: Side): Boolean {
    if (pieceCount(board) != 32) return false
    val redDev = colorDeviates(board, mySide, Side.RED)
    val blackDev = colorDeviates(board, mySide, Side.BLACK)
    if (!redDev && !blackDev) return true
    return redDev && !blackDev && singlePieceMoved(board, mySide, Side.RED)
}

// ---------- 内部 ----------

private fun expectedStartSquares(mySide: Side, color: Side): Set<Pair<Int, Int>> {
    val redSquares = mutableSetOf<Pair<Int, Int>>()
    val blackSquares = mutableSetOf<Pair<Int, Int>>()
    for ((id, squares) in START_SQUARES) {
        (if (pieceColor(id) == Side.RED) redSquares else blackSquares).addAll(squares)
    }
    val red = if (mySide == Side.BLACK) blackSquares else redSquares
    val black = if (mySide == Side.BLACK) redSquares else blackSquares
    return if (color == Side.RED) red else black
}

private fun colorDeviates(board: Board, mySide: Side, color: Side): Boolean {
    val expected = expectedStartSquares(mySide, color)
    for (r in 0 until ROWS) {
        for (c in 0 until COLS) {
            val p = board[r][c]
            if (p == null || p == Const.LIFT) continue // lift = 棋子仍在原格（提起中）
            if (pieceColor(p) == color && (r to c) !in expected) return true
        }
    }
    for (sq in expected) {
        val p = board[sq.first][sq.second]
        if (p == Const.LIFT) continue // 提起中的棋子视为仍在初始格
        if (p == null || pieceColor(p) != color) return true
    }
    return false
}

/** 该色棋子是否恰好只有 1 枚离开初始位（走了一步）。公开供 SettleWaiter 32 子布局校验使用。 */
fun singlePieceMoved(board: Board, mySide: Side, color: Side): Boolean {
    val expected = expectedStartSquares(mySide, color)
    var missing = 0
    var extra = 0
    for (r in 0 until ROWS) {
        for (c in 0 until COLS) {
            val p = board[r][c]
            if (p != null && p != Const.LIFT && pieceColor(p) == color) {
                if ((r to c) !in expected) extra++
            } else if ((r to c) in expected && p != Const.LIFT) {
                missing++
            }
        }
    }
    return missing == 1 && extra == 1
}
