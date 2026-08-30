package com.chess.bot.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.chess.bot.accessibility.BotAccessibilityService.Companion.instance

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

    /** 在屏幕绝对坐标 (x, y) 注入一次单击，结果经回调返回。 */
    fun tap(x: Float, y: Float, onResult: (Boolean) -> Unit = {}) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        dispatchGesture(buildTapGesture(x, y), gestureCallback(onResult), null)
    }

    /** 同步版点击（阻塞至手势回调或超时），供状态机串行流程使用。 */
    fun tapSync(x: Int, y: Int, timeoutMs: Long = 1_500): Boolean {
        var result = false
        var done = false
        dispatchGesture(
            buildTapGesture(x.toFloat(), y.toFloat()),
            gestureCallback { r ->
                result = r
                done = true
            },
            null,
        )
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!done && System.nanoTime() < deadline) Thread.sleep(5)
        return done && result
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
