package com.chess.bot.game

/** 走法推断、应用与格式化（纯函数，移植 python moves.py）。 */

/** 从 2 格变动推断一步棋：一起一落、棋子相同；不吻合返回 null。 */
fun inferMove(changes: List<Change>): Move? {
    if (changes.size != 2) return null
    var left: Triple<Int, Int, String>? = null
    var arrived: Quadr? = null
    for ((r, c, old, new) in changes) {
        if (old != null && new == null) left = Triple(r, c, old)
        else if (new != null) arrived = Quadr(r, c, old, new)
    }
    val l = left ?: return null
    val a = arrived ?: return null
    if (a.new != l.third) return null
    return Move((l.first to l.second), (a.r to a.c), l.third, a.old)
}

private data class Quadr(val r: Int, val c: Int, val old: String?, val new: String)

/** 把走法写入 board（起子清空、落子写子），返回新的 halfmoveClock：吃子归零，非吃 +1。 */
fun applyMove(board: Board, move: Move, clock: Int): Int {
    val (r1, c1) = move.src
    val (r2, c2) = move.dst
    board[r1][c1] = null
    board[r2][c2] = move.piece
    return if (move.captured != null) 0 else clock + 1
}

/** 走法是否与引擎着法完全吻合（起点/终点/棋子）。 */
fun moveMatches(move: Move, expected: Move): Boolean =
    move.src == expected.src && move.dst == expected.dst && move.piece == expected.piece

/** 格式化走棋日志：红方走帥：h2 -> e2（吃砲）。 */
fun formatMove(move: Move, mySide: Side): String {
    val colorCn = if (pieceColor(move.piece) == Side.RED) "红" else "黑"
    val from = gridToSquare(move.src.first, move.src.second, mySide)
    val to = gridToSquare(move.dst.first, move.dst.second, mySide)
    val captureNote = move.captured?.let { "（吃${pieceLabel(it)}）" } ?: ""
    return "${colorCn}方走${PIECE_CN[move.piece]}：$from -> $to$captureNote"
}

/** 格式化逐格变动日志（含标题行）。 */
fun formatChanges(changes: List<Change>, mySide: Side): List<String> {
    val lines = mutableListOf("棋子变动（${changes.size} 格）")
    for ((r, c, old, new) in changes) {
        val sq = gridToSquare(r, c, mySide)
        lines.add("变化：$sq ${old?.let { pieceLabel(it) } ?: "空"} -> ${new?.let { pieceLabel(it) } ?: "空"}")
    }
    return lines
}
