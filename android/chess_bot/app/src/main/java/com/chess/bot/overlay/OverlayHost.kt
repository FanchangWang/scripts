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
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply(layout)

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
            windowManager.removeViewImmediate(v)
        } catch (_: Exception) {
            // 窗口已被系统移除
        }
    }
}
