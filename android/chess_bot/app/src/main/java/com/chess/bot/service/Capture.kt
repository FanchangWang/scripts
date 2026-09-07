package com.chess.bot.service

import android.content.Context
import android.graphics.Bitmap
import com.chess.bot.accessibility.BotAccessibilityServiceHolder
import com.chess.bot.game.Const
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
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

    /** 上次和棋检查时间（纳秒），节流用。 */
    private var lastDrawCheckAt = 0L

    /** rgba 中转 Mat 缓存（2026-09-07 GC 优化：同分辨率复用，消每次 grab ~10MB 分配；Capture 随会话销毁）。 */
    private var rgbaCache: Mat? = null
    private var rgbaKey: Pair<Int, Int>? = null

    /** 原始最新帧（供文字识别）。 */
    fun screenshot(): Bitmap? = ScreenCaptureSource.get().latest()

    /** 截图 → 矫正，返回矫正后棋盘 Mat（和棋弹窗检查已移出为事件触发，见 dismissDrawDialog）。 */
    suspend fun grab(): Mat? {
        // 任何 Mat 使用前必须确保 OpenCV native 已加载（新进程首个入口就在这里）
        if (!VisionInit.init(context)) return null
        val bmp = screenshot() ?: return null
        // 矫正完成后 raw Bitmap（~10MB）即刻归还（D2=A：调用点显式 recycle）
        val dst = try {
            correct(bmp)
        } finally {
            bmp.recycle()
        }
        return dst
    }

    /** 透视矫正（缓存 homography）。 */
    fun correct(raw: Bitmap): Mat? {
        if (!VisionInit.init(context)) return null
        val h = Homography.get(raw.width, raw.height)
        homography = h
        val key = raw.width to raw.height
        val rgba = if (rgbaKey == key) rgbaCache!! else Mat().also { rgbaCache = it; rgbaKey = key }
        val src = VisionInit.bitmapToBgr(raw, rgba)
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

    /** 点击网格格心（逆透视映射 + 无障碍手势）。 */
    fun tap(r: Int, c: Int): Boolean {
        val h = homography ?: run {
            LogBus.log(LogLevel.ERROR, LogTag.INPUT, "尚无棋盘坐标信息，请先启动截屏")
            return false
        }
        val (x, y) = Homography.tapXy(h, r, c)
        LogBus.log(LogLevel.DEBUG, LogTag.INPUT, "点击 ($x,$y)")
        return tapXy(x, y)
    }

    fun tapXy(x: Int, y: Int): Boolean =
        BotAccessibilityServiceHolder.instance?.tapSync(x, y) ?: false

    /** 发送返回键（遮罩消除用）。 */
    fun back(): Boolean =
        BotAccessibilityServiceHolder.back() ?: false

    /**
     * 和棋弹窗检查（事件触发，2026-08-29 T1：不再每帧全图匹配）。
     * 2026-09-06 改 OCR：「对方请求和棋」+「同意」+「拒绝」三词同现才认定弹窗（TextMatcher.matchDrawDialog）；
     * 按 decideDrawReject 决策点击（OCR 框中心），循环直到弹窗消失。
     * 内置 ≥1s 节流：异常帧可能连环触发，避免退化为每帧检查。返回是否处理过弹窗。
     */
    suspend fun dismissDrawDialog(): Boolean {
        val now = System.nanoTime()
        if (now - lastDrawCheckAt < Const.DRAW_CHECK_THROTTLE_MS * 1_000_000) return false
        lastDrawCheckAt = now
        val first = screenshot() ?: return false
        var buttons = TextMatcher.findDrawDialog(context, first)
        first.recycle() // 2026-09-07 D2=A：raw Bitmap 用完即还
        if (buttons.isEmpty()) return false // 三词不全，不是和棋页面
        val reject = decideDrawReject()
        var count = 0
        while (shouldContinue()) {
            count++
            val target = if (reject) {
                buttons.first { it.word == Const.DRAW_REJECT_WORD }
            } else {
                buttons.first { it.word == Const.DRAW_ACCEPT_WORD }
            }
            LogBus.log(
                LogLevel.INFO,
                LogTag.INPUT,
                "检测到和棋弹窗，点击「${if (reject) "拒绝" else "同意"}」（第 $count 次）",
            )
            if (!tapXy(target.x, target.y)) break // 点击失败即中止
            delay(Const.DRAW_DIALOG_SETTLE_MS)
            val img = screenshot() ?: break
            buttons = TextMatcher.findDrawDialog(context, img)
            img.recycle() // 2026-09-07 D2=A：raw Bitmap 用完即还
            if (buttons.isEmpty()) break // 弹窗已消失
        }
        return true
    }
}
