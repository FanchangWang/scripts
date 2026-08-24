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

/** OpenCV 初始化 + 模板资产加载（BGR Mat，与 python cv2 语义一致）。 */
object VisionInit {

    @Volatile
    private var initialized = false

    @Volatile
    private var pieceTemplates: Map<String, Mat>? = null

    fun init(context: Context): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (initialized) return true
            if (!OpenCVLoader.initLocal()) {
                LogBus.log(LogKind.ERROR, LogTag.VISION, "OpenCV 本地库初始化失败")
                return false
            }
            initialized = true
            BoardCornersStore.attach(context)
            LogBus.log(LogKind.OK, LogTag.VISION, "OpenCV 已初始化")
        }
        return true
    }

    /** 懒加载棋子模板（assets 下 templates 目录的 png，BGR）。 */
    fun loadPieceTemplates(context: Context): Map<String, Mat> {
        pieceTemplates?.let { return it }
        return synchronized(this) {
            pieceTemplates?.let { return it }
            init(context)
            val map = mutableMapOf<String, Mat>()
            val names = context.assets.list("templates") ?: emptyArray()
            for (name in names.sorted()) {
                if (!name.endsWith(".png")) continue
                val id = name.removeSuffix(".png")
                val bytes = context.assets.open("templates/$name").use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val rgba = Mat()
                Utils.bitmapToMat(bmp, rgba)
                val bgr = Mat()
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                bmp.recycle()
                map[id] = bgr
            }
            LogBus.log(LogKind.DEBUG, LogTag.VISION, "已加载 ${map.size} 张棋子模板")
            pieceTemplates = map
            map
        }
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
