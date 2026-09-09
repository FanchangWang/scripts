package com.chess.bot

import com.chess.bot.game.Const
import com.chess.bot.game.OcrRoi
import com.chess.bot.vision.CropRect
import com.chess.bot.vision.TextHit
import com.chess.bot.vision.TextMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TextMatcher 纯函数测试（2026-09-06 T7：OCR 词匹配语义——包含匹配、选词优先级、和棋三词判定）。 */
class TextMatcherTest {

    private fun hit(word: String, x: Int = 0, y: Int = 0, score: Double = 0.9) =
        TextHit(word, x, y, score)

    // ---------- matchScanWords：结算扫描选词 ----------

    @Test
    fun `包含匹配——带装饰或后缀的框文本命中关键词`() {
        // 行内同时有遮罩词与按钮词：按优先级返回遮罩词「段位提升」
        val lines = listOf(hit("再来一局(3/5)"), hit("·段位提升·"))
        val r = TextMatcher.matchScanWords(lines, Const.GAMEOVER_BACK_WORDS, Const.GAMEOVER_BUTTON_WORDS)
        assertEquals("段位提升", r?.word)
    }

    @Test
    fun `包含匹配——仅按钮词时框文本带后缀也命中`() {
        val lines = listOf(hit("再来一局(3/5)"))
        val r = TextMatcher.matchScanWords(lines, Const.GAMEOVER_BACK_WORDS, Const.GAMEOVER_BUTTON_WORDS)
        assertEquals("再来一局", r?.word)
    }

    @Test
    fun `遮罩词表优先于按钮词表`() {
        val lines = listOf(hit("下一关"), hit("段位提升"))
        val r = TextMatcher.matchScanWords(lines, Const.GAMEOVER_BACK_WORDS, Const.GAMEOVER_BUTTON_WORDS)
        assertEquals("段位提升", r?.word)
    }

    @Test
    fun `中断词表优先于遮罩与按钮词表`() {
        // 终止类词（如「体力获取」弹窗）命中即自动中断对弈，优先级最高
        val lines = listOf(hit("再来一局"), hit("·结算奖励·"), hit("·体力获取·"))
        val r = TextMatcher.matchScanWords(
            lines, Const.GAMEOVER_BACK_WORDS, Const.GAMEOVER_BUTTON_WORDS, Const.GAMEOVER_INTERRUPT_WORDS
        )
        assertEquals("体力获取", r?.word)
    }

    @Test
    fun `无中断词时行为与旧版一致`() {
        val lines = listOf(hit("再来一局(3/5)"))
        val r = TextMatcher.matchScanWords(lines, Const.GAMEOVER_BACK_WORDS, Const.GAMEOVER_BUTTON_WORDS)
        assertEquals("再来一局", r?.word)
    }

    @Test
    fun `同类内按列表顺序——下一关优先于再来一局`() {
        val lines = listOf(hit("再来一局"), hit("下一关"))
        val r = TextMatcher.matchScanWords(lines, Const.GAMEOVER_BACK_WORDS, Const.GAMEOVER_BUTTON_WORDS)
        assertEquals("下一关", r?.word)
    }

    @Test
    fun `命中词的坐标与置信度保留自原文本行`() {
        val lines = listOf(hit("晋级赛", x = 540, y = 1600, score = 0.97))
        val r = TextMatcher.matchScanWords(lines, Const.GAMEOVER_BACK_WORDS, Const.GAMEOVER_BUTTON_WORDS)
        assertEquals(540, r?.x)
        assertEquals(1600, r?.y)
        assertEquals(0.97, r?.score!!, 1e-9)
    }

    @Test
    fun `无词命中返回null`() {
        val lines = listOf(hit("对方请求和棋"), hit("铜钱+100"))
        assertNull(
            TextMatcher.matchScanWords(lines, listOf("段位提升"), listOf("下一关", "再来一局"))
        )
    }

    @Test
    fun `空行列表返回null`() {
        assertNull(
            TextMatcher.matchScanWords(emptyList(), Const.GAMEOVER_BACK_WORDS, Const.GAMEOVER_BUTTON_WORDS)
        )
    }

    // ---------- matchDrawDialog：和棋三词同现判定 ----------

    @Test
    fun `三词同现返回同意与拒绝两按钮`() {
        val lines = listOf(hit("对方请求和棋", 540, 400), hit("拒绝", 400, 800), hit("同意", 680, 800))
        val buttons = TextMatcher.matchDrawDialog(lines)
        assertEquals(2, buttons.size)
        assertEquals(Const.DRAW_ACCEPT_WORD, buttons[0].word)
        assertEquals(Const.DRAW_REJECT_WORD, buttons[1].word)
        assertEquals(680, buttons[0].x)
    }

    @Test
    fun `缺标题词不算和棋页面`() {
        val lines = listOf(hit("拒绝"), hit("同意"))
        assertTrue(TextMatcher.matchDrawDialog(lines).isEmpty())
    }

