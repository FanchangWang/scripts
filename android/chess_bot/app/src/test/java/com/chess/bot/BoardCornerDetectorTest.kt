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
}
