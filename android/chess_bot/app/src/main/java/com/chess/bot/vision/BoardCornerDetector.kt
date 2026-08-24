package com.chess.bot.vision

import android.graphics.Bitmap
import com.chess.bot.game.COLS
import com.chess.bot.game.Const
import com.chess.bot.game.ROWS
import com.chess.bot.game.Side
import com.chess.bot.game.fullStartBoard
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * 棋盘四角自动识别：移植 python scripts/detect_board_corners.py。
 * 复用 VisionInit.loadPieceTemplates 的 b_r(黑車)/r_R(红俥) 角子模板（灰度）。
 *
 * 几何整理函数（排序/分边/全局NMS/尺度估计）为纯函数，便于单测；
 * 其余依赖 OpenCV 的匹配/矫正逻辑仅运行时在设备侧执行。
 */
object BoardCornerDetector {

    // 角子模板：黑車在上方两角，红俥在下方两角（玩家执红，黑上红下）
    private val CORNER_TEMPLATE_IDS = listOf("b_r", "r_R")
    private const val DEFAULT_MATCH_THRESHOLD = 0.55
    private const val FALLBACK_MATCH_THRESHOLD = 0.45
    private const val SCALE_RANGE_FACTOR = 0.30
    private const val MIN_SCALE = 0.40
    private const val MAX_SCALE = 2.50

    /** 单峰：(score, cx, cy, scale) */
    data class Peak(val score: Double, val cx: Double, val cy: Double, val scale: Double)

    /** 识别结果：四角按 [左上, 右上, 左下, 右下] 排序，与 Const.BOARD_CORNERS 顺序一致。 */
    data class Result(
        val corners: List<Pair<Double, Double>>,
        val scores: List<Double>,
        val matched: Int,
    )

    /** 依据截图宽度估计角子模板尺度（对齐 python _estimate_scale：img_width / 1000）。 */
    fun estimateScale(imgWidth: Int): Double = imgWidth / 1000.0

    /** 全局非极大值抑制：跨尺度去重，minDistance 像素内只保留最高分峰。 */
    fun nonMaxSuppression(peaks: List<Peak>, minDistance: Int): List<Peak> {
        val kept = mutableListOf<Peak>()
        for (p in peaks.sortedByDescending { it.score }) {
            val tooClose = kept.any { k ->
                kotlin.math.abs(p.cx - k.cx) < minDistance && kotlin.math.abs(p.cy - k.cy) < minDistance
            }
            if (!tooClose) kept.add(p)
        }
        return kept
    }

    /** 把 4 个角子中心按 y 分上下、再按 x 分左右，排成 [左上, 右上, 左下, 右下]。 */
    fun orderCorners(centers: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        require(centers.size == 4) { "需要恰好 4 个角子中心，实际 ${centers.size}" }
        val byY = centers.sortedBy { it.second }
        val top = byY.take(2).sortedBy { it.first }
        val bottom = byY.takeLast(2).sortedBy { it.first }
        return listOf(top[0], top[1], bottom[0], bottom[1])
    }

    /** Bitmap -> 灰度 Mat（确保 OpenCV 已 init）。 */
    fun toGray(bitmap: Bitmap): Mat {
        val bgr = VisionInit.bitmapToBgr(bitmap)
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        bgr.release()
        return gray
    }

    /** 任意模板转单通道灰度：角点检测在灰度图上做 matchTemplate，需与截图灰度类型一致。 */
    private fun toGrayMat(src: Mat): Mat {
        if (src.channels() == 1) return src
        val g = Mat()
        Imgproc.cvtColor(src, g, Imgproc.COLOR_BGR2GRAY)
        return g
    }

    /** 多尺度模板匹配（对齐 python：局部极大 + 跨尺度全局 NMS）。 */
    private fun findPeaks(
        gray: Mat,
        tmpl: Mat,
        scaleMin: Double,
        scaleMax: Double,
        nScales: Int,
        threshold: Double,
        minDistance: Int,
    ): List<Peak> {
        val h = gray.rows()
        val w = gray.cols()
        // 模板统一转灰度：截图是 CV_8UC1，模板是 BGR(CV_8UC3)，matchTemplate 要求类型一致
        val tmplGray = toGrayMat(tmpl)
        val th0 = tmplGray.rows()
        val tw0 = tmplGray.cols()
        val all = mutableListOf<Peak>()
        val kernel = Mat.ones(minDistance.coerceAtLeast(1), minDistance.coerceAtLeast(1), CvType.CV_8U)
        for (i in 0 until nScales) {
            val scale =
                if (nScales <= 1) scaleMin else scaleMin + (scaleMax - scaleMin) * i / (nScales - 1)
            val nw = maxOf(1, (tw0 * scale).roundToInt())
            val nh = maxOf(1, (th0 * scale).roundToInt())
            if (nw >= w || nh >= h) continue
            val resized = Mat()
            Imgproc.resize(
                tmplGray,
                resized,
                Size(nw.toDouble(), nh.toDouble()),
                0.0,
                0.0,
                if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR,
            )
            val result = Mat()
            Imgproc.matchTemplate(gray, resized, result, Imgproc.TM_CCOEFF_NORMED)
            val mask = Mat()
            Core.inRange(result, Scalar(threshold), Scalar(Double.MAX_VALUE), mask)
            val dilated = Mat()
            Imgproc.dilate(result, dilated, kernel)
            val eq = Mat()
            Core.compare(result, dilated, eq, Core.CMP_EQ)
            val finalMask = Mat()
            Core.bitwise_and(mask, eq, finalMask)
            val pts = MatOfPoint()
            Core.findNonZero(finalMask, pts)
            for (p in pts.toArray()) {
                val cx = p.x.roundToInt()
                val cy = p.y.roundToInt()
                val v = result.get(cy, cx)?.get(0) ?: 0.0
                all.add(Peak(v, p.x + nw / 2.0, p.y + nh / 2.0, scale))
            }
            result.release()
            mask.release()
            dilated.release()
            eq.release()
            finalMask.release()
            pts.release()
            resized.release()
        }
        kernel.release()
        if (tmplGray !== tmpl) tmplGray.release()
        return nonMaxSuppression(all, minDistance)
    }

