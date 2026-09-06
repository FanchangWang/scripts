package com.chess.bot.vision

import android.content.Context
import android.graphics.Bitmap
import com.chess.bot.game.COLS
import com.chess.bot.game.Const
import com.chess.bot.game.ROWS
import com.chess.bot.game.Side
import com.chess.bot.game.fullStartBoard
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import com.chess.bot.vision.BoardCornerDetector.ROI_HALF
import com.chess.bot.vision.BoardCornerDetector.isPlausibleQuad
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * 棋盘四角自动识别（校准流程专用；运行时定位链不变，见 Homography/BoardCornersStore）。
 *
 * 2026-09-05 二次改造（det 先行）：
 * 1. **YOLO det 先行**：CornerDetModel 一次推理得四角；须通过 [isPlausibleQuad] 几何合理性
 *    校验（det 的 conf 与位置精度无关，argmax 几乎总有输出，垃圾结果靠几何规则拦截）。
 * 2. **ROI 模板精修**：det 命中后，在每个角 ±[ROI_HALF]（200x200）小窗口内做全套模板匹配，
 *    每角对应模板 TL/TR=黑車(b_r)、BL/BR=红俥(r_R)，仍按 4 角均分选最优套（保留选套语义）。
 *    精修成功 -> 用模板中心（亚像素级对齐皮肤）；精修失败 -> 直接采用 det 结果（32 子校验兜底）。
 * 3. **det 无结果/几何不合理 -> 象限全量模板**：按象限约束逐角匹配
 *    （TL: x<20%w,y<50%h；TR: x>80%w,y<50%h；BL/BR 对称），任一角不达阈值即弃套，
 *    全部套弃掉返回 null（调用方进步骤 2 引导手动微调）。
 * 4. 32 子校验：validateAsOpening 用 YOLO cls 全盘识别比对开局 32 子（保存前强制，含手动微调）。
 *
 * 几何纯函数（orderCorners/isPlausibleQuad/nonMaxSuppression/estimateScale）可 JVM 单测；
 * 依赖 OpenCV/ONNX 的匹配逻辑仅运行时在设备侧执行。
 */
object BoardCornerDetector {

    private const val DEFAULT_MATCH_THRESHOLD = 0.55
    private const val SCALE_RANGE_FACTOR = 0.30
    private const val MIN_SCALE = 0.40
    private const val MAX_SCALE = 2.50

    /** det 命中后模板精修的 ROI 半径（200x200 窗口；det 误差 <2px，留足皮肤分布偏移余量）。 */
    private const val ROI_HALF = 100

    /** 单峰：(score, cx, cy, scale) */
    data class Peak(val score: Double, val cx: Double, val cy: Double, val scale: Double)

    /** 识别结果：四角按 [左上, 右上, 左下, 右下] 排序，与 Const.BOARD_CORNERS 顺序一致。
     *  source = "template:<setName>"（模板精修/象限命中套）或 "det"（det 结果直接采用）。 */
    data class Result(
        val corners: List<Pair<Double, Double>>,
        val scores: List<Double>,
        val source: String,
    )

    /** 依据截图宽度估计角子模板尺度（对齐 python _estimate_scale：img_width / 1000）。 */
    fun estimateScale(imgWidth: Int): Double = imgWidth / 1000.0

    /** 全局非极大值抑制：跨尺度去重，minDistance 像素内只保留最高分峰（保留供测试/复用）。 */
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

