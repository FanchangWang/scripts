package com.chess.bot.accessibility

/** 无障碍服务桥：状态机/悬浮窗通过它访问已连接的服务实例。 */
object BotAccessibilityServiceHolder {

    val instance: BotAccessibilityService?
        get() = BotAccessibilityService.instance

    /** 发送返回键；服务未连接返回 false。 */
    fun back(): Boolean = instance?.back() ?: false
}
