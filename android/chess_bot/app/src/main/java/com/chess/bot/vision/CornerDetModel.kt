package com.chess.bot.vision

import ai.onnxruntime.OnnxTensor
import android.content.Context
import com.chess.bot.game.Const
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * YOLO det 棋盘四角检测（ONNX）：
 * - 模型 assets/models/board_corners.onnx，4 类 tl/tr/bl/br（0=TL 1=TR 2=BL 3=BR）。
 * - 输入 1x3x1280x1280 NCHW RGB，letterbox 等比缩放 + 灰边填充(114)。
 * - 输出 1x8xN（N 与 imgsz 相关，运行时读取）：通道 0..3 为框 cx,cy,w,h（1280 空间），
 *   4..7 为 4 类置信度。后处理：每类取 conf 最高的框，框心即角点（切勿用常规阈值，会漏 BR）。
 * - decode 为纯函数（不依赖 OpenCV），便于 JVM 单测。
 */
object CornerDetModel {

    private const val MODEL_ASSET = "models/board_corners.onnx"
    private const val NUM_CLASSES = 4

    @Volatile
    private var ready = false

    /** 预热：加载 Session（校准流程前调用）。 */
    fun ensure(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            OnnxRuntime.session(context, MODEL_ASSET)
            ready = true
        }
    }

    /**
     * 检测四角：返回 [TL, TR, BL, BR]（原图像素）。任一类无框或推理异常返回 null（调用方回退）。
     */
    fun detectCorners(context: Context, bgr: Mat): List<Pair<Double, Double>>? {
        return try {
            ensure(context)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val session = OnnxRuntime.session(context, MODEL_ASSET)
            val imgsz = Const.DET_IMGSZ
            val w = bgr.cols()
            val h = bgr.rows()
            val scale = minOf(imgsz.toDouble() / w, imgsz.toDouble() / h)
            val nw = (w * scale).roundToInt().coerceIn(1, imgsz)
            val nh = (h * scale).roundToInt().coerceIn(1, imgsz)
            val padX = (imgsz - nw) / 2.0
            val padY = (imgsz - nh) / 2.0

            // letterbox：resize -> 常量填充 114 -> RGB
            val resized = Mat()
            Imgproc.resize(
                bgr, resized, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0,
                if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
            )
            val top = padY.roundToInt()
            val bottom = imgsz - nh - top
            val left = padX.roundToInt()
            val right = imgsz - nw - left
            val padded = Mat()
            Core.copyMakeBorder(
                resized, padded, top, bottom, left, right, Core.BORDER_CONSTANT,
                org.opencv.core.Scalar(114.0, 114.0, 114.0)
            )
            resized.release()
            val rgb = Mat()
            Imgproc.cvtColor(padded, rgb, Imgproc.COLOR_BGR2RGB)
            padded.release()

            // HWC RGB -> CHW。Mat.get(FloatArray) 要求 CV_32F：转 32F 时同步 /255 归一化
            val rgb32f = Mat()
            rgb.convertTo(rgb32f, CvType.CV_32F, 1.0 / 255.0)
            rgb.release()
            val whc = imgsz * imgsz * 3
            val pixels = FloatArray(whc)
            rgb32f.get(0, 0, pixels)
            rgb32f.release()
            val chw = toChw(pixels, imgsz)

            val input = OnnxRuntime.tensor(longArrayOf(1, 3, imgsz.toLong(), imgsz.toLong()), chw)
            val tPrep = android.os.SystemClock.elapsedRealtime()
            val output = session.run(mapOf(session.inputNames.iterator().next() to input))
            val tInfer = android.os.SystemClock.elapsedRealtime()
            LogBus.log(
                com.chess.bot.log.LogLevel.DEBUG, LogTag.VISION,
                "det 预处理 %dms / 推理 %dms".format(tPrep - t0, tInfer - tPrep)
            )
            val tensor = output.get(0) as OnnxTensor
            val shape = tensor.info.shape // [1, 8, N]
            val floatBuf = tensor.floatBuffer
            val n = shape[2].toInt()
            val out = FloatArray(floatBuf.remaining())
            floatBuf.get(out)
            input.close()
            output.close()

            decode(out, n, Const.DET_CONF, scale, left.toDouble(), top.toDouble(), w, h)
        } catch (e: Exception) {
            LogBus.log(LogLevel.WARN, LogTag.CALIB, "det 四角检测异常：${e.message}")
            null
        }
    }

    /**
     * 纯函数：HWC float RGB（imgsz*imgsz*3）→ CHW（R/G/B 三个平面）。
     * @param pixels HWC 布局（每像素连续 RGB）
     * @return CHW 布局数组（长度同输入）
     */
    internal fun toChw(pixels: FloatArray, imgsz: Int): FloatArray {
        val hw = imgsz * imgsz
        require(pixels.size == hw * 3) { "pixels.size=${pixels.size} != ${hw * 3}" }
        val chw = FloatArray(hw * 3)
        for (i in 0 until hw) {
            val src = i * 3
            chw[i] = pixels[src]              // R 平面
            chw[hw + i] = pixels[src + 1]     // G 平面
            chw[2 * hw + i] = pixels[src + 2] // B 平面
        }
        return chw
    }

    /**
     * 纯函数 decode：每类 argmax(conf) 取框心，映射回原图坐标。
     * @param out 输出原始数组（channel-major：out[ch * n + anchor]）
     * @param n 锚点数（输出第 3 维）
     * @return 4 角 [TL, TR, BL, BR]；任一类最高 conf < confThreshold 返回 null
     */
    fun decode(
        out: FloatArray,
        n: Int,
        confThreshold: Double,
        scale: Double,
        padX: Double,
        padY: Double,
        srcW: Int,
        srcH: Int,
    ): List<Pair<Double, Double>>? {
        if (out.size < (NUM_CLASSES + 4) * n) return null
        val corners = mutableListOf<Pair<Double, Double>>()
        for (c in 0 until NUM_CLASSES) {
            val base = (4 + c) * n
            var bestA = -1
            var bestConf = -1f
            for (a in 0 until n) {
                val v = out[base + a]
                if (v > bestConf) {
                    bestConf = v
                    bestA = a
                }
            }
            if (bestA < 0 || bestConf < confThreshold) return null
            val cx = out[bestA]       // ch0: cx（1280 空间）
            val cy = out[n + bestA]   // ch1: cy
            val x = ((cx - padX) / scale).coerceIn(0.0, srcW.toDouble())
            val y = ((cy - padY) / scale).coerceIn(0.0, srcH.toDouble())
            corners.add(x to y)
        }
        return corners
    }
}
