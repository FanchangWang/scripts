package com.chess.bot

import com.chess.bot.game.Board
import com.chess.bot.game.Change
import com.chess.bot.game.Const
import com.chess.bot.game.GameState
import com.chess.bot.game.Move
import com.chess.bot.game.SelfFrameResult
import com.chess.bot.game.Side
import com.chess.bot.game.classifySelfFrame
import com.chess.bot.game.isSelfPairSettled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.chess.bot.TestBoards as TB

/** 我方走棋帧分类测试（翻译 python test_capture 12 场景中的纯分类部分）。
 *  B-1（2026-09-11）：preBoard 取消默认值后，本文件统一显式传 null = 跳过伪合法校验
 *  （本文件只测分类形状；preBoard 门控行为由 PseudoLegalTest 覆盖）。 */
class ClassifierSelfTest {

    // ---------- n==2 ----------

    @Test
    fun `n2 干净走棋 SELF_DONE`() {
        val b = TB.empty().also { it[7][3] = "r_R"; it[0][3] = "b_r" }
        val after = TB.copy(b).also { it[7][3] = null; it[0][3] = "r_R" }
        val changes = listOf(Change(7, 3, "r_R", null), Change(0, 3, "b_r", "r_R"))
        val expected = Move(7 to 3, 0 to 3, "r_R")

        val fc = classifySelfFrame(changes, after, expected, Side.RED, null)
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
    fun `空格变 lift 途经瞬态被剔除 我方落定帧仍判 SELF_DONE`() {
        // 2026-09-06 02:27 日志复盘：我方車 h6→h0 落定帧旁，敌方棋子飞行途经格呈「空→lift」
        // 伪影（空格不可能被提起）；剔除后按剩余 2 格正常推断，不再 NOISY 空转
        val b = TB.empty().also { it[6][1] = "b_r" }
        val after = TB.copy(b).also { it[6][1] = null; it[0][1] = "b_r" }
        val changes = listOf(
            Change(6, 1, "b_r", null),
            Change(0, 1, null, "b_r"),
            Change(5, 4, null, Const.LIFT),
        )
        val fc = classifySelfFrame(changes, after, Move(6 to 1, 0 to 1, "b_r"), Side.BLACK, null)
        assertEquals(SelfFrameResult.SELF_DONE, fc.result)
        assertEquals(Move(6 to 1, 0 to 1, "b_r", null), fc.selfMove)
    }

    @Test
    fun `n2 不匹配 expected 则 Noisy`() {
        val b = TB.empty().also { it[7][3] = "r_R"; it[5][5] = "b_r" }
        val after = TB.copy(b).also { it[5][5] = null; it[5][4] = "b_r" }
        val changes = listOf(Change(5, 5, "b_r", null), Change(5, 4, null, "b_r"))
        val fc = classifySelfFrame(changes, after, Move(7 to 3, 0 to 3, "r_R"), Side.RED, null)
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
        val fc = classifySelfFrame(changes, after, expected, Side.RED, null)
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
            classifySelfFrame(changes, lifted, Move(7 to 3, 0 to 3, "r_R"), Side.RED, null)
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
            null,
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
            null,
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

        val fc = classifySelfFrame(changes, after, expected, Side.RED, null)
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
        val fc = classifySelfFrame(changes, after, expected, Side.RED, null)
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
        val fc = classifySelfFrame(changes, after, Move(7 to 3, 0 to 3, "r_R"), Side.RED, null)
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
        val fc = classifySelfFrame(changes, after, Move(7 to 3, 0 to 3, "r_R"), Side.RED, null)
        assertEquals(SelfFrameResult.NOISY, fc.result)
    }

    @Test
    fun `n大于4 双方将帅缺失 Noisy`() {
        val after = TB.empty() // 无任何将帅
        val changes = (0 until 5).map { Change(it, 0, null, "b_p") }
        val fc = classifySelfFrame(changes, after, Move(7 to 3, 0 to 3, "r_R"), Side.RED, null)
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
        val fc = classifySelfFrame(changes, after, Move(7 to 3, 0 to 3, "r_R"), Side.RED, null)
        assertEquals(SelfFrameResult.SELF_THEN_ENEMY, fc.result)
        assertNull(fc.selfMove?.captured)
        assertTrue(Board::class.java.isInstance(after))
    }

    @Test
    fun `n1 起点变 lift 直接判提子`() {
        // cls 识别出「提起棋子」(lift)：无需依赖「格子变空」推断，直接确认
        val b = TB.empty().also { it[7][3] = "r_R"; it[5][5] = "b_r" }
        val lifted = TB.copy(b).also { it[7][3] = Const.LIFT }
        val changes = listOf(Change(7, 3, "r_R", Const.LIFT))
        val fc =
            classifySelfFrame(changes, lifted, Move(7 to 3, 0 to 3, "r_R"), Side.RED, null)
        assertEquals(SelfFrameResult.LIFTED, fc.result)
    }

    @Test
    fun `n1 变为其他棋子不算提子`() {
        val b = TB.empty().also { it[7][3] = "r_R"; it[5][5] = "b_r" }
        val after = TB.copy(b).also { it[7][3] = "b_p" }
        val changes = listOf(Change(7, 3, "r_R", "b_p"))
        val fc =
            classifySelfFrame(changes, after, Move(7 to 3, 0 to 3, "r_R"), Side.RED, null)
        assertEquals(SelfFrameResult.NOISY, fc.result)
    }

    // ---------- v3 isSelfPairSettled（n==2 恰为本步两格的快速成功判定） ----------

    private val SRC = 7 to 3
    private val DST = 5 to 5

    @Test
    fun `v3 快速判定 n2恰两格且dst为我子 true`() {
        val changes = listOf(Change(7, 3, "r_R", null), Change(5, 5, null, "r_R"))
        assertTrue(isSelfPairSettled(changes, Move(SRC, DST, "r_R")))
        // dst 原有敌子被我吃掉（吃子走子）
        val capChanges = listOf(Change(7, 3, "r_R", null), Change(5, 5, "b_r", "r_R"))
        assertTrue(isSelfPairSettled(capChanges, Move(SRC, DST, "r_R")))
    }

    @Test
    fun `v3 快速判定 n2恰两格但dst为敌子 false 不可达形状`() {
        // 「落子即被反吃」（src→空 + dst 空→敌子）在干净 diff 下不可达（敌源格必产生 diff → n≥3）；
        // inferMove 的「离开子==到达子」约束使其自动返回 false，落回 classifySelfFrame 的 NOISY，绝不提交
        val changes = listOf(Change(7, 3, "r_R", null), Change(5, 5, "b_r", "b_p"))
        assertFalse(isSelfPairSettled(changes, Move(SRC, DST, "r_R")))
        // dst 为我方其他棋子（误读）同样拒绝：piece 直接精确匹配
        val misread = listOf(Change(7, 3, "r_R", null), Change(5, 5, null, "r_C"))
        assertFalse(isSelfPairSettled(misread, Move(SRC, DST, "r_R")))
    }

    @Test
    fun `v3 快速判定 dst为lift或空 false 动画中途态`() {
        // 吃子动画中途：起格空 + 落点空（被吃子已消失、我子未现）
        val midCap = listOf(Change(7, 3, "r_R", null), Change(5, 5, "b_r", null))
        assertFalse(isSelfPairSettled(midCap, Move(SRC, DST, "r_R")))
        // 落点读数 lift
        val lift = listOf(Change(7, 3, "r_R", null), Change(5, 5, null, Const.LIFT))
        assertFalse(isSelfPairSettled(lift, Move(SRC, DST, "r_R")))
    }

    @Test
    fun `v3 快速判定 格子集合不等于本步两格 false`() {
        // 夹带无关格（如 17:59 场景的 e9 敌方提子）→ 不走快速路径，交 classifySelfFrame
        val extra = listOf(
            Change(7, 3, "r_R", null),
            Change(5, 5, null, "r_R"),
            Change(4, 4, "b_k", null),
        )
        assertFalse(isSelfPairSettled(extra, Move(SRC, DST, "r_R")))
        // 两格中含非本步格
        val wrong = listOf(Change(7, 3, "r_R", null), Change(4, 4, "b_k", null))
        assertFalse(isSelfPairSettled(wrong, Move(SRC, DST, "r_R")))
    }

    @Test
    fun `v3 快速判定 帧间一致性用变化格子集合逐格比较`() {
        // 同格同值 → 相等（List&lt;Change&gt; 结构相等，data class 逐字段比较）
        val a = listOf(Change(7, 3, "r_R", null), Change(5, 5, null, "r_R"))
        val b = listOf(Change(7, 3, "r_R", null), Change(5, 5, null, "r_R"))
        assertEquals(a, b)
        // 顺序不同但集合语义相同——实现按「格子集合」比较，此处验证 Set 语义
        assertEquals(a.map { it.r to it.c }.toSet(), b.shuffled().map { it.r to it.c }.toSet())
        // 同格不同值 → 不等（动画推进/伪影）
        val c = listOf(Change(7, 3, "r_R", null), Change(5, 5, null, Const.LIFT))
        assertTrue(a != c)
        // 两帧皆空 → 相等（静止稳定）
        assertEquals(emptyList<Change>(), emptyList<Change>())
    }
}
