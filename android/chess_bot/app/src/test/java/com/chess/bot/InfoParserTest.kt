package com.chess.bot

import com.chess.bot.engine.EngineInfo
import com.chess.bot.engine.EngineInfoPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎 info 解析 / 伪影判定 / 质量门控 / 最终 info 选取 / 硬顶 纯函数测试。
 * 用户四条实测日志（2026-09-08）作为回归用例。
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

    // ---------- 伪影双规则（方案 §3.3） ----------

    @Test
    fun `A1规则丢弃搜索初期伪影`() {
        // 第一条实测行：time=1 < max(100, 800/10) → 伪影
        val i1 = EngineInfoPick.parseInfoLine(l1)!!
        assertTrue(EngineInfoPick.isArtifact(i1, 800))
    }

    @Test
    fun `A2规则区分TT注水与正常TT截断`() {
        // 用 time 充分的合成行隔离 A2 语义（排除 A1 干扰）：
        // 40/2 差 38>15 且 nodes=3217<10000 → 伪影；24/6 差 18>15 但 nodes=67 万 → 合法
        val artifact = EngineInfoPick.parseInfoLine(
            "info depth 40 seldepth 2 score cp 0 nodes 3217 time 900 pv g8d8"
        )!!
        assertTrue(EngineInfoPick.isArtifact(artifact, 800))
        val i2 = EngineInfoPick.parseInfoLine(l2)!!
        assertFalse(EngineInfoPick.isArtifact(i2, 800))
    }

    @Test
    fun `A2前置depth下限生效`() {
        // depth=12 < 20：即使 depth-seldepth 差大、nodes 小也不判伪影（低深度下深度差参考价值低）
        val low = EngineInfoPick.parseInfoLine(
            "info depth 12 seldepth 1 score cp 5 nodes 500 time 900 pv h2e2"
        )!!
        assertFalse(EngineInfoPick.isArtifact(low, 1000))
    }

    @Test
    fun `A1动态阈值适配快棋`() {
        // TARGET=200ms → 阈值 max(100, 20)=100；TARGET=3000 → 300
        assertEquals(100, EngineInfoPick.minValidInfoMs(200))
        assertEquals(300, EngineInfoPick.minValidInfoMs(3000))
        assertEquals(100, EngineInfoPick.minValidInfoMs(0))
    }

    // ---------- 质量门控（方案 §3.2 联合判定） ----------

    private fun info(
        depth: Int = 22, seldepth: Int = 25, nodes: Long = 600_000, timeMs: Int = 900,
        hasBound: Boolean = false, matePly: Int? = null,
    ) = EngineInfo(depth, seldepth, 0, matePly, nodes, timeMs, hasBound, "a0a1")

    @Test
    fun `联合门控全条件满足才达标`() {
        assertTrue(EngineInfoPick.qualityOk(info(), 800))
        assertFalse(EngineInfoPick.qualityOk(info(seldepth = 9), 800)) // seldepth 不足
        assertFalse(EngineInfoPick.qualityOk(info(depth = 7), 800)) // depth 不足
        assertFalse(EngineInfoPick.qualityOk(info(nodes = 9_999), 800)) // nodes 不足
        assertFalse(EngineInfoPick.qualityOk(info(timeMs = 50), 800)) // time 不足
        assertFalse(EngineInfoPick.qualityOk(info(hasBound = true), 800)) // bound 行分数不可靠
    }

    @Test
    fun `精确mate直接达标而bound mate不享受例外`() {
        assertTrue(EngineInfoPick.qualityOk(info(matePly = 5), 800))
        assertFalse(EngineInfoPick.qualityOk(info(matePly = 5, hasBound = true), 800))
    }

    @Test
    fun `短时限depth门槛下调`() {
        // TARGET=200 < 500 → depth 门槛 6；TARGET=800 → 8
        assertTrue(EngineInfoPick.qualityOk(info(depth = 6, seldepth = 10, timeMs = 150), 200))
        assertFalse(EngineInfoPick.qualityOk(info(depth = 6, seldepth = 10, timeMs = 150), 800))
    }

    // ---------- 近杀提前停（v3 R13：仅精确 mate） ----------

    @Test
    fun `近杀提前停含被将死方向但排除bound`() {
        // 方案 §3.1 语义：|N| ≤ 3（被将死在即同样无需续算，继续搜也改变不了防守着法）
        assertTrue(EngineInfoPick.nearMate(info(matePly = 3)))
        assertTrue(EngineInfoPick.nearMate(info(matePly = -2)))
        assertFalse(EngineInfoPick.nearMate(info(matePly = 3, hasBound = true))) // lowerbound mate
        assertFalse(EngineInfoPick.nearMate(info(matePly = 5))) // 超阈值
        assertFalse(EngineInfoPick.nearMate(null))
    }

    // ---------- pickFinalInfo（方案 §3.4 / R4） ----------

    @Test
    fun `取最深无bound行而非最后一行`() {
        // 最后一行是 upperbound（depth 19），前一行是精确分（depth 18）→ 取 18
        val lines = listOf(
            "info depth 18 seldepth 20 score cp 35 nodes 500000 time 700 pv a0a1",
            "info depth 19 seldepth 21 score cp 90 upperbound nodes 520000 time 750 pv a0a1",
        )
        val picked = EngineInfoPick.pickFinalInfo(lines, 800)!!
        assertEquals(18, picked.info.depth)
        assertFalse(picked.scoreUnreliable)
        assertEquals(35, picked.info.scoreCp)
    }

    @Test
    fun `全bound回退并标记不可靠`() {
        val lines = listOf(
            "info depth 17 seldepth 18 score cp -20 lowerbound nodes 400000 time 650 pv b0b2",
            "info depth 19 seldepth 20 score cp 90 upperbound nodes 520000 time 750 pv a0a1",
        )
        val picked = EngineInfoPick.pickFinalInfo(lines, 800)!!
        assertEquals(19, picked.info.depth)
        assertTrue(picked.scoreUnreliable)
    }

    @Test
    fun `伪影行不进入最终选取`() {
        // depth 40 time 1 伪影行与 depth 30 正常行并存 → 取 30
        val lines = listOf(l1, l3)
        val picked = EngineInfoPick.pickFinalInfo(lines, 800)!!
        assertEquals(21, picked.info.depth)
    }

    @Test
    fun `无有效行返回null`() {
        assertNull(EngineInfoPick.pickFinalInfo(listOf("bestmove a0a1"), 800))
        assertNull(
            EngineInfoPick.pickFinalInfo(
                listOf("info depth 12 currmove h2e2 currmovenumber 1"), 800
            )
        )
    }

    @Test
    fun `depth并列取更晚者`() {
        val lines = listOf(
            "info depth 21 seldepth 22 score cp 10 nodes 600000 time 700 pv a0a1",
            "info depth 21 seldepth 23 score cp 12 nodes 610000 time 750 pv b0b2",
        )
        assertEquals(12, EngineInfoPick.pickFinalInfo(lines, 800)!!.info.scoreCp)
    }

    // ---------- 丢弃规则（R9/multipv/currmove/string） ----------

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

    // ---------- 硬顶（方案 §3.5 分段 + 追加上限） ----------

    @Test
    fun `硬顶分段与追加上限`() {
        assertEquals(600, EngineInfoPick.hardCapMs(200)) // ≤1s ×3
        assertEquals(2400, EngineInfoPick.hardCapMs(800)) // ≤1s ×3，追加上限 5800 不触发
        assertEquals(6000, EngineInfoPick.hardCapMs(3000)) // 1–5s ×2
        assertEquals(9000, EngineInfoPick.hardCapMs(6000)) // >5s ×1.5=9000，追加上限 11000 不触发
        assertEquals(25_000, EngineInfoPick.hardCapMs(20_000)) // 20s×1.5=30s → clamp 到 20000+5000
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
