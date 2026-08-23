package com.chess.bot.vision

import android.content.Context
import android.graphics.Bitmap
import com.chess.bot.game.Board
import com.chess.bot.game.COLS
import com.chess.bot.game.Const
import com.chess.bot.game.PIECE_CN
import com.chess.bot.game.ROWS
import com.chess.bot.game.correctedCenter
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * 棋盘识别：透视矫正 + 矫正空间模板匹配（移植 python vision.py 的 analyze_* 系列）。
 */
object Recognizer {

    /** Bitmap -> 矫正棋盘 Mat（900x1000 BGR）。 */
    fun correctBoard(bitmap: Bitmap): Mat {
        val src = VisionInit.bitmapToBgr(bitmap)
        val h = Homography.get(src.cols(), src.rows())
        val dst = Mat()
        Imgproc.warpPerspective(src, dst, h, Size(Const.CORRECT_W.toDouble(), Const.CORRECT_H.toDouble()))
        src.release()
        return dst
    }

    /** 分析矫正棋盘某格的棋子 ID，空格返回 null。 */
    fun analyzeCell(
        corrected: Mat,
        r: Int,
        c: Int,
        templates: Map<String, Mat>,
    ): String? {
        val (cx, cy) = correctedCenter(r, c)
        val px = cx.roundToInt()
        val py = cy.roundToInt()
        val half = Const.MATCH_SEARCH_HALF + Const.CORRECT_TEMPLATE_SIZE / 2
        val x1 = maxOf(0, px - half)
        val y1 = maxOf(0, py - half)
        val x2 = minOf(corrected.cols(), px + half)
        val y2 = minOf(corrected.rows(), py + half)
        if (x2 - x1 < 1 || y2 - y1 < 1) return null
        val window = corrected.submat(y1, y2, x1, x2)
        var bestId: String? = null
        var bestScore = -1.0
        for ((id, tpl) in templates) {
            if (window.cols() < tpl.cols() || window.rows() < tpl.rows()) continue
            val result = Mat()
            Imgproc.matchTemplate(window, tpl, result, Imgproc.TM_CCOEFF_NORMED)
            val score = Core.minMaxLoc(result).maxVal
            result.release()
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        return if (bestId == null || bestScore < Const.EMPTY_MATCH_THRESHOLD) null else bestId
    }

    /** 优先匹配 priority_id（上一帧该格棋子），命中直接返回；否则匹配其余模板。 */
    fun analyzeCellWithPriority(
        corrected: Mat,
        r: Int,
        c: Int,
        templates: Map<String, Mat>,
        priorityId: String?,
    ): String? {
        if (priorityId != null && priorityId in templates) {
            val (cx, cy) = correctedCenter(r, c)
            val px = cx.roundToInt()
            val py = cy.roundToInt()
            val half = Const.MATCH_SEARCH_HALF + Const.CORRECT_TEMPLATE_SIZE / 2
            val x1 = maxOf(0, px - half)
            val y1 = maxOf(0, py - half)
            val x2 = minOf(corrected.cols(), px + half)
            val y2 = minOf(corrected.rows(), py + half)
            val window = corrected.submat(y1, y2, x1, x2)
            val tpl = templates[priorityId]!!
            if (window.cols() >= tpl.cols() && window.rows() >= tpl.rows()) {
                val result = Mat()
                Imgproc.matchTemplate(window, tpl, result, Imgproc.TM_CCOEFF_NORMED)
                val score = Core.minMaxLoc(result).maxVal
                result.release()
                if (score >= Const.EMPTY_MATCH_THRESHOLD) return priorityId
            }
        }
        val remaining = templates.filterKeys { it != priorityId }
        return analyzeCell(corrected, r, c, remaining)
    }

    /** 遍历 90 格，返回 10x9 布局。 */
    fun analyzeBoard(corrected: Mat, templates: Map<String, Mat>): Board =
        Array(ROWS) { r -> Array<String?>(COLS) { c -> analyzeCell(corrected, r, c, templates) } }

    /**
     * 一站式：最新帧 Bitmap -> 10x9 布局。失败返回 null（原因写入日志）。
     */
    fun recognize(context: Context, bitmap: Bitmap): Board? {
        if (!VisionInit.init(context)) return null
        val templates = VisionInit.loadPieceTemplates(context)
        if (templates.size < 14) {
            LogBus.log(LogKind.ERROR, "棋子模板数量不足：${templates.size}/14")
            return null
        }
        val corrected = correctBoard(bitmap)
        try {
            return analyzeBoard(corrected, templates)
        } finally {
            corrected.release()
        }
    }

    /** 布局 -> 可读文本行（日志打印用）。 */
    fun formatLayout(board: Board): List<String> {
        val lines = mutableListOf<String>()
        for (r in ROWS - 1 downTo 0) {
            val cells = (0 until COLS).joinToString(" ") { c ->
                board[r][c]?.let { PIECE_CN[it] } ?: "·"
            }
            lines.add("r$r $cells")
        }
        return lines
    }
}
