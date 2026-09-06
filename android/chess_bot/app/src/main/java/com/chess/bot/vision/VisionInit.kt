package com.chess.bot.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.chess.bot.data.BoardCornersStore
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * OpenCV 初始化 + 资产加载（BGR Mat，与 python cv2 语义一致）。
 *
 * 2026-09-05：棋子识别已由 YOLO cls 替代，assets 下的棋子模板仅保留各皮肤套的
 * b_r/r_R 两张角子模板（assets/templates/set_NN/），仅供四角校准 BoardCornerDetector 使用。
 */
object VisionInit {

    @Volatile
    private var initialized = false

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var cornerSets: List<CornerSet>? = null

    /** 一套皮肤的两个角子模板：黑車(上方两角) / 红俥(下方两角)。 */
    data class CornerSet(val name: String, val bR: Mat, val rR: Mat)

    fun init(context: Context): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (initialized) return true
            if (!OpenCVLoader.initLocal()) {
                LogBus.log(LogKind.ERROR, LogTag.VISION, "OpenCV 本地库初始化失败")
                return false
            }
            initialized = true
            appCtx = context.applicationContext
            BoardCornersStore.attach(context)
            LogBus.log(LogKind.OK, LogTag.VISION, "OpenCV 已初始化")
        }
        return true
    }

    /** 已注入的应用上下文（未初始化时抛错，识别入口应在 init 之后调用）。 */
    fun requireContext(): Context =
        appCtx ?: throw IllegalStateException("VisionInit 未初始化，请先调用 init()")

    /** 懒加载角子模板套（assets/templates/set_NN/{b_r,r_R}.png，BGR）。 */
    fun loadCornerTemplateSets(context: Context): List<CornerSet> {
        cornerSets?.let { return it }
        return synchronized(this) {
            cornerSets?.let { return it }
            init(context)
            val sets = mutableListOf<CornerSet>()
            val names = context.assets.list("templates") ?: emptyArray()
            for (name in names.sorted()) {
                if (!name.startsWith("set_")) continue
                val bR = loadBgr(context, "templates/$name/b_r.png") ?: continue
                val rR = loadBgr(context, "templates/$name/r_R.png") ?: continue
                sets.add(CornerSet(name, bR, rR))
            }
            LogBus.log(LogKind.DEBUG, LogTag.VISION, "已加载 ${sets.size} 套角子模板")
            cornerSets = sets
            sets
        }
    }

    private fun loadBgr(context: Context, path: String): Mat? {
        val bytes = try {
            context.assets.open(path).use { it.readBytes() }
        } catch (e: Exception) {
            return null
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        bmp.recycle()
        return bgr
    }

    /** Bitmap -> BGR Mat。 */
    fun bitmapToBgr(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }
}