    @Test
    fun `缺同意或拒绝任一按钮不算和棋页面`() {
        assertTrue(TextMatcher.matchDrawDialog(listOf(hit("对方请求和棋"), hit("同意"))).isEmpty())
        assertTrue(TextMatcher.matchDrawDialog(listOf(hit("对方请求和棋"), hit("拒绝"))).isEmpty())
        assertTrue(TextMatcher.matchDrawDialog(listOf(hit("对方请求和棋"))).isEmpty())
    }

    @Test
    fun `空行列表不算和棋页面`() {
        assertTrue(TextMatcher.matchDrawDialog(emptyList()).isEmpty())
    }

    // ---------- ROI：并集 / 像素换算 / 坐标回映射 ----------

    @Test
    fun `unionRois——多个ROI求外包矩形`() {
        val u = TextMatcher.unionRois(
            listOf(OcrRoi(0.1f, 0.2f, 0.5f, 0.4f), OcrRoi(0.3f, 0.5f, 0.9f, 0.8f))
        )
        assertEquals(0.1f, u?.x1!!)
        assertEquals(0.2f, u.y1)
        assertEquals(0.9f, u.x2)
        assertEquals(0.8f, u.y2)
    }

    @Test
    fun `unionRois——空列表返回null表示全图查找`() {
        assertNull(TextMatcher.unionRois(emptyList()))
    }

    @Test
    fun `词表无ROI配置时mapNotNull过滤为空并集为null`() {
        // 兜底语义：词表里没有任何词配置 ROI → 全图查找
        val rois = listOf("未知词A", "未知词B").mapNotNull { Const.OCR_WORD_ROIS[it] }
        assertNull(TextMatcher.unionRois(rois))
    }

    @Test
    fun `词表有部分词配置ROI时只union已配置的`() {
        val rois = listOf("对方请求和棋", "下一关", "未知词").mapNotNull { Const.OCR_WORD_ROIS[it] }
        // 期望值从 Const 动态取（ROI 值由用户校准维护，测试只验证并集语义，不绑定具体数值）
        val u = TextMatcher.unionRois(rois)
        assertEquals(rois.minOf { it.x1 }, u?.x1!!)
        assertEquals(rois.minOf { it.y1 }, u.y1)
        assertEquals(rois.maxOf { it.x2 }, u.x2)
        assertEquals(rois.maxOf { it.y2 }, u.y2)
    }

    @Test
    fun `cropRectOf——百分率换算像素并收紧边界`() {
        val r = TextMatcher.cropRectOf(OcrRoi(0.1f, 0.4f, 0.7f, 0.6f), 1080, 2400)
        assertEquals(108, r.x)
        assertEquals(960, r.y)
        assertEquals(756 - 108, r.width)  // 0.7*1080=756
        assertEquals(1440 - 960, r.height) // 0.6*2400=1440
    }

    @Test
    fun `cropRectOf——越界值收敛到图像边界且宽高至少1px`() {
        val r = TextMatcher.cropRectOf(OcrRoi(-0.5f, -0.5f, 2.0f, 2.0f), 1080, 2400)
        assertEquals(0, r.x)
        assertEquals(0, r.y)
        assertEquals(1080, r.width)
        assertEquals(2400, r.height)
        // 退化 ROI（x1≥x2）：仍保证宽高 ≥1
        val deg = TextMatcher.cropRectOf(OcrRoi(0.5f, 0.5f, 0.5f, 0.5f), 1080, 2400)
        assertEquals(540, deg.x)
        assertEquals(1200, deg.y)
        assertEquals(1, deg.width)
        assertEquals(1, deg.height)
    }

    @Test
    fun `mapHitsToFull——裁剪坐标系命中加回偏移`() {
        val crop = CropRect(108, 960, 648, 480)
        val mapped = TextMatcher.mapHitsToFull(listOf(hit("下一关", x = 432, y = 240)), crop)
        assertEquals(108 + 432, mapped[0].x)
        assertEquals(960 + 240, mapped[0].y)
        assertEquals("下一关", mapped[0].word)
    }

    @Test
    fun `OCR_WORD_ROIS覆盖全部扫描词`() {
        // 词表里所有词都应配置 ROI（漏配的词回落全图，失去裁剪收益；此处强制对齐防遗漏）
        val words = Const.GAMEOVER_INTERRUPT_WORDS + Const.GAMEOVER_BUTTON_WORDS +
            Const.GAMEOVER_BACK_WORDS +
            listOf(Const.DRAW_REQUEST_WORD, Const.DRAW_ACCEPT_WORD, Const.DRAW_REJECT_WORD)
        words.forEach { w ->
            assertTrue("词「$w」缺少 OCR_WORD_ROIS 配置", Const.OCR_WORD_ROIS.containsKey(w))
        }
    }
}
