package com.chess.bot.ui

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.chess.bot.accessibility.BotAccessibilityService

/** 权限状态检查工具。 */
object Permissions {

    /** 通知权限：API < 33 无需运行时请求，视为已授予。 */
    fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 悬浮窗特殊权限。 */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 无障碍服务是否已开启：解析系统设置里的已启用服务列表；
     * 部分 ROM 写入有延迟，兜底看服务实例是否存活。
     */
    fun accessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, BotAccessibilityService::class.java)
        val enabled =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        if (enabled != null) {
            val found = enabled.split(':').any {
                ComponentName.unflattenFromString(it) == expected ||
                    it.equals(expected.flattenToString(), ignoreCase = true)
            }
            if (found) return true
        }
        return BotAccessibilityService.instance != null
    }
}
