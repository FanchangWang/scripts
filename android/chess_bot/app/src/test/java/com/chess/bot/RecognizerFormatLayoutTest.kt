package com.chess.bot

import com.chess.bot.game.Const
import com.chess.bot.game.Side
import com.chess.bot.game.formatLayoutLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val TB = TestBoards

/**
 * formatLayout 打印格式（2026-09-12 用户批示）：board 网格原样打印（行序恒 r0→r9），
 * 行/列表头按我方视角输出 UCI 坐标（红 a..i / 9..0，黑 i..a / 0..9），首末行均为列头。
 * 网格口径我方恒在 r5..9（r9=我方后排）→ 最后一行数据恒为我方后排。
 */
class RecognizerFormatLayoutTest {

    @Test
    fun `执红列头为 a-i 行号为 9-0`() {
        val lines = formatLayoutLines(TB.fullBoard(Side.RED), Side.RED)
        assertEquals(12, lines.size) // 列头 + 10 行 + 列头
        assertEquals("  a b c d e f g h i", lines.first())
        // 首行数据 = 网格 r0 = rank 9 = 敌方后排
        assertEquals("9 車 馬 象 士 將 士 象 馬 車", lines[1])
        // 末行数据 = 网格 r9 = rank 0 = 我方后排
        assertEquals("0 俥 傌 相 仕 帥 仕 相 傌 俥", lines[10])
        assertEquals("  a b c d e f g h i", lines.last())
    }

    @Test
    fun `执黑列头为 i-a 行号为 0-9`() {
        val lines = formatLayoutLines(TB.fullBoard(Side.BLACK), Side.BLACK)
        assertEquals(12, lines.size)
        assertEquals("  i h g f e d c b a", lines.first())
        // 首行数据 = 网格 r0 = rank 0（黑方视角）= 敌方（红方）后排
        assertEquals("0 俥 傌 相 仕 帥 仕 相 傌 俥", lines[1])
        // 末行数据 = 网格 r9 = rank 9 = 我方（黑方）后排
        assertEquals("9 車 馬 象 士 將 士 象 馬 車", lines[10])
        assertEquals("  i h g f e d c b a", lines.last())
    }

    @Test
    fun `行号标签与 gridToSquare 同映射`() {
        // 行序不翻转（r0 永远第一行数据），仅标签随视角变化——与其他日志可对照
        val board = TB.fullBoard(Side.BLACK)
        board[4][4] = Const.LIFT
        val lines = formatLayoutLines(board, Side.BLACK)
        // 网格 r4 → 数据行 index 5（1 头 + r0..r3 四行）
        assertTrue(lines[5].startsWith("4 ") && lines[5].contains("提"))
        assertTrue(lines[10].startsWith("9 "))
    }

    @Test
    fun `默认视角为红`() {
        val a = formatLayoutLines(TB.fullBoard(Side.RED))
        val b = formatLayoutLines(TB.fullBoard(Side.RED), Side.RED)
        assertEquals(a, b)
    }
}
