package com.chess.bot.overlay

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.chess.bot.log.LogEvent
import com.chess.bot.log.LogKind

/**
 * 顶部悬浮日志窗：状态行（阶段/阵营/状态）+ 滚动日志，可折叠成单行状态条。
 * 高度不超过屏幕 1/4，不遮挡棋子区。
 */
@Composable
fun LogPanelContent(
    statusLine: String,
    collapsed: Boolean,
    logs: List<LogEvent>,
    onToggleCollapse: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val config = LocalConfiguration.current
    // 默认高度压到 20% 屏高：默认位置(状态栏下方)不遮挡棋盘上沿棋子；
    // 需要更大空间时仍可拖入通知栏区域。
    val maxH = (config.screenHeightDp * 0.2f).dp
    val scroll = rememberScrollState()

    // 内容高度变化（新日志/长文本换行/展开）必然触发 maxValue 更新 → 滚到绝对底部。
    // 用 snapshotFlow 而非一次性 withFrameNanos：与布局时序解耦，无竞态欠账。
    LaunchedEffect(collapsed) {
        if (collapsed) return@LaunchedEffect
        snapshotFlow { logs.size to scroll.maxValue }
            .collect {
                scroll.scrollTo(scroll.maxValue)
            }
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color.Black.copy(alpha = 0.65f),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxH)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    statusLine,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onToggleCollapse) {
                    Text(if (collapsed) "▼" else "▲", color = Color.White)
                }
            }
            if (!collapsed) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxH)
                        .verticalScroll(scroll),
                ) {
                    logs.forEach { event ->
                        Text(
                            "[${event.kind.name}] ${event.msg}",
                            color = kindColor(event.kind),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun kindColor(kind: LogKind): Color = when (kind) {
    LogKind.ERROR -> Color(0xFFFF8A80)
    LogKind.WARN -> Color(0xFFFFD54F)
    LogKind.OK -> Color(0xFF69F0AE)
    LogKind.MOVE -> Color(0xFF82B1FF)
    LogKind.ENEMY -> Color(0xFFB388FF)
    LogKind.GAMEOVER -> Color(0xFFFFAB91)
    else -> Color.White
}
