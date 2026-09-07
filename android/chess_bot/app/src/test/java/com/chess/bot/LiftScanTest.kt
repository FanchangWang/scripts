package com.chess.bot

import com.chess.bot.game.pickLiftedCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** pickLiftedCandidate 多数票选择（2026-09-08 D1-A），用例对齐 2026-09-08 真机实测读数形态。 */
class LiftScanTest {

    @Test
    fun `实测形态_五档一致多数票命中`() {
        // r7c1 红炮（01:40 实测）：r_C×5 一致
        val candidates = listOf(
            "r_C" to 1.00f, "r_C" to 1.00f, "r_C" to 1.00f, "r_C" to 1.00f, "r_C" to 0.85f,
        )
        assertEquals("r_C", pickLiftedCandidate(candidates, abovePiece = null))
    }

    @Test
    fun `上格孤例高置信被多数票免疫`() {
        // 污染场景：上格棋子 r_R 在两档高置信出现，真实棋子 b_r 五档一致但单档置信略低
        val candidates = listOf(
            "b_r" to 0.90f, "b_r" to 1.00f, "b_r" to 1.00f, "b_r" to 0.99f, "b_r" to 0.83f,
            "r_R" to 1.00f, "r_R" to 1.00f,
        )
        assertEquals("b_r", pickLiftedCandidate(candidates, abovePiece = null))
    }

    @Test
    fun `abovePiece排除仍生效`() {
        // 上格读取正常时先剔除上格棋子档，再多数票（原语义保留）
        val candidates = listOf("b_r" to 0.90f, "r_R" to 1.00f)
        assertEquals("b_r", pickLiftedCandidate(candidates, abovePiece = "r_R"))
        // 上格棋子是唯一候选时不排除（避免全滤空后误判 null）
        assertEquals("r_R", pickLiftedCandidate(listOf("r_R" to 1.00f), abovePiece = "r_R"))
    }

    @Test
    fun `同票数比最高置信`() {
        val candidates = listOf("b_n" to 0.95f, "b_n" to 0.90f, "r_P" to 0.99f, "r_P" to 0.80f)
        assertEquals("r_P", pickLiftedCandidate(candidates, abovePiece = null))
    }

    @Test
    fun `空候选返回null`() {
        assertNull(pickLiftedCandidate(emptyList(), abovePiece = null))
    }
}
