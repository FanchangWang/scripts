package com.chess.bot

import com.chess.bot.game.Side
import com.chess.bot.game.decideDraw
import com.chess.bot.game.fenOfBoard
import com.chess.bot.game.fullStartBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.chess.bot.TestBoards as TB

/** FEN 生成 + 和棋决策测试。 */
class FenAndDrawTest {

    /** python AGENTS 已实测的初始局面 FEN（ICCS 绝对坐标系）。 */
    private val initialFen =
        "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"

    @Test
    fun `红方视角初始 FEN`() {
        assertEquals(initialFen, fenOfBoard(TB.fullBoard(Side.RED), Side.RED, toMove = Side.RED))
    }

    @Test
    fun `黑方视角翻转后棋子布局与红方一致（ICCS 绝对坐标系）`() {
        assertEquals(
            initialFen,
            fenOfBoard(fullStartBoard(Side.BLACK), Side.BLACK, toMove = Side.RED),
        )
    }

    @Test
    fun `to_move 黑方时 w 变 b`() {
        val fen = fenOfBoard(TB.fullBoard(Side.RED), Side.RED, toMove = Side.BLACK)
        assertTrue(fen.endsWith(" b - - 0 1"))
    }

    @Test
    fun `halfmove clock 写入第六字段`() {
        val fen = fenOfBoard(TB.fullBoard(Side.RED), Side.RED, halfmoveClock = 17)
        assertTrue(fen.endsWith(" 17 1"))
    }

    @Test
    fun `和棋决策 超阈值拒绝 否则同意`() {
        assertTrue(decideDraw(1001, 1000))
        assertFalse(decideDraw(1000, 1000))
        assertFalse(decideDraw(-500, 1000))
    }
}
