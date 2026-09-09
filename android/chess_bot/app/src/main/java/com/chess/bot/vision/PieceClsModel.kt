package com.chess.bot.vision

import android.content.Context
import com.chess.bot.game.Const
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
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

    /** 分类结果：key 语义同 classifyCell；附模型输出的 top1 概率。 */
    data class ClsResult(val key: String?, val top1Prob: Float)

    /**
     * 带概率的分类（帧差触发格用）：返回 top1 概率。
     * （2026-09-10 D1=A 实证 16 类 softmax 互斥：top1≥0.98 时其余 15 类总和 ≤0.02，
     * 「棋子+lift 双高」形态日志 0 条——原 isLiftAmbiguous 门控与 liftProb 字段均删除。）
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
                // 注意：ultralytics cls ONNX 导出图末尾自带 Softmax 算子（已核实 graph 末尾
                // ...Gemm→Softmax），输出就是概率——绝不可再 softmax 一次（2026-09-07 修复：
                // 旧代码二次 softmax 把概率压向均匀分布，top1 被钉死在 16 类天花板 e/(e+15)≈0.1534，
                // 看起来像「置信度 0.15」；argmax 单调不受影响所以分类一直正确，仅概率失真）。
                val probs = (output.get(0) as ai.onnxruntime.OnnxTensor).floatBuffer
                var best = 0
                for (i in 1 until CLASS_KEYS.size) {
                    if (probs.get(i) > probs.get(best)) best = i
                }
                val key = when (val k = CLASS_KEYS[best]) {
                    "empty" -> null
                    "lift" -> Const.LIFT
                    else -> k
                }
                ClsResult(key, probs[best])
            } finally {
                output.close()
            }
        } catch (e: Exception) {
            LogBus.log(LogLevel.WARN, LogTag.VISION, "cls 分类异常：${e.message}")
            ClsResult(null, 0f)
        } finally {
            input.close()
        }
    }
}
