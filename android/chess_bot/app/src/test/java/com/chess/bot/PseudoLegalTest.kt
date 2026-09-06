package com.chess.bot.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** isPseudoLegal 伪合法校验纯函数测试（2026-09-06 T-A，阻断动画中间帧伪着法提交）。 */
class PseudoLegalTest {

    private fun board(vararg pieces: Triple<Int, Int, String>): Board {
        val b = makeEmptyBoard()
        for ((r, c, p) in pieces) b[r][c] = p
        return b
    }

    private fun move(src: Pair<Int, Int>, dst: Pair<Int, Int>, piece: String, captured: String? = null) =
        Move(src, dst, piece, captured)

    /** 本文件既有用例均按「我方执红」屏幕方位构造；新增执黑回归用例须显式传 Side.BLACK。 */
    private fun pl(b: Board, m: Move, mySide: Side = Side.RED): Boolean = isPseudoLegal(b, m, mySide)

    // ---------- 象（本局真实事故：黑象 c9->e7 飞行途经 d8） ----------

    @Test
    fun `象 田字合法`() {
        val b = board(Triple(0, 2, "b_b"))
        // c9(0,2) -> e7(2,4)：田字，象眼 (1,3) 空
        assertTrue(pl(b, move(0 to 2, 2 to 4, "b_b")))
    }

    @Test
    fun `象 一格斜线非法_动画中间帧事故着法`() {
        val b = board(Triple(0, 2, "b_b"))
        // c9(0,2) -> d8(1,3)：走一格斜线，非法 → 2026-09-06 P0 事故着法
        assertFalse(pl(b, move(0 to 2, 1 to 3, "b_b")))
    }

    @Test
    fun `象 塞象眼非法`() {
        val b = board(Triple(0, 2, "b_b"), Triple(1, 3, "r_P"))
        assertFalse(pl(b, move(0 to 2, 2 to 4, "b_b")))
    }

    @Test
    fun `象 不得过河`() {
        // 红相 (9,2) -> (3,4)？田字但跨河；用 (5,2)->(3,4) 已在红半区边缘检查
        val b = board(Triple(5, 2, "r_B"))
        // (5,2)->(3,4)：落点 row3 < 5 过河 → 非法
        assertFalse(pl(b, move(5 to 2, 3 to 4, "r_B")))
        // 黑象 (4,2)->(2,4)：黑半区 rows<=4，(4,2)->(2,4) 合法
        val b2 = board(Triple(4, 2, "b_b"))
        assertTrue(pl(b2, move(4 to 2, 2 to 4, "b_b")))
    }

    // ---------- 马 ----------

    @Test
    fun `马 日字无蹩腿合法`() {
        val b = board(Triple(0, 1, "b_n"))
        assertTrue(pl(b, move(0 to 1, 2 to 2, "b_n")))
    }

    @Test
    fun `马 蹩腿非法`() {
        val b = board(Triple(0, 1, "b_n"), Triple(1, 1, "r_P"))
        assertFalse(pl(b, move(0 to 1, 2 to 2, "b_n")))
    }

    // ---------- 车 ----------

    @Test
    fun `车 路径畅通合法_中途有子非法`() {
        val b = board(Triple(9, 0, "r_R"))
        assertTrue(pl(b, move(9 to 0, 8 to 0, "r_R")))
        // 开局红兵在 (6,0)：路径被挡 → 到 (5,0) 非法
        b[6][0] = "r_P"
        assertFalse(pl(b, move(9 to 0, 5 to 0, "r_R")))
    }

    @Test
    fun `车 不吃己方`() {
        val b = board(Triple(9, 0, "r_R"), Triple(6, 0, "r_P"))
        assertFalse(pl(b, move(9 to 0, 6 to 0, "r_R")))
    }

    // ---------- 炮 ----------

    @Test
    fun `炮 不吃子走法同车`() {
        val b = board(Triple(7, 1, "r_C"))
        assertTrue(pl(b, move(7 to 1, 7 to 3, "r_C")))
    }

