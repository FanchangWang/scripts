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
import android.os.HandlerThread
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 截屏管线：MediaProjection + VirtualDisplay(ImageReader)。
 *
 * 常驻缓存最新一帧（新帧直接覆盖旧帧），消费方按需取用——对齐 python 版
 * capture._take_screenshot 的"取当前屏幕"语义。帧回调运行在独立线程。
 */
class ScreenCaptureSource private constructor() {

    private val lock = Any()

    @Volatile
    private var latest: Bitmap? = null

    @Volatile
    private var started = false

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var bufferBitmap: Bitmap? = null

    fun start(context: Context, resultCode: Int, data: Intent): Boolean {
        if (started) return true
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val dpi = context.resources.displayMetrics.densityDpi

        val manager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp: MediaProjection =
            try {
                manager.getMediaProjection(resultCode, data)
                    ?: run {
                        com.chess.bot.log.LogBus.log(
                            com.chess.bot.log.LogKind.ERROR,
                            com.chess.bot.log.LogTag.SERVICE,
                            "getMediaProjection 返回 null",
                        )
                        return false
                    }
            } catch (e: Exception) {
                com.chess.bot.log.LogBus.log(
                    com.chess.bot.log.LogKind.ERROR,
                    com.chess.bot.log.LogTag.SERVICE,
                    "getMediaProjection 异常：${e::class.java.simpleName}: ${e.message}",
                )
                android.util.Log.e(TAG, "getMediaProjection 失败", e)
                return false
            }
        projection = mp

        val ht = HandlerThread("ScreenCapture").apply { start() }
        thread = ht
        handler = Handler(ht.looper)

        // 新版 Android 要求：必须在 createVirtualDisplay 之前注册回调
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户从系统面板停止投屏或授权被回收：清理并通知
                com.chess.bot.log.LogBus.log(
                    com.chess.bot.log.LogKind.WARN,
                    com.chess.bot.log.LogTag.SERVICE,
                    "屏幕捕获已停止（系统回收），请重新点击启动",
                )
                stop()
            }
        }, handler)

        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        newReader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                consume(image, width, height)
            } finally {
                image.close()
            }
        }, handler)
        reader = newReader

        display = mp.createVirtualDisplay(
            "chessbot-capture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            newReader.surface,
            null,
            handler,
        )

        started = true
        active.value = true
        com.chess.bot.log.LogBus.log(
            com.chess.bot.log.LogKind.DEBUG,
            com.chess.bot.log.LogTag.SERVICE,
            "VirtualDisplay 已创建：${width}x${height}@$dpi",
        )
        return true
    }

    /** 取最新一帧的独立副本；未启动时返回 null。副本避免与采集线程的复用缓冲互相覆盖。 */
    fun latest(): Bitmap? {
        synchronized(lock) { return latest?.copy(Bitmap.Config.ARGB_8888, false) }
    }

    fun stop() {
        if (!started && display == null) {
            active.value = false
            return
        }
        started = false
        active.value = false
        synchronized(lock) { latest = null }
        bufferBitmap = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun consume(image: android.media.Image, width: Int, height: Int) {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var bmp = bufferBitmap
        val bufferWidth = rowStride / pixelStride
        if (bmp == null || bmp.width != bufferWidth || bmp.height != height) {
            bmp = Bitmap.createBitmap(bufferWidth, height, Bitmap.Config.ARGB_8888)
            bufferBitmap = bmp
        }
        bmp.copyPixelsFromBuffer(plane.buffer)
        val snapshot =
            if (rowStride == width * pixelStride) {
                bmp
            } else {
                // 行对齐带 padding：裁出有效区域副本
                Bitmap.createBitmap(bmp, 0, 0, width, height)
            }
        synchronized(lock) { latest = snapshot }
    }

    companion object {
        private const val TAG = "ScreenCaptureSource"
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
