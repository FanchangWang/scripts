package com.chess.bot.game

import com.chess.bot.vision.Recognizer
import org.opencv.core.Mat

/**
 * 棋盘识别（移植 python recognition.py）：逐格调 analyzeCellWithPriority，
 * 一次遍历完成识别 + 与 prevBoard 的对比。返回 (新布局, 变动列表)。
 */
fun recognizeBoard(
    corrected: Mat,
    templates: Map<String, Mat>,
    prevBoard: Board?,
): Pair<Board, List<Change>> {
    val board = makeEmptyBoard()
    val changes = mutableListOf<Change>()
    for (r in 0 until ROWS) {
        for (c in 0 until COLS) {
            val old = prevBoard?.get(r)?.get(c)
            val new = Recognizer.analyzeCellWithPriority(corrected, r, c, templates, old)
            board[r][c] = new
            if (old != new) changes.add(Change(r, c, old, new))
        }
    }
    return board to changes
}
