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

/** 判断对局阶段：开局（32 子未走或恰一方走一步）/ 残局（其余一切）。 */
fun detectPhase(board: Board, mySide: Side): Phase {
    val count = board.sumOf { row -> row.count { it != null } }
    if (count != 32) return Phase.ENDGAME

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
 * 用于在 waitForBoardSettled 区分两种 31 子局面：
 * - 全在初始位置 → 提子过渡态（对方刚提子，棋盘暂时少 1 子），应继续等待 32 子；
 * - 有子已离初始位置 → 残局（已开下），应走稳定计数后返回、轮到我方走，不应无限等待。
 */
fun allOnInitialSquares(board: Board, mySide: Side): Boolean {
    val start = fullStartBoard(mySide)
    for (r in 0 until ROWS) {
        for (c in 0 until COLS) {
            val p = board[r][c]
            if (p != null && p != start[r][c]) return false
        }
    }
    return true
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
            if (p != null && pieceColor(p) == color && (r to c) !in expected) return true
        }
    }
    for (sq in expected) {
        val p = board[sq.first][sq.second]
        if (p == null || pieceColor(p) != color) return true
    }
    return false
}

private fun singlePieceMoved(board: Board, mySide: Side, color: Side): Boolean {
    val expected = expectedStartSquares(mySide, color)
    var missing = 0
    var extra = 0
    for (r in 0 until ROWS) {
        for (c in 0 until COLS) {
            val p = board[r][c]
            if (p != null && pieceColor(p) == color) {
                if ((r to c) !in expected) extra++
            } else if ((r to c) in expected) {
                missing++
            }
        }
    }
    return missing == 1 && extra == 1
}
