package com.chess.bot

import android.content.ComponentName
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogEvent
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import com.chess.bot.service.BotForegroundService
import com.chess.bot.data.BoardCornersStore
import com.chess.bot.ui.CalibrationCard
import com.chess.bot.ui.CalibrationResultScreen
import com.chess.bot.ui.CalibrationScreen
import com.chess.bot.ui.CalibrationSession
import com.chess.bot.ui.ManualTuneScreen
import com.chess.bot.ui.PlayCard
import com.chess.bot.ui.RecognizingScreen
import com.chess.bot.ui.Step1Screen
import com.chess.bot.ui.Permissions
import com.chess.bot.ui.theme.ChessBotTheme

class MainActivity : ComponentActivity() {

    private var notificationsGranted = mutableStateOf(false)
    private var overlayGranted = mutableStateOf(false)
    private var accessibilityGranted = mutableStateOf(false)
    private var batteryIgnoreGranted = mutableStateOf(false)

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsGranted.value = granted
            if (!granted) LogBus.log(LogKind.WARN, LogTag.SYSTEM, "通知权限被拒绝：前台服务通知将不显示")
        }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            overlayGranted.value = Permissions.canDrawOverlays(this)
        }

    private val accessibilityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            accessibilityGranted.value = Permissions.accessibilityEnabled(this)
        }

    /** 系统忽略电池优化授权弹窗；返回后 onResume/batteryIgnore 重查。 */
    private val ignoreBatteryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            batteryIgnoreGranted.value = Permissions.batteryIgnoreGranted(this)
        }

    /** 屏幕捕获授权（对弈）：成功即启动前台服务 + 截屏管线 + 对弈控制条悬浮窗。 */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                BotForegroundService.start(this, result.resultCode, result.data!!)
                LogBus.log(LogKind.OK, LogTag.SYSTEM, "屏幕捕获已授权，服务已启动")
            } else {
                LogBus.log(LogKind.WARN, LogTag.SYSTEM, "屏幕捕获授权被拒绝")
            }
        }

    /** 屏幕捕获授权（校准）：成功即启动前台服务持管线（不弹控制条），随后弹出悬浮截图条。 */
    private val calibrationProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                BotForegroundService.start(this, result.resultCode, result.data!!, calibration = true)
                CalibrationSession.onProjectionGranted(this)
                LogBus.log(LogKind.OK, LogTag.CALIB, "屏幕捕获已授权，进入截屏")
            } else {
                LogBus.log(LogKind.WARN, LogTag.CALIB, "屏幕捕获授权被拒绝")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CalibrationSession.projectionRequest = { launchCalibrationProjection() }
        setContent {
            ChessBotTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun refreshStates() {
        notificationsGranted.value = Permissions.notificationsGranted(this)
        overlayGranted.value = Permissions.canDrawOverlays(this)
        accessibilityGranted.value = Permissions.accessibilityEnabled(this)
        batteryIgnoreGranted.value = Permissions.batteryIgnoreGranted(this)
    }

    @Composable
    private fun MainScreen(modifier: Modifier = Modifier) {
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshStates()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val playActive by com.chess.bot.overlay.BotRuntime.playActive.collectAsState()
        val calScreen by CalibrationSession.screen

        when (calScreen) {
            CalibrationScreen.HOME -> HomeContent(modifier, playActive)
            CalibrationScreen.STEP1 -> Box(modifier) {
                Step1Screen(
                    onGoScreenshot = { CalibrationSession.onGoScreenshot(this@MainActivity) },
                    onCancel = { CalibrationSession.cancelStep1() },
                )
            }
            CalibrationScreen.RECOGNIZING -> Box(modifier) { RecognizingScreen() }
            CalibrationScreen.RESULT -> Box(modifier) { CalibrationResultScreen() }
            CalibrationScreen.MANUAL -> Box(modifier) { ManualTuneScreen() }
        }
    }

    @Composable
    private fun HomeContent(modifier: Modifier, playActive: Boolean) {
        val permsOk = notificationsGranted.value && overlayGranted.value &&
            accessibilityGranted.value && batteryIgnoreGranted.value
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val calibrated = BoardCornersStore.has(w, h, this@MainActivity)
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ChessBot 象棋自动对弈", style = MaterialTheme.typography.titleLarge)

            SectionTitle("① 权限与授权")
            ChecklistCard()

            SectionTitle("② 棋盘四角校准")
            CalibrationCard(permsOk = permsOk, onStart = { CalibrationSession.start(this@MainActivity) })

            SectionTitle("③ 对弈")
            PlayCard(
                permsOk = permsOk,
                calibrated = calibrated,
                captureActive = playActive,
                onStart = { launchProjectionConsent() },
                onStop = { BotForegroundService.stop(this@MainActivity) },
            )

            LogTail()
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }

    @Composable
    private fun LogTail() {
        val logs = remember { mutableStateListOf<LogEvent>() }
        var expanded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            LogBus.events.collect {
                logs.add(it)
                while (logs.size > 50) logs.removeAt(0)
            }
        }
        // 标题行置于 Card 外：左侧标题，右侧展开/收起开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("运行日志（${logs.size}）", style = MaterialTheme.typography.titleMedium)
            Text(
                if (expanded) "▼ 收起" else "▶ 展开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        // 收起时不渲染 Card，避免留下空边框
        if (expanded) {
            // 最大高度限制：日志再多也只内部滚动，不把页面撑长
            val logScroll = rememberScrollState()
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .heightIn(max = 320.dp)
                        .verticalScroll(logScroll),
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            "（暂无日志）",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    logs.forEach { event ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                "${event.time} ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                "● ",
                                style = MaterialTheme.typography.bodySmall,
                                color = when (event.kind) {
                                    LogKind.ERROR -> MaterialTheme.colorScheme.error
                                    LogKind.WARN -> MaterialTheme.colorScheme.tertiary
                                    LogKind.OK, LogKind.GAME -> MaterialTheme.colorScheme.primary
                                    LogKind.MOVE -> MaterialTheme.colorScheme.secondary
                                    LogKind.ENEMY -> Color(0xFF7C4DFF)
                                    else -> MaterialTheme.colorScheme.outline
                                },
                            )
                            Text(
                                "[${event.tag.cn}] ${event.msg}",
                                style = MaterialTheme.typography.bodySmall,
                                color = when (event.kind) {
                                    LogKind.ERROR -> MaterialTheme.colorScheme.error
                                    LogKind.WARN -> MaterialTheme.colorScheme.tertiary
                                    LogKind.OK -> MaterialTheme.colorScheme.primary
                                    LogKind.DEBUG -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ChecklistCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                PermRow(
                    "① 通知权限", "POST_NOTIFICATIONS",
                    notificationsGranted.value, "去设置",
                    onAction = { notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                )
                PermRow(
                    "② 悬浮窗权限", "Overlay 悬浮窗",
                    overlayGranted.value, "去设置",
                    onAction = {
                        overlayLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        )
                    },
                )
                PermRow(
                    "③ 省电策略", "无限制后台",
                    batteryIgnoreGranted.value, "去设置",
                    onAction = {
                        ignoreBatteryLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                        )
                    },
                )
                PermRow(
                    "④ 无障碍服务", "Accessibility",
                    accessibilityGranted.value, "去开启",
                    onAction = { accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )
            }
        }
    }

    /** 权限行：左侧序号徽标 + 标题/说明，右侧授权结果徽章或交互按钮（对齐预览左右样式）。 */
    @Composable
    private fun PermRow(
        title: String,
        sub: String,
        granted: Boolean,
        actionLabel: String,
        onAction: () -> Unit,
    ) {
        val code = title.take(1)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(26.dp).background(
                        if (granted) Color(0xFFE6F4EA) else Color(0xFFF1F3F4),
                        CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(code, style = MaterialTheme.typography.bodySmall, color = if (granted) Color(0xFF1E8E3E) else Color(0xFF8A9099))
                }
                Column {
                    Text(title.removePrefix(code).trim(), style = MaterialTheme.typography.bodyMedium)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (granted) {
                Text(
                    "已授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF1E8E3E),
                    modifier = Modifier.background(Color(0xFFE6F4EA), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            } else {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun launchCalibrationProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        calibrationProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
