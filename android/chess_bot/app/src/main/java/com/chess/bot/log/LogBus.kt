package com.chess.bot.log

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志 Kind：纯级别 5 档 + 3 个专用事件。
 * - DEBUG：技术细节（FEN、点击坐标、识别布局、逐帧变动），悬浮窗弱化显示
 * - INFO / OK / WARN / ERROR：常规级别（OK = 初始化成功等正向结果）
 * - MOVE：我方着法（中文记谱 + 评估分）
 * - ENEMY：对方着法
 * - GAME：对局节点（开局确认 / 下一局开始 / 对局结束）
 */
enum class LogKind {
    DEBUG, INFO, OK, WARN, ERROR, MOVE, ENEMY, GAME;

    /** 等级序（文件日志级别过滤用；事件级按 INFO 归档）。 */
    fun rank(): Int = when (this) {
        DEBUG -> 0
        INFO, OK, MOVE, ENEMY, GAME -> 1
        WARN -> 2
        ERROR -> 3
    }
}

/** 模块标签：日志来源，悬浮窗中作为前缀展示（替代手工「[校准]」等前缀）。 */
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
    val kind: LogKind,
    val tag: LogTag,
    val msg: String,
    /** HH:mm:ss，由 LogBus 统一生成。 */
    val time: String,
)

object LogBus {
    private const val REPLAY = 100
    private const val LOGCAT_TAG = "ChessBot"

    private val _events = MutableSharedFlow<LogEvent>(replay = REPLAY, extraBufferCapacity = 256)
    val events: SharedFlow<LogEvent> = _events.asSharedFlow()

    /** SimpleDateFormat 非线程安全：按工作线程各自持有一份。 */
    private val timeFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun log(kind: LogKind, tag: LogTag, msg: String) {
        val event = LogEvent(kind, tag, msg, timeFmt.get()!!.format(Date()))
        _events.tryEmit(event)
        mirrorToLogcat(event)
        writeToSessionFile(kind, tag, msg)
    }

    /** 会话文件落盘（FileLogger 未启动时为空操作；任何异常不得影响主流程）。 */
    private fun writeToSessionFile(kind: LogKind, tag: LogTag, msg: String) {
        runCatching { FileLogger.write(kind, tag, msg) }
    }

    /** 同步镜像一条到 adb logcat（tag=ChessBot）。 */
    private fun mirrorToLogcat(event: LogEvent) {
        val priority = when (event.kind) {
            LogKind.ERROR -> Log.ERROR
            LogKind.WARN -> Log.WARN
            LogKind.DEBUG -> Log.DEBUG
            else -> Log.INFO
        }
        runCatching {
            Log.println(priority, LOGCAT_TAG, "[${event.kind.name}/${event.tag.name}] ${event.msg}")
        }
    }
}
