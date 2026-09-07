package com.chess.bot.log

import android.content.Context
import com.chess.bot.data.BotConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志（2026-09-07 调整）：每次从主页「开始对弈」弹出前台服务时**清空全部历史日志**，
 * 只保留本次对弈；单文件超过 MAX_BYTES 轮转分片（不再截断），最多保留 MAX_FILES 个分片，
 * 超出时删除最早的一片。文件名 `session-<ts>-<NNN>.log`，NNN 序号保证字典序=时间序。
 *
 * 位置 filesDir/logs/（App 私有，无需权限）；导出经 FileProvider + 系统分享面板。
 * 校准模式启动的前台服务不开会话日志（不清历史、不写文件）。
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

    /** 开启新会话：清空全部历史会话文件（只保留最后一次对弈），创建第一个分片。 */
    @Synchronized
    fun start(context: Context) {
        stop()
        val d = File(context.filesDir, "logs").apply { mkdirs() }
        dir = d
        d.listFiles { f -> f.name.startsWith(PREFIX) }?.forEach { it.delete() }
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
        write(LogKind.INFO, LogTag.SYSTEM, "会话日志开始（分片 #$seq）：${file.name}")
        d.listFiles { f -> f.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_FILES)
            ?.forEach { it.delete() }
    }

    /** 写一条（等级低于设置级别时丢弃；超 5MB 自动轮转到下一分片）。 */
    fun write(kind: LogKind, tag: LogTag, msg: String) {
        val w = writer ?: return
        if (kind.rank() < BotConfig.data.fileLogLevel.rank()) return
        val line = "${fmt.get()!!.format(Date())} [${kind.name}] [${tag.cn}] $msg"
        synchronized(this) {
            val out = writer ?: return
            out.println(line)
            bytes += line.length + 1
            if (bytes > MAX_BYTES) {
                out.println(
                    "${
                        fmt.get()!!.format(Date())
                    } [WARN] [系统] 单文件超过 5MB，轮转到下一分片"
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
