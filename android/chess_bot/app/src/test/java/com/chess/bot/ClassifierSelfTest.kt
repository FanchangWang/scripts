package com.chess.bot

import com.chess.bot.game.Board
import com.chess.bot.game.Change
import com.chess.bot.game.GameState
import com.chess.bot.game.Move
import com.chess.bot.game.SelfFrameResult
import com.chess.bot.game.Side
import com.chess.bot.game.classifySelfFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.chess.bot.TestBoards as TB

/** 我方走棋帧分类测试（翻译 python test_capture 12 场景中的纯分类部分）。 */
class ClassifierSelfTest {

    // ---------- n==2 ----------

    @Test
    fun `n2 干净走棋 SELF_DONE`() {
        val b = TB.empty().also { it[7][3] = "r_R"; it[0][3] = "b_r" }
        val after = TB.copy(b).also { it[7][3] = null; it[0][3] = "r_R" }
        val changes = listOf(Change(7, 3, "r_R", null), Change(0, 3, "b_r", "r_R"))
        val expected = Move(7 to 3, 0 to 3, "r_R")

        val fc = classifySelfFrame(changes, after, expected, Side.RED)
        assertEquals(SelfFrameResult.SELF_DONE, fc.result)
        assertEquals(Move(7 to 3, 0 to 3, "r_R", "b_r"), fc.selfMove)

        val state = GameState()
        state.replaceBoard(TB.copy(b))
        state.mySide = Side.RED
        state.turn = Side.RED
        state.applySelfMove(fc.selfMove!!)
        assertNull(state.boardAt(7, 3))
        assertEquals("r_R", state.boardAt(0, 3))
        assertEquals(Side.BLACK, state.turn)
        assertEquals(1, 1) // clock 非吃 +1（GameState 内部断言见 apply 用例）
    }

    @Test
    fun `n2 不匹配 expected 则 Noisy`() {
        val b = TB.empty().also { it[7][3] = "r_R"; it[5][5] = "b_r" }
        val after = TB.copy(b).also { it[5][5] = null; it[5][4] = "b_r" }
        val changes = listOf(Change(5, 5, "b_r", null), Change(5, 4, null, "b_r"))
        val fc = classifySelfFrame(changes, after, Move(7 to 3, 0 to 3, "r_R"), Side.RED)
        assertEquals(SelfFrameResult.NOISY, fc.result)
    }

    @Test
    fun `n2 兜底 敌方同终点反吃`() {
        val changes = listOf(
            Change(7, 3, "r_R", null),
            Change(5, 5, "b_c", null),
        )
        val after = TB.empty().also { it[0][3] = "b_c" }
        val expected = Move(7 to 3, 0 to 3, "r_R")
        val fc = classifySelfFrame(changes, after, expected, Side.RED)
        assertEquals(SelfFrameResult.SELF_THEN_ENEMY, fc.result)
        assertNull(fc.selfMove?.captured)
        assertEquals(Move(5 to 5, 0 to 3, "b_c", "r_R"), fc.enemyMove)
    }

    // ---------- n==1 ----------

    @Test
    fun `n1 提起未落 Lifted`() {
        val b = TB.empty().also { it[7][3] = "r_R"; it[9][4] = "r_K" }
        val lifted = TB.copy(b).also { it[7][3] = null }
        val changes = listOf(Change(7, 3, "r_R", null))

        // 新模型不再区分「是否最后一帧」：n==1 且正是我方起点提子即判 Lifted（循环会持续等到落定）
        val fc =
            classifySelfFrame(changes, lifted, Move(7 to 3, 0 to 3, "r_R"), Side.RED)
        assertEquals(SelfFrameResult.LIFTED, fc.result)
    }

    @Test
    fun `n1 非提子 Noisy`() {
        val b = TB.empty().also { it[7][3] = "r_R"; it[5][5] = "b_r" }
        val after = TB.copy(b).also { it[5][5] = null }
        val fc = classifySelfFrame(
            listOf(Change(5, 5, "b_r", null)),
            after,
            Move(7 to 3, 0 to 3, "r_R"),
            Side.RED,
        )
        assertEquals(SelfFrameResult.NOISY, fc.result)
    }

    // ---------- n==0 ----------

    @Test
    fun `n0 Silent`() {
        val fc = classifySelfFrame(
            emptyList(),
            TB.fullBoard(Side.RED),
            Move(7 to 3, 0 to 3, "r_R"),
            Side.RED,
        )
        assertEquals(SelfFrameResult.SILENT, fc.result)
    }

    // ---------- n==3 ----------

