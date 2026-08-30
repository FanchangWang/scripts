package com.chess.bot.book

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.chess.bot.game.Board
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import java.io.File

/** 开局库命中结果（已按命中通道把着法转换回真实坐标）。 */
data class BookMove(
    val iccs: String,
    val vscore: Int,
    val winRate: Float,
    val viaMirror: Boolean,
    val vkey: Long,
)

/**
 * OBK(兵河五四) 开局库读取器。
 *
 * 库文件 assets/start.obk（SQLite，293 万局面 / 约 134 MB，经 obk_optimize.py REINDEX+VACUUM 修复
 * idxkey 索引并清空 vmemo），首启拷贝到 filesDir 后只读打开。assets 中 .obk 已在 build.gradle.kts
 * 配置 noCompress（拷贝快、可取精确字节长度用于换库检测）。
 * 格式细节与查询策略均已实证（scripts/obk_check.py）：
 * - vkey 双存储：正键 INTEGER 直接存；负键按位转 Double 存 REAL——数值字面量查询两种形态都能命中
 *   （优化库中 SQLite 整数亲和性转换出的 INTEGER 负值行数值与 punned Double 精确相等）
 * - 查询走正常 + 左右镜像两个键（书通常只存一种朝向）；修复后 idxkey 索引健康，按 vkey 走索引点查
 * - 选着：候选按 vscore 降序取第 1（已确认决策）；vvalid=1 过滤
 */
class ObkBook private constructor(context: Context) {

    private val db: SQLiteDatabase

    init {
        val app = context.applicationContext
        val file = File(app.filesDir, BOOK_FILE_NAME)
        // assets 中 .obk 已配置 noCompress，可直接取精确字节长度；压缩资产取不到时退化为「仅判存在」
        val assetLen = runCatching {
            app.assets.openFd(BOOK_FILE_NAME).use { it.length }
        }.getOrNull()
        // 旧版本拷贝残留（换新库后私有目录旧文件仍在）→ 比对长度判定是否需重新拷贝
        val needCopy = !file.exists() || file.length() == 0L ||
                (assetLen != null && assetLen != file.length())
        if (needCopy) {
            LogBus.log(
                LogKind.INFO, LogTag.PLAY,
                "拷贝开局库 $BOOK_FILE_NAME 到私有目录" +
                        (if (file.exists()) "（检测到旧副本，长度不一致，重新拷贝）" else ""),
            )
            val tmp = File(app.filesDir, "$BOOK_FILE_NAME.tmp")
            app.assets.open(BOOK_FILE_NAME).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            if (assetLen != null && file.length() != assetLen) {
                LogBus.log(LogKind.WARN, LogTag.PLAY, "开局库拷贝后长度与资产不一致，可能拷贝不完整")
            }
        }
        db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    /**
     * 查询局面最优书着；未命中返回 null。redGo = 当前轮到红方。
     *
     * board 必须是 ICCS 标准方向（黑上红下、a 列在左）；我方执黑时屏幕棋盘须先 rotateBoard180 归一化，
     * 返回的 iccs 为标准 ICCS（黑上视角），调用方经 squareToGrid(iccs, mySide) 转回屏幕网格。
     */
    fun queryBest(board: Board, redGo: Boolean): BookMove? {
        val normalKey = TChessZobrist.zobrist(board, redGo, mirror = false)
        queryRow(normalKey, viaMirror = false)?.let { return it }
        val mirrorKey = TChessZobrist.zobrist(board, redGo, mirror = true)
        if (mirrorKey != normalKey) {
            queryRow(mirrorKey, viaMirror = true)?.let { return it }
        }
        return null
    }

    /** 单键查询（取 vscore 最高的一条）；数值字面量内嵌 SQL，规避参数绑定的 TEXT 亲和性歧义。 */
    private fun queryRow(key: Long, viaMirror: Boolean): BookMove? {
        // 正键用整数字面量；负键按位转 Double 用浮点字面量（Double.toString 可精确往返）
        val keyLiteral = if (key >= 0) key.toString() else doubleLiteral(key)
        val sql =
            "SELECT vmove, vscore, vwin, vdraw, vlost FROM bhobk " +
                    "WHERE vkey=$keyLiteral AND vvalid=1 ORDER BY vscore DESC, vwin DESC LIMIT 1"
        return try {
            db.rawQuery(sql, emptyArray()).use { c ->
                if (!c.moveToFirst()) return null
                val vmove = c.getInt(0)
                val vscore = c.getInt(1)
                val win = c.getInt(2)
                val draw = c.getInt(3)
                val lost = c.getInt(4)
                val iccs = TChessZobrist.vmoveToIccs(vmove, viaMirror) ?: return null
                val total = win + draw + lost
                val winRate = if (total > 0) (win + draw / 2f) / total else 0f
                BookMove(iccs, vscore, winRate, viaMirror, key)
            }
        } catch (e: Exception) {
            LogBus.log(LogKind.WARN, LogTag.PLAY, "开局库查询失败：${e.message}")
            null
        }
    }

    private fun doubleLiteral(key: Long): String =
        java.lang.Double.longBitsToDouble(key).toString()

    fun close() {
        runCatching { db.close() }
    }

    companion object {
        private const val BOOK_FILE_NAME = "start.obk"

        @Volatile
        private var instance: ObkBook? = null

        fun get(context: Context): ObkBook =
            instance ?: synchronized(this) {
                instance ?: ObkBook(context).also { instance = it }
            }
    }
}
