package com.chess.bot.vision

import com.chess.bot.game.COLS
import com.chess.bot.game.Const
import com.chess.bot.game.ROWS
import com.chess.bot.game.correctedCenter
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * 透视矫正单应：源分辨率四角格中心 -> 矫正空间对应格中心（移植 python vision.homography）。
 * 带缓存；tap 用逆矩阵把矫正格心映射回屏幕坐标。
 */
object Homography {

    private val cache = mutableMapOf<Pair<Int, Int>, Mat>()

    fun get(width: Int, height: Int): Mat {
        val key = width to height
        cache[key]?.let { return it }
        val corners = Const.BOARD_CORNERS[key]
            ?: throw IllegalArgumentException("未配置 ${width}x${height} 分辨率的棋盘四角坐标")
        val src = MatOfPoint2f(
            Point(corners[0].first, corners[0].second),
            Point(corners[1].first, corners[1].second),
            Point(corners[2].first, corners[2].second),
            Point(corners[3].first, corners[3].second),
        )
        val (x0, y0) = correctedCenter(0, 0)
        val (x1, y1) = correctedCenter(0, COLS - 1)
        val (x2, y2) = correctedCenter(ROWS - 1, 0)
        val (x3, y3) = correctedCenter(ROWS - 1, COLS - 1)
        val dst = MatOfPoint2f(Point(x0, y0), Point(x1, y1), Point(x2, y2), Point(x3, y3))
        val h = Imgproc.getPerspectiveTransform(src, dst)
        src.release()
        dst.release()
        synchronized(cache) { cache[key] = h }
        return h
    }

    /** 矫正空间点 -> 源截图屏幕坐标（逆透视映射）。 */
    fun sourcePoint(h: Mat, x: Double, y: Double): Pair<Double, Double> {
        val inv = Mat()
        Core.invert(h, inv, Core.DECOMP_SVD)
        val w = inv.get(2, 0)[0] * x + inv.get(2, 1)[0] * y + inv.get(2, 2)[0]
        val sx = (inv.get(0, 0)[0] * x + inv.get(0, 1)[0] * y + inv.get(0, 2)[0]) / w
        val sy = (inv.get(1, 0)[0] * x + inv.get(1, 1)[0] * y + inv.get(1, 2)[0]) / w
        inv.release()
        return sx to sy
    }

    /** 网格 -> 源截图屏幕坐标（取整）。对应 python vision.tap_xy。 */
    fun tapXy(h: Mat, r: Int, c: Int): Pair<Int, Int> {
        val (cx, cy) = correctedCenter(r, c)
        val (sx, sy) = sourcePoint(h, cx, cy)
        return sx.roundToInt() to sy.roundToInt()
    }
}
