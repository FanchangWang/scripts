package com.chess.bot.data

import android.content.Context
import com.chess.bot.game.Const
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 棋盘四角坐标持久化：App 私有目录 board_corners.json（无需存储权限）。
 *
 * 解析优先级：手动校准 JSON > Const.BOARD_CORNERS 硬编码。
 * 即手动校准可覆盖任一内置分辨率；两者皆无则返回 null（由调用方引导校准）。
 */
object BoardCornersStore {

    private const val FILE_NAME = "board_corners.json"

    @Volatile
    private var appContext: Context? = null

    /** 由 VisionInit.init 等早期入口注入，供无参 get 使用。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    /** 读取某分辨率的四角；手动校准优先，其次硬编码；均无返回 null。 */
    fun get(width: Int, height: Int, context: Context? = null): List<Pair<Double, Double>>? {
        val ctx = context?.applicationContext ?: appContext
        val key = "${width}x${height}"
        ctx?.let {
            runCatching {
                val f = file(it)
                if (f.exists()) {
                    val obj = JSONObject(f.readText())
                    if (obj.has(key)) return parseEntry(obj.getJSONArray(key))
                }
            }.onFailure { e ->
                LogBus.log(
                    LogKind.WARN,
                    LogTag.CALIB,
                    "读取校准 JSON 失败：${e.message}"
                )
            }
        }
        return Const.BOARD_CORNERS[width to height]
    }

    /** 是否已有校准（手动或硬编码均可）。 */
    fun has(width: Int, height: Int, context: Context? = null): Boolean =
        get(width, height, context) != null

    /** 写入某分辨率四角，合并保留其它分辨率；原子写（临时文件 + rename）。 */
    fun put(
        width: Int,
        height: Int,
        corners: List<Pair<Double, Double>>,
        context: Context? = null
    ) {
        val ctx = context?.applicationContext ?: appContext
        ?: throw IllegalStateException("BoardCornersStore 未注入 Context")
        require(corners.size == 4) { "四角需恰好 4 个点" }
        val f = file(ctx)
        val obj =
            if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        obj.put("${width}x${height}", buildEntry(corners))
        val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(obj.toString(2))
        tmp.renameTo(f)
        LogBus.log(LogKind.OK, LogTag.CALIB, "已保存 ${width}x${height} 四角校准结果")
    }

    private fun parseEntry(arr: JSONArray): List<Pair<Double, Double>> =
        List(4) { i -> val o = arr.getJSONArray(i); o.getDouble(0) to o.getDouble(1) }

    private fun buildEntry(corners: List<Pair<Double, Double>>): JSONArray =
        JSONArray().apply { corners.forEach { (x, y) -> put(JSONArray().apply { put(x); put(y) }) } }
}
