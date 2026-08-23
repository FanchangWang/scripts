package com.chess.bot.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** 圆形描边小按钮（收起 / 退出共用样式）。 */
@Composable
fun RoundIconButton(
    symbol: String,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tint),
        modifier = Modifier.size(34.dp),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
            Text(symbol, style = MaterialTheme.typography.titleMedium, color = tint)
        }
    }
}

/**
 * 悬浮操作条（常驻紧贴右缘，仅可上下拖动）：
 * - 展开态：[开始/中断] [下一局开关] [▶收起] [✕退出]
 * - 收起态：右缘仅「◀」小圆钮，点击展开。
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
    onDragY: (Float) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
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
                    Text(exitPrompt, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onConfirmExit) { Text("确认退出") }
                        OutlinedButton(onClick = onCancelExit) { Text("取消") }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            collapsed -> {
                Row(modifier = Modifier.padding(3.dp)) {
                    RoundIconButton(
                        "◀",
                        "展开操作条",
                        MaterialTheme.colorScheme.primary,
                        onToggleCollapse,
                    )
                }
            }

            else -> {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(onClick = onStartStop) {
                        Text(if (running) "中断" else "开始")
                    }
                    Text("下一局", style = MaterialTheme.typography.labelLarge)
                    Switch(checked = autoNext, onCheckedChange = onAutoNextChange)
                    RoundIconButton(
                        "▶",
                        "收起操作条",
                        MaterialTheme.colorScheme.outline,
                        onToggleCollapse,
                    )
                    RoundIconButton(
                        "✕",
                        "退出",
                        MaterialTheme.colorScheme.error,
                        onRequestClose,
                    )
                }
            }
        }
    }
}
