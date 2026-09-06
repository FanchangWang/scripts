package com.chess.bot.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CornerDetModel.decode 纯函数单测：
 * 输出布局 1x8xN（ch0..3 框 cx,cy,w,h；ch4..7 类 conf，tl/tr/bl/br），
 * 每类 argmax 取框心，(cx-pad)/scale 映射回原图，任一类 conf 低于阈值判失败。
 */
class CornerDetPostprocessTest {

    private val n = 10

    private fun out(): FloatArray = FloatArray(8 * n)

    private fun put(out: FloatArray, ch: Int, a: Int, v: Float) {
        out[ch * n + a] = v
    }

    @Test
    fun decode_每类argmax并映射回原图坐标() {
        val out = out()
        // 干扰锚点：低置信
        put(out, 4, 0, 0.1f); put(out, 5, 2, 0.2f); put(out, 6, 4, 0.3f); put(out, 7, 6, 0.4f)
        // TL: anchor 3, 1280空间框心 (402,200) -> 原图 ((402-352)/0.5, 200/0.5) = (100,400)
        put(out, 0, 3, 402f); put(out, 1, 3, 200f); put(out, 4, 3, 0.9f)
        // TR: anchor 1, (902,210) -> (1100,420)
        put(out, 0, 1, 902f); put(out, 1, 1, 210f); put(out, 5, 1, 0.8f)
        // BL: anchor 5, (402,2200) -> y=4400 超出原图 2400 -> 钳制
        put(out, 0, 5, 402f); put(out, 1, 5, 2200f); put(out, 6, 5, 0.7f)
        // BR: anchor 7, (902,2190) -> x/y 均超出 -> 钳制
        put(out, 0, 7, 902f); put(out, 1, 7, 2190f); put(out, 7, 7, 0.6f)

        val res = CornerDetModel.decode(out, n, 0.001, 0.5, 352.0, 0.0, 1080, 2400)
        assertEquals(
            listOf(
                100.0 to 400.0,
                1080.0 to 420.0,
                100.0 to 2400.0,
                1080.0 to 2400.0,
            ),
            res,
        )
    }

    @Test
    fun decode_任一类无框返回null() {
        val out = out()
        // 仅 TL/TR 有置信，BL/BR 全 0（< 0.001）
        put(out, 0, 3, 402f); put(out, 1, 3, 200f); put(out, 4, 3, 0.9f)
        put(out, 0, 1, 902f); put(out, 1, 1, 210f); put(out, 5, 1, 0.8f)
        assertNull(CornerDetModel.decode(out, n, 0.001, 0.5, 0.0, 0.0, 1080, 2400))
    }

    @Test
    fun decode_置信低于极低阈值返回null() {
        val out = out()
        put(out, 0, 3, 402f); put(out, 1, 3, 200f); put(out, 4, 3, 0.0005f)
        assertNull(CornerDetModel.decode(out, n, 0.001, 0.5, 0.0, 0.0, 1080, 2400))
    }

    @Test
    fun decode_输出长度不足返回null() {
        assertNull(CornerDetModel.decode(FloatArray(8), n, 0.001, 0.5, 0.0, 0.0, 1080, 2400))
    }

    @Test
    fun toChw_HWC转平面布局() {
        // imgsz=2：像素0..3，HWC = [r0,g0,b0, r1,g1,b1, r2,g2,b2, r3,g3,b3]
        val pixels = floatArrayOf(
            0.0f, 0.1f, 0.2f,
            1.0f, 1.1f, 1.2f,
            2.0f, 2.1f, 2.2f,
            3.0f, 3.1f, 3.2f,
        )
        val chw = CornerDetModel.toChw(pixels, 2)
        // R 平面在前，其次 G、B；平面内按像素序
        assertEquals(
            floatArrayOf(0.0f, 1.0f, 2.0f, 3.0f, 0.1f, 1.1f, 2.1f, 3.1f, 0.2f, 1.2f, 2.2f, 3.2f).toList(),
            chw.toList(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun toChw_长度不符抛异常() {
        CornerDetModel.toChw(FloatArray(8), 2) // 应为 12
    }
}
