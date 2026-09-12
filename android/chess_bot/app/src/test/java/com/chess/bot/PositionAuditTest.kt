package com.chess.bot

import com.chess.bot.game.Board
import com.chess.bot.game.Move
import com.chess.bot.game.Side
import com.chess.bot.game.applyMove
import com.chess.bot.game.auditEnginePosition
import com.chess.bot.game.boardFromFen
import com.chess.bot.game.fenOfBoard
import com.chess.bot.game.fullStartBoard
import com.chess.bot.game.squareToGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎 position 自检测试（2026-09-12 用户批复 D2：自检 + 报错中止）。
 *
 * 覆盖：合法交替序列通过 / 同色连走（2026-09-12 真机事故形状）被拒 / 起点为空被拒 /
 * 非伪合法被拒 / 演进局面与已提交棋盘不一致被拒 / 轮次不符被拒 / FEN 往返一致 / 解析失败被拒。
 */
class PositionAuditTest {

    /** 红先 32 子开局的基线 FEN（红炮 h2e2 为红方第 1 手、黑馬 b9c7 为黑方第 1 手）。 */
    private fun startFen(): String = fenOfBoard(fullStartBoard(Side.RED), Side.RED, Side.RED)

    /** 按着法列表从开局演进出一份棋盘（充当「已提交棋盘」期望值）。 */
    private fun boardAfter(vararg moves: String): Board {
        val b = fullStartBoard(Side.RED)
        for (m in moves) {
            val src = squareToGrid(m.substring(0, 2), Side.RED)
            val dst = squareToGrid(m.substring(2, 4), Side.RED)
            applyMove(b, Move(src, dst, b[src.first][src.second]!!), 0)
        }
        return b
    }

    @Test
    fun `合法交替序列通过`() {
        val moves = listOf("h2e2", "b9c7")
        assertNull(
            auditEnginePosition(
                startFen(), Side.RED, moves, boardAfter("h2e2", "b9c7"), Side.RED
            )
        )
    }

    @Test
    fun `同色连走被拒（2026-09-12 事故形状）`() {
        // 红炮走完轮到黑方，第 2 手却仍是红炮 → 正是真机事故里「我方一手被排到敌手之后」的形状
        val problem = auditEnginePosition(
            startFen(), Side.RED, listOf("h2e2", "b2e2"), boardAfter("h2e2"), Side.RED
        )
        assertNotNull(problem)
        assertTrue(problem!!.contains("顺序错乱"))
    }

    @Test
    fun `起点为空被拒`() {
        val problem = auditEnginePosition(
            startFen(), Side.RED, listOf("h2e2", "c5c6"), fullStartBoard(Side.RED), Side.RED
        )
        assertNotNull(problem)
        assertTrue(problem!!.contains("起点为空"))
    }

    @Test
    fun `非伪合法着法被拒`() {
        // 黑将 e9 -> d7：横竖两步，违反九宫一步走法
        val problem = auditEnginePosition(
            startFen(), Side.RED, listOf("h2e2", "e9d7"), fullStartBoard(Side.RED), Side.RED
        )
        assertNotNull(problem)
        assertTrue(problem!!.contains("非伪合法"))
    }

    @Test
    fun `演进局面与已提交棋盘不一致被拒`() {
        val moves = listOf("h2e2", "b9c7")
        val problem = auditEnginePosition(
            startFen(), Side.RED, moves, fullStartBoard(Side.RED), Side.RED
        )
        assertNotNull(problem)
        assertTrue(problem!!.contains("不一致"))
    }

    @Test
    fun `演进后轮次不符被拒`() {
        // 只携带 1 手（红）→ 演进后轮到黑方，但已提交棋盘轮到我方（红）
        val problem = auditEnginePosition(
            startFen(), Side.RED, listOf("h2e2"), boardAfter("h2e2"), Side.RED
        )
        assertNotNull(problem)
        assertTrue(problem!!.contains("不符"))
    }

    @Test
    fun `FEN 往返一致（红黑两向）`() {
        for (side in listOf(Side.RED, Side.BLACK)) {
            val b = fullStartBoard(side)
            val parsed = boardFromFen(fenOfBoard(b, side, side), side)
            assertNotNull("mySide=$side 应能解析自产 FEN", parsed)
            assertEquals(side, parsed!!.second)
            for (r in 0 until 10) {
                for (c in 0 until 9) {
                    assertEquals("mySide=$side 格($r,$c)", b[r][c], parsed.first[r][c])
                }
            }
        }
    }

    @Test
    fun `FEN 解析失败被拒`() {
        assertNull(boardFromFen("garbage", Side.RED))
        val problem = auditEnginePosition(
            "garbage", Side.RED, emptyList(), fullStartBoard(Side.RED), Side.RED
        )
        assertNotNull(problem)
        assertTrue(problem!!.contains("解析失败"))
    }
}
