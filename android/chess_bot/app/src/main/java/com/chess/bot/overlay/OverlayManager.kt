package com.chess.bot.overlay

import android.content.Context
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.chess.bot.accessibility.BotAccessibilityService
import com.chess.bot.data.BotSettings
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogEvent
import com.chess.bot.log.LogKind

/** 跨悬浮窗/后续 BotSession 共享的运行态。 */
object BotRuntime {
    /** 自动对弈是否运行中（M2 为占位开关，M5 接真实状态机）。 */
    val running = MutableStateFlow(false)

    /** 自动下一局开关（默认开，DataStore 持久化）。 */
    val autoNext = MutableStateFlow(true)

    /** 悬浮日志窗状态行：阶段 / 阵营 / 运行状态。 */
    val statusLine = MutableStateFlow("未同步 · 未运行")

    /** 无法推断轮次时悬浮日志窗弹出确认卡片。 */
    val pendingTurnConfirm = MutableStateFlow(false)
}

/**
 * 悬浮窗总管：操作条 + 日志窗的创建、拖动、回调与运行态。
 * 生命周期跟随前台服务。
 */
object OverlayManager {

    private var appContext: Context? = null
    private var controlHost: OverlayHost? = null
    private var logHost: OverlayHost? = null
    private var dialogHost: OverlayHost? = null
    private var settings: BotSettings? = null

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val botDispatcher = newSingleThreadContext("bot-worker")
    private val botScope = CoroutineScope(
        SupervisorJob() + botDispatcher + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            LogBus.log(LogKind.ERROR, "后台任务未捕获异常：${e::class.java.simpleName}: ${e.message}")
            android.util.Log.e("OverlayManager", "uncaught in botScope", e)
        },
    )

    private val logs = mutableStateListOf<LogEvent>()
    private val collapsed = mutableStateOf(false)

    /** 操作条收起态：收起后仅剩右缘「◀」小圆钮。 */
    private val barCollapsed = mutableStateOf(false)

    /** 退出内联确认文案；null=正常态。 */
    private val exitPrompt = mutableStateOf<String?>(null)
    private var exitPromptJob: kotlinx.coroutines.Job? = null

    private var session: com.chess.bot.game.BotSession? = null

    fun ensureSession(context: Context): com.chess.bot.game.BotSession =
        session ?: synchronized(this) {
            session ?: com.chess.bot.game.BotSession(context.applicationContext).also { session = it }
        }

    /** 显示（或恢复显示）全部悬浮窗；幂等。 */
    fun ensureShown(context: Context) {
        val ctx = context.applicationContext
        if (appContext == null) {
            appContext = ctx
            settings = BotSettings(ctx)
            uiScope.launch { BotRuntime.autoNext.value = settings!!.autoNextEnabled.first() }
            uiScope.launch {
                LogBus.events.collect { event ->
                    logs.add(event)
                    while (logs.size > 100) logs.removeAt(0)
                }
            }
            uiScope.launch {
                BotRuntime.pendingTurnConfirm.collect { show ->
                    if (show) showTurnConfirm() else dismissTurnConfirm()
                }
            }
        }

        if (logHost?.isShowing != true) {
            OverlayHost(ctx).also { logHost = it }.show(
                layout = {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                    width = ctx.resources.displayMetrics.widthPixels
                },
            ) {
                val statusLine by BotRuntime.statusLine.collectAsState()
                LogPanelContent(
                    statusLine = statusLine,
                    collapsed = collapsed.value,
                    logs = logs,
                    onToggleCollapse = { collapsed.value = !collapsed.value },
                    onDrag = { dx, dy -> dragLog(dx, dy) },
                )
            }
        }

        if (controlHost?.isShowing != true) {
            OverlayHost(ctx).also { controlHost = it }.show(
                layout = {
                    gravity = Gravity.BOTTOM or Gravity.END
                    x = 8
                    y = 180
                },
            ) {
                val running by BotRuntime.running.collectAsState()
                val autoNext by BotRuntime.autoNext.collectAsState()
                ControlBarContent(
                    collapsed = barCollapsed.value,
                    running = running,
                    autoNext = autoNext,
                    exitPrompt = exitPrompt.value,
                    onStartStop = ::onStartStop,
                    onAutoNextChange = ::onAutoNextChange,
                    onRequestClose = ::onRequestClose,
                    onConfirmExit = ::onConfirmExit,
                    onCancelExit = ::onCancelExit,
                    onToggleCollapse = { barCollapsed.value = !barCollapsed.value },
                    onDragY = { dy -> dragControl(dy) },
                )
            }
        }
        LogBus.log(LogKind.OK, "悬浮操作条与日志窗已显示")
    }

    fun dismissAll() {
        controlHost?.dismiss()
        controlHost = null
        logHost?.dismiss()
        logHost = null
        dialogHost?.dismiss()
        dialogHost = null
    }

    /** 彻底关停：中断会话、关闭引擎子进程、清空引用（前台服务销毁时调用）。 */
    fun shutdown() {
        session?.interrupt()
        session?.close()
        session = null
        dismissAll()
    }

    /** 屏幕中央轮次确认弹窗（全屏遮罩，点遮罩=暂不）。 */
    private fun showTurnConfirm() {
        val ctx = appContext ?: return
        if (dialogHost?.isShowing == true) return
        val sideCn = session?.let { it.state.mySide.cn } ?: ""
        val phaseCn = session?.let { it.state.phase.cn } ?: ""
        OverlayHost(ctx).also { dialogHost = it }.show(
            layout = {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                // 模态：遮罩拦截全部触摸，避免弹窗期间误触游戏
                flags = 0
            },
        ) {
            TurnConfirmDialog(
                mySideCn = sideCn,
                phaseCn = phaseCn,
                onConfirm = { session?.answerTurn(true) },
                onDecline = { session?.answerTurn(false) },
            )
        }
    }

    private fun dismissTurnConfirm() {
        dialogHost?.dismiss()
        dialogHost = null
    }

    // ---------- 回调 ----------

    private fun onStartStop() {
        val ctx = appContext ?: return
        val s = ensureSession(ctx)
        if (BotRuntime.running.value) {
            s.interrupt()
            LogBus.log(LogKind.WARN, "已请求中断棋局")
            barCollapsed.value = false // 中断后展开，方便再次操作
        } else {
            scopeLaunchStart(ctx)
        }
    }

    private fun scopeLaunchStart(ctx: Context) {
        barCollapsed.value = true // 开始后自动收起到右缘，避免遮挡棋盘/按钮
        botScope.launch { ensureSession(ctx).start() }
        LogBus.log(LogKind.INFO, "开始棋局：同步棋盘并启动对弈")
    }

    private fun onAutoNextChange(value: Boolean) {
        BotRuntime.autoNext.value = value
        LogBus.log(LogKind.INFO, "自动下一局已${if (value) "开启" else "关闭"}")
        uiScope.launch { settings?.setAutoNextEnabled(value) }
    }



    /** ✕ 第一段：进入内联确认态（进行中提示先中断；空闲 3 秒未确认自动还原）。 */
    private fun onRequestClose() {
        exitPromptJob?.cancel()
        if (BotRuntime.running.value) {
            exitPrompt.value = "棋局进行中：将自动中断棋局并退出，是否确认？"
            return
        }
        exitPrompt.value = "确认退出 ChessBot？"
        exitPromptJob = uiScope.launch {
            kotlinx.coroutines.delay(3000)
            if (exitPrompt.value == "确认退出 ChessBot？") exitPrompt.value = null
        }
    }

    /** ✕ 第二段：确认——中断棋局（如在进行中）并停止前台服务完全退出。 */
    private fun onConfirmExit() {
        val ctx = appContext ?: return
        if (BotRuntime.running.value) {
            session?.interrupt()
            LogBus.log(LogKind.WARN, "已中断棋局")
        }
        exitPromptJob?.cancel()
        exitPrompt.value = null
        com.chess.bot.service.BotForegroundService.stop(ctx)
    }

    private fun onCancelExit() {
        exitPromptJob?.cancel()
        exitPrompt.value = null
    }

    private fun dragControl(dy: Float) {
        controlHost?.updateLayout {
            // 常驻右缘：仅允许上下拖动
            y -= dy.roundToInt()
        }
    }

    private fun dragLog(dx: Float, dy: Float) {
        logHost?.updateLayout {
            x += dx.roundToInt()
            y += dy.roundToInt()
        }
    }
}
