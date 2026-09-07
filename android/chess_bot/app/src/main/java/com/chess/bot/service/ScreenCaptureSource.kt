package com.chess.bot.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 截屏管线：MediaProjection + VirtualDisplay(ImageReader)。
 *
 * 2026-09-07 由「每渲染帧推送消费」改为「按需拉取」：VirtualDisplay 持续渲染进 ImageReader
 * 队列（队列满后由系统丢帧，不占 Java 堆），仅当调用方 [latest] 时 acquireLatestImage
 * 取最新帧——消除 60~120Hz 下每帧 ~10MB 的 copyPixelsFromBuffer/padding 副本分配
 * （治理 non sticky GC 日志刷屏，方案见 .workbuddy/gc_churn_reduction_plan.md）。
 *
 * latest() 返回独立副本（copy），消费方可安全持有；无新帧（画面静止）回落缓存。
 * 全部共享状态收进 synchronized(lock)（stop 可能来自主线程 MediaProjection 回调）。
 */
class ScreenCaptureSource private constructor() {

    private val lock = Any()

    @Volatile
    private var started = false

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0

    /** rowStride 对齐全宽缓冲（复用；padding 场景不再每帧重建）。 */
    private var bufferBitmap: Bitmap? = null

    /** 最新有效帧（无 padding 时即 bufferBitmap 本体；消费方拿到的是 copy）。 */
    private var latest: Bitmap? = null

    fun start(context: Context, resultCode: Int, data: Intent): Boolean {
        if (started) return true
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        width = bounds.width()
        height = bounds.height()
        val dpi = context.resources.displayMetrics.densityDpi

        val manager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp: MediaProjection =
            try {
                manager.getMediaProjection(resultCode, data)
                    ?: run {
                        com.chess.bot.log.LogBus.log(
                            com.chess.bot.log.LogLevel.ERROR,
                            com.chess.bot.log.LogTag.SERVICE,
                            "getMediaProjection 返回 null",
                        )
                        return false
                    }
            } catch (e: Exception) {
                com.chess.bot.log.LogBus.log(
                    com.chess.bot.log.LogLevel.ERROR,
                    com.chess.bot.log.LogTag.SERVICE,
                    "getMediaProjection 异常：${e::class.java.simpleName}: ${e.message}",
                )
                com.chess.bot.log.LogBus.log(
                    com.chess.bot.log.LogLevel.ERROR,
                    com.chess.bot.log.LogTag.SERVICE,
                    "getMediaProjection 失败：${e::class.java.simpleName}: ${e.message}"
                )
                return false
            }
        projection = mp

        // 新版 Android 要求：必须在 createVirtualDisplay 之前注册回调。
        // 注意：回调注册到【主线程 looper】——stop() 里会同步释放资源，锁内安全。
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户从系统面板停止投屏或授权被回收：清理并通知
                com.chess.bot.log.LogBus.log(
                    com.chess.bot.log.LogLevel.WARN,
                    com.chess.bot.log.LogTag.SERVICE,
                    "屏幕捕获已停止（系统回收），请重新点击启动",
                )
                stop()
            }
        }, Handler(Looper.getMainLooper()))

        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        // 拉模式：不注册 ImageAvailable 监听器，帧只在 latest() 被调用时消费
        reader = newReader

        display = mp.createVirtualDisplay(
            "chessbot-capture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            newReader.surface,
            null,
            null,
        )

        started = true
        active.value = true
        com.chess.bot.log.LogBus.log(
            com.chess.bot.log.LogLevel.DEBUG,
            com.chess.bot.log.LogTag.SERVICE,
            "VirtualDisplay 已创建（拉模式）：${width}x${height}@$dpi",
        )
        return true
    }

    /**
     * 取最新一帧的独立副本；未启动时返回 null。
     * 按需 acquireLatestImage：有新帧则刷新缓存，无新帧（画面静止）沿用缓存。
     * 副本避免消费方持有期间被下一帧覆盖。
     */
    fun latest(): Bitmap? {
        synchronized(lock) {
            if (!started) return null
            acquireLatestLocked()
            return latest?.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!started && display == null) {
                active.value = false
                return
            }
            started = false
            active.value = false
            display?.release()
            display = null
            reader?.close()
            reader = null
            projection?.stop()
            projection = null
            latest = null
            bufferBitmap = null
        }
    }

    /** 按需拉取最新帧写入缓存（须持 lock）。 */
    private fun acquireLatestLocked() {
        val r = reader ?: return
        // acquireLatestImage 自动丢弃并关闭队列中的旧帧；无新帧返回 null（沿用缓存）
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            // reader 已在 stop() 中关闭（锁内不会发生，防御性兜底）
            com.chess.bot.log.LogBus.log(
                com.chess.bot.log.LogLevel.WARN,
                com.chess.bot.log.LogTag.SERVICE,
                "acquireLatestImage 跳过已关闭 reader：${e.message}"
            )
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            var bmp = bufferBitmap
            val bufferWidth = rowStride / pixelStride
            if (bmp == null || bmp.width != bufferWidth || bmp.height != height) {
                // 分辨率/行对齐变化（少见：旋转/跨会话）：latest 可能与旧缓冲同体，一并作废
                bmp?.recycle()
                latest = null
                bmp = Bitmap.createBitmap(bufferWidth, height, Bitmap.Config.ARGB_8888)
                bufferBitmap = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)
            latest =
                if (rowStride == width * pixelStride) {
                    bmp
                } else {
                    // 行对齐带 padding：裁出有效区域副本
                    Bitmap.createBitmap(bmp, 0, 0, width, height)
                }
        } catch (e: IllegalStateException) {
            com.chess.bot.log.LogBus.log(
                com.chess.bot.log.LogLevel.WARN,
                com.chess.bot.log.LogTag.SERVICE,
                "consume 跳过已关闭帧：${e.message}"
            )
        } finally {
            image.close()
        }
    }

    companion object {
        private const val MAX_IMAGES = 2

        /** 截屏管线是否运行中（供 UI 同步状态）。 */
        val active = MutableStateFlow(false)

        @Volatile
        private var instance: ScreenCaptureSource? = null

        fun get(): ScreenCaptureSource =
            instance ?: synchronized(this) {
                instance ?: ScreenCaptureSource().also { instance = it }
            }
    }
}
