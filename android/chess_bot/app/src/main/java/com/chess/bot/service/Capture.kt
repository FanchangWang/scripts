package com.chess.bot.service

import android.content.Context
import android.graphics.Bitmap
import com.chess.bot.game.Const
import com.chess.bot.log.LogBus
import com.chess.bot.accessibility.BotAccessibilityServiceHolder
import com.chess.bot.log.LogKind
import com.chess.bot.vision.Homography
import com.chess.bot.vision.TextMatcher
import com.chess.bot.vision.VisionInit
import kotlinx.coroutines.delay
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 设备侧截图/矫正/点击/和棋弹窗处理（移植 python capture.py）。
 *
 * shouldContinue：和棋弹窗循环的中断条件（由 BotSession 提供）；
 * decideDrawReject：和棋决策回调（true=拒绝）。
 */
class Capture(
    private val context: Context,
    private val shouldContinue: () -> Boolean,
    private val decideDrawReject: () -> Boolean,
) {

    @Volatile
    var homography: Mat? = null
        private set

    /** 原始最新帧（供文字识别）。 */
    fun screenshot(): Bitmap? = ScreenCaptureSource.get().latest()

    /** 截图 → 和棋弹窗处理 → 矫正，返回矫正后棋盘 Mat。 */
    suspend fun grab(): Mat? {
        // 任何 Mat 使用前必须确保 OpenCV native 已加载（新进程首个入口就在这里）
        if (!VisionInit.init(context)) return null
        var bmp = screenshot() ?: return null
        bmp = dismissDraw(bmp)
        return correct(bmp)
    }

    /** 透视矫正（缓存 homography）。 */
    fun correct(raw: Bitmap): Mat? {
        if (!VisionInit.init(context)) return null
        val h = Homography.get(raw.width, raw.height)
        homography = h
        val src = VisionInit.bitmapToBgr(raw)
        val dst = Mat()
        Imgproc.warpPerspective(src, dst, h, Size(Const.CORRECT_W.toDouble(), Const.CORRECT_H.toDouble()))
        src.release()
        return dst
    }

    /** 点击网格格心（逆透视映射 + 无障碍手势）。 */
    fun tap(r: Int, c: Int): Boolean {
        val h = homography ?: run {
            LogBus.log(LogKind.ERROR, "尚无棋盘坐标信息，请先启动截屏")
            return false
        }
        val (x, y) = Homography.tapXy(h, r, c)
        LogBus.log(LogKind.INFO, "点击 ($x,$y)")
        return tapXy(x, y)
    }

    fun tapXy(x: Int, y: Int): Boolean =
        BotAccessibilityServiceHolder.instance?.tapSync(x, y) ?: false

    /** 发送返回键（遮罩消除用）。 */
    fun back(): Boolean =
        BotAccessibilityServiceHolder.back() ?: false

    /**
     * 和棋弹窗处理：同意+拒绝两按钮同时存在才认定；按 decideDrawReject 决策点击，
     * 循环直到弹窗消失（点击失败即中止）。返回最终截图与点击次数。
     */
    private suspend fun dismissDraw(img0: Bitmap): Bitmap {
        var img = img0
        var count = 0
        var reject: Boolean? = null
        while (shouldContinue()) {
            val hits = TextMatcher.findDrawDialog(context, img)
            val accept = hits.firstOrNull { it.word == "和棋_同意" }
            val deny = hits.firstOrNull { it.word == "和棋_拒绝" }
            if (accept == null || deny == null) break // 两按钮不全，不是和棋页面
            if (reject == null) {
                reject = decideDrawReject()
            }
            val target = if (reject) deny else accept
            count++
            LogBus.log(
                LogKind.INFO,
                "检测到和棋弹窗，点击${if (reject) "拒绝" else "同意"} (${target.x},${target.y})（第 $count 次）",
            )
            if (!tapXy(target.x, target.y)) break // 点击失败即中止
            delay(Const.MOVE_SETTLE_MS)
            img = screenshot() ?: break
        }
        return img
    }
}
