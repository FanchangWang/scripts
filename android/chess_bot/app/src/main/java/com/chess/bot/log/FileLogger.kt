package com.chess.bot.log

import android.content.Context
import com.chess.bot.data.BotConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志：每次运行（前台服务启动）生成一个新会话文件，保留最近 MAX_FILES 个；
 * 单文件超过 MAX_BYTES 停止写入（截断）。格式：`yyyy-MM-dd HH:mm:ss.SSS [LEVEL] [模块] msg`。
 *
 * 位置 filesDir/logs/（App 私有，无需权限）；导出经 FileProvider + 系统分享面板。
 */
object FileLogger {

    private const val MAX_FILES = 3
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val PREFIX = "session-"

    private var writer: java.io.PrintWriter? = null
    private var bytes = 0L
    private var truncated = false
    private var current: File? = null

    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val nameFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }

    /** 开启新会话文件；清理过期文件。服务 onCreate 调用。 */
    @Synchronized
    fun start(context: Context) {
        stop()
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith(PREFIX) }
            ?.sortedBy { it.name }
            ?.dropLast(MAX_FILES - 1)
            ?.forEach { it.delete() }
        val file = File(dir, PREFIX + nameFmt.get()!!.format(Date()) + ".log")
        current = file
        writer = file.printWriter()
        bytes = 0
        truncated = false
        write(LogKind.INFO, LogTag.SYSTEM, "会话日志开始：$file")
    }

    /** 写一条（等级低于设置级别时丢弃；截断后静默丢弃）。 */
    fun write(kind: LogKind, tag: LogTag, msg: String) {
        val w = writer ?: return
        if (kind.rank() < BotConfig.data.fileLogLevel.rank()) return
        val line = "${fmt.get()!!.format(Date())} [${kind.name}] [${tag.cn}] $msg"
        synchronized(this) {
            if (writer == null || truncated) return
            w.println(line)
            bytes += line.length + 1
            if (bytes > MAX_BYTES) {
                truncated = true
                w.println("${fmt.get()!!.format(Date())} [WARN] [系统] 日志超过 5MB，已截断")
                w.flush()
                w.close()
                writer = null
            }
        }
    }

    /** 当前（或最近一个）会话文件；供导出分享。 */
    @Synchronized
    fun latestFile(context: Context): File? = retainedFiles(context).firstOrNull()

    /** 全部保留的会话文件（新→旧，最多 MAX_FILES 个）；供批量导出。 */
    @Synchronized
    fun retainedFiles(context: Context): List<File> {
        val dir = File(context.filesDir, "logs")
        return dir.listFiles { f -> f.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.name }
            ?.toList()
            ?: emptyList()
    }

    @Synchronized
    fun stop() {
        writer?.run { runCatching { flush(); close() } }
        writer = null
        current = null
    }
}
