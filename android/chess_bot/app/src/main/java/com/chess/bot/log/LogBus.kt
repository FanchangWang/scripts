package com.chess.bot.log

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志级别：纯严重度 5 档，1:1 映射 Android logcat 原生级别（priority + 单字母）。
 * - VERBOSE：最细碎（默认不落文件、logcat 也基本不用）
 * - DEBUG：技术细节（FEN、点击坐标、识别布局、逐帧变动）
 * - INFO：常规信息（原 OK/MOVE/ENEMY/GAME 等事件类别统一归 INFO，靠 LogTag 区分模块）
 * - WARN：告警（异常分支兜底、重试、降级）
 * - ERROR：错误（引擎/识别/点击失败，需关注）
 *
 * 事件类别（我方走棋/对方走棋/对局节点）不再作为独立级别，统一用 INFO + 对应 LogTag（SELF/ENEMY/PLAY…）表达；
 * logcat 不额外打印自定义级别，级别走原生 D/I/W/E 列。
 */
enum class LogLevel(val priority: Int, val letter: Char) {
    VERBOSE(Log.VERBOSE, 'V'),
    DEBUG(Log.DEBUG, 'D'),
    INFO(Log.INFO, 'I'),
    WARN(Log.WARN, 'W'),
    ERROR(Log.ERROR, 'E');
}

/** 模块标签：日志来源，logcat/文件均作为 [TAG] 前缀展示（替代手工「[校准]」等前缀）。 */
enum class LogTag(val cn: String) {
    SYSTEM("系统"),
    SERVICE("服务"),
    ENGINE("引擎"),
    VISION("视觉"),
    CALIB("校准"),
    PLAY("对局"),
    SELF("我方"),
    ENEMY("对方"),
    INPUT("交互"),
    NEXT("下一局"),
}

data class LogEvent(
    val level: LogLevel,
    val tag: LogTag,
    val msg: String,
    /** HH:mm:ss，由 LogBus 统一生成。 */
    val time: String,
)

object LogBus {
    private const val LOGCAT_TAG = "ChessBot"

    private val timeFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun log(level: LogLevel, tag: LogTag, msg: String) {
        val event = LogEvent(level, tag, msg, timeFmt.get()!!.format(Date()))
        mirrorToLogcat(event)
        writeToSessionFile(event)
    }

    /** 会话文件落盘（FileLogger 未启动时为空操作；任何异常不得影响主流程）。 */
    private fun writeToSessionFile(event: LogEvent) {
        runCatching { FileLogger.write(event.level, event.tag, event.msg) }
    }

    /** 同步镜像一条到 adb logcat（tag=ChessBot；级别走原生列，不额外打印自定义级别）。 */
    private fun mirrorToLogcat(event: LogEvent) {
        runCatching {
            Log.println(event.level.priority, LOGCAT_TAG, "[${event.tag.name}] ${event.msg}")
        }
    }
}
