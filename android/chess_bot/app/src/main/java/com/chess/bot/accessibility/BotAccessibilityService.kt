package com.chess.bot.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.chess.bot.accessibility.BotAccessibilityService.Companion.instance
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * 手势点击服务：替代 python 版的 ADB input tap。
 * 用户需在系统设置中手动开启；开启后通过 [instance] 获取实例注入点击。
 */
class BotAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * 在屏幕绝对坐标 (x, y) 注入一次单击，挂起等待手势回调（超时 [timeoutMs] 返回 false）。
     * dispatchGesture 为异步 API，用 suspendCancellableCoroutine 桥接：
     * - 手势回调 → 恢复并返回结果；
     * - 超时 → withTimeout 抛 TimeoutCancellationException，catch 转 false（调用方 Boolean 语义不变）；
     * - 外层协程取消（如用户中断会话）→ 立即恢复并传播取消；
     *   已发出的手势无法撤回（系统无单手势 cancel API），物理点击可能照常生效。
     */
    suspend fun tapAwait(x: Int, y: Int, timeoutMs: Long = 1_500): Boolean =
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    dispatchGesture(
                        buildTapGesture(x.toFloat(), y.toFloat()),
                        gestureCallback { r -> cont.resume(r) },
                        null,
                    )
                }
            }
        } catch (e: CancellationException) {
            // 区分外层取消（继续传播）与仅超时（按原 tapSync 语义转 false）
            if (!currentCoroutineContext().isActive) throw e
            LogBus.log(LogLevel.DEBUG, LogTag.INPUT, "点击注入超时（${timeoutMs}ms 无手势回调）")
            false
        }

    /** 发送系统返回键（遮罩消除）。 */
    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    private fun buildTapGesture(x: Float, y: Float): GestureDescription =
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }, 0, TAP_DURATION_MS))
            .build()

    private fun gestureCallback(onResult: (Boolean) -> Unit) =
        object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onResult(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = onResult(false)
        }

    companion object {
        private const val TAP_DURATION_MS = 60L

        @Volatile
        var instance: BotAccessibilityService? = null
            private set
    }
}