    /**
     * det 四角几何合理性校验（纯函数）：conf 与位置精度无关，须用几何规则拦截垃圾输出。
     * 要求四点可排成 [TL,TR,BL,BR] 且：上下水平边均 > 0.5*宽，左右垂直边均 > 0.3*高，
     * 坐标均在屏幕内。棋盘实际横跨约 96% 宽、纵跨约 68% 高，阈值留足余量。
     */
    fun isPlausibleQuad(
        corners: List<Pair<Double, Double>>,
        srcW: Int,
        srcH: Int,
    ): Boolean {
        if (corners.size != 4) return false
        if (corners.any { (x, y) -> !x.isFinite() || !y.isFinite() }) return false
        if (corners.any { (x, y) -> x < -1.0 || y < -1.0 || x > srcW + 1.0 || y > srcH + 1.0 }) {
            return false
        }
        val ordered = try {
            orderCorners(corners)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val (tl, tr, bl, br) = ordered
        val topW = tr.first - tl.first
        val botW = br.first - bl.first
        val leftH = bl.second - tl.second
        val rightH = br.second - tr.second
        return topW > srcW * 0.5 && botW > srcW * 0.5 &&
                leftH > srcH * 0.3 && rightH > srcH * 0.3
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

    /** det 角点周围 ±half 的匹配窗口（钳制到图像边界）。 */
    private fun roiAround(p: Pair<Double, Double>, half: Int, w: Int, h: Int): Rect {
        val x0 = (p.first.roundToInt() - half).coerceAtLeast(0)
        val y0 = (p.second.roundToInt() - half).coerceAtLeast(0)
        val x1 = (p.first.roundToInt() + half).coerceAtMost(w)
        val y1 = (p.second.roundToInt() + half).coerceAtMost(h)
        return Rect(x0, y0, (x1 - x0).coerceAtLeast(1), (y1 - y0).coerceAtLeast(1))
    }

    /**
     * ROI 内多尺度模板匹配，取全局最高分峰（窗口小，无需局部极大/NMS）。
     * 低于阈值返回 null。峰心坐标已加回 ROI 偏移（原图坐标系）。
     */
    private fun bestPeakInRoi(
        gray: Mat,
        tmpl: Mat,
        roi: Rect,
        scaleMin: Double,
        scaleMax: Double,
        nScales: Int,
        threshold: Double,
    ): Peak? {
        val roiGray = gray.submat(roi)
        val tmplGray = toGrayMat(tmpl)
        val tw0 = tmplGray.cols()
        val th0 = tmplGray.rows()
        var best: Peak? = null
        try {
            for (i in 0 until nScales) {
                val scale =
                    if (nScales <= 1) scaleMin else scaleMin + (scaleMax - scaleMin) * i / (nScales - 1)
                val nw = maxOf(1, (tw0 * scale).roundToInt())
                val nh = maxOf(1, (th0 * scale).roundToInt())
                if (nw >= roi.width || nh >= roi.height) continue
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
                Imgproc.matchTemplate(roiGray, resized, result, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(result)
                result.release()
                resized.release()
                if (best == null || mm.maxVal > best.score) {
                    best = Peak(
                        mm.maxVal,
                        roi.x + mm.maxLoc.x + nw / 2.0,
                        roi.y + mm.maxLoc.y + nh / 2.0,
                        scale,
                    )
                }
            }
        } finally {
            roiGray.release()
            if (tmplGray !== tmpl) tmplGray.release()
        }
        return if (best != null && best.score >= threshold) best else null
    }

    /** 每角对应的模板：TL/TR=黑車(b_r)，BL/BR=红俥(r_R)（黑上红下）。 */
    private fun templatesForCorners(set: VisionInit.CornerSet): List<Mat> =
        listOf(set.bR, set.bR, set.rR, set.rR)

    /** 统一尺度参数（baseScale ±30%，步长 0.03）。 */
    private fun scaleParams(baseScale: Double): Triple<Double, Double, Int> {
        val scaleMin = maxOf(MIN_SCALE, baseScale * (1 - SCALE_RANGE_FACTOR))
        val scaleMax = minOf(MAX_SCALE, baseScale * (1 + SCALE_RANGE_FACTOR))
        val nScales = maxOf(15, ((scaleMax - scaleMin) / 0.03).roundToInt() + 1)
        return Triple(scaleMin, scaleMax, nScales)
    }

    /** 四角坐标日志片段（与匹配度一起打印，便于排查）。 */
    private fun cornersLog(corners: List<Pair<Double, Double>>): String =
        corners.joinToString(", ") { "(%.0f,%.0f)".format(it.first, it.second) }

    /**
     * det 命中后的模板精修：每个角在 ±ROI_HALF 窗口内用对应模板匹配，
     * 任一角不达阈值即弃套；全套遍历按 4 角均分选最优。
     */
    private fun refineWithTemplates(
        gray: Mat,
        cornerSets: List<VisionInit.CornerSet>,
        detCorners: List<Pair<Double, Double>>,
        onProgress: (String) -> Unit,
    ): Result? {
        val w = gray.cols()
        val h = gray.rows()
        val (scaleMin, scaleMax, nScales) = scaleParams(estimateScale(w))
        var best: Result? = null
        var bestMean = -1.0
        cornerSets.forEachIndexed { idx, set ->
            onProgress("模板精修 ${set.name}（${idx + 1}/${cornerSets.size}）…")
            val corners = mutableListOf<Pair<Double, Double>>()
            val scores = mutableListOf<Double>()
            var ok = true
            for (i in 0 until 4) {
                val peak = bestPeakInRoi(
                    gray, templatesForCorners(set)[i], roiAround(detCorners[i], ROI_HALF, w, h),
                    scaleMin, scaleMax, nScales, DEFAULT_MATCH_THRESHOLD,
                )
                if (peak == null) {
                    LogBus.log(
                        LogKind.DEBUG, LogTag.CALIB,
                        "${set.name} 精修第 ${i + 1} 角未达阈值，跳过该套"
                    )
                    ok = false
                    break
                }
                corners.add(peak.cx to peak.cy)
                scores.add(peak.score)
            }
            if (!ok) return@forEachIndexed
            val mean = scores.average()
            LogBus.log(
                LogKind.DEBUG, LogTag.CALIB,
                "${set.name} 精修命中，均分 %.3f，四角 ${cornersLog(corners)}".format(mean)
            )
            if (mean > bestMean) {
                bestMean = mean
                best = Result(corners, scores, "template:${set.name}")
            }
        }
        return best
    }

    /**
     * 象限全量模板识别（det 无结果/几何不合理时）：按象限约束逐角匹配，
     * 任一角不达阈值即弃套，全部套弃掉返回 null。
     * 象限：TL x<20%w,y<50%h；TR x>80%w,y<50%h；BL/BR 对称。
     */
    private fun detectQuadrants(
        gray: Mat,
        cornerSets: List<VisionInit.CornerSet>,
        onProgress: (String) -> Unit,
    ): Result? {
        val w = gray.cols()
        val h = gray.rows()
        val (scaleMin, scaleMax, nScales) = scaleParams(estimateScale(w))
        val qw = (w * 0.2).roundToInt()
        val qh = (h * 0.5).roundToInt()
        val quads = listOf(
            Rect(0, 0, qw, qh),                 // TL
            Rect(w - qw, 0, qw, qh),            // TR
            Rect(0, h - qh, qw, qh),            // BL
            Rect(w - qw, h - qh, qw, qh),       // BR
        )
        var best: Result? = null
        var bestMean = -1.0
        cornerSets.forEachIndexed { idx, set ->
            onProgress("全量模板 ${set.name}（${idx + 1}/${cornerSets.size}）…")
            val corners = mutableListOf<Pair<Double, Double>>()
            val scores = mutableListOf<Double>()
            var ok = true
            for (i in 0 until 4) {
                val peak = bestPeakInRoi(
                    gray, templatesForCorners(set)[i], quads[i],
                    scaleMin, scaleMax, nScales, DEFAULT_MATCH_THRESHOLD,
                )
                if (peak == null) {
                    LogBus.log(
                        LogKind.DEBUG, LogTag.CALIB,
                        "${set.name} 象限第 ${i + 1} 角未达阈值，放弃该套"
                    )
                    ok = false
                    break
                }
                corners.add(peak.cx to peak.cy)
                scores.add(peak.score)
            }
            if (!ok) return@forEachIndexed
            val mean = scores.average()
            LogBus.log(
                LogKind.DEBUG, LogTag.CALIB,
                "${set.name} 象限命中四角，均分 %.3f，四角 ${cornersLog(corners)}".format(mean)
            )
            if (mean > bestMean) {
                bestMean = mean
                best = Result(corners, scores, "template:${set.name}")
            }
        }
        return best
    }

    /**
     * 主入口（2026-09-05 det 先行版）：
     * 1. YOLO det 一次推理 -> [isPlausibleQuad] 几何校验；
     * 2. 通过 -> 全套 ROI 模板精修，成功用模板结果，失败直接用 det（32 子校验兜底）；
     * 3. det 无结果/几何不合理 -> 象限全量模板；仍失败返回 null（进步骤 2 引导手动微调）。
     * @param gray 原始截图的灰度图（模板匹配用）
     * @param frame 原始截图（det 路径 letterbox 前处理用）
     * @param onProgress 进度回调（UI 展示用，工作线程调用）
     */
    fun detectWithFallback(
        context: Context,
        gray: Mat,
        frame: Bitmap,
        cornerSets: List<VisionInit.CornerSet>,
        onProgress: (String) -> Unit = {},
    ): Result? {
        // 1. YOLO det 先行
        onProgress("YOLO det 四角识别中（含首次会话加载）…")
        val bgr = VisionInit.bitmapToBgr(frame)
        val detCorners = try {
            CornerDetModel.detectCorners(context, bgr)
        } finally {
            bgr.release()
        }
        if (detCorners != null && isPlausibleQuad(detCorners, frame.width, frame.height)) {
            LogBus.log(
                LogKind.OK, LogTag.CALIB,
                "YOLO det 定位四角 ${cornersLog(detCorners)}，进入模板精修"
            )
            // 2. ROI 模板精修；失败直接采用 det（32 子校验兜底）
            return refineWithTemplates(gray, cornerSets, detCorners, onProgress)
                ?: Result(detCorners, emptyList(), "det").also {
                    LogBus.log(
                        LogKind.WARN, LogTag.CALIB,
                        "全部模板精修未通过，直接采用 det 结果（32 子校验兜底）"
                    )
                }
        }
        // 3. det 无结果或几何不合理 -> 象限全量模板兜底
        LogBus.log(
            LogKind.INFO, LogTag.CALIB,
            if (detCorners == null) "YOLO det 无结果，转全量模板识别"
            else "YOLO det 结果几何不合理 ${cornersLog(detCorners)}，转全量模板识别"
        )
        return detectQuadrants(gray, cornerSets, onProgress)
    }

    /**
     * 用四角构造临时单应，把截图矫正为 900x1000，YOLO cls 全盘识别后比对 32 子开局。
     * 保存前强制校验（模板/det/手动微调任一来源都过此关）。
     * 用于在保存前挡住坏坐标（不污染 Homography 缓存）。
     * @param onProgress 进度回调（可选，工作线程调用）
     */
    fun validateAsOpening(
        bitmap: Bitmap,
        corners: List<Pair<Double, Double>>,
        onProgress: (String) -> Unit = {},
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
            onProgress("开局校验：cls 全盘 32 子识别…")
            val detected = Recognizer.analyzeBoard(corrected)
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
