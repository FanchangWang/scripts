package com.chess.bot

import com.chess.bot.game.Board
import com.chess.bot.game.START_SQUARES
import com.chess.bot.game.Side

/** 测试共用棋盘构造（对齐 python conftest）。 */
object TestBoards {

    /** 完整开局布局（side 决定红方在屏幕下方还是上方）。 */
    fun fullBoard(side: Side): Board {
        val b: Board = Array(10) { Array<String?>(9) { null } }
        for ((id, squares) in START_SQUARES) {
            for ((r, c) in squares) {
                val tr = if (side == Side.BLACK) 9 - r else r
                b[tr][c] = id
            }
        }
        return b
    }

    fun copy(b: Board): Board = Array(10) { r -> b[r].copyOf() }

    fun movePiece(b: Board, r1: Int, c1: Int, r2: Int, c2: Int) {
        b[r2][c2] = b[r1][c1]
        b[r1][c1] = null
    }

    fun empty(): Board = Array(10) { Array<String?>(9) { null } }
}
