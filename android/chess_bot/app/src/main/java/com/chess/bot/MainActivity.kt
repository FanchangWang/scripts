package com.chess.bot

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chess.bot.data.BoardCornersStore
import com.chess.bot.data.BotConfig
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import com.chess.bot.service.BotForegroundService
import com.chess.bot.ui.CalibrationCard
import com.chess.bot.ui.CalibrationResultScreen
import com.chess.bot.ui.CalibrationScreen
import com.chess.bot.ui.CalibrationSession
import com.chess.bot.ui.ManualTuneScreen
import com.chess.bot.ui.Permissions
import com.chess.bot.ui.PlayCard
import com.chess.bot.ui.RecognizingScreen
import com.chess.bot.ui.SettingsScreen
import com.chess.bot.ui.Step1Screen
import com.chess.bot.ui.theme.ChessBotTheme

class MainActivity : ComponentActivity() {

    private var notificationsGranted = mutableStateOf(false)
    private var overlayGranted = mutableStateOf(false)
    private var accessibilityGranted = mutableStateOf(false)
    private var batteryIgnoreGranted = mutableStateOf(false)

    /** 运行设置页开关（引擎/开局库/节奏/日志等，2026-08-28 审计 R1）。 */
    private var showSettings = mutableStateOf(false)

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsGranted.value = granted
            if (!granted) LogBus.log(
                LogKind.WARN,
                LogTag.SYSTEM,
                "通知权限被拒绝：前台服务通知将不显示"
            )
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
                BotForegroundService.start(
                    this,
                    result.resultCode,
                    result.data!!,
                    calibration = true
                )
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

        // 系统返回键：子页（设置 / 校准各步）拦截回上一层，而不是关闭整个 App
        BackHandler(enabled = showSettings.value) { showSettings.value = false }
        BackHandler(enabled = !showSettings.value && calScreen != CalibrationScreen.HOME) {
            when (calScreen) {
                CalibrationScreen.STEP1 -> CalibrationSession.cancelStep1()
                CalibrationScreen.RECOGNIZING -> CalibrationSession.reset()
                CalibrationScreen.RESULT -> CalibrationSession.reset()
                CalibrationScreen.MANUAL -> CalibrationSession.backToResult()
                CalibrationScreen.HOME -> {}
            }
        }

        if (showSettings.value) {
            SettingsScreen(onBack = { showSettings.value = false })
            return
        }

        when (calScreen) {
            CalibrationScreen.HOME -> HomeContent(modifier, playActive)
            CalibrationScreen.STEP1 -> Step1Screen(
                onGoScreenshot = { CalibrationSession.onGoScreenshot(this@MainActivity) },
                onCancel = { CalibrationSession.cancelStep1() },
            )

            CalibrationScreen.RECOGNIZING -> RecognizingScreen()
            CalibrationScreen.RESULT -> CalibrationResultScreen()
            CalibrationScreen.MANUAL -> ManualTuneScreen()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeContent(modifier: Modifier, playActive: Boolean) {
        val permsOk = notificationsGranted.value && overlayGranted.value &&
                accessibilityGranted.value && batteryIgnoreGranted.value
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val calibrated = BoardCornersStore.has(w, h, this@MainActivity)
        // 加载运行配置，供「对弈」卡片摘要展示（设置页改后返回即重读内存最新值）
        LaunchedEffect(Unit) { BotConfig.load(this@MainActivity) }
        Column(modifier = modifier.fillMaxSize()) {
            TopAppBar(title = { Text("象棋机器人") })
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChecklistCard()
                CalibrationCard(
                    permsOk = permsOk,
                    onStart = { CalibrationSession.start(this@MainActivity) })
                PlayCard(
                    permsOk = permsOk,
                    calibrated = calibrated,
                    captureActive = playActive,
                    cfg = BotConfig.data,
                    onStart = { launchProjectionConsent() },
                    onStop = { BotForegroundService.stop(this@MainActivity) },
                    onOpenSettings = { showSettings.value = true },
                )
            }
        }
    }


    @Composable
    private fun ChecklistCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "权限与授权",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                )
                PermRow(
                    "通知权限", notificationsGranted.value, "已授权", "去设置",
                    onAction = { notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                )
                PermRow(
                    "悬浮窗权限", overlayGranted.value, "已授权", "去设置",
                    onAction = {
                        overlayLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            ),
                        )
                    },
                )
                PermRow(
                    "后台运行不受限", batteryIgnoreGranted.value, "已开启", "去设置",
                    onAction = {
                        ignoreBatteryLauncher.launch(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName")
                            ),
                        )
                    },
                )
                PermRow(
                    "无障碍服务", accessibilityGranted.value, "已开启", "去开启",
                    onAction = { accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )
            }
        }
    }

    /** 权限行：左标签，右状态/操作（对齐预览：标签 + 右侧状态文字，无序号徽标、无副标题）。 */
    @Composable
    private fun PermRow(
        title: String,
        granted: Boolean,
        grantedText: String,
        actionLabel: String,
        onAction: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (granted) {
                Text(
                    grantedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
