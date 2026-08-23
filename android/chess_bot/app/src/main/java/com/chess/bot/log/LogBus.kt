package com.chess.bot.log

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class LogKind {
    INFO, OK, WARN, ERROR, MOVE, ENEMY, GAMEOVER,
}

data class LogEvent(val kind: LogKind, val msg: String)

object LogBus {
    private const val REPLAY = 100
    private const val LOGCAT_TAG = "ChessBot"

    private val _events = MutableSharedFlow<LogEvent>(replay = REPLAY, extraBufferCapacity = 256)
    val events: SharedFlow<LogEvent> = _events.asSharedFlow()

    fun log(kind: LogKind, msg: String) {
        _events.tryEmit(LogEvent(kind, msg))
        mirrorToLogcat(kind, msg)
    }

    /** 同步镜像一条到 adb logcat（tag=ChessBot）。 */
    private fun mirrorToLogcat(kind: LogKind, msg: String) {
        val priority = when (kind) {
            LogKind.ERROR -> Log.ERROR
            LogKind.WARN -> Log.WARN
            else -> Log.INFO
        }
        runCatching { Log.println(priority, LOGCAT_TAG, "[${kind.name}] $msg") }
    }
}
