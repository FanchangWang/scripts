package com.chess.bot.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * BoardGeometryGuard.maxDeviation 纯函数测试：
 * 摆棋接受门几何守卫的四角偏差计算（同序 TL,TR,BL,BR）。
 */
class GeometryGuardTest {

    private val calibrated = listOf(
        100.0 to 100.0, // TL
        900.0 to 100.0, // TR
        100.0 to 700.0, // BL
        900.0 to 700.0, // BR
    )

    @Test
    fun `完全一致偏差为0`() {
        assertEquals(0.0, BoardGeometryGuard.maxDeviation(calibrated, calibrated), 1e-9)
    }

    @Test
    fun `微小平移在容差内`() {
        val shifted = calibrated.map { (x, y) -> (x + 3.0) to (y + 4.0) } // 每点欧氏距离 5px
        assertEquals(5.0, BoardGeometryGuard.maxDeviation(shifted, calibrated), 1e-9)
    }

    @Test
    fun `缩小到80百分之居中棋盘偏差远超容差`() {
        // 以棋盘中心 (500,400) 为基准缩放 0.8：各角向中心收 20% 半径
        val cx = 500.0
        val cy = 400.0
        val shrunk = calibrated.map { (x, y) -> cx + (x - cx) * 0.8 to cy + (y - cy) * 0.8 }
        // TL: 半径向量 (-400,-300)*0.2 -> 距离 100px
        assertEquals(100.0, BoardGeometryGuard.maxDeviation(shrunk, calibrated), 1e-9)
    }

    @Test
    fun `单角偏移取最大值`() {
        val detected = calibrated.toMutableList()
        detected[3] = 910.0 to 700.0 // 仅 BR 偏 10px
        assertEquals(10.0, BoardGeometryGuard.maxDeviation(detected, calibrated), 1e-9)
    }

    @Test
    fun `点数不是4抛异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            BoardGeometryGuard.maxDeviation(calibrated, calibrated.take(3))
        }
    }
}
