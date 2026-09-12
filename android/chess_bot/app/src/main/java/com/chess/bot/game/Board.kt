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

/** 棋子总数（lift 提子瞬时态不计入，对齐空格）。 */
fun pieceCount(board: Board): Int =
    board.sumOf { row -> row.count { it != null && it != Const.LIFT } }

/** lift 提子格坐标列表（摆棋等待期 lift 感知分流用，2026-09-08）。 */
fun liftCells(board: Board): List<Pair<Int, Int>> =
    buildList {
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                if (board[r][c] == Const.LIFT) add(r to c)
            }
        }
    }

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

/**
 * 棋盘 180° 旋转（行翻转 + 列翻转）。
 *
 * 我方执黑时屏幕棋盘相对 ICCS 标准方向（黑上红下、a 列在左）恰为 180° 旋转；
 * 开局库 vkey 计算前必须归一化到标准方向（返回的 ICCS 着法经 squareToGrid(iccs, mySide)
 * 再转回屏幕网格，两条链路对称）。
 */
fun rotateBoard180(board: Board): Board =
    Array(ROWS) { r -> Array(COLS) { c -> board[ROWS - 1 - r][COLS - 1 - c] } }

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

/** 棋子 ID -> 中文名（如 b_r -> 黑車）；lift 语义格返回「提起」（否则 PIECE_CN 查不到拼出「黑null」）。 */
fun pieceLabel(pieceId: String): String =
    when (pieceId) {
        Const.LIFT -> "提起"
        else -> (if (pieceColor(pieceId) == Side.RED) "红" else "黑") + PIECE_CN[pieceId]
    }

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

/**
 * FEN -> 屏幕网格局面 + 行棋方（与 [fenOfBoard] **严格互逆**；解析失败返回 null）。
 *
 * 仅支持本项目自产格式 `<rank9>/…/<rank0> <w|b> …`（恒黑上红下）。解析出的 rank/file 数组
 * 按 mySide 反向翻转：执黑时屏幕棋盘相对 ICCS 标准方向恰为 180°（与 fenOfBoard 生成侧同一套
 * 规则，两侧对称 → 可 round-trip 单测）。`w`=红方行棋 / `b`=黑方行棋。
 */
fun boardFromFen(fen: String, mySide: Side = Side.RED): Pair<Board, Side>? {
    val parts = fen.trim().split(Regex("\\s+"))
    if (parts.size < 2) return null
    val rows = parts[0].split("/")
    if (rows.size != ROWS) return null
    val byChar = PIECE_FEN.entries.associate { (id, ch) -> ch to id }
    val iccs = Array(ROWS) { arrayOfNulls<String>(COLS) }
    for ((i, line) in rows.withIndex()) {
        var c = 0
        for (ch in line) {
            if (ch.isDigit()) {
                c += ch - '0'
            } else {
                if (c >= COLS) return null
                iccs[i][c] = byChar[ch] ?: return null
                c++
            }
        }
        if (c != COLS) return null
    }
    val toMove = when (parts[1]) {
        "w" -> Side.RED
        "b" -> Side.BLACK
        else -> return null
    }
    val board = Array(ROWS) { r ->
        Array(COLS) { c ->
            if (mySide == Side.BLACK) iccs[ROWS - 1 - r][COLS - 1 - c] else iccs[r][c]
        }
    }
    return board to toMove
}

/**
 * 引擎 position 自检（2026-09-12 用户批复 D2：自检 + 报错中止；纯函数，JVM 单测可覆盖）。
 *
 * 校验「基线 FEN + 着法列表」能否**严格交替**地演进到与已提交棋盘（[expectedBoard]/[expectedTurn]）
 * 完全一致的局面：每一手必须由**轮到的一方**持子、且为伪合法着法。任一步不满足 → 返回问题描述
 * （调用方打 ERROR 并中止本步，绝不把错局面发给引擎）；全部通过返回 null。
 *
 * 为什么必须有这一道（2026-09-12 真机事故）：我方 a7c5 因落点格被误读而确认迟到 9.5s，期间
 * 「吞点击恢复」先把敌着提交了 → movesList 变成「敌·敌·我·敌」（同色连走）。UCI 引擎解析
 * `position fen … moves …` 时**遇到第一个非法着法即停止**，后续着法全部丢弃 → 引擎在过期局面
 * 上算棋（该例返回已走过的 a7c5）；若该着法在 board 上恰好合法，`unpackMove` 守卫也拦不住。
 */
