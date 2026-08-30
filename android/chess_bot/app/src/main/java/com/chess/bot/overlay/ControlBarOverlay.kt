package com.chess.bot.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chess.bot.game.BotStatus
import com.chess.bot.game.MoveSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.pow

// ---------- 浅/深色双配色（悬浮窗跟随系统主题切换） ----------
private data class BarPalette(
    val bg: Color,              // 窗体底（保留半透明，2026-08-29 拍板）
    val border: Color,          // 窗体描边 = outline
    val ctrlBg: Color,          // 图标按钮底 = surfaceVariant
    val ctrlBorder: Color,      // 图标按钮描边 = outline
    val textLight: Color,       // onSurface
    val textDim: Color,         // onSurfaceVariant
    val startGreen: Color,      // ▶ 开始（非运行态，拍板保留）
    val stopRed: Color,         // 退出确认「中断并返回」按钮底
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val dotGreen: Color,
    val dotGray: Color,
    val dotAmber: Color,
    val bookFg: Color,          // 📖开局库 pill（HTML #A06BD9）
    val fishFg: Color,          // 🐟皮卡鱼 pill（HTML #5B8FD9）
    val rateFg: Color,          // 🏆胜率 pill（HTML #E0A940）
    val toggleOn: Color,        // 下一局指示开=绿 #4CAF6D
    val toggleOff: Color,
)

private val DarkPalette = BarPalette(
    bg = Color(0xD114161C),
    border = Color(0xFF3D4048),
    ctrlBg = Color(0xFF2A2D36),
    ctrlBorder = Color(0xFF3D4048),
    textLight = Color(0xFFE8EAF0),
    textDim = Color(0xFF9AA0AD),
    startGreen = Color(0xFF2E7D5B),
    stopRed = Color(0xFFC0392B),
    primary = Color(0xFFD0BCFF),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    dotGreen = Color(0xFF4ADE80),
    dotGray = Color(0xFF6B7280),
    dotAmber = Color(0xFFE0A940),
    bookFg = Color(0xFFA06BD9),
    fishFg = Color(0xFF5B8FD9),
    rateFg = Color(0xFFE0A940),
    toggleOn = Color(0xFF4CAF6D),
    toggleOff = Color(0xFF9AA0AD),
)

private val LightPalette = BarPalette(
    bg = Color(0xEEF7F8FA),
    border = Color(0xFF79747E),
    ctrlBg = Color(0xFFE7E0EC),
    ctrlBorder = Color(0xFF79747E),
    textLight = Color(0xFF1B1B1F),
    textDim = Color(0xFF49454F),
    startGreen = Color(0xFF2E7D5B),
    stopRed = Color(0xFFC0392B),
    primary = Color(0xFF6750A4),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    dotGreen = Color(0xFF1E8E3E),
    dotGray = Color(0xFF9AA0A8),
    dotAmber = Color(0xFFB06A00),
    bookFg = Color(0xFFA06BD9),
    fishFg = Color(0xFF5B8FD9),
    rateFg = Color(0xFFE0A940),
    toggleOn = Color(0xFF4CAF6D),
    toggleOff = Color(0xFF49454F),
)

private fun palette(dark: Boolean) = if (dark) DarkPalette else LightPalette

/** 评估分（厘兵）→ 胜率（0..1）标准逻辑斯蒂换算。 */
private fun cpToWinRate(cp: Int): Float {
    val c = cp.coerceIn(-1500, 1500)
    return (1.0 / (1.0 + 10.0.pow((-c / 400.0)))).toFloat()
}

/** 操控条动作按钮：等宽圆角图标按钮（对齐 HTML ④ .ib）。颜色由调用方显式给定。 */
@Composable
private fun BarActionButton(
    symbol: String,
    contentDescription: String,
    tint: Color,
    bg: Color,
    border: Color,
    onClick: () -> Unit,
    p: BarPalette,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = modifier
            .height(44.dp)
            .pointerInput(Unit) { detectTapGestures { onClick() } },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, style = MaterialTheme.typography.titleMedium, color = tint)
        }
    }
}

