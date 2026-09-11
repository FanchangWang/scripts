package com.chess.bot

import com.chess.bot.game.Board
import com.chess.bot.game.Change
import com.chess.bot.game.Const
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

/** 敌方走棋检测 + 认输疑似判断测试（翻译 python test_noisy/test_resign 纯分类部分）。
 *  B-1（2026-09-11）：preBoard 取消默认值后，本文件（只测分类形状）统一显式传 null = 跳过伪合法校验；
 *  preBoard 门控行为本身由 PseudoLegalTest 覆盖。 */
class ClassifierEnemyTest {

    @Test
    fun `n2 推断出敌方走法`() {
        val changes = listOf(
            Change(7, 7, "b_c", null),
            Change(7, 4, null, "b_c"),
        )
        val frame = classifyEnemyFrame(changes, Side.RED, null)
        assertEquals(EnemyFrame(EnemyFrameResult.MOVED, Move(7 to 7, 7 to 4, "b_c", null)), frame)
    }

    @Test
    fun `n1 敌方提子 Lifted`() {
        val frame = classifyEnemyFrame(listOf(Change(7, 7, "b_c", null)), Side.RED, null)
        assertEquals(EnemyFrame(EnemyFrameResult.LIFTED), frame)
    }

    @Test
    fun `n1 敌方棋子变 lift 直接确认提子`() {
        // cls 识别出「提起棋子」(lift)：与「格子变空」推断同等判定
        val frame = classifyEnemyFrame(listOf(Change(7, 7, "b_c", Const.LIFT)), Side.RED, null)
        assertEquals(EnemyFrame(EnemyFrameResult.LIFTED), frame)
    }

    @Test
    fun `n1 敌方棋子变 lift 但仍是我方棋子不算提子`() {
        // 我方棋子从起点提起(变 lift) 不属于敌方提子场景
        val frame = classifyEnemyFrame(listOf(Change(5, 5, "r_P", Const.LIFT)), Side.RED, null)
        assertEquals(EnemyFrame(EnemyFrameResult.NOISY), frame)
    }

    @Test
    fun `空格变 lift 是飞行途经瞬态 剔除后判提子`() {
        // 2026-09-06 02:27 日志：红炮 i2→g2 飞行途经 h2，h2 空→lift 为动画伪影
        //（空格不可能被提起）；剔除后只剩 i2 起点变空 → LIFTED，不计入噪声
        val changes = listOf(
            Change(2, 0, "r_C", null),
            Change(2, 1, null, Const.LIFT),
        )
        val frame = classifyEnemyFrame(changes, Side.BLACK, null)
        assertEquals(EnemyFrame(EnemyFrameResult.LIFTED), frame)
    }

    @Test
    fun `仅空格变 lift 剔除后为 Silent`() {
        val frame = classifyEnemyFrame(listOf(Change(2, 1, null, Const.LIFT)), Side.BLACK, null)
        assertEquals(EnemyFrame(EnemyFrameResult.SILENT), frame)
    }

    @Test
    fun `n1 我方棋子消失不算提子`() {
        val frame = classifyEnemyFrame(listOf(Change(5, 5, "r_P", null)), Side.RED, null)
        assertEquals(EnemyFrame(EnemyFrameResult.NOISY), frame)
    }

    @Test
    fun `n0 Silent`() {
        assertEquals(
            EnemyFrame(EnemyFrameResult.SILENT),
            classifyEnemyFrame(emptyList(), Side.RED, null),
        )
    }

    @Test
    fun `n大于2 Noisy`() {
        val changes = (0 until 3).map { Change(it, 0, null, "b_p") }
        assertEquals(EnemyFrame(EnemyFrameResult.NOISY), classifyEnemyFrame(changes, Side.RED, null))
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
