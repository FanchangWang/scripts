package com.chess.bot.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag

/**
 * 悬浮窗内 Compose 的手工生命周期桥：
 * ComposeView 不在 Activity 窗口树里，必须自备
 * LifecycleOwner / SavedStateRegistryOwner / ViewModelStoreOwner。
 */
class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore = store

    private var destroyed = true

    fun moveToFront() {
        if (destroyed) {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            destroyed = false
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun moveToDestroyed() {
        if (destroyed) return
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        // 清空 ViewModelStore：窗口销毁即释放 ViewModel，防复用/重建时残留引用泄漏
        store.clear()
        destroyed = true
    }
}

/**
 * 单个悬浮窗宿主：WindowManager + ComposeView。
 */
class OverlayHost(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val owner = OverlayLifecycleOwner()
    private var view: ComposeView? = null

    val isShowing: Boolean get() = view != null

    fun show(layout: WindowManager.LayoutParams.() -> Unit, content: @Composable () -> Unit) {
        if (view != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_LAYOUT_NO_LIMITS（2026-09-11 用户批示）：解除「应用可见区域」限制——
            // 默认悬浮窗被限制在状态栏下方的可见区内，通知栏显隐时 y=0 的基准随之变化
            // （桌面贴状态栏底、游戏贴屏幕顶），窗口上下跳动。加上后 x/y 为屏幕绝对坐标，
            // 恒以物理屏幕顶边为基准，不受通知栏影响。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply(layout)
        // 允许悬浮窗延伸进状态栏/刘海区（2026-09-11 用户批示）：定位使用屏幕绝对坐标，
        // 不随游戏沉浸式隐藏/显示通知栏而上下移动。默认 CUTOUT_MODE_NEVER 会被系统
        // 压到状态栏下方，通知栏显隐时窗口随之跳动——正是要消除的行为。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent(content)
        }
        view = composeView
        windowManager.addView(composeView, params)
        owner.moveToFront()
    }

    fun updateLayout(update: WindowManager.LayoutParams.() -> Unit) {
        val v = view ?: return
        val lp = (v.layoutParams as WindowManager.LayoutParams).apply(update)
        windowManager.updateViewLayout(v, lp)
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        owner.moveToDestroyed()
        try {
            // 非 immediate：removeView 把 DIE 调度到下一轮消息循环，
            // 避免在窗口自身输入事件派发途中移除窗口而抛异常（如信息框长按中断）
            windowManager.removeView(v)
        } catch (e: Exception) {
            // 窗口已被系统移除等场景：记录后忽略，不中断 dismiss 流程
            LogBus.log(
                LogLevel.DEBUG, LogTag.SYSTEM,
                "悬浮窗 removeView 失败（可能已被系统移除）: ${e.message}",
            )
        }
    }

    /** 视图完成首帧布局后回调（参数=测量宽高，像素）；用于在窗口创建后按真实内容高度校正位置。 */
    fun postLayout(action: (Int, Int) -> Unit) {
        val v = view ?: return
        v.post {
            if (v.height > 0) action(v.width, v.height)
        }
    }
}