/** ⏹ 中断（HTML .ib.stop）：errorContainer 底 + error 描边 + error 图标。 */
private fun stopButtonColors(p: BarPalette) = Triple(p.error, p.errorContainer, p.error)

/** 普通键（HTML .ib）：surfaceVariant 底 + outline 描边 + onSurface 图标。 */
private fun normalButtonColors(p: BarPalette) = Triple(p.textLight, p.ctrlBg, p.ctrlBorder)

/** 开启键（HTML .ib.on）：primaryContainer 底 + primary 描边 + onPrimaryContainer 图标。 */
private fun onButtonColors(p: BarPalette) =
    Triple(p.onPrimaryContainer, p.primaryContainer, p.primary)

/** 信息框右上角的「自动下一局」指示：开启时显示 ⏭（绿色，与操控条下一局按钮图标一致）；关闭时不显示（用户要求）。 */
@Composable
private fun NextGameIndicator(on: Boolean, p: BarPalette) {
    if (!on) return
    Text("⏭", style = MaterialTheme.typography.titleSmall, color = p.toggleOn)
}

/** 状态行：彩点 + 「阶段 · 阵营 · 状态」。 */
@Composable
private fun StatusLineRow(statusLine: String, running: Boolean, p: BarPalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .background(if (running) p.dotGreen else p.dotGray, CircleShape)
        )
        Text(
            statusLine,
            style = MaterialTheme.typography.labelMedium,
            color = p.textLight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 引擎行胶囊（对齐 HTML .pill）：圆角底 + 前景同色系；着法用等宽字体（.pill.mono）。 */
@Composable
private fun EnginePill(text: String, fg: Color, bg: Color, mono: Boolean) {
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(20.dp))
            .padding(horizontal = 11.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.5.sp,
                fontFamily = if (mono) FontFamily.Monospace else null,
            ),
            color = fg,
        )
    }
}

/**
 * 引擎行（第二行，对齐 HTML ④）：三个胶囊 pill——
 * 📖开局库(紫) / 🐟皮卡鱼(蓝) + 着法（mono）、🏆胜率(琥珀)、📊评估分(primary/primaryContainer)。
 */
@Composable
private fun EngineLineRow(
    moveSource: MoveSource,
    lastMoveIccs: String?,
    moveDepth: Int,
    evalScore: Int,
    bookWinRate: Float,
    p: BarPalette,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val book = moveSource == MoveSource.BOOK
        val srcFg = if (book) p.bookFg else p.fishFg
        // 着法后加深度括号，与信息框一致（仅引擎搜索 depth>0 显示，开局库 depth=0 不加）
        val moveText =
            "${if (book) "📖" else "🐟"} ${lastMoveIccs ?: "--"}${if (moveDepth > 0) "($moveDepth)" else ""}"
        EnginePill(moveText, srcFg, srcFg.copy(alpha = 0.18f), mono = true)
        val winRate = if (book) bookWinRate else cpToWinRate(evalScore)
        EnginePill(
            "🏆${(winRate * 100).toInt()}%",
            p.rateFg,
            p.rateFg.copy(alpha = 0.18f),
            mono = false
        )
        val evalText = if (evalScore > 0) "+$evalScore" else "$evalScore"
        EnginePill("📊$evalText", p.primary, p.primaryContainer, mono = false)
    }
}

/**
 * 悬浮操控条（展开态，常驻右缘、仅上下拖动；2026-08-29 重构）：
 * 三行结构——
 * ① 状态行：彩点 + 阶段·阵营·状态
 * ② 引擎行：📖/🐟+着法 · 🏆胜率 · 📊评估分
 * ③ 按钮行（方案 A 图标横排）：⏹开始/中断 · ⏭下一局⇄ · ▦棋盘⇄ · ⌃收缩 · ⌂返回
 * 中断/开关/棋盘均不切换为信息框；仅 ⌃收缩 回信息框、⌂返回退出。
 */
