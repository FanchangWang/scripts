package com.chess.bot

import com.chess.bot.game.Const
import com.chess.bot.game.LandingState
import com.chess.bot.game.Side
import com.chess.bot.game.probeLandingState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 落点四态探测单元测试（2026-09-13 用户批复 D2/D5 共用件）。
 *
 * 覆盖：本案 h6h4 的**两个真实形状**（RETRY 前「格中心有子 = LANDED」/ RETRY 后「格中心空 +
 * 向上扫描命中 = LIFTED_ABOVE」）、被吃优先于悬空、我方另一颗子的异常兜底、红黑两侧对称。
 *
 * 注：`probeLandingState` 是纯函数、不接触置信度——调用方必须传入**原始 top1 读数**（例如
 * 本案 h4 的 `红炮[0.88]`），由本函数按「值是否等于 expected」判定，故 0.88 与 1.00 同判 LANDED。
 */
class LandingProbeTest {

    private fun probe(
        center: String?,
        lifted: String? = null,
        expected: String = "r_C",
        mySide: Side = Side.RED,
    ): LandingState = probeLandingState(center, lifted, expected, mySide)

    // ---------- LANDED ----------

    @Test
    fun `格中心读到 expected 棋子即判已落定`() {
        assertEquals(LandingState.LANDED, probe(center = "r_C"))
    }

    @Test
    fun `本案 RETRY 前形状——格中心读到红炮（0_88 的低置信读数由调用方原样传入）`() {
        // 真机 log.txt 13067：`h4 空→红炮[0.88]`，0.88 < CLS_TRUST_MIN(0.95) 故未进 changes，
        // 但格中心确实读出了 r_C —— 纯函数据此判 LANDED（D2 拦下的正是这一帧）。
        assertEquals(LandingState.LANDED, probe(center = "r_C", expected = "r_C"))
    }

    // ---------- LIFTED_ABOVE ----------

    @Test
    fun `本案 RETRY 后形状——格中心空且向上扫描命中即判悬空`() {
        // 真机 log.txt 13095 之后：h4 读数彻底消失（格中心空），子悬在 h4 上方。
        assertEquals(LandingState.LIFTED_ABOVE, probe(center = null, lifted = "r_C"))
    }

    @Test
    fun `格中心读到 lift 且向上扫描命中即判悬空`() {
        assertEquals(LandingState.LIFTED_ABOVE, probe(center = Const.LIFT, lifted = "r_C"))
    }

    @Test
    fun `格中心是我方另一颗子但向上扫描命中 expected 仍判悬空`() {
        // 异常兜底：格中心读数是我方别的子（cls 误读）→ 未命中 expected、也非敌方子 →
        // 继续看向上扫描结果，命中则仍可判悬空。
        assertEquals(LandingState.LIFTED_ABOVE, probe(center = "r_P", lifted = "r_C"))
    }

    // ---------- ABSENT ----------

    @Test
    fun `格中心空且向上扫描也探不到则判缺席`() {
        assertEquals(LandingState.ABSENT, probe(center = null, lifted = null))
    }

    @Test
    fun `格中心是我方另一颗子且向上扫描无命中判缺席`() {
        assertEquals(LandingState.ABSENT, probe(center = "r_P", lifted = null))
    }

    // ---------- CAPTURED ----------

    @Test
    fun `格中心是敌方子即判被吃`() {
        assertEquals(LandingState.CAPTURED, probe(center = "b_p", expected = "r_C"))
    }

    @Test
    fun `被吃优先于悬空——即使向上扫描命中也判被吃`() {
        assertEquals(LandingState.CAPTURED, probe(center = "b_p", lifted = "r_C"))
    }

    // ---------- 红黑对称 ----------

    @Test
    fun `黑方视角——格中心读到黑方便已落定`() {
        assertEquals(
            LandingState.LANDED,
            probe(center = "b_c", expected = "b_c", mySide = Side.BLACK)
        )
    }

    @Test
    fun `黑方视角——格中心是红子判被吃`() {
        assertEquals(
            LandingState.CAPTURED,
            probe(center = "r_C", expected = "b_c", mySide = Side.BLACK)
        )
    }

    @Test
    fun `黑方视角——格中心空且向上扫描命中黑子判悬空`() {
        assertEquals(
            LandingState.LIFTED_ABOVE,
            probe(center = null, lifted = "b_c", expected = "b_c", mySide = Side.BLACK)
        )
    }
}
