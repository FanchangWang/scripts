package com.chess.bot.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 统一行基座（2026-09-07 UI 重构组B，修 B1/B2/B4）：
 * - 全 app **唯一**行实现：minHeight 48dp 触控靶、行内无独立竖向 padding；
 *   行间距由容器 spacedBy(4.dp) 统一（卡内行距），不再各行为自设 padding。
 * - 结构：左标题（+可选副标题）、右 trailing 控件；onClick 非空时整行可点（Role.Button）。
 * - ValueRow / SwitchRow / ChevronRow 为该基座的预设封装；各页不得再手写行内边距。
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val base = modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
    // M3 禁用内容色惯例：onSurface 38% alpha（androidx 内部 disabled 统一值，明显置灰；
    // 勿用 onSurfaceVariant——与 onSurface 色差太微弱，视觉上「看不出禁用」，2026-09-10 用户实测反馈）
    val disabledContent = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        // enabled=false：整行不可点 + 标题置灰（2026-09-10 对弈锁定：主页对弈中禁用配置行）
        modifier = if (onClick != null && enabled) base.clickable(role = Role.Button) { onClick() } else base,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Color.Unspecified else disabledContent,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else disabledContent,
                )
            }
        }
        trailing()
    }
}

/** 信息行：左标题、右只读值（替换原各处 InfoRow）。 */
@Composable
fun ValueRow(title: String, value: String) {
    SettingRow(title = title) {
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 开关行：左标题、右 M3 Switch（点击区域仅 Switch 本体，M3 自动保证 48dp 触控靶）；enabled=false 整行置灰禁用。 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(title = title, enabled = enabled) {
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** 导航行：左标题、右 ›，整行可点（替换原 NavRow / 「设置›」手写行）；enabled=false 不可点且置灰。 */
@Composable
fun ChevronRow(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    SettingRow(title = title, onClick = onClick, enabled = enabled) {
        Text(
            "›",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

/**
 * 主按钮（2026-09-07 修「禁用态变纯文字」）：
 * - 启用 = M3 primary 填充；禁用 = surfaceVariant 灰底 + onSurfaceVariant 文字
 *   （对齐 HTML .btn.dis）+ 1dp outlineVariant 描边，保证浅色卡片上仍保有按钮轮廓
 *   （此前 surfaceVariant 与卡片 surfaceContainerLow 过近、无描边，观感退化为纯文字）。
 */
@Composable
fun PrimaryActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val m = if (enabled) modifier else modifier.border(
        1.dp, cs.outlineVariant, ButtonDefaults.shape
    )
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = m,
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = cs.surfaceVariant,
            disabledContentColor = cs.onSurfaceVariant,
        ),
    ) { Text(text) }
}