@Composable
fun ControlBarContent(
    dark: Boolean,
    running: Boolean,
    autoNext: Boolean,
    statusLine: String,
    status: BotStatus,
    evalScore: Int,
    moveSource: MoveSource,
    moveDepth: Int,
    bookWinRate: Float,
    lastMoveIccs: String?,
    boardShown: Boolean,
    exitPrompt: String?,
    waitElapsedS: Int,
    waitDetail: String,
    onStartStop: () -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
    onBoardToggle: () -> Unit,
    onRequestClose: () -> Unit,
    onConfirmExit: () -> Unit,
    onCancelExit: () -> Unit,
    onToggleCollapse: () -> Unit,
    onLongPressInterrupt: () -> Unit,
    onDragY: (Float) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    val p = palette(dark)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = p.bg,
        border = BorderStroke(1.dp, p.border),
        modifier = Modifier
            .width(344.dp)
            // 与棋盘小窗一致的标准拖动手势（事件消费 + 按增量移动），消除自研手势的抖动
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
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        exitPrompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.textLight
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onConfirmExit,
                            colors = ButtonDefaults.buttonColors(containerColor = p.stopRed),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 5.dp
                            ),
                        ) { Text("中断并返回") }
                        OutlinedButton(
                            onClick = onCancelExit,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = p.textDim),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 14.dp,
                                vertical = 5.dp
                            ),
                        ) { Text("取消") }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // ① 状态行
                    StatusLineRow(statusLine, running, p)
                    // ② 引擎行：📖/🐟+着法 · 🏆胜率 · 📊评估分
                    EngineLineRow(moveSource, lastMoveIccs, moveDepth, evalScore, bookWinRate, p)
                    // ③ 按钮行（方案 A：5 个等宽图标按钮，对齐 HTML ④ .ib-row）
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // ⏹ 中断（运行态=errorContainer 样式）/ ▶ 开始（非运行态保留，拍板①②）
                        val (stTint, stBg, stBorder) =
                            if (running) stopButtonColors(p)
                            else Triple(p.startGreen, p.ctrlBg, p.ctrlBorder)
                        BarActionButton(
                            if (running) "⏹" else "▶",
                            if (running) "中断" else "开始",
                            stTint, stBg, stBorder,
                            onStartStop, p,
                            modifier = Modifier.weight(1f),
                        )
                        val (nxTint, nxBg, nxBorder) =
                            if (autoNext) onButtonColors(p) else normalButtonColors(p)
                        BarActionButton(
                            "⏭", "自动下一局", nxTint, nxBg, nxBorder,
                            { onAutoNextChange(!autoNext) }, p, modifier = Modifier.weight(1f)
                        )
                        val (bdTint, bdBg, bdBorder) =
                            if (boardShown) onButtonColors(p) else normalButtonColors(p)
                        BarActionButton(
                            "▦", "棋盘", bdTint, bdBg, bdBorder,
                            onBoardToggle, p, modifier = Modifier.weight(1f)
                        )
                        val (clTint, clBg, clBorder) = normalButtonColors(p)
                        BarActionButton(
                            "⟩",
                            "收起为信息框",
                            clTint,
                            clBg,
                            clBorder,
                            onToggleCollapse,
                            p,
                            modifier = Modifier.weight(1f)
                        )
                        BarActionButton(
                            "⌂",
                            "返回 App",
                            clTint,
                            clBg,
                            clBorder,
                            onRequestClose,
                            p,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 信息框（收起小窗，独立悬浮窗；2026-08-29 精简为两行）：
 * 第一行：状态点 + 状态字（左）+「自动下一局」空心圆圈›（最右）；
 * 第二行：走棋（📖开局库 / 🐟皮卡鱼 + 着法，等宽）与评估分；等待摆棋时显示「已等待 Ns」。
 * 整窗可拖动（结束时落盘）；点击=展开操控条；长按(≥600ms,仅运行态)=中断并展开操控条。
 */
@Composable
fun InfoBoxMini(
    dark: Boolean,
    running: Boolean,
    status: BotStatus,
    evalScore: Int,
    moveSource: MoveSource,
    moveDepth: Int,
    lastMoveIccs: String?,
    autoNext: Boolean,
    waitElapsedS: Int,
    onToggleExpand: () -> Unit,
    onLongPressInterrupt: () -> Unit,
    onDragY: (Float) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    val p = palette(dark)
    val waiting = running && status == BotStatus.WAIT_PLACEMENT
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = p.bg,
        border = BorderStroke(1.dp, p.border),
        modifier = Modifier
            .width(168.dp)
            .dragTapLongPress(
                onDrag = onDragY,
                onTap = onToggleExpand,
                onLongPress = { if (running) onLongPressInterrupt() },
                onDragEnd = onDragEnd,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 第一行：状态（左）+ 自动下一局角标（最右；fillMaxWidth 缺失会紧贴状态字，2026-08-29 修复）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val dot = when {
                        !running -> p.dotGray
                        status == BotStatus.WAIT_PLACEMENT -> p.dotAmber
                        else -> p.dotGreen
                    }
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(dot, CircleShape)
                    )
                    Text(
                        if (running) status.cn else "已中断",
                        style = MaterialTheme.typography.labelSmall,
                        color = p.textLight,
                        maxLines = 1,
                    )
                }
                NextGameIndicator(autoNext, p)
            }
            // 第二行：走棋（mono + 深度括号）+ 评估分；等待摆棋时显示「已等待 Ns」
            val book = moveSource == MoveSource.BOOK
            val srcIcon = if (book) "📖" else "🐟"
            val scoreText = if (evalScore > 0) "+$evalScore" else "$evalScore"
            val moveText = if (waiting) "已等待 ${waitElapsedS}s"
            else "${srcIcon} ${lastMoveIccs ?: "--"}${if (moveDepth > 0) "($moveDepth)" else ""}"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    moveText,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = p.textDim,
                    maxLines = 1,
                )
                if (!waiting) {
                    Text(
                        scoreText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = p.primary,
                    )
                }
            }
        }
    }
}