    @Test
    fun `炮 吃子须恰一个炮架`() {
        val b = board(Triple(7, 1, "r_C"), Triple(7, 3, "r_P"), Triple(7, 5, "b_p"))
        assertTrue(pl(b, move(7 to 1, 7 to 5, "r_C", captured = "b_p")))
        // 两个炮架 → 非法
        b[7][4] = "b_a"
        assertFalse(pl(b, move(7 to 1, 7 to 5, "r_C", captured = "b_p")))
        // 无炮架直接吃 → 非法
        val b2 = board(Triple(7, 1, "r_C"), Triple(7, 5, "b_p"))
        assertFalse(pl(b2, move(7 to 1, 7 to 5, "r_C", captured = "b_p")))
    }

    // ---------- 兵 ----------

    @Test
    fun `兵 前进合法_过河前横走非法_过河后横走合法_后退非法`() {
        val b = board(Triple(6, 0, "r_P"))
        assertTrue(pl(b, move(6 to 0, 5 to 0, "r_P")))
        // 未过河横走 → 非法
        assertFalse(pl(b, move(6 to 0, 6 to 1, "r_P")))
        // 过河（r<=4）横走 → 合法
        val b2 = board(Triple(4, 2, "r_P"))
        assertTrue(pl(b2, move(4 to 2, 4 to 3, "r_P")))
        // 后退 → 非法
        assertFalse(pl(b2, move(4 to 2, 5 to 2, "r_P")))
        // 黑卒前进方向相反
        val b3 = board(Triple(3, 4, "b_p"))
        assertTrue(pl(b3, move(3 to 4, 4 to 4, "b_p")))
        assertFalse(pl(b3, move(3 to 4, 2 to 4, "b_p")))
    }

    // ---------- 士 / 将 ----------

    @Test
    fun `士 九宫内斜走合法_出宫非法`() {
        val b = board(Triple(9, 3, "r_A"))
        assertTrue(pl(b, move(9 to 3, 8 to 4, "r_A")))
        // 落点出宫（row 6）：单步斜走不可达出宫点，改用 dst (8,4)→(7,5) 之外的组合验证宫界——
        // 直接构造 dst 出宫的「假想一步」(9,3)->(6,4)（dr=-3）本就不满足斜走一步；
        // 宫界校验专测：士已在宫边 (8,4)，斜走到 (7,5) 合法（仍在宫内）
        val b2 = board(Triple(8, 4, "r_A"))
        assertTrue(pl(b2, move(8 to 4, 7 to 5, "r_A")))
        // 宫角 (9,4) 斜走出界到 (8,5)? 仍在宫。真正出宫：黑士 (2,3)->(3,4) 出黑宫（rows 0..2）
        val b3 = board(Triple(2, 3, "b_a"))
        assertFalse(pl(b3, move(2 to 3, 3 to 4, "b_a")))
    }

    @Test
    fun `将 九宫内直走合法_出宫非法_斜走非法`() {
        val b = board(Triple(9, 4, "r_K"))
        assertTrue(pl(b, move(9 to 4, 8 to 4, "r_K")))
        // 出宫（dst row 6）
        val b2 = board(Triple(7, 4, "r_K"))
        assertFalse(pl(b2, move(7 to 4, 6 to 4, "r_K")))
        // 斜走
        assertFalse(pl(b, move(9 to 4, 8 to 5, "r_K")))
    }

    // ---------- 通用约束 ----------

    @Test
    fun `起点须为该子`() {
        val b = board(Triple(9, 0, "r_R"))
        assertFalse(pl(b, move(9 to 0, 8 to 0, "r_N")))
        // 起点为空
        assertFalse(pl(b, move(5 to 5, 5 to 4, "r_R")))
    }

    @Test
    fun `起终点相同非法`() {
        val b = board(Triple(9, 0, "r_R"))
        assertFalse(pl(b, move(9 to 0, 9 to 0, "r_R")))
    }

    // ---------- 2026-09-06 01:53 事故回归：我方执黑时屏幕方位（我方恒在下半区，敌方红子上半区） ----------

