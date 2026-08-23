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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogEvent
import com.chess.bot.log.LogKind
import com.chess.bot.service.BotForegroundService
import com.chess.bot.service.ScreenCaptureSource
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
            if (!granted) LogBus.log(LogKind.WARN, "通知权限被拒绝：前台服务通知将不显示")
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

    /** 屏幕捕获授权：成功即启动前台服务 + 截屏管线 + 悬浮窗。 */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                BotForegroundService.start(this, result.resultCode, result.data!!)
                LogBus.log(LogKind.OK, "屏幕捕获已授权，服务已启动")
            } else {
                LogBus.log(LogKind.WARN, "屏幕捕获授权被拒绝")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        val captureActive by ScreenCaptureSource.active.collectAsState()

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ChessBot 权限引导", style = MaterialTheme.typography.titleLarge)
            ChecklistCard()
            StartOrStopButton(captureActive)

            Text("运行日志", style = MaterialTheme.typography.titleMedium)
            LogTail()
        }
    }

    @Composable
    private fun LogTail() {
        val logs = remember { mutableStateListOf<LogEvent>() }
        LaunchedEffect(Unit) {
            LogBus.events.collect {
                logs.add(it)
                while (logs.size > 50) logs.removeAt(0)
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (logs.isEmpty()) {
                    Text("（暂无日志）", style = MaterialTheme.typography.bodySmall)
                }
                logs.forEach { event ->
                    Text(
                        "[${event.kind.name}] ${event.msg}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (event.kind) {
                            LogKind.ERROR -> MaterialTheme.colorScheme.error
                            LogKind.WARN -> MaterialTheme.colorScheme.tertiary
                            LogKind.OK -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun ChecklistCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CheckRow(
                    title = "① 通知权限",
                    granted = notificationsGranted.value,
                    actionLabel = "去设置",
                    onAction = {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
                CheckRow(
                    title = "② 悬浮窗权限",
                    granted = overlayGranted.value,
                    actionLabel = "去设置",
                    onAction = {
                        overlayLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                )
                // ③ 后台无限制：系统「忽略电池优化」白名单（唯一判定与设置途径）
                CheckRow(
                    title = "③  省电策略[无限制]",
                    granted = batteryIgnoreGranted.value,
                    actionLabel = "去设置",
                    onAction = {
                        ignoreBatteryLauncher.launch(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                )
                CheckRow(
                    title = "④ 无障碍服务",
                    granted = accessibilityGranted.value,
                    actionLabel = "去开启",
                    onAction = {
                        accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
                Text(
                    "⑤ 屏幕捕获授权在下方按钮中一并完成（每次启动需重新授权）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    @Composable
    private fun CheckRow(
        title: String,
        granted: Boolean,
        actionLabel: String,
        onAction: () -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (granted) "✅" else "⬜")
                Text(title)
            }
            if (!granted) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }

    @Composable
    private fun StartOrStopButton(captureActive: Boolean) {
        if (captureActive) {
            OutlinedButton(
                onClick = { BotForegroundService.stop(this@MainActivity) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("停止并退出悬浮窗")
            }
        } else {
            val prerequisitesMet =
                notificationsGranted.value && overlayGranted.value &&
                    accessibilityGranted.value && batteryIgnoreGranted.value
            Button(
                onClick = { launchProjectionConsent() },
                enabled = prerequisitesMet,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (prerequisitesMet) "授权并启动" else "请先完成 ①②③④")
            }
        }
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