/**
 * 拖动 / 点击 / 长按 三合一手势（同一 pointerInput，解决手势冲突 #47）：
 * - 位移超过 slop → 视为拖动，回调 onDrag（消费事件，不触发点击/长按）
 * - 未拖动且未到长按时限即抬起 → 点击 onTap
 * - 按住 ≥minLongPressMs → 长按 onLongPress（协程计时，静止按住也能触发）
 *
 * 拖动复用 Compose 原生 `drag()` 原语（与 `detectDragGestures` 同底层，自带 move 事件
 * history 合并），手感与操控条一致、无抖动；长按/点击判定仍用手写协程，因原生
 * `detectDragGestures` 不提供长按。早期自研 `awaitPointerEvent` 循环因未合并历史事件导致抖动。
 */
private fun Modifier.dragTapLongPress(
    minLongPressMs: Long = 600,
    onDrag: (Float) -> Unit = {},
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
): Modifier = pointerInput(Unit) {
    val scope = CoroutineScope(coroutineContext)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var longFired = false
        val longJob = if (onLongPress != null) {
            scope.launch {
                delay(minLongPressMs)
                if (down.pressed) {
                    longFired = true
                    onLongPress.invoke()
                }
            }
        } else null
        try {
            // 原生 touch-slop 判定：越过阈值才视为拖动，避免微抖误触发
            val overSlop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
            if (overSlop != null) {
                // 原生 drag 原语：事件由 Compose 正确派发/消费，手感与操控条一致、无抖动
                longJob?.cancel()
                var lastY = down.position.y
                drag(down.id) { change ->
                    val step = change.position.y - lastY
                    lastY = change.position.y
                    if (step != 0f) onDrag(step)
                    change.consume()
                }
                onDragEnd?.invoke()
            } else if (!longFired) {
                // 未越过 slop 即抬起（且长按未触发）→ 点击
                onTap?.invoke()
            }
        } finally {
            longJob?.cancel()
        }
    }
}