    /** 检测一侧角子：先窄尺度，不足 2 个峰则扩大尺度回退。 */
    private fun detectSide(gray: Mat, tmpl: Mat, baseScale: Double, label: String): List<Peak> {
        val scaleMin = maxOf(MIN_SCALE, baseScale * (1 - SCALE_RANGE_FACTOR))
        val scaleMax = minOf(MAX_SCALE, baseScale * (1 + SCALE_RANGE_FACTOR))
        val nScales = maxOf(15, ((scaleMax - scaleMin) / 0.03).roundToInt() + 1)
        val minDistance = maxOf(10, (baseScale * 40).roundToInt())
        var peaks = findPeaks(gray, tmpl, scaleMin, scaleMax, nScales, DEFAULT_MATCH_THRESHOLD, minDistance)
        if (peaks.size < 2) {
            LogBus.log(LogKind.DEBUG, LogTag.CALIB, "$label 窄范围仅 ${peaks.size} 个峰，扩大尺度重试")
            peaks = findPeaks(gray, tmpl, MIN_SCALE, MAX_SCALE, 45, FALLBACK_MATCH_THRESHOLD, minDistance)
        }
        return peaks
    }

    /**
     * 主入口：灰度图 + 已加载的棋子模板 -> 四角坐标（屏幕像素，[左上, 右上, 左下, 右下]）。
     * 任一侧不足 2 个峰抛异常（调用方据此重试 / 引导手动微调）。
     */
    fun detect(gray: Mat, templates: Map<String, Mat>): Result {
        val bTmpl = templates[CORNER_TEMPLATE_IDS[0]]
            ?: throw IllegalStateException("缺少角子模板 ${CORNER_TEMPLATE_IDS[0]}")
        val rTmpl = templates[CORNER_TEMPLATE_IDS[1]]
            ?: throw IllegalStateException("缺少角子模板 ${CORNER_TEMPLATE_IDS[1]}")
        val baseScale = estimateScale(gray.cols())
        val bPeaks = detectSide(gray, bTmpl, baseScale, "黑車")
        val rPeaks = detectSide(gray, rTmpl, baseScale, "红俥")
        if (bPeaks.size < 2) {
            throw IllegalStateException("黑車(${CORNER_TEMPLATE_IDS[0]}) 仅匹配 ${bPeaks.size} 个，无法定位上方两角")
        }
        if (rPeaks.size < 2) {
            throw IllegalStateException("红俥(${CORNER_TEMPLATE_IDS[1]}) 仅匹配 ${rPeaks.size} 个，无法定位下方两角")
        }
        val centers = (bPeaks.take(2) + rPeaks.take(2)).map { it.cx to it.cy }
        val corners = orderCorners(centers)
        val scores = (bPeaks.take(2) + rPeaks.take(2)).map { it.score }
        return Result(corners, scores, 4)
    }

    /**
     * 用四角构造临时单应，把截图矫正为 900x1000，全盘识别后比对 32 子开局。
     * 用于在保存前挡住坏坐标（不污染 Homography 缓存）。
     */
    fun validateAsOpening(
        bitmap: Bitmap,
        corners: List<Pair<Double, Double>>,
        templates: Map<String, Mat>,
    ): Boolean {
        if (corners.size != 4) return false
        val src = VisionInit.bitmapToBgr(bitmap)
        return try {
            val srcPts = MatOfPoint2f(
                Point(corners[0].first, corners[0].second),
                Point(corners[1].first, corners[1].second),
                Point(corners[2].first, corners[2].second),
                Point(corners[3].first, corners[3].second),
            )
            val dstPts = MatOfPoint2f(
                Point(correctedX(0), correctedY(0)),
                Point(correctedX(COLS - 1), correctedY(0)),
                Point(correctedX(0), correctedY(ROWS - 1)),
                Point(correctedX(COLS - 1), correctedY(ROWS - 1)),
            )
            val h = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            val corrected = Mat()
            Imgproc.warpPerspective(
                src,
                corrected,
                h,
                Size(Const.CORRECT_W.toDouble(), Const.CORRECT_H.toDouble()),
            )
            srcPts.release()
            dstPts.release()
            h.release()
            src.release()
            val detected = Recognizer.analyzeBoard(corrected, templates)
            corrected.release()
            detected.contentDeepEquals(fullStartBoard(Side.RED))
        } catch (e: Exception) {
            LogBus.log(LogKind.WARN, LogTag.CALIB, "开局校验异常：${e.message}")
            src.release()
            false
        }
    }

    // 矫正空间格心（格边长 100，中心 50+100*idx）
    private fun correctedX(c: Int) = Const.CORRECT_CELL * (c + 0.5)
    private fun correctedY(r: Int) = Const.CORRECT_CELL * (r + 0.5)
}
