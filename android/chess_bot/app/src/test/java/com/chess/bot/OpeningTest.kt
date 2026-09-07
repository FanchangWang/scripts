package com.chess.bot

import com.chess.bot.game.Const
import com.chess.bot.game.Side
import com.chess.bot.game.allOnInitialSquares
import com.chess.bot.game.detectPhase
import com.chess.bot.game.detectSide
import com.chess.bot.game.inferTurn
import com.chess.bot.game.isEarlyOpeningForm
import com.chess.bot.game.plausibleNewGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---------- plausibleNewGame（2026-08-28 审计 §二.E 32 子分支校验） ----------

    @Test
    fun `红方视角全默认位判完整新开局`() {
        assertTrue(plausibleNewGame(TB.fullBoard(Side.RED), Side.RED))
    }

    @Test
    fun `黑方视角全默认位判完整新开局`() {
        assertTrue(plausibleNewGame(TB.fullBoard(Side.BLACK), Side.BLACK))
    }

    @Test
    fun `走一步后不再是完整新开局`() {
        val b = TB.fullBoard(Side.RED)
        TB.movePiece(b, 7, 7, 7, 4)
        assertFalse(plausibleNewGame(b, Side.RED))
    }

    @Test
    fun `视角与棋盘朝向不符判非新开局`() {
        // 棋盘为红下黑上，却按黑方视角（黑下红上）比对
        assertFalse(plausibleNewGame(TB.fullBoard(Side.RED), Side.BLACK))
    }

    // ---------- 2026-09-08 lift 感知（game_start_lift_recovery_plan） ----------

    @Test
    fun `allOnInitialSquares_lift格按棋子仍在原格处理_Q2修复`() {
        // 修复前：lift 非空且 != 初始位棋子 → 提子过渡态被自己击穿成「有子离位」
        val b = TB.fullBoard(Side.RED)
        b[6][4] = Const.LIFT // 红兵提起，原格在初始位
        assertTrue(allOnInitialSquares(b, Side.RED))
    }

    @Test
    fun `allOnInitialSquares_真实离位仍判false`() {
        val b = TB.fullBoard(Side.RED)
        TB.movePiece(b, 6, 4, 5, 4) // 红兵真实前进一步
        assertFalse(allOnInitialSquares(b, Side.RED))
    }

    @Test
    fun `isEarlyOpeningForm_全默认位为真`() {
        assertTrue(isEarlyOpeningForm(TB.fullBoard(Side.RED), Side.RED))
        assertTrue(isEarlyOpeningForm(TB.fullBoard(Side.BLACK), Side.BLACK))
    }

    @Test
    fun `isEarlyOpeningForm_仅红方走一子为真_D2严格版`() {
        val b = TB.fullBoard(Side.BLACK)
        TB.movePiece(b, 2, 7, 2, 4) // 红炮走一步
        assertTrue(isEarlyOpeningForm(b, Side.BLACK))
    }

    @Test
    fun `isEarlyOpeningForm_仅黑方走一子为假_D2严格版`() {
        val b = TB.fullBoard(Side.RED)
        TB.movePiece(b, 0, 1, 3, 1) // 黑馬走一步
        assertFalse(isEarlyOpeningForm(b, Side.RED))
    }

    @Test
    fun `isEarlyOpeningForm_双方各走一子为假`() {
        val b = TB.fullBoard(Side.RED)
        TB.movePiece(b, 0, 0, 4, 0)
        TB.movePiece(b, 9, 0, 5, 0)
        assertFalse(isEarlyOpeningForm(b, Side.RED))
    }

    @Test
    fun `isEarlyOpeningForm_子数不足32为假`() {
        val b = TB.fullBoard(Side.RED)
        b[3][4] = null // 黑卒被吃
        assertFalse(isEarlyOpeningForm(b, Side.RED))
    }
}
