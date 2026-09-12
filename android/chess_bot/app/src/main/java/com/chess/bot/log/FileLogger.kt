package com.chess.bot.log

import android.content.Context
import com.chess.bot.data.BotConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志（2026-09-12 调整）：**不再清空历史**——每次从主页「开始对弈」弹出前台服务时只新建
 * 一个会话分片，历史分片全部保留；单文件超过 MAX_BYTES 轮转分片（不再截断），最多保留
 * MAX_FILES 个分片（**跨会话滚动**，按文件名时间序取最新 5 个），超出时删除最早的一片。
 * 文件名 `session-<ts>-<NNN>.log`，NNN 序号保证字典序=时间序。
 *
 * 位置 filesDir/logs/（App 私有，无需权限）；导出经 FileProvider + 系统分享面板。
 * 校准模式启动的前台服务不开会话日志（不写文件）。
 */
object FileLogger {

    private const val MAX_FILES = 5
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val PREFIX = "session-"

    private var writer: java.io.PrintWriter? = null
    private var bytes = 0L
    private var current: File? = null
    private var dir: File? = null
    private var seq = 1

    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val nameFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }

    /**
     * 开启新会话：**保留全部历史分片**，只创建本会话的第一个分片（2026-09-12 起不再清空历史）。
     * 分片总数上限由 [openNew] 按文件名时间序滚动淘汰到 MAX_FILES。
     */
    @Synchronized
    fun start(context: Context) {
        stop()
        val d = File(context.filesDir, "logs").apply { mkdirs() }
        dir = d
        seq = 1
        openNew()
    }

    /** 打开第 seq 个分片文件；超出 MAX_FILES 时删除最旧分片。须持锁调用。 */
    private fun openNew() {
        val d = dir ?: return
        val file = File(d, "$PREFIX${nameFmt.get()!!.format(Date())}-%03d.log".format(seq))
        current = file
        writer = file.printWriter()
        bytes = 0
        write(LogLevel.INFO, LogTag.SYSTEM, "会话日志开始（分片 #$seq）：${file.name}")
        d.listFiles { f -> f.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_FILES)
            ?.forEach { it.delete() }
    }

    /** 写一条（等级低于设置级别时丢弃；超 5MB 自动轮转到下一分片）。 */
    fun write(level: LogLevel, tag: LogTag, msg: String) {
        val w = writer ?: return
        if (level.priority < BotConfig.data.fileLogLevel.priority) return
        val line = "${fmt.get()!!.format(Date())} ${level.letter} [${tag.name}] $msg"
        synchronized(this) {
            val out = writer ?: return
            out.println(line)
            bytes += line.length + 1
            if (bytes > MAX_BYTES) {
                out.println(
                    "${
                        fmt.get()!!.format(Date())
                    } ${LogLevel.WARN.letter} [${LogTag.SYSTEM.name}] 单文件超过 5MB，轮转到下一分片"
                )
                out.flush()
                out.close()
                writer = null
                seq += 1
                openNew()
            }
        }
    }

    /** 当前（或最近一个）会话文件；供导出分享。 */
    @Synchronized
    fun latestFile(context: Context): File? = retainedFiles(context).firstOrNull()

    /** 全部保留的会话分片（新→旧，最多 MAX_FILES 个）；供批量导出。 */
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
