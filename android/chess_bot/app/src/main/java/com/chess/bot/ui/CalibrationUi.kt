package com.chess.bot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.chess.bot.data.BoardCornersStore
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val CORNER_LABELS = listOf("左上", "右上", "左下", "右下")
private val ACCENT = Color(0xFF1E8E3E)

/** 主界面「棋盘四角校准」卡片：分辨率 + 状态 + 开始/重新校准；权限未齐时禁用并提示。 */
@Composable
fun CalibrationCard(permsOk: Boolean, onStart: () -> Unit) {
    val context = LocalContext.current
    val w = context.resources.displayMetrics.widthPixels
    val h = context.resources.displayMetrics.heightPixels
    val calibrated = BoardCornersStore.has(w, h, context)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "棋盘四角校准",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            )
            InfoRow("分辨率 ${w} × ${h}", if (calibrated) "已校准" else "未校准")
            if (!permsOk) {
                Text(
                    "请先在上方完成「权限与授权」四项授权，再进行校准。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 与「开始对弈」完全同款：启用=主色填充（.fbtn.fill），禁用=灰底实心
            Button(
                enabled = permsOk,
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(if (calibrated) "重新校准" else "开始校准")
            }
        }
    }
}

/** 校准步骤 1：引导进入人机模式（执红、停在 32 子开局），点「去截图」进入截屏。 */
@Composable
fun Step1Screen(onGoScreenshot: () -> Unit, onCancel: () -> Unit) {
    SubPageScaffold(
        title = "棋盘四角校准",
        subtitle = "步骤 1/2 · 进入人机模式",
        onBack = onCancel,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "打开象棋 App → 人机对战 → 选择执红，停在 32 子开局、未走棋 状态，再点下方按钮。\n\n" +
                                "黑車位于上方两角、红俥位于下方两角，算法据此定位四个角。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                Button(onClick = onGoScreenshot, modifier = Modifier.weight(1f)) {
                    Text("去截图")
                }
            }
        }
    }
}

/** 主界面「对弈」卡片：展示当前引擎/开局库/节奏配置摘要 + 内联「设置」入口；授权并启动。 */
@Composable
fun PlayCard(
    permsOk: Boolean,
    calibrated: Boolean,
    captureActive: Boolean,
    cfg: com.chess.bot.data.BotConfigData,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val enabled = permsOk && calibrated
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "对弈",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            )
            // 配置摘要（引擎仅模式名、开局库仅启用状态）
            InfoRow("引擎", cfg.thinkMode.cn)
            // 思考摘要：跟随思考模式显示对应参数（时长→思考时间 / 层数→思考层数 / 先到为准→两者）
            InfoRow("思考", thinkSummary(cfg))
            InfoRow("开局库", if (cfg.bookEnabled) "已启用" else "已关闭")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSettings() }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("设置", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!enabled) {
                val reason = when {
                    !permsOk -> "请先在上方完成「权限与授权」四项授权"
                    !calibrated -> "请先完成「棋盘四角校准」"
                    else -> ""
                }
                Text(
                    reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (captureActive) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("停止并退出悬浮窗")
                }
            } else {
                Button(
                    enabled = enabled,
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    // 禁用态保持可见：surfaceVariant 底 + 次级文字（默认 12% 透明度过淡）
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("开始对弈（悬浮窗模式）")
                }
            }
        }
    }
}

/** 单行配置摘要：左侧标题、右侧值。 */
@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 首页「思考」行摘要：跟随思考模式显示生效参数（值前不加「思考时间/思考层数」前缀）。 */
private fun thinkSummary(cfg: com.chess.bot.data.BotConfigData): String {
    return when (cfg.thinkMode) {
        com.chess.bot.data.ThinkMode.TIME -> "${cfg.movetimeMs} ms"
        com.chess.bot.data.ThinkMode.DEPTH -> "${cfg.depth} 层"
        com.chess.bot.data.ThinkMode.BOTH -> "${cfg.movetimeMs} ms · ${cfg.depth} 层"
    }
}

