package com.chess.bot.vision

import android.graphics.Bitmap
import com.chess.bot.game.Board
import com.chess.bot.game.COLS
import com.chess.bot.game.Const
import com.chess.bot.game.PIECE_CN
import com.chess.bot.game.ROWS
import com.chess.bot.game.correctedCenter
import com.chess.bot.log.LogBus
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * 棋盘识别：透视矫正 + 矫正空间 YOLO cls 逐格分类（2026-09-05 起替代模板匹配）。
 * - analyzeCell/analyzeBoard：格心裁 64x64 -> PieceClsModel 16 类（empty->null / lift->Const.LIFT）。
 * - 帧差逻辑（cropCellGray/cellChanged）不变。
 */
object Recognizer {

    /** Bitmap -> 矫正棋盘 Mat（900x1000 BGR）。 */
    fun correctBoard(bitmap: Bitmap): Mat {
        val src = VisionInit.bitmapToBgr(bitmap)
        val h = Homography.get(src.cols(), src.rows())
        val dst = Mat()
        Imgproc.warpPerspective(
            src,
            dst,
            h,
            Size(Const.CORRECT_W.toDouble(), Const.CORRECT_H.toDouble())
        )
        src.release()
        return dst
    }

    /** 矫正空间格心裁 64x64 BGR 小图（cls 输入；调用方负责 release）。 */
    fun cropCell64(corrected: Mat, r: Int, c: Int): Mat {
        val (cx, cy) = correctedCenter(r, c)
        val px = cx.roundToInt()
        val py = cy.roundToInt()
        val half = Const.CLS_CELL / 2
        val x1 = (px - half).coerceIn(0, corrected.cols() - Const.CLS_CELL)
        val y1 = (py - half).coerceIn(0, corrected.rows() - Const.CLS_CELL)
        return corrected.submat(y1, y1 + Const.CLS_CELL, x1, x1 + Const.CLS_CELL)
    }

    /**
     * 分析矫正棋盘某格：cls 分类，空格返回 null，提子返回 Const.LIFT。
     * @param gateLift 帧差触发格传 true：top1 为棋子但 lift 概率达门控阈值时判为动画帧，
     *   返回 Const.LIFT（走子动画/选中高亮中的棋子外观不可信，宁判提起不判错子；
     *   全量识别 analyzeBoard 不启用，保持 argmax 直判语义）。
     */
    fun analyzeCell(corrected: Mat, r: Int, c: Int, gateLift: Boolean = false): String? {
        val cell = cropCell64(corrected, r, c)
        return try {
            val res = PieceClsModel.classifyCellEx(VisionInit.requireContext(), cell)
            if (gateLift && PieceClsModel.isLiftAmbiguous(res.key, res.liftProb)) {
                LogBus.log(
                    com.chess.bot.log.LogKind.DEBUG, com.chess.bot.log.LogTag.VISION,
                    "动画帧抑制 r$r c$c：top1=${res.key}(%.2f) lift=%.2f -> 判提起".format(
                        res.top1Prob, res.liftProb
                    )
                )
                Const.LIFT
            } else {
                res.key
            }
        } finally {
            cell.release()
        }
    }

    // ---------- 格子中心小图（方案 A 变种：10x10 单通道灰度，用于帧间 diff） ----------
    // 仅取格子中心 10x10：棋子落点变化集中反映在此；敌方「走子光圈」是绕棋子外缘的环，
    // 半径远大于 5px，不进中心区 → 未动棋子的光圈不会触发 diff（零误判）。
    // 但「上一步落子高亮」会整格统一染色（覆盖中心 10x10），导致未动棋子的格也高频误触发；
    // 故 cellChanged 对两图做去均值（零均值）再比，消除整格统一亮度差，只保留棋子结构差异。
    private const val CELL_PATCH = 10
    const val CELL_PIXEL_DIFF = 25.0   // 单像素灰度差 ≥ 此值计为「变化像素」（去均值后）
    const val CELL_MIN_CHANGED = 10    // 100 像素中 ≥ 此数变化 → 整格视为变化（初值，后续按日志调）

    /** 直接裁格子中心 10x10 并转单通道灰度（不做 80x80→缩放；中心区即可反映落子变化）。 */
    fun cropCellGray(corrected: Mat, r: Int, c: Int): Mat {
        val (cx, cy) = correctedCenter(r, c)
        val px = cx.roundToInt()
        val py = cy.roundToInt()
        val out = Mat(CELL_PATCH, CELL_PATCH, CvType.CV_8UC1)
        Imgproc.cvtColor(
            corrected.submat(py - 5, py + 5, px - 5, px + 5),
            out,
            Imgproc.COLOR_BGR2GRAY,
        )
        return out
    }

    /** 中心小图是否相对基线变化（base==null 即首帧 → 视为变化）。
     *  去均值处理：消除「上一步落子高亮」整格统一染色（只改亮度不改结构）造成的误触发，
     *  仅当棋子结构（边缘/纹理）真正变化时才判为变化。 */
    fun cellChanged(cur: Mat, base: Mat?): Boolean {
        if (base == null) return true
        // 转 16S 避免零均值后负值被 8U 截断
        val c = Mat(); cur.convertTo(c, CvType.CV_16S)
        val b = Mat(); base.convertTo(b, CvType.CV_16S)
        Core.subtract(c, Scalar(Core.mean(c).`val`[0]), c)
        Core.subtract(b, Scalar(Core.mean(b).`val`[0]), b)
        val d = Mat()
        Core.absdiff(c, b, d)
        Imgproc.threshold(d, d, CELL_PIXEL_DIFF, 255.0, Imgproc.THRESH_BINARY)
        val n = Core.countNonZero(d)
        c.release(); b.release(); d.release()
        return n >= CELL_MIN_CHANGED
    }

    /** 遍历 90 格，返回 10x9 布局（YOLO cls 全量识别）。 */
    fun analyzeBoard(corrected: Mat): Board =
        Array(ROWS) { r -> Array<String?>(COLS) { c -> analyzeCell(corrected, r, c) } }

    /** 布局 -> 可读文本行（日志打印用；lift 显示为「提」）。 */
    fun formatLayout(board: Board): List<String> {
        val lines = mutableListOf<String>()
        for (r in ROWS - 1 downTo 0) {
            val cells = (0 until COLS).joinToString(" ") { c ->
                when (val v = board[r][c]) {
                    null -> "·"
                    Const.LIFT -> "提"
                    else -> PIECE_CN[v] ?: v
                }
            }
            lines.add("r$r $cells")
        }
        return lines
    }
}
