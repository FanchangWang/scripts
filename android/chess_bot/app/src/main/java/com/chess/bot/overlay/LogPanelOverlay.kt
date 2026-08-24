package com.chess.bot.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.chess.bot.log.LogEvent
import com.chess.bot.log.LogKind

/** 深色主题（与对弈操控条统一）。 */
private val PanelBg = Color(0xD114161C)
private val PanelBorder = Color(0x24FFFFFF)
private val TextMain = Color(0xFFF2F4F7)
private val TextDim = Color(0x8CFFFFFF)

private val DotError = Color(0xFFFF8A80)
private val DotWarn = Color(0xFFFFD54F)
private val DotOk = Color(0xFF69F0AE)
private val DotMove = Color(0xFF82B1FF)
private val DotEnemy = Color(0xFFB388FF)
private val DotGame = Color(0xFFFFAB91)
private val DotPlain = Color(0x66FFFFFF)

/**
 * 顶部悬浮日志窗：状态行（阶段/阵营/状态 + 着色评估分）+ 滚动日志，可折叠成单行状态条。
 * - 深色半透明主题，与对弈操控条风格统一
 * - 仅标题行可拖动（⠿ 把手），正文区专职滚动
 * - 折叠期间出现 WARN/ERROR/GAME 时折叠钮显示红点未读标记
 * - 日志行：HH:mm:ss + 彩色圆点 + [模块] 文本；DEBUG 行半透明弱化
 * 高度不超过屏幕 1/4，不遮挡棋子区。
 */
@Composable
fun LogPanelContent(
    statusLine: String,
    evalScore: Int,
    collapsed: Boolean,
    unreadAlert: Boolean,
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
        shape = RoundedCornerShape(12.dp),
        color = PanelBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = if (collapsed) 46.dp else maxH),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
            // 标题行 = 拖动把手：⠿ 提示可拖，右侧折叠钮（折叠时带未读红点）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("⠿", color = TextDim, style = MaterialTheme.typography.labelSmall)
                // weight(1f) 占满剩余宽度 → 折叠钮始终贴最右侧；状态文本超长时省略号截断
                StatusLineText(statusLine, evalScore, Modifier.weight(1f))
                CollapseButton(collapsed = collapsed, unread = unreadAlert, onClick = onToggleCollapse)
            }
            if (!collapsed) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxH)
                        .verticalScroll(scroll),
                ) {
                    logs.forEach { event -> LogRow(event) }
                }
            }
        }
    }
}

/** 状态行：前三段白字 + 「评估 X」按分数着色（正=绿 负=红 0=白）。 */
@Composable
private fun StatusLineText(statusLine: String, evalScore: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // 前三段状态占满可用宽度（超长省略号），「评估 X」固定展示不被截断
        Text(
            statusLine,
            color = TextMain,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val evalColor = when {
            evalScore > 0 -> DotOk
            evalScore < 0 -> DotError
            else -> TextMain
        }
        Text(
            " · 评估 ",
            color = TextMain,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            if (evalScore > 0) "+$evalScore" else "$evalScore",
            color = evalColor,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** 26dp 圆形折叠钮：⟩ 收起 / ⟨ 展开（符号体系与操控条一致）；折叠时右上角红点=有未读告警。 */
@Composable
private fun CollapseButton(collapsed: Boolean, unread: Boolean, onClick: () -> Unit) {
    Box {
        androidx.compose.material3.OutlinedButton(
            onClick = onClick,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier.size(26.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = TextMain,
            ),
        ) {
            Text(
                if (collapsed) "⟨" else "⟩",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (collapsed && unread) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(18.dp.roundToPx(), (-2).dp.roundToPx()) }
                    .size(8.dp)
                    .background(Color(0xFFFF5252), CircleShape)
                    .border(1.dp, PanelBg, CircleShape),
            )
        }
    }
}

/** 单条日志：时间戳(暗) + 彩色圆点 + [模块] 文本；DEBUG 行整体降透明度弱化。 */
@Composable
private fun LogRow(event: LogEvent) {
    val mainColor = kindColor(event.kind)
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "${event.time} ",
            color = TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "● ",
            color = mainColor,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "[${event.tag.cn}] ${event.msg}",
            color = if (event.kind == LogKind.DEBUG) TextDim else mainColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun kindColor(kind: LogKind): Color = when (kind) {
    LogKind.ERROR -> DotError
    LogKind.WARN -> DotWarn
    LogKind.OK -> DotOk
    LogKind.MOVE -> DotMove
    LogKind.ENEMY -> DotEnemy
    LogKind.GAME -> DotGame
    else -> TextMain
}
