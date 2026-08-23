package com.chess.bot.game

/**
 * 棋盘状态与坐标转换（移植 python board.py）。
 *
 * 网格 (r, c)：固定于屏幕，(0,0) 恒为左上角格子。
 * 记谱 a-i/0-9 为 ICCS 绝对坐标系，与 pikafish UCI 方块一致：
 * e9 恒为黑将、e0 恒为红帥。网格<->记谱换算与红黑方相关；记谱/FEN 不随红黑变化。
 */

const val ROWS = 10
const val COLS = 9

/** 棋盘布局：10x9，元素为棋子 ID（如 "b_r"/"r_K"）或 null */
typealias Board = Array<Array<String?>>

fun makeEmptyBoard(): Board = Array(ROWS) { Array<String?>(COLS) { null } }

fun copyBoard(board: Board): Board = Array(ROWS) { r -> board[r].copyOf() }

// 棋子 ID -> FEN 字符（黑小写/红大写）
val PIECE_FEN: Map<String, Char> = mapOf(
    "b_r" to 'r', "b_n" to 'n', "b_b" to 'b', "b_a" to 'a',
    "b_k" to 'k', "b_c" to 'c', "b_p" to 'p',
    "r_R" to 'R', "r_N" to 'N', "r_B" to 'B', "r_A" to 'A',
    "r_K" to 'K', "r_C" to 'C', "r_P" to 'P',
)

// 棋子 ID -> 中文显示字
val PIECE_CN: Map<String, String> = mapOf(
    "b_r" to "車", "b_n" to "馬", "b_b" to "象", "b_a" to "士",
    "b_k" to "將", "b_c" to "砲", "b_p" to "卒",
    "r_R" to "俥", "r_N" to "傌", "r_B" to "相", "r_A" to "仕",
    "r_K" to "帥", "r_C" to "炮", "r_P" to "兵",
)

// 各棋子开局时的默认网格位置（红色在下、黑色在上），用于轮次推断
val START_SQUARES: Map<String, List<Pair<Int, Int>>> = mapOf(
    "b_r" to listOf(0 to 0, 0 to 8),
    "b_n" to listOf(0 to 1, 0 to 7),
    "b_b" to listOf(0 to 2, 0 to 6),
    "b_a" to listOf(0 to 3, 0 to 5),
    "b_k" to listOf(0 to 4),
    "b_c" to listOf(2 to 1, 2 to 7),
    "b_p" to listOf(3 to 0, 3 to 2, 3 to 4, 3 to 6, 3 to 8),
    "r_R" to listOf(9 to 0, 9 to 8),
    "r_N" to listOf(9 to 1, 9 to 7),
    "r_B" to listOf(9 to 2, 9 to 6),
    "r_A" to listOf(9 to 3, 9 to 5),
    "r_K" to listOf(9 to 4),
    "r_C" to listOf(7 to 1, 7 to 7),
    "r_P" to listOf(6 to 0, 6 to 2, 6 to 4, 6 to 6, 6 to 8),
)

/** 完整开局布局（side 决定红方在屏幕下方还是上方）。 */
fun fullStartBoard(side: Side = Side.RED): Board {
    val b = makeEmptyBoard()
    for ((id, squares) in START_SQUARES) {
        for ((r, c) in squares) {
            val tr = if (side == Side.BLACK) 9 - r else r
            b[tr][c] = id
        }
    }
    return b
}

/** 矫正空间格心：格边长 100，中心 (50+100c, 50+100r)。 */
fun correctedCenter(r: Int, c: Int): Pair<Double, Double> =
    Const.CORRECT_CELL * (c + 0.5) to Const.CORRECT_CELL * (r + 0.5)

/** 网格 -> 记谱（红方视角 file=a+c、rank=9-r；黑方视角 file 反向、rank=r）。 */
fun gridToSquare(r: Int, c: Int, mySide: Side = Side.RED): String =
    if (mySide == Side.BLACK) "${'i' - c}$r" else "${'a' + c}${9 - r}"

/** 记谱 -> 网格。 */
fun squareToGrid(square: String, mySide: Side = Side.RED): Pair<Int, Int> {
    val file = square[0]
    val rank = square.substring(1).toInt()
    return if (mySide == Side.BLACK) {
        rank to ('i' - file)
    } else {
        (9 - rank) to (file - 'a')
    }
}

/** 棋子 ID -> 颜色。 */
fun pieceColor(pieceId: String): Side =
    if (pieceId.startsWith("r_")) Side.RED else Side.BLACK

/** 棋子 ID -> 中文名（如 b_r -> 黑車）。 */
fun pieceLabel(pieceId: String): String =
    (if (pieceColor(pieceId) == Side.RED) "红" else "黑") + PIECE_CN[pieceId]

/** 棋盘布局 -> FEN 字符串（ICCS 绝对坐标系，黑方在上；按我方红黑翻转行列）。 */
fun fenOfBoard(
    board: Board,
    side: Side,
    toMove: Side? = null,
    halfmoveClock: Int = 0,
): String {
    val sideChar = if ((toMove ?: side) == Side.RED) "w" else "b"
    val rowRange = if (side == Side.BLACK) ROWS - 1 downTo 0 else 0 until ROWS
    val lines = mutableListOf<String>()
    for (r in rowRange) {
        val parts = mutableListOf<String>()
        var empty = 0
        val colRange = if (side == Side.BLACK) COLS - 1 downTo 0 else 0 until COLS
        for (c in colRange) {
            val piece = board[r][c]
            if (piece == null) {
                empty++
            } else {
                if (empty > 0) {
                    parts.add(empty.toString())
                    empty = 0
                }
                parts.add(PIECE_FEN[piece].toString())
            }
        }
        if (empty > 0) parts.add(empty.toString())
        lines.add(parts.joinToString(""))
    }
    return "${lines.joinToString("/")} $sideChar - - $halfmoveClock 1"
}
