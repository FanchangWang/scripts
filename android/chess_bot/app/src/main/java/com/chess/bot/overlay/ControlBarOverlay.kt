package com.chess.bot.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chess.bot.game.BotStatus
import com.chess.bot.game.MoveSource
import com.chess.bot.ui.theme.LocalExtendedColors

// ---------- 业务强调色（2026-09-07 组A4）----------
// 原私有 BarAccent 调色板已并入主题体系：success/danger/棋谱强调色走
// LocalExtendedColors（ui/theme/Color.kt，深浅各一套）；结构色一律走 MaterialTheme.colorScheme。

/** 总状态推导（信息框第 1 行）。 */
private fun topStateLabel(running: Boolean, status: BotStatus): String = when {
    !running -> "已中断"
    status == BotStatus.INITIALIZING || status == BotStatus.WAIT_PLACEMENT -> "初始化"
    status == BotStatus.AUTO_NEXT || status == BotStatus.NEXT_MASK || status == BotStatus.NEXT_BUTTON -> "自动下一局"
    else -> "对弈中"
}

/**
 * 悬浮窗图标按钮：M3 FilledIconButton + 矢量图标。
 * 尺寸 40dp（2026-09-07 用户实测 48dp 偏大，缩小一档；悬浮条非主触达 UI，40dp 可接受），
 * 需关闭 M3 最小触控靶强制（默认会撑回 48dp）。
 * 形状固定 12dp 圆角；borderColor=null 表示无描边
 * （选中/彩色态用实底自明，未选中态用 outlineVariant 描边保证浅色下轮廓可见——A4）。
 */
