package com.chess.bot

import com.chess.bot.game.Const
import com.chess.bot.game.GameState
import com.chess.bot.game.Move
import com.chess.bot.game.Phase
import com.chess.bot.game.Side
import com.chess.bot.game.fenOfBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.chess.bot.TestBoards as TB

/**
 * position 滚动重锚测试（2026-09-12 D1=B 窗口 6 / D2=A DEBUG 日志）：
 * movesList 达 ENGINE_MOVE_WINDOW → 基线 FEN 滚动重拍、moves 清零；
 * applySelfThenEnemy 须两手提交完才判定（中途滚动会拍到错误的行棋方）。
 */
class GameStateRollTest {

    /** 新建初始化完毕、mySide=红/轮红的会话，摆 r_R(7,3) / b_r(0,3) / r_P(0,0)。 */
    private fun newState(): GameState {
        val s = GameState()
        val b = TB.empty()
        b[7][3] = "r_R"
        b[0][3] = "b_r"
        b[0][0] = "r_P"
        s.replaceBoard(b)
        s.markInitialized(Side.RED, Phase.OPENING)
        s.turn = Side.RED
        return s
    }

    private fun selfRook(s: GameState, from: Pair<Int, Int>, to: Pair<Int, Int>) {
        s.applySelfMove(Move(from, to, "r_R", null))
    }

    private fun enemyRook(s: GameState, from: Pair<Int, Int>, to: Pair<Int, Int>) {
        s.applyEnemyMove(Move(from, to, "b_r", null))
    }

    @Test
    fun `未达窗口不滚动`() {
        val s = newState()
        val base = s.ensureEngineBaseline()
        // 5 手（< 窗口 6）：基线不动、moves 全保留
        selfRook(s, 7 to 3, 7 to 4)
        enemyRook(s, 0 to 3, 0 to 4)
        selfRook(s, 7 to 4, 7 to 3)
        enemyRook(s, 0 to 4, 0 to 3)
        selfRook(s, 7 to 3, 7 to 4)
        assertEquals(5, s.movesList.size)
        assertEquals(base, s.baselineFen)
    }

    @Test
    fun `达窗口滚动重拍基线并清空 moves`() {
        val s = newState()
        s.ensureEngineBaseline()
        selfRook(s, 7 to 3, 7 to 4)
        enemyRook(s, 0 to 3, 0 to 4)
        selfRook(s, 7 to 4, 7 to 3)
        enemyRook(s, 0 to 4, 0 to 3)
        selfRook(s, 7 to 3, 7 to 4)
        // 第 6 手（敌走）：达窗口 → 滚动
        enemyRook(s, 0 to 3, 0 to 4)
        assertEquals(0, s.movesList.size)
        assertEquals(
            fenOfBoard(s.board, s.mySide, s.turn, s.halfmoveClock),
            s.baselineFen,
        )
        // 敌走后轮我方（红）→ 行棋方 w；全非吃 → 半回合钟 6
        val fen = s.baselineFen!!
        assertTrue("行棋方应为 w：$fen", fen.contains(" w "))
        assertTrue("半回合钟应为 6：$fen", fen.endsWith(" 6 1"))
    }

    @Test
    fun `applySelfThenEnemy 两手提交完才判定滚动`() {
        val s = newState()
        s.ensureEngineBaseline()
        selfRook(s, 7 to 3, 7 to 4)
        enemyRook(s, 0 to 3, 0 to 4)
        selfRook(s, 7 to 4, 7 to 3)
        enemyRook(s, 0 to 4, 0 to 3)
        selfRook(s, 7 to 3, 7 to 4)
        assertEquals(5, s.movesList.size)
        // 一并提交我方+敌方两手（累积 7 ≥ 6 → 提交完滚动一次）
        s.applySelfThenEnemy(
            Move(7 to 4, 7 to 3, "r_R", null),
            Move(0 to 3, 0 to 4, "b_r", null),
        )
        assertEquals(0, s.movesList.size)
        assertEquals(Side.RED, s.turn)
        // 若中途（我方提交后）误滚动，行棋方会拍成黑 b；正确应在轮红时拍 → w
        assertTrue("行棋方应为 w：${s.baselineFen}", s.baselineFen!!.contains(" w "))
        assertEquals(
            fenOfBoard(s.board, s.mySide, s.turn, s.halfmoveClock),
            s.baselineFen,
        )
    }

    @Test
    fun `滚动前吃子归零正确带入新基线`() {
        val s = newState()
        s.ensureEngineBaseline()
        selfRook(s, 7 to 3, 7 to 4)
        enemyRook(s, 0 to 3, 0 to 4)
        selfRook(s, 7 to 4, 7 to 3)
        enemyRook(s, 0 to 4, 0 to 3)
        selfRook(s, 7 to 3, 7 to 4)
        // 第 6 手敌走吃 r_P(0,0) → 半回合钟归零进新基线
        s.applyEnemyMove(Move(0 to 3, 0 to 0, "b_r", "r_P"))
        assertEquals(0, s.movesList.size)
        assertEquals(0, s.halfmoveClock)
        assertTrue("吃子后半回合钟应为 0：${s.baselineFen}", s.baselineFen!!.endsWith(" 0 1"))
    }

    @Test
    fun `滚动后 moves 重新从零累计`() {
        val s = newState()
        s.ensureEngineBaseline()
        for (i in 0 until Const.ENGINE_MOVE_WINDOW + 2) {
            if (i % 2 == 0) {
                selfRook(s, 7 to 3, 7 to 4)
            } else {
                enemyRook(s, 0 to 3, 0 to 4)
            }
        }
        // 8 手经历一次滚动（6 手时）→ 剩 2 手
        assertEquals(2, s.movesList.size)
        // 滚动后的 moves ICCS 与最新基线坐标系一致（ICCS 首格为起点）
        assertTrue(s.movesList.all { it.length == 4 })
    }
}
