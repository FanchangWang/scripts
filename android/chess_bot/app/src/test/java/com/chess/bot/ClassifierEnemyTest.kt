package com.chess.bot

import com.chess.bot.game.Board
import com.chess.bot.game.Change
import com.chess.bot.game.EnemyFrame
import com.chess.bot.game.EnemyFrameResult
import com.chess.bot.game.Move
import com.chess.bot.game.Side
import com.chess.bot.game.classifyEnemyFrame
import com.chess.bot.game.isResignSuspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.chess.bot.TestBoards as TB

/** 敌方走棋检测 + 认输疑似判断测试（翻译 python test_noisy/test_resign 纯分类部分）。 */
class ClassifierEnemyTest {

    @Test
    fun `n2 推断出敌方走法`() {
        val changes = listOf(
            Change(7, 7, "b_c", null),
            Change(7, 4, null, "b_c"),
        )
        val frame = classifyEnemyFrame(changes, Side.RED)
        assertEquals(EnemyFrame(EnemyFrameResult.MOVED, Move(7 to 7, 7 to 4, "b_c", null)), frame)
    }

    @Test
    fun `n1 敌方提子 Lifted`() {
        val frame = classifyEnemyFrame(listOf(Change(7, 7, "b_c", null)), Side.RED)
        assertEquals(EnemyFrame(EnemyFrameResult.LIFTED), frame)
    }

    @Test
    fun `n1 我方棋子消失不算提子`() {
        val frame = classifyEnemyFrame(listOf(Change(5, 5, "r_P", null)), Side.RED)
        assertEquals(EnemyFrame(EnemyFrameResult.NOISY), frame)
    }

    @Test
    fun `n0 Silent`() {
        assertEquals(EnemyFrame(EnemyFrameResult.SILENT), classifyEnemyFrame(emptyList(), Side.RED))
    }

    @Test
    fun `n大于2 Noisy`() {
        val changes = (0 until 3).map { Change(it, 0, null, "b_p") }
        assertEquals(EnemyFrame(EnemyFrameResult.NOISY), classifyEnemyFrame(changes, Side.RED))
    }

    @Test
    fun `双方将帅缺失 suspect`() {
        assertTrue(isResignSuspect(TB.empty(), Side.RED))
    }

    @Test
    fun `仅存我方将帅 none`() {
        val board: Board = TB.empty().also { it[9][4] = "r_K" }
        assertFalse(isResignSuspect(board, Side.RED))
    }

    @Test
    fun `仅存敌方将帅 none`() {
        val board: Board = TB.empty().also { it[0][4] = "b_k" }
        assertFalse(isResignSuspect(board, Side.RED))
    }
}