    @Test
    fun `n3 情况2 敌方在终点反吃`() {
        val b = TB.empty().also {
            it[8][4] = "r_P"; it[9][3] = "b_k"; it[7][0] = "b_p"; it[2][0] = "r_P"
        }
        val after = TB.copy(b).also { it[8][4] = null; it[9][3] = null; it[9][4] = "b_k" }
        val changes = listOf(
            Change(8, 4, "r_P", null),
            Change(9, 3, "b_k", null),
            Change(9, 4, null, "b_k"),
        )
        val expected = Move(8 to 4, 9 to 4, "r_P")

        val fc = classifySelfFrame(changes, after, expected, Side.RED)
        assertEquals(SelfFrameResult.SELF_THEN_ENEMY, fc.result)

        val state = GameState()
        state.replaceBoard(TB.copy(b))
        state.mySide = Side.RED
        state.turn = Side.RED
        state.applySelfThenEnemy(fc.selfMove!!, fc.enemyMove!!)
        assertFalse(state.gameOver)
        assertEquals(Side.RED, state.turn)
        assertNull(state.boardAt(8, 4))
        assertNull(state.boardAt(9, 3))
        assertEquals("b_k", state.boardAt(9, 4))
        assertEquals("b_k", after[9][4])
    }

    @Test
    fun `n3 情况3 敌方占我原位`() {
        val b = TB.empty().also {
            it[7][3] = "r_R"; it[5][5] = "b_c"; it[9][4] = "r_K"; it[0][4] = "b_k"
        }
        val after = TB.copy(b).also {
            it[7][3] = "b_c"; it[5][5] = null; it[0][3] = "r_R"
        }
        val changes = listOf(
            Change(7, 3, "r_R", "b_c"),
            Change(5, 5, "b_c", null),
            Change(0, 3, null, "r_R"),
        )
        val expected = Move(7 to 3, 0 to 3, "r_R")
        val fc = classifySelfFrame(changes, after, expected, Side.RED)
        assertEquals(SelfFrameResult.SELF_THEN_ENEMY, fc.result)
        assertEquals(Move(5 to 5, 7 to 3, "b_c", null), fc.enemyMove)
    }

    // ---------- n==4 ----------

    @Test
    fun `n4 我方加敌方走棋`() {
        val b = TB.empty().also {
            it[7][3] = "r_R"; it[7][7] = "b_c"; it[9][4] = "r_K"; it[0][4] = "b_k"
        }
        val after = TB.copy(b).also {
            it[7][3] = null; it[0][3] = "r_R"; it[7][7] = null; it[7][4] = "b_c"
        }
        val changes = listOf(
            Change(7, 3, "r_R", null),
            Change(0, 3, null, "r_R"),
            Change(7, 7, "b_c", null),
            Change(7, 4, null, "b_c"),
        )
        val fc = classifySelfFrame(changes, after, Move(7 to 3, 0 to 3, "r_R"), Side.RED)
        assertEquals(SelfFrameResult.SELF_THEN_ENEMY, fc.result)
        assertEquals(Move(7 to 7, 7 to 4, "b_c", null), fc.enemyMove)
    }

    // ---------- n>4 ----------

    @Test
    fun `n大于4 有将帅 Noisy`() {
        val b = TB.fullBoard(Side.RED)
        val after = TB.copy(b)
        val changes = mutableListOf<Change>()
        for (i in 0 until 5) {
            after[i][0] = "b_p"
            changes.add(Change(i, 0, null, "b_p"))
        }
        val fc = classifySelfFrame(changes, after, Move(7 to 3, 0 to 3, "r_R"), Side.RED)
        assertEquals(SelfFrameResult.NOISY, fc.result)
    }

    @Test
    fun `n大于4 双方将帅缺失 Noisy`() {
        val after = TB.empty() // 无任何将帅
        val changes = (0 until 5).map { Change(it, 0, null, "b_p") }
        val fc = classifySelfFrame(changes, after, Move(7 to 3, 0 to 3, "r_R"), Side.RED)
        assertEquals(SelfFrameResult.NOISY, fc.result)
    }

    @Test
    fun `n2 兜底 captured 取落点旧子`() {
        // 落点原有敌子 b_k（被识别为同帧未提交），敌方 b_c 反吃：
        // 变动只有 src 与 enemy src 两格，落点在 newBoard 中已是反吃后的敌子
        val changes = listOf(
            Change(7, 3, "r_R", null),
            Change(5, 5, "b_c", null),
        )
        val after = TB.empty().also { it[0][3] = "b_c" }
        val fc = classifySelfFrame(changes, after, Move(7 to 3, 0 to 3, "r_R"), Side.RED)
        assertEquals(SelfFrameResult.SELF_THEN_ENEMY, fc.result)
        assertNull(fc.selfMove?.captured)
        assertTrue(Board::class.java.isInstance(after))
    }

}