fun auditEnginePosition(
    baselineFen: String,
    mySide: Side,
    moves: List<String>,
    expectedBoard: Board,
    expectedTurn: Side,
): String? {
    val parsed = boardFromFen(baselineFen, mySide) ?: return "基线 FEN 解析失败（$baselineFen）"
    val board = parsed.first
    var sideToMove = parsed.second
    moves.forEachIndexed { i, iccs ->
        val no = i + 1
        if (iccs.length != 4) return "第 $no 手格式非法（$iccs）"
        val src = squareToGrid(iccs.substring(0, 2), mySide)
        val dst = squareToGrid(iccs.substring(2, 4), mySide)
        if (src.first !in 0 until ROWS || src.second !in 0 until COLS ||
            dst.first !in 0 until ROWS || dst.second !in 0 until COLS
        ) {
            return "第 $no 手 $iccs 坐标越界"
        }
        val piece = board[src.first][src.second]
            ?: return "第 $no 手 $iccs 起点为空（按交替应轮到 ${sideToMove.cn}方走）"
        if (pieceColor(piece) != sideToMove) {
            return "第 $no 手 $iccs 持子为 ${pieceColor(piece).cn}方，但按交替应轮到 " +
                    "${sideToMove.cn}方（着法列表顺序错乱）"
        }
        val move = Move(src, dst, piece, board[dst.first][dst.second])
        if (!isPseudoLegal(board, move, mySide)) {
            return "第 $no 手 $iccs（${pieceLabel(piece)}）非伪合法着法"
        }
        applyMove(board, move, 0)
        sideToMove = sideToMove.opponent
    }
    if (sideToMove != expectedTurn) {
        return "着法列表演进后轮到 ${sideToMove.cn}方，与已提交棋盘（${expectedTurn.cn}方）不符" +
                "（着法列表缺手/多手）"
    }
    for (r in 0 until ROWS) {
        for (c in 0 until COLS) {
            val evolved = board[r][c]
            val committed = expectedBoard[r][c]
            if (evolved != committed && !(evolved == null && committed == Const.LIFT)) {
                return "演进局面与已提交棋盘不一致：${gridToSquare(r, c, mySide)} " +
                        "${cellText(committed)} != ${cellText(evolved)}"
            }
        }
    }
    return null
}

private fun cellText(piece: String?): String =
    when (piece) {
        null, Const.LIFT -> "空"
        else -> pieceLabel(piece)
    }

/**
 * 布局日志格式化（2026-09-12 用户批示：打印 UCI 的 file 竖列与 rank 横排）。
 * - board 网格**原样打印**（行序恒 r0→r9，不做红黑翻转）：网格口径我方恒在 r5..9，
 *   故最后一行恒为我方后排（帥/將行），与棋盘小窗显示方向一致。
 *   （原实现红方分支 r9→r0 把我方后排打到文本块顶部，属方向 bug，已删。）
 * - 行/列表头跟随我方视角（与 [gridToSquare] 同一映射）：
 *   执红：列头 a b c … i、行号 9 8 … 0（自上而下）；执黑：列头 i h … a、行号 0 1 … 9。
 * - 首行与末行均为列头（真机棋盘上下边界的坐标标识）。
 * 空格用全宽中点「・」(U+30FB) 与汉字等宽（2026-09-08 Q2：窄字符「·」导致列不对齐）。
 * lift 提子瞬时态显示为「提」。纯函数（无 OpenCV 依赖），JVM 单测可直接覆盖。
 */
fun formatLayoutLines(board: Board, mySide: Side = Side.RED): List<String> {
    val lines = mutableListOf<String>()
    val files = (0 until COLS).map { c -> gridToSquare(0, c, mySide)[0] }
    val header = "  " + files.joinToString(" ")
    lines.add(header)
    for (r in 0 until ROWS) {
        val cells = (0 until COLS).joinToString(" ") { c ->
            when (val v = board[r][c]) {
                null -> "・"
                Const.LIFT -> "提"
                else -> PIECE_CN[v] ?: v
            }
        }
        lines.add("${gridToSquare(r, 0, mySide).substring(1)} $cells")
    }
    lines.add(header)
    return lines
}
