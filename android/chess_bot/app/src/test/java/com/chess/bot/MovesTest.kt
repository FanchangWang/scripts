package com.chess.bot

import com.chess.bot.game.Change
import com.chess.bot.game.Move
import com.chess.bot.game.Side
import com.chess.bot.game.applyMove
import com.chess.bot.game.inferMove
import com.chess.bot.game.moveMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.chess.bot.TestBoards as TB

/** 走法推断/应用测试。 */
class MovesTest {

    @Test
    fun `infer 干净走法`() {
        val changes = listOf(
            Change(7, 3, "r_R", null),
            Change(0, 3, null, "r_R"),
        )
        val move = inferMove(changes)
        assertEquals(Move(7 to 3, 0 to 3, "r_R", null), move)
    }

    @Test
    fun `infer 吃子带 captured`() {
        val changes = listOf(
            Change(7, 3, "r_R", null),
            Change(0, 3, "b_r", "r_R"),
        )
        val move = inferMove(changes)
        assertEquals("b_r", move?.captured)
    }

    @Test
    fun `infer 两格同起或同落返回 null`() {
        assertNull(inferMove(listOf(Change(5, 5, "b_r", null), Change(6, 6, "r_P", null))))
        assertNull(inferMove(listOf(Change(5, 5, null, "b_r"), Change(6, 6, null, "r_P"))))
        assertNull(inferMove(listOf(Change(5, 5, "b_r", "r_P"))))
        assertTrue(inferMove(emptyList()) == null)
    }

    @Test
    fun `matches 全等判断`() {
        val expected = Move(7 to 3, 0 to 3, "r_R")
        assertTrue(moveMatches(Move(7 to 3, 0 to 3, "r_R", null), expected))
        assertFalse(moveMatches(Move(7 to 3, 0 to 4, "r_R", null), expected))
        assertFalse(moveMatches(Move(7 to 2, 0 to 3, "r_R", null), expected))
        assertFalse(moveMatches(Move(7 to 3, 0 to 3, "r_P", null), expected))
    }

    @Test
    fun `apply 非吃加一 吃归零`() {
        var clock = 5
        clock = applyMove(
            TB.empty(),
            Move(7 to 3, 0 to 3, "r_R", null),
            clock,
        )
        assertEquals(6, clock)
        clock = applyMove(TB.empty(), Move(7 to 3, 0 to 3, "r_R", "b_r"), clock)
        assertEquals(0, clock)
    }

    @Test
    fun `apply 写盘正确`() {
        val b = TB.empty()
        b[7][3] = "r_R"
        applyMove(b, Move(7 to 3, 0 to 3, "r_R", null), 0)
        assertNull(b[7][3])
        assertEquals("r_R", b[0][3])
        assertEquals(Side.RED, Side.RED.opponent.opponent)
    }
}
