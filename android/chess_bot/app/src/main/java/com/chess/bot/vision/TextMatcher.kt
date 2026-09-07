package com.chess.bot.vision

import android.content.Context
import android.graphics.Bitmap
import com.chess.bot.game.Const
import com.chess.bot.game.OcrRoi
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.sync.withLock

data class TextHit(val word: String, val x: Int, val y: Int, val score: Double)

/** OCR 裁剪矩形（全屏像素坐标）。 */
data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 结算文字 / 和棋按钮识别（2026-09-06 由图片模板匹配改为 PP-OCRv6 官方 ppocr-sdk）。
 *
 * 旧实现按 GAMEOVER_TEMPLATE_W 缩放后逐词 matchTemplate，模板制作繁琐且对分辨率/皮肤敏感；
 * OCR 直接输出整屏文本行+像素坐标+置信度，新增按钮只需在 Const 词表加一个词。
 * 词匹配为「包含」语义（如框文本「再来一局(3/5)」命中「再来一局」），误报由词表特异性兜底。
 *
 * 纯函数 matchScanWords / matchDrawDialog 与 Android 依赖分离，可 JVM 单测。
 */
object TextMatcher {

    @Volatile
    private var engine: PaddleOCR? = null
    private val engineMutex = kotlinx.coroutines.sync.Mutex()

    /** 懒创建 OCR 引擎（suspend，协程内调用）；模型与字典路径指向 assets/ocr/。 */
    private suspend fun obtain(context: Context): PaddleOCR {
        engine?.let { return it }
        return engineMutex.withLock {
            engine ?: PaddleOCR.create(
                context = context.applicationContext,
                config = PaddleOCRConfig(recScoreThresh = Const.OCR_REC_SCORE_MIN),
                engineConfig = EngineConfig(numThreads = 4),
                detModelAssetPath = "ocr/det.onnx",
                recModelAssetPath = "ocr/rec.onnx",
                recConfigAssetPath = "ocr/rec.yml",
            ).also {
                engine = it
                LogBus.log(
                    LogLevel.INFO, LogTag.VISION,
                    "OCR 引擎已就绪（coldLoad ${it.coldLoadTimeMs}ms）"
                )
            }
        }
    }

    /** 预热：提前创建 OCR 引擎（服务启动时后台调用），避免首次调用在局中冷加载 1~3s。 */
    suspend fun warmUp(context: Context) {
        obtain(context)
    }

    /**
     * 整屏 OCR，返回文本行命中（word=行文本原文，坐标=检测框中心，score=识别置信度）。
     * 识别异常（引擎未就绪/图像无效）按空结果处理，调用方走原有兜底流程。
     */
    suspend fun ocr(context: Context, img: Bitmap): List<TextHit> = try {
        OpenCVUtils.init(context)
        val result = obtain(context).recognize(img)
        result.results.map { r ->
            val xs = r.box.points.map { it.x }
            val ys = r.box.points.map { it.y }
            TextHit(
                word = r.text,
                x = Math.round(xs.average()).toInt(),
                y = Math.round(ys.average()).toInt(),
                score = r.confidence.toDouble(),
            )
        }
    } catch (e: Exception) {
        LogBus.log(LogLevel.WARN, LogTag.VISION, "OCR 识别异常：${e.message}")
        emptyList()
    }

    /**
     * 自动下一局扫描选词（对齐 python _scan_gameover_text 的优先级语义）：
     * 遮罩类词表优先于按钮类词表；同类内按列表顺序（先命中先返回），忽略跨词分数比较。
     */
    suspend fun findGameoverScan(context: Context, img: Bitmap): TextHit? =
        matchScanWords(
            ocrForWords(context, img, Const.GAMEOVER_BACK_WORDS + Const.GAMEOVER_BUTTON_WORDS),
            Const.GAMEOVER_BACK_WORDS, Const.GAMEOVER_BUTTON_WORDS,
        )

    /** 和棋弹窗按钮（含标题词校验，见 matchDrawDialog）。 */
    suspend fun findDrawDialog(context: Context, img: Bitmap): List<TextHit> =
        matchDrawDialog(
            ocrForWords(
                context, img,
                listOf(Const.DRAW_REQUEST_WORD, Const.DRAW_ACCEPT_WORD, Const.DRAW_REJECT_WORD),
            )
        )

