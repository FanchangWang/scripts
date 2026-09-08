package com.chess.bot.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.chess.bot.data.BoardCornersStore
import com.chess.bot.data.BotConfig
import com.chess.bot.ui.theme.LocalExtendedColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val CORNER_LABELS = listOf("左上", "右上", "左下", "右下")

/** 主界面「棋盘四角校准」卡片：分辨率 + 状态 + 开始/重新校准；权限未齐时禁用并提示。 */
@Composable
fun CalibrationCard(permsOk: Boolean, onStart: () -> Unit) {
    val context = LocalContext.current
    val w = context.resources.displayMetrics.widthPixels
    val h = context.resources.displayMetrics.heightPixels
    val calibrated = BoardCornersStore.has(w, h, context)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("棋盘四角校准", style = MaterialTheme.typography.titleMedium)
            ValueRow("分辨率 ${w} × ${h}", if (calibrated) "已校准" else "未校准")
            if (!permsOk) {
                Text(
                    "请先在上方完成「权限与授权」四项授权，再进行校准。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 与「开始对弈」完全同款：启用=主色填充（.fbtn.fill），禁用=灰底+描边（PrimaryActionButton）
            PrimaryActionButton(
                text = if (calibrated) "重新校准" else "开始校准",
                enabled = permsOk,
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            )
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
            // 指引卡：标题 + 说明（对齐 HTML「操作指引」卡）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("操作指引", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "打开象棋 App → 人机对战 → 选择执红，停在 32 子开局、未走棋状态，再点下方按钮。\n\n" +
                                "黑車位于上方两角、红俥位于下方两角，算法据此定位四个角。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // 操作卡：按钮纵向全宽堆叠，主操作在上（对齐 HTML：去截图 → 取消）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(onClick = onGoScreenshot, modifier = Modifier.fillMaxWidth()) {
                        Text("去截图")
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

/** 主界面「对弈」卡片：展示当前引擎思考时间/开局库配置摘要 + 内联「设置」入口；授权并启动。 */
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
    val context = LocalContext.current
    var cfgState by remember { mutableStateOf(cfg) }
    LaunchedEffect(cfg) { cfgState = cfg }
    val scope = rememberCoroutineScope()
    fun update(transform: (com.chess.bot.data.BotConfigData) -> com.chess.bot.data.BotConfigData) {
        cfgState = transform(cfgState)
        val snap = cfgState
        scope.launch { BotConfig.save(context, snap) }
    }

    val enabled = permsOk && calibrated
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("对弈", style = MaterialTheme.typography.titleMedium)
            // 配置摘要（开局库仅启用状态）
            ValueRow("引擎思考时间", thinkSummary(cfgState))
            ValueRow("开局库", if (cfgState.bookEnabled) "已启用" else "已关闭")
            // 主界面快捷开关：与设置页一致，BotConfig.save → DataStore，开局时读取（不实时驱动运行中的 BotRuntime）
            SwitchRow("自动下一局", cfgState.autoNext) { v -> update { it.copy(autoNext = v) } }
            SwitchRow("棋盘绘制", cfgState.boardDraw) { v -> update { it.copy(boardDraw = v) } }
            ChevronRow("设置", onOpenSettings)
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
                PrimaryActionButton(
                    text = "开始对弈（悬浮窗模式）",
                    enabled = enabled,
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 首页「引擎思考时间」行摘要：显示思考时长（质量门控下「思考时间」= 最短思考时长）。 */
private fun thinkSummary(cfg: com.chess.bot.data.BotConfigData): String {
    return "${cfg.movetimeMs} ms"
}

/** 步骤 2/2 截图后：回 App 立即显示「识别中」，后台识别完成后自动切 RESULT。 */
@Composable
fun RecognizingScreen() {
    val progress by CalibrationSession.progress
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
            progress.ifEmpty { "准备识别环境…" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            "首次识别需初始化 OpenCV 并加载模板套与 ONNX 会话，可能耗时数秒",
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                // 32 子校验未通过（或未定位四角）时禁用保存（2026-09-05 需求）
                enabled = dispCorners != null && passed,
                onClick = { CalibrationSession.save(context) {} },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        dispCorners == null -> "未定位四角，请手动微调"
                        passed -> "确认并保存"
                        else -> "32 子校验未通过，禁止保存"
                    }
                )
            }
            // 32 子校验结果提示：置于保存按钮正下方，便于查看
            if (dispCorners != null) {
                val ext = LocalExtendedColors.current
                val badgeColor = if (passed) ext.success else ext.danger
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
            }

            if (bitmap != null && dispCorners != null) {
                CornerCropGrid(bitmap, dispCorners)
            }

            err?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (dispCorners != null) {
                if (CalibrationSession.detectSource.isNotEmpty() && CalibrationSession.corners != null) {
                    val sourceCn = if (CalibrationSession.detectSource == "det") {
                        "YOLO det 模型"
                    } else {
                        "模板匹配 · ${CalibrationSession.detectSource.removePrefix("template:")}"
                    }
                    Text(
                        "识别来源：$sourceCn",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (CalibrationSession.matchScores.isNotEmpty() && CalibrationSession.corners != null) {
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
    val accentColor = LocalExtendedColors.current.success // 角标强调色（深浅自适应，A3）

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
        if (bitmap == null || handles.isNotEmpty()) return@LaunchedEffect
        // 有自动结果用自动结果；无结果（det/模板全失败）时按屏幕比例给默认位置，用户拖动修正
        val src = auto ?: listOf(
            0.12 * bitmap.width to 0.16 * bitmap.height,
            0.88 * bitmap.width to 0.16 * bitmap.height,
            0.12 * bitmap.width to 0.84 * bitmap.height,
            0.88 * bitmap.width to 0.84 * bitmap.height,
        )
        handles.clear()
        handles.addAll(src.map { c -> Offset(c.first.toFloat(), c.second.toFloat()) })
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        accentColor.copy(alpha = if (isSel) 0.35f else 0.2f),
                                        CircleShape
                                    )
                                    .border(
                                        2.dp,
                                        accentColor,
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
                            color = if (i == selected.value) accentColor else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 四角裁剪图 2x2 网格：每角以坐标为中心裁 200x200（边缘自动钳制），替代整图标注展示。
 * 原因：整张截图过长，会把下方坐标文字顶出屏幕，不便查看（2026-09-05 需求）。
 * 每张裁剪图上画出角点标记（白外圈 + 主题色内圈），位置为角点在裁块内的实际偏移。
 */
@Composable
private fun CornerCropGrid(bitmap: Bitmap, corners: List<Pair<Double, Double>>) {
    val half = 100
    val accent = LocalExtendedColors.current.success.toArgb()
    val crops = remember(bitmap, corners, accent) {
        corners.map { (x, y) ->
            val cx = x.roundToInt()
            val cy = y.roundToInt()
            val l = (cx - half).coerceAtLeast(0)
            val t = (cy - half).coerceAtLeast(0)
            val r = (cx + half).coerceAtMost(bitmap.width)
            val b = (cy + half).coerceAtMost(bitmap.height)
            val base = Bitmap.createBitmap(
                bitmap, l, t, (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1)
            )
            // 可变副本上画角点标记（边缘钳制时角点不一定在裁块中心，按实际偏移画）
            val crop = base.copy(Bitmap.Config.ARGB_8888, true) ?: base
            val canvas = android.graphics.Canvas(crop)
            val ox = (cx - l).toFloat()
            val oy = (cy - t).toFloat()
            val outer = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 6f
                color = android.graphics.Color.WHITE
            }
            val inner = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f
                color = accent
            }
            canvas.drawCircle(ox, oy, 16f, outer)
            canvas.drawCircle(ox, oy, 12f, inner)
            crop
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (col in 0 until 2) {
                    val i = row * 2 + col
                    Column(modifier = Modifier.weight(1f)) {
                        Image(
                            bitmap = crops[i].asImageBitmap(),
                            contentDescription = CORNER_LABELS[i],
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
                        )
                        Text(
                            "${CORNER_LABELS[i]}（${corners[i].first.roundToInt()}, ${corners[i].second.roundToInt()}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalExtendedColors.current.success,
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
