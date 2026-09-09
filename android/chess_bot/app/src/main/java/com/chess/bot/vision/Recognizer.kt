package com.chess.bot.vision

import android.graphics.Bitmap
import com.chess.bot.game.Board
import com.chess.bot.game.COLS
import com.chess.bot.game.Const
import com.chess.bot.game.ROWS
import com.chess.bot.game.Side
import com.chess.bot.game.correctedCenter
import com.chess.bot.game.formatLayoutLines
import com.chess.bot.game.pickLiftedCandidate
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import com.chess.bot.vision.Recognizer.analyzeCell
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
        return cropCell64At(corrected, cx.roundToInt(), cy.roundToInt())
    }

    /** 以矫正图任意像素点为中心裁剪 CLS_CELL 输入（提子身份向上扫描用，2026-09-08）。 */
    fun cropCell64At(corrected: Mat, px: Int, py: Int): Mat {
        val half = Const.CLS_CELL / 2
        val x1 = (px - half).coerceIn(0, corrected.cols() - Const.CLS_CELL)
        val y1 = (py - half).coerceIn(0, corrected.rows() - Const.CLS_CELL)
        return corrected.submat(y1, y1 + Const.CLS_CELL, x1, x1 + Const.CLS_CELL)
    }

    /**
     * 提子身份识别（2026-09-08，方案 game_start_lift_recovery_plan.md）：
     * lift 格中心向上逐 dy 裁 64x64 送 cls，取置信度最高的有效棋子读数。
     * 原理：提子 3D 悬浮特效投影 2D 后棋子位于格子上半部（部分覆盖正上方格子），
     * 向上扫描必有一档让悬浮棋子居中——无需任何先验猜测。
     *
     * @param abovePiece 正上方格已识别棋子（board[r-1][c]）：dy≈100 档的读数中心恰好
     *   落在其中心，会高置信污染扫描；存在其他候选时排除该类型防误判
     *   （同型棋子相邻时排除无影响——FEN 只需要棋子类型）。
     * @return 棋子 ID（如 "r_K"）；全部档位无有效读数返回 null（调用方下帧重试）。
     */
    fun identifyLiftedPiece(corrected: Mat, r: Int, c: Int, abovePiece: String?): String? {
        val (cx, cy) = correctedCenter(r, c)
        val px = cx.roundToInt()
        val reads = mutableListOf<String>()
        val candidates = mutableListOf<Pair<String, Float>>() // pieceId -> top1Prob
        for (dy in Const.LIFT_SCAN_MIN_DY_PX..Const.LIFT_SCAN_MAX_DY_PX step Const.LIFT_SCAN_STEP_PX) {
            val py = cy.roundToInt() - dy
            if (py < Const.CLS_CELL / 2) break // 越界保护（我方半区 lift 正常不会触发）
            val cell = cropCell64At(corrected, px, py)
            val res = try {
                PieceClsModel.classifyCellEx(VisionInit.requireContext(), cell)
            } finally {
                cell.release()
            }
            val key = res.key
            if (key != null && key != Const.LIFT && res.top1Prob >= Const.LIFT_SCAN_MIN_PROB) {
                candidates.add(key to res.top1Prob)
                reads.add("dy=$dy:$key(${("%.2f".format(res.top1Prob))})")
            } else {
                reads.add("dy=$dy:${key ?: "空"}(${("%.2f".format(res.top1Prob))})")
            }
        }
        LogBus.log(LogLevel.DEBUG, LogTag.VISION, "提子识别扫描 r$r c$c：${reads.joinToString(" ")}")
        // 多数票选择（D1-A，2026-09-08）：实现见 game 包 pickLiftedCandidate（纯函数可单测）
        return pickLiftedCandidate(candidates, abovePiece)
    }

    /**
     * 分析矫正棋盘某格：cls 分类，空格返回 null，提子返回 Const.LIFT。
     * （2026-09-10 D1=A 删除 gateLift 参数：原 lift 混淆门控在 0.98 确认门下永不触发，
     * 已随 isLiftAmbiguous 一并移除。）
     */
    fun analyzeCell(corrected: Mat, r: Int, c: Int): String? =
        analyzeCellEx(corrected, r, c).first

    /**
     * [analyzeCell] 的带概率版本：同时返回 cls 原始分类结果（top1 类别/top1 概率/lift 概率），
     * 供调用方在「变化格」日志中打印识别置信度（2026-09-07：排查敌方 transit 帧误提交时
     * 需要确认 cls 对飞行棋子的置信度水平）。
     */
    fun analyzeCellEx(
        corrected: Mat,
        r: Int,
        c: Int
    ): Pair<String?, PieceClsModel.ClsResult> {
        val cell = cropCell64(corrected, r, c)
        return try {
            val res = PieceClsModel.classifyCellEx(VisionInit.requireContext(), cell)
            res.key to res
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

    /** 直接裁格子中心 10x10 并转单通道灰度（不做 80x80→缩放；中心区即可反映落子变化）。
     *  产物可能被持久持有（GameState 基线），每次分配新 Mat，调用方负责 release。 */
    fun cropCellGray(corrected: Mat, r: Int, c: Int): Mat =
        cropCellGrayInto(corrected, r, c, Mat(CELL_PATCH, CELL_PATCH, CvType.CV_8UC1))

    /** diff 扫描热路径专用（2026-09-07 GC 优化）：同 cropCellGray 但写入调用方复用缓冲。
     *  仅限「当帧内用完即弃」场景（Recognition.kt 90 格循环），勿用于持久基线。 */
    fun cropCellGrayInto(corrected: Mat, r: Int, c: Int, dst: Mat): Mat {
        val (cx, cy) = correctedCenter(r, c)
        val px = cx.roundToInt()
        val py = cy.roundToInt()
        val sub = corrected.submat(py - 5, py + 5, px - 5, px + 5)
        Imgproc.cvtColor(sub, dst, Imgproc.COLOR_BGR2GRAY)
        sub.release() // 仅 submat 头，无像素拷贝
        return dst
    }

    /** diff 扫描 patch 复用缓冲工厂（10x10 灰度；供 Recognition.kt 90 格循环持有）。 */
    fun newCellPatchScratch(): Mat = Mat(CELL_PATCH, CELL_PATCH, CvType.CV_8UC1)

    /** 中心小图是否相对基线变化（base==null 即首帧 → 视为变化）。
     *  去均值处理：消除「上一步落子高亮」整格统一染色（只改亮度不改结构）造成的误触发，
     *  仅当棋子结构（边缘/纹理）真正变化时才判为变化。
     *  ⚠️ 2026-09-07 GC 优化：内部改为 object 级 scratch（消每格 3 个临时 Mat），
     *  单 worker 识别管线内串行调用，非线程安全，勿跨线程并发使用。 */
    fun cellChanged(cur: Mat, base: Mat?): Boolean {
        if (base == null) return true
        // 转 16S 避免零均值后负值被 8U 截断
        cur.convertTo(diffCur16, CvType.CV_16S)
        base.convertTo(diffBase16, CvType.CV_16S)
        Core.subtract(diffCur16, Scalar(Core.mean(diffCur16).`val`[0]), diffCur16)
        Core.subtract(diffBase16, Scalar(Core.mean(diffBase16).`val`[0]), diffBase16)
        Core.absdiff(diffCur16, diffBase16, diffAbs)
        Imgproc.threshold(diffAbs, diffAbs, CELL_PIXEL_DIFF, 255.0, Imgproc.THRESH_BINARY)
        return Core.countNonZero(diffAbs) >= CELL_MIN_CHANGED
    }

    // cellChanged 的 object 级 scratch（单 worker 识别管线串行使用，见上方线程安全注记）
    private val diffCur16 = Mat()
    private val diffBase16 = Mat()
    private val diffAbs = Mat()

    /** 遍历 90 格，返回 10x9 布局（YOLO cls 全量识别）。 */
    fun analyzeBoard(corrected: Mat): Board =
        Array(ROWS) { r -> Array<String?>(COLS) { c -> analyzeCell(corrected, r, c) } }

    /**
     * 布局日志格式化：实现见 game 包 formatLayout（Board.kt，纯函数便于 JVM 单测）。
     */
    fun formatLayout(board: Board, mySide: Side = Side.RED): List<String> =
        formatLayoutLines(board, mySide)
}
