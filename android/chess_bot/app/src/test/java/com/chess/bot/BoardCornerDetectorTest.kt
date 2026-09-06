package com.chess.bot.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BoardCornerDetector 纯函数单测（移植 python detect_board_corners 的不变式）。
 * 仅覆盖不依赖 OpenCV 原生库的几何整理逻辑；匹配/矫正逻辑在真机侧验证。
 */
class BoardCornerDetectorTest {

    @Test
    fun estimateScale_isWidthOver1000() {
        assertEquals(1.08, BoardCornerDetector.estimateScale(1080), 1e-9)
        assertEquals(1.44, BoardCornerDetector.estimateScale(1440), 1e-9)
        assertEquals(2.40, BoardCornerDetector.estimateScale(2400), 1e-9)
    }

    @Test
    fun orderCorners_arrangesTL_TR_BL_BR() {
        // 乱序输入：右下、左下、右上、左上
        val centers = listOf(100.0 to 200.0, 10.0 to 200.0, 100.0 to 20.0, 10.0 to 20.0)
        val ordered = BoardCornerDetector.orderCorners(centers)
        assertEquals(
            listOf(10.0 to 20.0, 100.0 to 20.0, 10.0 to 200.0, 100.0 to 200.0),
            ordered,
        )
    }

    @Test
    fun orderCorners_throwsWhenNotFour() {
        assertThrows(IllegalArgumentException::class.java) {
            BoardCornerDetector.orderCorners(listOf(1.0 to 1.0))
        }
    }

    @Test
    fun nonMaxSuppression_keepsOnlyDistantPeaks() {
        val peaks = listOf(
            BoardCornerDetector.Peak(0.90, 10.0, 20.0, 1.0),
            BoardCornerDetector.Peak(0.80, 12.0, 22.0, 1.0), // 与首个过近，应被抑制
            BoardCornerDetector.Peak(0.70, 300.0, 400.0, 1.0),
        )
        val kept = BoardCornerDetector.nonMaxSuppression(peaks, 20)
        assertEquals(2, kept.size)
        assertTrue(kept.any { it.cx == 10.0 && it.cy == 20.0 })
        assertTrue(kept.any { it.cx == 300.0 && it.cy == 400.0 })
    }

    @Test
    fun nonMaxSuppression_prefersHigherScoreWithinDistance() {
        val peaks = listOf(
            BoardCornerDetector.Peak(0.60, 100.0, 100.0, 1.0),
            BoardCornerDetector.Peak(0.95, 105.0, 103.0, 1.0), // 近且分更高，应保留
        )
        val kept = BoardCornerDetector.nonMaxSuppression(peaks, 20)
        assertEquals(1, kept.size)
        assertEquals(0.95, kept[0].score, 1e-9)
    }

    // ---------- isPlausibleQuad：det 结果几何合理性（1080x2400） ----------

    private val W = 1080
    private val H = 2400

    @Test
    fun isPlausibleQuad_acceptsTypicalBoard() {
        // 典型开局四角（黑上红下）：横跨约 96% 宽、纵跨约 68% 高
        val quad = listOf(
            35.0 to 380.0, 1045.0 to 380.0,
            35.0 to 2020.0, 1045.0 to 2020.0,
        )
        assertTrue(BoardCornerDetector.isPlausibleQuad(quad, W, H))
    }

    @Test
    fun isPlausibleQuad_acceptsSlightlySkewedQuad() {
        // 轻微透视/偏移仍应通过
        val quad = listOf(
            50.0 to 400.0, 1030.0 to 370.0,
            40.0 to 2000.0, 1050.0 to 2030.0,
        )
        assertTrue(BoardCornerDetector.isPlausibleQuad(quad, W, H))
    }

    @Test
    fun isPlausibleQuad_rejectsClusteredGarbage() {
        // det 垃圾输出：四点聚在一起
        val quad = listOf(
            500.0 to 500.0, 510.0 to 505.0,
            495.0 to 515.0, 520.0 to 520.0,
        )
        assertTrue(!BoardCornerDetector.isPlausibleQuad(quad, W, H))
    }

    @Test
    fun isPlausibleQuad_rejectsTooNarrow() {
        // 横向跨度不足屏宽 50%（如只框住半边棋盘）
        val quad = listOf(
            400.0 to 380.0, 900.0 to 380.0,
            400.0 to 2020.0, 900.0 to 2020.0,
        )
        assertTrue(!BoardCornerDetector.isPlausibleQuad(quad, W, H))
    }

    @Test
    fun isPlausibleQuad_rejectsOutOfBounds() {
        // 角点远超屏幕范围
        val quad = listOf(
            35.0 to -500.0, 1045.0 to 380.0,
            35.0 to 2020.0, 1045.0 to 3000.0,
        )
        assertTrue(!BoardCornerDetector.isPlausibleQuad(quad, W, H))
    }

    @Test
    fun isPlausibleQuad_rejectsWrongCount() {
        assertTrue(!BoardCornerDetector.isPlausibleQuad(listOf(0.0 to 0.0), W, H))
    }
}
