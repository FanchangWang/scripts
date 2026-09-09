package com.chess.bot.vision

import android.content.Context
import android.graphics.Bitmap
import com.chess.bot.data.BoardCornersStore
import com.chess.bot.game.Const
import kotlin.math.hypot

/**
 * 棋盘几何一致性守卫（2026-09-08 结束动画缩小棋盘误开局防护）。
 *
 * 背景：JJ 象棋终局动画末期棋盘缩至 ~80%，摆棋稳定扫描会把上局终局盘面当成新一局——
 * 内容级校验（子数/将帅同现/逐值稳定）对几何变形免疫：缩小棋盘的布局就是上局残局，
 * 合法、将帅双全、静止。误开局后以校准四角采样全部错位 → 点击落空 → verify 满屏 NOISY。
 *
 * 方案（用户批复 D1=A / D2=YOLO det / D3=10px）：摆棋稳定接受前用 [CornerDetModel] 重定位四角，
 * 与校准四角（[BoardCornersStore]，与 Homography 同源）逐点比较，最大偏差 ≤
 * [Const.BOARD_GEOMETRY_TOL_PX] 才接受；否则视为结束动画/缩放棋盘，继续等待。
 *
 * det 选型依据：det 检测的是棋盘角点本身（tl/tr/bl/br 四类，不依赖角上有子），残局空角可检出；
 * 模板匹配依赖角子（b_r/r_R 車），残局四角无子必失配，不可用。
 * 成本：det 一次推理仅在接受新局时执行（每局 1 次，~几十 ms），不在热路径。
 */
object BoardGeometryGuard {

    /** 校验结论：PASS=几何一致；MISMATCH=四角偏移超容差（缩放/位移）；NO_DETECT=det 未检出或无校准数据。 */
    enum class Verdict { PASS, MISMATCH, NO_DETECT }

    /** 校验结果：verdict + MISMATCH 时的最大偏差（px），供日志。 */
    data class Result(val verdict: Verdict, val maxDevPx: Double? = null)

    /** 纯函数：同序（TL,TR,BL,BR）两组四角的最大逐点欧氏距离（px）。 */
    fun maxDeviation(
        detected: List<Pair<Double, Double>>,
        calibrated: List<Pair<Double, Double>>,
    ): Double {
        require(detected.size == 4 && calibrated.size == 4) { "四角需恰好 4 个点" }
        var max = 0.0
        for (i in 0 until 4) {
            val d = hypot(
                detected[i].first - calibrated[i].first,
                detected[i].second - calibrated[i].second
            )
            if (d > max) max = d
        }
        return max
    }

    /**
     * 设备侧校验：raw 全屏截图 → det 四角 → 与校准四角比对。
     * det 异常/无输出/几何不合理/无校准数据 → NO_DETECT（调用方按未通过处理，继续等待；
     * autoNext 有 180s 总超时兜底，不会死锁）。
     */
    fun verify(context: Context, raw: Bitmap): Result {
        val w = raw.width
        val h = raw.height
        val calibrated = BoardCornersStore.get(w, h) ?: return Result(Verdict.NO_DETECT)
        val bgr = VisionInit.bitmapToBgr(raw)
        val detected = try {
            CornerDetModel.detectCorners(context, bgr)
        } catch (e: Exception) {
            null
        } finally {
            bgr.release()
        } ?: return Result(Verdict.NO_DETECT)
        if (!BoardCornerDetector.isPlausibleQuad(detected, w, h)) return Result(Verdict.NO_DETECT)
        val dev = maxDeviation(detected, calibrated)
        return if (dev <= Const.BOARD_GEOMETRY_TOL_PX) {
            Result(Verdict.PASS, dev)
        } else {
            Result(Verdict.MISMATCH, dev)
        }
    }
}