    /**
     * 词表 ROI 裁剪扫描：对词表中「已配置 ROI」的词求并集（外包矩形），裁剪后 OCR，
     * 命中坐标加回裁剪偏移映射为全屏坐标。词表完全无 ROI 配置 → 全图查找（兜底）。
     */
    private suspend fun ocrForWords(
        context: Context,
        img: Bitmap,
        words: List<String>,
    ): List<TextHit> {
        val union = unionRois(words.mapNotNull { Const.OCR_WORD_ROIS[it] })
            ?: return ocr(context, img)
        val crop = cropRectOf(union, img.width, img.height)
        // 裁剪子图与源帧可能共享像素缓冲（createBitmap 优化），不手动 recycle，交给 GC
        val sub = Bitmap.createBitmap(img, crop.x, crop.y, crop.width, crop.height)
        return mapHitsToFull(ocr(context, sub), crop)
    }

    /** 【纯函数】多个 ROI 求并集（外包矩形）；空列表返回 null（表示全图查找）。 */
    fun unionRois(rois: List<OcrRoi>): OcrRoi? =
        if (rois.isEmpty()) null
        else OcrRoi(
            rois.minOf { it.x1 }, rois.minOf { it.y1 },
            rois.maxOf { it.x2 }, rois.maxOf { it.y2 },
        )

    /** 【纯函数】百分率 ROI → 全屏像素裁剪矩形（取整 + 收紧到图像边界，宽高至少 1px）。 */
    fun cropRectOf(roi: OcrRoi, imgW: Int, imgH: Int): CropRect {
        val x1 = (roi.x1.coerceIn(0f, 1f) * imgW).toInt().coerceIn(0, imgW - 1)
        val y1 = (roi.y1.coerceIn(0f, 1f) * imgH).toInt().coerceIn(0, imgH - 1)
        val x2 = (roi.x2.coerceIn(0f, 1f) * imgW).toInt().coerceIn(x1 + 1, imgW)
        val y2 = (roi.y2.coerceIn(0f, 1f) * imgH).toInt().coerceIn(y1 + 1, imgH)
        return CropRect(x1, y1, x2 - x1, y2 - y1)
    }

    /** 【纯函数】把裁剪坐标系下的命中偏移回全屏坐标（按钮点击用全屏坐标）。 */
    fun mapHitsToFull(hits: List<TextHit>, crop: CropRect): List<TextHit> =
        hits.map { it.copy(x = it.x + crop.x, y = it.y + crop.y) }

    /**
     * 【纯函数】自动下一局扫描选词：lines 为 OCR 文本行；先遮罩词表后按钮词表、表内按顺序，
     * 返回首个命中关键词的 TextHit（word 归一为关键词本身，isBackWord 据此判断），无命中返回 null。
     */
    fun matchScanWords(
        lines: List<TextHit>,
        backWords: List<String>,
        buttonWords: List<String>,
    ): TextHit? {
        for (word in backWords) {
            lines.firstOrNull { it.word.contains(word) }?.let { return it.copy(word = word) }
        }
        for (word in buttonWords) {
            lines.firstOrNull { it.word.contains(word) }?.let { return it.copy(word = word) }
        }
        return null
    }

    /**
     * 【纯函数】和棋弹窗判定：「对方请求和棋」+「同意」+「拒绝」三词同时存在才是和棋页面
     * （缺一可能是其他含「同意/拒绝」按钮的页面）。返回「同意」「拒绝」两个按钮命中（供点击）。
     */
    fun matchDrawDialog(lines: List<TextHit>): List<TextHit> {
        val hasRequest = lines.any { it.word.contains(Const.DRAW_REQUEST_WORD) }
        if (!hasRequest) return emptyList()
        val accept = lines.firstOrNull { it.word.contains(Const.DRAW_ACCEPT_WORD) }
            ?: return emptyList()
        val deny = lines.firstOrNull { it.word.contains(Const.DRAW_REJECT_WORD) }
            ?: return emptyList()
        return listOf(accept, deny)
    }
}