@Composable
private fun BarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderColor: Color? = MaterialTheme.colorScheme.outlineVariant,
) {
    var m = modifier.size(40.dp)
    if (borderColor != null) {
        m = m.border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
    }
    FilledIconButton(
        onClick = onClick,
        modifier = m,
        shape = RoundedCornerShape(12.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) { Icon(icon, contentDescription) }
}

/**
 * 悬浮操控条（常驻右缘、仅上下拖动；2026-09-07 重构为单行 5 按钮）：
 * 开始/中断(▶/⏹) · 下一局(⏭) · 棋盘绘制(▦) · 信息框(ℹ) · 返回(⌂)
 * - 信息框按钮：显隐开关（默认开，不持久化——每次从主页开始对弈都复位为显示）。
 * - 退出确认：exitPrompt 非空时就地替换为「中断并返回 / 取消」（3s 超时还原）。
 * - 配色全部走 MaterialTheme.colorScheme（结构色）+ LocalExtendedColors（业务强调色）。
 */
@Composable
fun ControlBarContent(
    dark: Boolean,
    running: Boolean,
    autoNext: Boolean,
    boardShown: Boolean,
    infoShown: Boolean,
    exitPrompt: String?,
    onStartStop: () -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
    onBoardToggle: () -> Unit,
    onInfoToggle: () -> Unit,
    onRequestClose: () -> Unit,
    onConfirmExit: () -> Unit,
    onCancelExit: () -> Unit,
    onDragY: (Float) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    val ext = LocalExtendedColors.current
    val cs = MaterialTheme.colorScheme
    val (startIcon, startDesc) =
        if (running) Icons.Filled.Stop to "中断" else Icons.Filled.PlayArrow to "开始"
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = cs.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier
            // 宽度自适应内容：图标态 = 40dp 正方按钮 ×5 + 间距 8×4 + 内边距 24 ≈ 256dp
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragY(dragAmount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            },
    ) {
        when {
            exitPrompt != null -> {
                Column(
                    modifier = Modifier
                        .width(184.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        exitPrompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onConfirmExit,
                            colors = ButtonDefaults.buttonColors(containerColor = ext.danger),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 5.dp,
                            ),
                        ) { Text("中断并返回") }
                        OutlinedButton(
                            onClick = onCancelExit,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.onSurfaceVariant),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 14.dp,
                                vertical = 5.dp,
                            ),
                        ) { Text("取消") }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            else -> {
                // 关闭 M3 最小触控靶强制（否则 FilledIconButton 40dp 会被撑回 48dp）。
                // LocalMinimumInteractiveComponentEnforcement 已弃用，新 API = 尺寸槽设 0.dp
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides 0.dp
                ) {
                    // 单行 5 按钮（2026-09-07 由 2×2 改为一行）：开始 · 下一局 · 棋盘绘制 · 信息框 · 返回
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BarIconButton(
                            startIcon, startDesc, onStartStop,
                            containerColor = if (running) cs.error else ext.success,
                            contentColor = if (running) cs.onError else Color.White,
                            borderColor = null,
                        )
                        // 开关键选中态 = primary 实底 + onPrimary 图标，未选中 = surfaceContainerHigh + 描边
                        BarIconButton(
                            Icons.Filled.SkipNext, "自动下一局",
                            { onAutoNextChange(!autoNext) },
                            containerColor = if (autoNext) cs.primary else cs.surfaceContainerHigh,
                            contentColor = if (autoNext) cs.onPrimary else cs.onSurface,
                            borderColor = if (autoNext) null else cs.outlineVariant,
                        )
                        BarIconButton(
                            Icons.Filled.GridOn, "棋盘绘制", onBoardToggle,
                            containerColor = if (boardShown) cs.primary else cs.surfaceContainerHigh,
                            contentColor = if (boardShown) cs.onPrimary else cs.onSurface,
                            borderColor = if (boardShown) null else cs.outlineVariant,
                        )
                        // 信息框显隐（同款选中态样式；状态不持久化，每次弹出悬浮窗默认开）
                        BarIconButton(
                            Icons.AutoMirrored.Filled.ReceiptLong, "信息框", onInfoToggle,
                            containerColor = if (infoShown) cs.primary else cs.surfaceContainerHigh,
                            contentColor = if (infoShown) cs.onPrimary else cs.onSurface,
                            borderColor = if (infoShown) null else cs.outlineVariant,
                        )
                        // 返回 App 用 Home 图标（2026-09-07 用户选定，比向左箭头直观）
                        BarIconButton(
                            Icons.Filled.Home, "返回 App", onRequestClose,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 信息框（常驻左缘独立悬浮窗；2026-09-07 重构为 4 行）：
 * 第1行 总状态（圆点 + 文字）
 * 第2行 子状态（Timeline 小图标 + BotStatus.cn）
 * 第3行 棋谱（📖/🐟 + 最近着法 + 思考层数）
 * 第4行 分数（Insights 小图标 + 评估文本：mate 优先「绝杀 N/被绝杀 N」，否则「+N/N」，盲区「评估 -」；按正负着色）
 * 四行统一 12sp（labelMedium）、行距 4dp、图标槽统一 14dp 宽。
 * 自由拖动（dx/dy 双向，2026-09-07 由仅上下拖改为一律允许），整窗拖动。
 */
@Composable
fun InfoBoxMini(
    dark: Boolean,
    running: Boolean,
    status: BotStatus,
    evalScore: Int,
    evalText: String,
    moveSource: MoveSource,
    moveDepth: Int,
    lastMoveIccs: String?,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    val ext = LocalExtendedColors.current
    val cs = MaterialTheme.colorScheme
    val waiting = running && status == BotStatus.WAIT_PLACEMENT
    val dot = when {
        !running -> cs.onSurfaceVariant
        status == BotStatus.WAIT_PLACEMENT ||
                status == BotStatus.AUTO_NEXT ||
                status == BotStatus.NEXT_MASK ||
                status == BotStatus.NEXT_BUTTON -> ext.rateFg

        else -> ext.success
    }
    val scoreColor = when {
        evalScore > 0 -> ext.success
        evalScore < 0 -> ext.danger
        else -> cs.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier
            .width(150.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 四行统一「14dp 图标槽 + 6dp 间距 + 文字」，字号统一 12sp（labelMedium）。
            // 图标槽统一 14dp 宽：圆点/矢量图标/表情都居中放进槽内，四行文字起点严格对齐
            // （修：第 1 行圆点裸放 9dp、与其他行 14dp 图标宽度不一致）
            // 第1行：总状态（圆点 + 文字）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(dot, CircleShape)
                    )
                }
                Text(
                    topStateLabel(running, status),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                )
            }
            // 第2行：子状态（Timeline 小图标 + 文字）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Timeline,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    status.cn,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 第3行：棋谱（📖/🐟 表意图标进 14dp 槽，随文字着色；正文仅着法+层数）
            val book = moveSource == MoveSource.BOOK
            val moveColor = if (book) ext.bookFg else ext.fishFg
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (book) "📖" else "🐟",
                        style = MaterialTheme.typography.labelMedium,
                        color = moveColor,
                        maxLines = 1,
                    )
                }
                Text(
                    if (waiting) "—" else "${lastMoveIccs ?: "--"}${if (moveDepth > 0) "($moveDepth)" else ""}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = moveColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 第4行：分数（Insights 小图标 + 文本；文本由 BotSession 推送，着色按 evalScore 正负——mate 正分绿 / 被绝杀负分红）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Insights,
                    contentDescription = null,
                    tint = scoreColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    evalText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = scoreColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
