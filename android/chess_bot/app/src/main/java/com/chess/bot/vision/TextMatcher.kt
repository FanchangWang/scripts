package com.chess.bot.vision

import android.content.Context
import android.graphics.Bitmap
import com.chess.bot.game.Const
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

data class TextHit(val word: String, val x: Int, val y: Int, val score: Double)

/**
 * 结算文字 / 和棋按钮模板匹配（移植 python find_gameover_text/find_draw_dialog）。
 *
 * 游戏 UI 随分辨率线性缩放：先把截图等比缩放到 GAMEOVER_TEMPLATE_W 宽再匹配，
 * 坐标还原到源分辨率。每词只取最高分命中（调用方按词优先级遍历）。
 */
object TextMatcher {

    private var gameoverCache: Map<String, Mat>? = null
    private var drawCache: Map<String, Mat>? = null

    private fun loadGrayTemplates(context: Context, dir: String): Map<String, Mat> {
        val map = mutableMapOf<String, Mat>()
        for (name in (context.assets.list(dir) ?: emptyArray()).sorted()) {
            if (!name.endsWith(".png")) continue
            val bytes = context.assets.open("$dir/$name").use { it.readBytes() }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            val rgba = Mat()
            org.opencv.android.Utils.bitmapToMat(bmp, rgba)
            bmp.recycle()
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            map[name.removeSuffix(".png")] = gray
        }
        return map
    }

    @Synchronized
    fun gameoverTemplates(context: Context): Map<String, Mat> {
        if (gameoverCache == null) gameoverCache = loadGrayTemplates(context, "templates/text")
        return gameoverCache!!
    }

    @Synchronized
    fun drawTemplates(context: Context): Map<String, Mat> {
        if (drawCache == null) drawCache = loadGrayTemplates(context, "templates/draw")
        return drawCache!!
    }

    /**
     * 在原始截图上匹配模板，返回所有高于阈值的 [(词, 屏幕x, 屏幕y, 分)]（中心点，按分降序）。
     */
    fun findText(
        context: Context,
        img: Bitmap,
        templates: Map<String, Mat>,
        threshold: Double,
    ): List<TextHit> {
        if (templates.isEmpty()) return emptyList()
        val scale = img.width.toDouble() / Const.GAMEOVER_TEMPLATE_W
        var work = img
        if (img.width != Const.GAMEOVER_TEMPLATE_W) {
            val targetH = Math.max(1, Math.round(img.height / scale).toInt())
            work = Bitmap.createScaledBitmap(img, Const.GAMEOVER_TEMPLATE_W, targetH, true)
        }
        val rgba = Mat()
        org.opencv.android.Utils.bitmapToMat(work, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()

        val hits = mutableListOf<TextHit>()
        for ((word, tpl) in templates) {
            if (gray.cols() < tpl.cols() || gray.rows() < tpl.rows()) continue
            val result = Mat()
            Imgproc.matchTemplate(gray, tpl, result, Imgproc.TM_CCOEFF_NORMED)
            val loc = Core.minMaxLoc(result)
            result.release()
            if (loc.maxVal >= threshold) {
                val cx = Math.round((loc.maxLoc.x + tpl.cols() / 2.0) * scale).toInt()
                val cy = Math.round((loc.maxLoc.y + tpl.rows() / 2.0) * scale).toInt()
                hits.add(TextHit(word, cx, cy, loc.maxVal))
            }
        }
        if (work !== img) work.recycle()
        gray.release()
        return hits.sortedByDescending { it.score }
    }

    /**
     * 自动下一局扫描选词（对齐 python _scan_gameover_text 的优先级语义）：
     * 遮罩类词表优先于按钮类词表；同类内按列表顺序（先命中先返回），忽略跨词分数比较。
     */
    fun findGameoverScan(context: Context, img: Bitmap): TextHit? {
        val templates = gameoverTemplates(context)
        if (templates.isEmpty()) return null
        val bestByWord = mutableMapOf<String, TextHit>()
        for (hit in findText(context, img, templates, Const.GAMEOVER_TEXT_THRESHOLD)) {
            val cur = bestByWord[hit.word]
            if (cur == null || hit.score > cur.score) bestByWord[hit.word] = hit
        }
        for (word in Const.GAMEOVER_BACK_WORDS) bestByWord[word]?.let { return it }
        for (word in Const.GAMEOVER_BUTTON_WORDS) bestByWord[word]?.let { return it }
        return null
    }

    /** 和棋弹窗按钮。 */
    fun findDrawDialog(context: Context, img: Bitmap): List<TextHit> =
        findText(context, img, drawTemplates(context), Const.DRAW_TEXT_THRESHOLD)
}
