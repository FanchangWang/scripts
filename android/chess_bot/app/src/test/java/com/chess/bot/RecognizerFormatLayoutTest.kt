package com.chess.bot

import com.chess.bot.game.Const
import com.chess.bot.game.Side
import com.chess.bot.game.formatLayoutLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val TB = TestBoards

/** formatLayout 打印视角：无论执红执黑，文本块底部（最后一行）应为我方棋子（2026-09-08 Q1）。 */
class RecognizerFormatLayoutTest {

    @Test
    fun `执红时底部为红方后排`() {
        // fullBoard(RED)：红方后排（俥傌相仕帥…）在原始网格 r9
        val lines = formatLayoutLines(TB.fullBoard(Side.RED), Side.RED)
        assertEquals(10, lines.size)
        assertEquals("r9 俥 傌 相 仕 帥 仕 相 傌 俥", lines.first())
        assertEquals("r0 車 馬 象 士 將 士 象 馬 車", lines.last())
    }

    @Test
    fun `执黑时行序翻转为底部为黑方后排`() {
        // fullBoard(BLACK)：黑方后排（車馬象士將…）在原始网格 r9（我方恒在屏幕下半区）
        val lines = formatLayoutLines(TB.fullBoard(Side.BLACK), Side.BLACK)
        assertEquals(10, lines.size)
        assertEquals("r0 俥 傌 相 仕 帥 仕 相 傌 俥", lines.first())
        assertEquals("r9 車 馬 象 士 將 士 象 馬 車", lines.last())
    }

    @Test
    fun `行号标签保留原始网格坐标`() {
        // 翻转只改打印顺序，r 编号不变——与 gridToSquare 等其他日志可对照
        val board = TB.fullBoard(Side.BLACK)
        board[4][4] = Const.LIFT
        val lines = formatLayoutLines(board, Side.BLACK)
        assertTrue(lines.last().startsWith("r9 "))
        assertTrue(lines[4].startsWith("r4 ") && lines[4].contains("提"))
    }
}
