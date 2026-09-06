package com.chess.bot.vision

import android.content.Context
import com.chess.bot.game.Const
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import com.chess.bot.vision.PieceClsModel.isLiftAmbiguous
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * YOLO cls 棋子分类（ONNX）：
 * - 模型 assets/models/chess_pieces.onnx，16 类：14 种棋子 + empty + lift（提起棋子）。
 * - 输入 1x3x64x64 NCHW RGB（矫正空间格心 64x64 裁块），输出 [1,16] logits。
 * - 空格判定 = 方案 A：argmax 直判（val top1=1.0，概率校准好，无额外阈值）；
 *   empty -> null；lift -> Const.LIFT（配合 classifier 提子推断，提交点归一化为 null）。
 */
object PieceClsModel {

    private const val MODEL_ASSET = "models/chess_pieces.onnx"

    /** 类别索引 -> key（与 cls/export/model_info.json 的 class_keys 严格一致）。 */
    val CLASS_KEYS = listOf(
        "b_a", "b_b", "b_c", "b_k", "b_n", "b_p", "b_r",
        "empty", "lift",
        "r_A", "r_B", "r_C", "r_K", "r_N", "r_P", "r_R",
    )

    @Volatile
    private var ready = false

    /** 预热：加载 Session。 */
    fun ensure(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            OnnxRuntime.session(context, MODEL_ASSET)
            ready = true
        }
    }

    /**
     * 分类单格：cell 为 64x64 BGR Mat（矫正空间格心裁块）。
     * 返回棋子 key；empty -> null；lift -> Const.LIFT。
     */
    fun classifyCell(context: Context, cell: Mat): String? = classifyCellEx(context, cell).key

    /** 分类结果：key 语义同 classifyCell；附 softmax 概率（动画帧门控/调参用）。 */
    data class ClsResult(val key: String?, val top1Prob: Float, val liftProb: Float)

    /**
     * 带概率的分类（帧差触发格用）：softmax 后返回 top1 与 lift 概率。
     * [isLiftAmbiguous] 为 true 时调用方可把该格按提子处理（动画帧抑制）。
     */
    fun classifyCellEx(context: Context, cell: Mat): ClsResult {
        ensure(context)
        val session = OnnxRuntime.session(context, MODEL_ASSET)
        val size = Const.CLS_CELL
        val input = run {
            val rgb = Mat()
            Imgproc.cvtColor(cell, rgb, Imgproc.COLOR_BGR2RGB)
            // Mat.get(FloatArray) 要求 CV_32F：转 32F 时同步完成 /255 归一化
            val rgb32f = Mat()
            rgb.convertTo(rgb32f, CvType.CV_32F, 1.0 / 255.0)
            rgb.release()
            val pixels = FloatArray(size * size * 3)
            rgb32f.get(0, 0, pixels)
            rgb32f.release()
            val plane = size * size
            val chw = FloatArray(plane * 3)
            for (i in 0 until plane) {
                chw[i] = pixels[i * 3]
                chw[plane + i] = pixels[i * 3 + 1]
                chw[2 * plane + i] = pixels[i * 3 + 2]
            }
            OnnxRuntime.tensor(longArrayOf(1, 3, size.toLong(), size.toLong()), chw)
        }
        return try {
            val output = session.run(mapOf(session.inputNames.iterator().next() to input))
            try {
                val logits = (output.get(0) as ai.onnxruntime.OnnxTensor).floatBuffer
                var best = 0
                for (i in 1 until CLASS_KEYS.size) {
                    if (logits.get(i) > logits.get(best)) best = i
                }
                val probs = softmax(logits, CLASS_KEYS.size)
                val key = when (val k = CLASS_KEYS[best]) {
                    "empty" -> null
                    "lift" -> Const.LIFT
                    else -> k
                }
                ClsResult(key, probs[best], probs[LIFT_INDEX])
            } finally {
                output.close()
            }
        } catch (e: Exception) {
            LogBus.log(LogKind.WARN, LogTag.VISION, "cls 分类异常：${e.message}")
            ClsResult(null, 0f, 0f)
        } finally {
            input.close()
        }
    }

    /** lift 类在 CLASS_KEYS 中的索引（softmax 概率取用）。 */
    private val LIFT_INDEX = CLASS_KEYS.indexOf("lift")

    /**
     * 纯函数：动画帧判定。top1 是真实棋子但 lift 概率显著（≥ [Const.CLS_LIFT_GATE]），
     * 说明该格处于提起/选中/滑动动画中，棋子外观不可信 → 按提子处理。
     * empty 与 lift 本身不需要门控。
     */
    fun isLiftAmbiguous(key: String?, liftProb: Float): Boolean =
        key != null && key != Const.LIFT && liftProb >= Const.CLS_LIFT_GATE

    /** softmax（数值稳定版）：logits 长度 n，返回概率数组。 */
    internal fun softmax(logits: java.nio.FloatBuffer, n: Int): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until n) if (logits.get(i) > max) max = logits.get(i)
        val exp = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) {
            exp[i] = kotlin.math.exp(logits.get(i) - max)
            sum += exp[i]
        }
        for (i in 0 until n) exp[i] /= sum
        return exp
    }
}