/** 步骤 2/2 截图后：回 App 立即显示「识别中」，后台识别完成后自动切 RESULT。 */
@Composable
fun RecognizingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            "正在识别棋盘四角…",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "首次识别需初始化 OpenCV 并加载棋子模板，可能耗时数秒",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 步骤 2/2 识别结果：操作按钮置于图片上方 + 四角叠加 + 校验 + 四角坐标 + 返回。手动微调为本步骤子功能。 */
@Composable
fun CalibrationResultScreen() {
    val context = LocalContext.current
    val bitmap = CalibrationSession.capturedBitmap
    val corners = CalibrationSession.corners
    val manual = CalibrationSession.manualCorners
    val passed by CalibrationSession.validationPassed
    val err by CalibrationSession.errorMsg
    val dispCorners = manual ?: corners

    SubPageScaffold(
        title = "棋盘四角校准",
        subtitle = "步骤 2/2 · 截屏识别",
        onBack = { CalibrationSession.reset() },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 操作按钮置于图片上方（第一眼可见）
            OutlinedButton(
                onClick = { CalibrationSession.reset() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("返回 / 放弃")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { CalibrationSession.start(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("重新截图")
                }
                OutlinedButton(
                    onClick = { CalibrationSession.openManual() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("手动微调")
                }
            }
            Button(
                enabled = dispCorners != null,
                onClick = { CalibrationSession.save(context) {} },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("确认并保存")
            }

            if (bitmap != null) {
                val img = bitmap.asImageBitmap()
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                    val heightPx =
                        if (bitmap.width > 0) widthPx * bitmap.height / bitmap.width else widthPx
                    val sx = if (widthPx > 0) widthPx / bitmap.width else 1f
                    val sy = if (heightPx > 0) heightPx / bitmap.height else 1f
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val pts = dispCorners ?: return@Canvas
                            val p = pts.map {
                                Offset(
                                    (it.first * sx).toFloat(),
                                    (it.second * sy).toFloat()
                                )
                            }
                            drawLine(ACCENT, p[0], p[1], 3f)
                            drawLine(ACCENT, p[1], p[3], 3f)
                            drawLine(ACCENT, p[3], p[2], 3f)
                            drawLine(ACCENT, p[2], p[0], 3f)
                            p.forEach { drawCircle(ACCENT, 13f, it) }
                        }
                        dispCorners?.forEachIndexed { i, (x, y) ->
                            Text(
                                CORNER_LABELS[i],
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (x * sx).roundToInt(),
                                            (y * sy).roundToInt()
                                        )
                                    }
                                    .background(Color.White.copy(alpha = 0.75f)),
                                color = ACCENT,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            err?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (dispCorners != null) {
                val badgeColor = if (passed) Color(0xFF1E8E3E) else Color(0xFFC0202E)
                val badgeText =
                    if (passed) "校验通过 · 识别为 32 子开局 ✓" else "校验未通过 · 可重新截图或手动微调"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(badgeColor.copy(alpha = 0.12f), CircleShape)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(badgeText, color = badgeColor, style = MaterialTheme.typography.bodySmall)
                }
                if (CalibrationSession.matchScores.isNotEmpty()) {
                    Text(
                        "匹配度：" + CalibrationSession.matchScores.joinToString(", ") {
                            "%.2f".format(
                                it
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                // 四角坐标（需求：排查问题）
                Text("四角坐标（屏幕像素）", style = MaterialTheme.typography.bodySmall)
                dispCorners.forEachIndexed { i, (x, y) ->
                    Text(
                        "${CORNER_LABELS[i]}：(${x.roundToInt()}, ${y.roundToInt()})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** 手动微调（步骤 2/2 的子功能）：拖动 / 方向键微调选中角标（默认左上）+ 缩放放大，按钮置于图片上方。 */
@Composable
fun ManualTuneScreen() {
    val bitmap = CalibrationSession.capturedBitmap
    val auto = CalibrationSession.corners
    val selected = remember { mutableStateOf(0) }
    val zoom = remember { mutableStateOf(1f) }
    val handles = remember { mutableStateListOf<Offset>() } // 位图坐标系

    // 显示尺寸（含缩放）：基准宽度 = 屏宽 - 列表内边距(32dp)，再乘 zoom
    val density = LocalDensity.current
    val baseWpx = with(density) { (LocalConfiguration.current.screenWidthDp - 32).dp.toPx() }
    val zoomV = zoom.value
    val widthPx = baseWpx * zoomV
    val heightPx =
        if (bitmap != null && bitmap.width > 0) widthPx * bitmap.height / bitmap.width else widthPx
    val sx = if (bitmap != null && bitmap.width > 0) widthPx / bitmap.width else 1f
    val sy = if (bitmap != null && bitmap.height > 0) heightPx / bitmap.height else 1f
    // 方向键每按一次移动 2 个显示像素（缩放越大，单次位移越小、越精准）
    val step = if (sx > 0f) 2f / sx else 2f

    LaunchedEffect(bitmap) {
        auto?.let {
            handles.clear()
            handles.addAll(it.map { c -> Offset(c.first.toFloat(), c.second.toFloat()) })
        }
    }

    SubPageScaffold(
        title = "棋盘四角校准",
        subtitle = "手动微调 · 步骤 2/2 子功能",
        onBack = { CalibrationSession.backToResult() },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "拖动角标，或用方向键微调选中的角标（默认选中左上）；放大后可更精准。",
                style = MaterialTheme.typography.bodySmall
            )

            // 操作按钮置于图片上方（不覆盖图片）
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { CalibrationSession.backToResult() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
                Button(
                    enabled = handles.isNotEmpty(),
                    onClick = {
                        val src = handles.map { it.x.toDouble() to it.y.toDouble() }
                        CalibrationSession.setManualCorners(src)
                        CalibrationSession.backToResult()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("确认并保存") }
            }

            if (bitmap != null) {
                // 选中角标
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "选中：${CORNER_LABELS.getOrElse(selected.value) { "" }}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = {
                        selected.value = (selected.value + 3) % 4
                    }) { Text("◀ 上一个") }
                    OutlinedButton(onClick = {
                        selected.value = (selected.value + 1) % 4
                    }) { Text("下一个 ▶") }
                }
                // 方向微调（长按连续移动）
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val enabled = handles.isNotEmpty()
                    val move: (Float, Float) -> Unit = { dx, dy ->
                        if (handles.isNotEmpty()) {
                            val i = selected.value
                            val h = handles[i]
                            handles[i] = Offset(
                                (h.x + dx * step).coerceIn(0f, bitmap.width.toFloat()),
                                (h.y + dy * step).coerceIn(0f, bitmap.height.toFloat()),
                            )
                        }
                    }
                    Text("微调：", style = MaterialTheme.typography.bodyMedium)
                    HoldButton(enabled = enabled, onPress = { move(0f, -1f) }) { Text("↑") }
                    HoldButton(enabled = enabled, onPress = { move(0f, 1f) }) { Text("↓") }
                    HoldButton(enabled = enabled, onPress = { move(-1f, 0f) }) { Text("←") }
                    HoldButton(enabled = enabled, onPress = { move(1f, 0f) }) { Text("→") }
                }
                // 缩放
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("图片缩放：", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = {
                        zoom.value = (zoom.value / 1.25f).coerceAtLeast(1f)
                    }) { Text("−") }
                    Text(
                        "${(zoom.value * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = {
                        zoom.value = (zoom.value * 1.25f).coerceAtMost(4f)
                    }) { Text("＋") }
                }

                // 可滚动预览（缩放 > 1 时上下左右平移）。
                // 注意：外层 Column 已 verticalScroll（子项最大高度=无穷），此处再 verticalScroll 会触发
                // "infinite maximum height constraints" 崩溃，必须先 heightIn 限定有界高度。
                val img = bitmap.asImageBitmap()
                val wDp = with(density) { widthPx.toDp() }
                val hDp = with(density) { heightPx.toDp() }
                val previewMaxH = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = previewMaxH)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Box(modifier = Modifier.size(wDp, hDp)) {
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            modifier = Modifier.size(wDp, hDp)
                        )
                        handles.forEachIndexed { i, p ->
                            val dx = (p.x * sx).roundToInt()
                            val dy = (p.y * sy).roundToInt()
                            val isSel = i == selected.value
                            // 角标圈尺寸统一（选中态只靠颜色区分），并跟随缩放：与图片同比例放大/缩小
                            val handleDp = 30.dp * zoomV
                            Box(
                                modifier = Modifier
                                    // 以角标点为圆心居中绘制（此前是左上角对齐，导致保存坐标偏移半个圈）
                                    .offset {
                                        IntOffset(
                                            dx - handleDp.roundToPx() / 2,
                                            dy - handleDp.roundToPx() / 2
                                        )
                                    }
                                    .size(handleDp)
                                    .background(
                                        ACCENT.copy(alpha = if (isSel) 0.35f else 0.2f),
                                        CircleShape
                                    )
                                    .border(
                                        2.dp,
                                        if (isSel) Color(0xFF0F6E56) else ACCENT,
                                        CircleShape
                                    )
                                    // 单击即选中：detectDragGestures 的 onDragStart 要过触摸阈值才触发，纯点击选不中
                                    .pointerInput(i) {
                                        detectTapGestures { selected.value = i }
                                    }
                                    .pointerInput(i) {
                                        detectDragGestures(
                                            onDragStart = { selected.value = i },
                                        ) { _, drag ->
                                            val cur = handles[i]
                                            val nx = (cur.x + drag.x / sx).coerceIn(
                                                0f,
                                                bitmap.width.toFloat()
                                            )
                                            val ny = (cur.y + drag.y / sy).coerceIn(
                                                0f,
                                                bitmap.height.toFloat()
                                            )
                                            handles[i] = Offset(nx, ny)
                                        }
                                    },
                            )
                        }
                    }
                }

                // 角标中心点坐标（屏幕像素）：微调后保存的就是这些值，便于排查偏移问题
                if (handles.isNotEmpty()) {
                    Text("角标中心点坐标（屏幕像素）", style = MaterialTheme.typography.bodySmall)
                    handles.forEachIndexed { i, p ->
                        Text(
                            "${CORNER_LABELS[i]}：(${p.x.roundToInt()}, ${p.y.roundToInt()})",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (i == selected.value) ACCENT else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/** 长按连发按钮：按下立即触发一次，持续按住 300ms 后每 60ms 连续触发；单击也触发一次。 */
@Composable
private fun HoldButton(
    enabled: Boolean,
    onPress: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) {
            onPress()
            delay(300)
            while (true) {
                onPress()
                delay(60)
            }
        }
    }
    OutlinedButton(
        enabled = enabled,
        onClick = onPress,
        interactionSource = interaction,
        content = content
    )
}
