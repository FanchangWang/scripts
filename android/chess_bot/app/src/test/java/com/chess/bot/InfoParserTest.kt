package com.chess.bot

import com.chess.bot.engine.EngineInfoPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎 info 解析 / 最终 info 选取 / 硬顶 纯函数测试。
 * 用户四条实测日志（2026-09-08）作为回归用例。
 *
 * 2026-09-11 质量检测重做（R4/R5）：伪影判定 / 质量门控 / 近杀提前停用例随实现一并删除。
 */
class InfoParserTest {

    // ---------- 用户四条实测 info 行 ----------

    private val l1 =
        "info depth 40 seldepth 2 multipv 1 score cp 0 wdl 0 0 0 nodes 3217 nps 3217000 hashfull 16 tbhits 0 time 1 pv g8d8"
    private val l2 =
        "info depth 24 seldepth 6 multipv 1 score cp 0 wdl 0 0 0 nodes 674738 nps 836106 hashfull 4 tbhits 0 time 807 pv a0a1 g6h6 h4g4 h6g6 g4h4"
    private val l3 =
        "info depth 21 seldepth 10 multipv 1 score cp 0 wdl 0 0 0 nodes 676709 nps 834413 hashfull 3 tbhits 0 time 811 pv h1h4 c5e7 f0e1 c6c5 i3i4 c5b5 e2c4 b5c5 c4e2"
    private val l4 =
        "info depth 23 seldepth 30 multipv 1 score cp 0 wdl 0 0 0 nodes 742273 nps 926682 hashfull 7 tbhits 0 time 801 pv i6i5 e5e3 i5a5 a6a3 a5f5 f9e9 i4i5 e8d7 i9i7 d7"

    @Test
    fun `解析四条实测行`() {
        val i1 = EngineInfoPick.parseInfoLine(l1)!!
        assertEquals(40, i1.depth)
        assertEquals(2, i1.seldepth)
        assertEquals(3217L, i1.nodes)
        assertEquals(1, i1.timeMs)
        assertEquals("g8d8", i1.pvFirst)
        assertFalse(i1.hasBound)

        val i2 = EngineInfoPick.parseInfoLine(l2)!!
        assertEquals(24, i2.depth)
        assertEquals(6, i2.seldepth)
        assertEquals(807, i2.timeMs)

        val i4 = EngineInfoPick.parseInfoLine(l4)!!
        assertEquals(23, i4.depth)
        assertEquals(30, i4.seldepth)
    }

    // ---------- pickFinalInfo（R4：不做任何可信度判断） ----------

    @Test
    fun `取depth最深行而非最后一行`() {
        // 最后一行是 upperbound（depth 19），前一行精确分（depth 18）→ 新口径直接取 19
        val lines = listOf(
            "info depth 18 seldepth 20 score cp 35 nodes 500000 time 700 pv a0a1",
            "info depth 19 seldepth 21 score cp 90 upperbound nodes 520000 time 750 pv a0a1",
        )
        val picked = EngineInfoPick.pickFinalInfo(lines)!!
        assertEquals(19, picked.depth)
        assertEquals(90, picked.scoreCp)
    }

    @Test
    fun `全bound行也直接取最深`() {
        val lines = listOf(
            "info depth 17 seldepth 18 score cp -20 lowerbound nodes 400000 time 650 pv b0b2",
            "info depth 19 seldepth 20 score cp 90 upperbound nodes 520000 time 750 pv a0a1",
        )
        val picked = EngineInfoPick.pickFinalInfo(lines)!!
        assertEquals(19, picked.depth)
        assertTrue(picked.hasBound)
    }

    @Test
    fun `不再滤伪影直接取最深`() {
        // depth 40 time 1 的「搜索初期行」不再被剔除 → 取 40（l1）
        val picked = EngineInfoPick.pickFinalInfo(listOf(l1, l3))!!
        assertEquals(40, picked.depth)
    }

    @Test
    fun `bound行不参与选取但可被选中`() {
        // bound 行可被选中（depth 最高者胜出）；hasBound 仅用于 buildResult 层不外泄 matePly
        val lines = listOf(
            "info depth 18 seldepth 20 score cp 35 nodes 500000 time 700 pv a0a1",
            "info depth 19 seldepth 21 score cp 90 upperbound nodes 520000 time 750 pv a0a1",
        )
        val picked = EngineInfoPick.pickFinalInfo(lines)!!
        assertEquals(19, picked.depth)
        assertTrue(picked.hasBound)
    }

    @Test
    fun `无有效行返回null`() {
        assertNull(EngineInfoPick.pickFinalInfo(listOf("bestmove a0a1")))
        assertNull(
            EngineInfoPick.pickFinalInfo(
                listOf("info depth 12 currmove h2e2 currmovenumber 1")
            )
        )
    }

    @Test
    fun `depth并列取更晚者`() {
        val lines = listOf(
            "info depth 21 seldepth 22 score cp 10 nodes 600000 time 700 pv a0a1",
            "info depth 21 seldepth 23 score cp 12 nodes 610000 time 750 pv b0b2",
        )
        assertEquals(12, EngineInfoPick.pickFinalInfo(lines)!!.scoreCp)
    }

    // ---------- 丢弃规则（multipv/currmove/string） ----------

    @Test
    fun `multipv非1与进度行与string行丢弃`() {
        assertNull(
            EngineInfoPick.parseInfoLine(
                "info depth 20 seldepth 20 multipv 2 score cp 5 nodes 1 time 100 pv b0b2"
            )
        )
        assertNull(EngineInfoPick.parseInfoLine("info depth 12 currmove h2e2 currmovenumber 1"))
        assertNull(EngineInfoPick.parseInfoLine("info string nnue loaded"))
        assertNull(EngineInfoPick.parseInfoLine("bestmove a0a1"))
    }

    // ---------- 硬顶（R6：TARGET + 1000ms） ----------

    @Test
    fun `硬顶为TARGET加1000`() {
        assertEquals(1200, EngineInfoPick.hardCapMs(200))
        assertEquals(1800, EngineInfoPick.hardCapMs(800))
        assertEquals(4000, EngineInfoPick.hardCapMs(3000))
        assertEquals(7000, EngineInfoPick.hardCapMs(6000))
        assertEquals(21_000, EngineInfoPick.hardCapMs(20_000))
    }

    @Test
    fun `硬顶短时限不截断`() {
        // 最小思考时间 400ms 仍留满 1s 余量（旧分段倍率会把它压到 1200ms，长时限则被 5s 上限截断）
        assertEquals(1400, EngineInfoPick.hardCapMs(400))
    }

    // ---------- 解析完整字段 ----------

    @Test
    fun `mate分数换算与tbhits保留`() {
        val mateLine = EngineInfoPick.parseInfoLine(
            "info depth 8 seldepth 9 score mate 2 nodes 5000 time 300 tbhits 3 pv a0a1"
        )!!
        assertEquals(2, mateLine.matePly)
        assertEquals(100000 - 2, mateLine.scoreCp)
        assertEquals(3L, mateLine.tbHits)

        val negMate = EngineInfoPick.parseInfoLine(
            "info depth 8 seldepth 9 score mate -3 nodes 5000 time 300 pv a0a1"
        )!!
        assertEquals(-3, negMate.matePly)
        assertEquals(-99997, negMate.scoreCp) // 对齐旧 parseScore 口径：-100000-(-3)
    }
}
