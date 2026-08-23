package com.chess.bot

import com.chess.bot.game.Side
import com.chess.bot.game.detectPhase
import com.chess.bot.game.detectSide
import com.chess.bot.game.inferTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.chess.bot.TestBoards as TB

/** 开局分析测试（翻译 python test_fresh 5 场景）。 */
class OpeningTest {

    @Test
    fun `场景1 对方走一步应自动开局且轮到我方黑`() {
        val b = TB.fullBoard(Side.BLACK)
        TB.movePiece(b, 2, 7, 2, 4)
        assertEquals(Side.BLACK, detectSide(b))
        val phase = detectPhase(b, Side.BLACK)
        assertEquals(com.chess.bot.game.Phase.OPENING, phase)
        assertEquals(Side.BLACK, inferTurn(b, Side.BLACK, phase))
    }

    @Test
    fun `场景2 全默认位红方视角自动开局红先`() {
        val b = TB.fullBoard(Side.RED)
        assertEquals(Side.RED, detectSide(b))
        val phase = detectPhase(b, Side.RED)
        assertEquals(com.chess.bot.game.Phase.OPENING, phase)
        assertEquals(Side.RED, inferTurn(b, Side.RED, phase))
    }

    @Test
    fun `场景3 双方各走一步判残局且无法推断轮次`() {
        val b = TB.fullBoard(Side.BLACK)
        TB.movePiece(b, 2, 7, 2, 4)
        TB.movePiece(b, 7, 7, 7, 4)
        assertEquals(com.chess.bot.game.Phase.ENDGAME, detectPhase(b, Side.BLACK))
        assertNull(inferTurn(b, Side.BLACK, com.chess.bot.game.Phase.ENDGAME))
    }

    @Test
    fun `场景4 残局棋子过少判残局`() {
        val b = TB.empty().also {
            it[0][4] = "b_k"; it[9][4] = "r_K"; it[0][0] = "b_r"; it[9][0] = "r_R"
        }
        assertEquals(com.chess.bot.game.Phase.ENDGAME, detectPhase(b, Side.BLACK))
        assertNull(inferTurn(b, Side.BLACK, com.chess.bot.game.Phase.ENDGAME))
    }

    @Test
    fun `场景5 对方多步偏离不满足刚开局`() {
        val b = TB.fullBoard(Side.BLACK)
        TB.movePiece(b, 2, 7, 2, 4)
        TB.movePiece(b, 0, 7, 2, 6)
        assertNotEquals(com.chess.bot.game.Phase.OPENING, detectPhase(b, Side.BLACK))
    }
}