    @Test
    fun `执黑时_敌方红相上半区田字合法_本局事故着法`() {
        // 我方执黑：红相在屏幕上半区，c0(0,6) -> e2(2,4) 田字合法（本局被误杀的真实落定帧）
        val b = board(Triple(0, 6, "r_B"))
        assertTrue(pl(b, move(0 to 6, 2 to 4, "r_B"), Side.BLACK))
        // 同一着法按红方视角（红在下）必须非法（红子不应出现在上半区 rows 0..4）
        assertFalse(pl(b, move(0 to 6, 2 to 4, "r_B"), Side.RED))
    }

    @Test
    fun `执黑时_敌方红仕红将九宫在上方`() {
        // 红仕 (0,3)->(1,4)：我方执黑时红九宫 = rows 0..2 → 合法；红视角 → 非法
        val b = board(Triple(0, 3, "r_A"))
        assertTrue(pl(b, move(0 to 3, 1 to 4, "r_A"), Side.BLACK))
        assertFalse(pl(b, move(0 to 3, 1 to 4, "r_A"), Side.RED))
        // 红将宫内直走 (1,4)->(2,4) 合法，出宫 (2,4)->(3,4) 非法
        val b2 = board(Triple(1, 4, "r_K"))
        assertTrue(pl(b2, move(1 to 4, 2 to 4, "r_K"), Side.BLACK))
        assertFalse(pl(b2, move(2 to 4, 3 to 4, "r_K"), Side.BLACK))
    }

    @Test
    fun `执黑时_兵方向按屏幕方位互换`() {
        // 我方黑兵在下半区向上进 (6,4)->(5,4)；按红视角（黑在上）该方向为后退 → 非法
        val b = board(Triple(6, 4, "b_p"))
        assertTrue(pl(b, move(6 to 4, 5 to 4, "b_p"), Side.BLACK))
        assertFalse(pl(b, move(6 to 4, 5 to 4, "b_p"), Side.RED))
        // 敌方红兵在上半区向下进 (3,4)->(4,4)
        val b2 = board(Triple(3, 4, "r_P"))
        assertTrue(pl(b2, move(3 to 4, 4 to 4, "r_P"), Side.BLACK))
        // 敌方红兵过河（进入下半区 rows 5..9）后可横走；红视角下 (5,2) 未过河 → 非法
        val b3 = board(Triple(5, 2, "r_P"))
        assertTrue(pl(b3, move(5 to 2, 5 to 3, "r_P"), Side.BLACK))
        assertFalse(pl(b3, move(5 to 2, 5 to 3, "r_P"), Side.RED))
    }

    // ---------- classifier 门控接线（preBoard 参数） ----------

    @Test
    fun `classifyEnemyFrame 传入preBoard时_象中间帧着法拒判NOISY`() {
        val b = board(Triple(0, 2, "b_b"))
        val changes = listOf(
            Change(0, 2, "b_b", null),
            Change(1, 3, null, "b_b"),
        )
        assertEquals(
            EnemyFrame(EnemyFrameResult.NOISY),
            classifyEnemyFrame(changes, Side.RED, b),
        )
        // 合法落定帧 c9->e7 正常放行
        val ok = listOf(
            Change(0, 2, "b_b", null),
            Change(2, 4, null, "b_b"),
        )
        assertEquals(
            EnemyFrame(EnemyFrameResult.MOVED, move(0 to 2, 2 to 4, "b_b")),
            classifyEnemyFrame(ok, Side.RED, b),
        )
    }

    @Test
    fun `classifyEnemyFrame 不传preBoard保持旧行为`() {
        val b = board(Triple(0, 2, "b_b"))
        val changes = listOf(
            Change(0, 2, "b_b", null),
            Change(1, 3, null, "b_b"),
        )
        assertEquals(
            EnemyFrame(EnemyFrameResult.MOVED, move(0 to 2, 1 to 3, "b_b")),
            classifyEnemyFrame(changes, Side.RED),
        )
    }
}
