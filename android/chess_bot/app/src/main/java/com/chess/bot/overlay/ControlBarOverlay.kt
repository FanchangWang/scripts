package com.chess.bot.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// ---------- 深色半透明主题（与顶部日志窗同风格） ----------
private val BarBg = Color(0xD114161C)      // 背景 黑 82%
private val BarBorder = Color(0x24FFFFFF)  // 外框 白 14%
private val CtrlBg = Color(0x14FFFFFF)     // 圆钮底 白 8%
private val CtrlBorder = Color(0x40FFFFFF) // 圆钮描边 白 25%
private val TextLight = Color(0xFFD3D7DD)
private val TextDim = Color(0xFFC8CDD4)
private val StartGreen = Color(0xFF2E7D5B)
private val StopRed = Color(0xFFC0392B)
private val ExitRed = Color(0xFFE3645A)
private val DotGreen = Color(0xFF4ADE80)   // 收起态运行指示点
private val DotGray = Color(0xFF6B7280)    // 收起态暂停指示点

/** 垂直分隔线。 */
@Composable
private fun VDivider() {
    Box(Modifier.width(1.dp).height(22.dp).background(BarBorder))
}

/** 紧凑型小开关（30x16dp），替代全尺寸 Material Switch 省宽度。 */
@Composable
private fun MiniSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(
        if (checked) StartGreen else Color(0xFF4A4F58),
        label = "track",
    )
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 16.dp)
            .clip(CircleShape)
            .background(track)
            .pointerInput(Unit) { detectTapGestures { onChange(!checked) } },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * 圆形描边按钮（收起 / 退出共用样式）：38dp 触控目标。
 * showDot 非空时右上角显示运行状态点；onLongPress 用于收起态长按中断。
 */
@Composable
fun RoundIconButton(
    symbol: String,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    showDot: Color? = null,
    onLongPress: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.size(38.dp)) {
        Surface(
            shape = CircleShape,
            color = CtrlBg,
            border = BorderStroke(1.dp, CtrlBorder),
            modifier = Modifier
                .size(38.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onLongPress?.invoke() },
                    )
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(symbol, style = MaterialTheme.typography.titleMedium, color = tint)
            }
        }
        if (showDot != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(9.dp)
                    .background(showDot, CircleShape),
            )
        }
    }
}

/**
 * 悬浮操作条（常驻紧贴右缘，仅可上下拖动）：
 * - 展开态：[开始(绿)/中断(红)] | [下一局小开关] | [⟩收起] [✕退出]
 * - 收起态：右缘「⟨」圆钮（右上角状态点：绿=对弈中/灰=已暂停），长按直接中断并自动展开。
 */
@Composable
fun ControlBarContent(
    collapsed: Boolean,
    running: Boolean,
    autoNext: Boolean,
    exitPrompt: String?,
    onStartStop: () -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
    onRequestClose: () -> Unit,
    onConfirmExit: () -> Unit,
    onCancelExit: () -> Unit,
    onToggleCollapse: () -> Unit,
    onLongPressInterrupt: () -> Unit,
    onDragY: (Float) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = BarBg,
        border = BorderStroke(1.dp, BarBorder),
        modifier = Modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                onDragY(dragAmount.y)
            }
        },
    ) {
        when {
            exitPrompt != null -> {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(exitPrompt, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE8EAED))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onConfirmExit,
                            colors = ButtonDefaults.buttonColors(containerColor = StopRed),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                        ) { Text("确认退出") }
                        OutlinedButton(
                            onClick = onCancelExit,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextDim),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                        ) { Text("取消") }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            collapsed -> {
                Row(modifier = Modifier.padding(3.dp)) {
                    RoundIconButton(
                        "⟨",
                        "展开操作条",
                        TextLight,
                        onToggleCollapse,
                        showDot = if (running) DotGreen else DotGray,
                        onLongPress = onLongPressInterrupt,
                    )
                }
            }

            else -> {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onStartStop,
                        // 状态色：空闲绿「开始」/ 运行红「中断」，扫一眼即知状态
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (running) StopRed else StartGreen,
                        ),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp),
                    ) {
                        Text(if (running) "中断" else "开始")
                    }
                    VDivider()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text("下一局", style = MaterialTheme.typography.labelSmall, color = TextDim)
                        MiniSwitch(autoNext, onAutoNextChange)
                    }
                    VDivider()
                    RoundIconButton(
                        "⟩",
                        "收起操作条",
                        TextLight,
                        onToggleCollapse,
                    )
                    RoundIconButton(
                        "✕",
                        "退出",
                        ExitRed,
                        onRequestClose,
                    )
                }
            }
        }
    }
}
