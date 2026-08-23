package com.chess.bot

import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogEvent
import com.chess.bot.log.LogKind
import com.chess.bot.service.BotForegroundService
import com.chess.bot.service.ScreenCaptureSource
import com.chess.bot.ui.Permissions
import com.chess.bot.ui.theme.ChessBotTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.chess.bot.vision.Recognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var notificationsGranted = mutableStateOf(false)
    private var overlayGranted = mutableStateOf(false)
    private var accessibilityGranted = mutableStateOf(false)

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsGranted.value = granted
            if (!granted) LogBus.log(LogKind.WARN, "通知权限被拒绝：前台服务通知将不显示")
        }

    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayGranted.value = Permissions.canDrawOverlays(this)
    }

    private val accessibilityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            accessibilityGranted.value = Permissions.accessibilityEnabled(this)
        }

    /** 屏幕捕获授权：成功即启动前台服务 + 截屏管线。 */
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
    }

    @Composable
    private fun MainScreen(modifier: Modifier = Modifier) {
        // 回到前台时刷新各权限状态（从系统设置页返回）
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
            StartOrStopButton()

            if (captureActive) {
                Text("截屏预览", style = MaterialTheme.typography.titleMedium)
                CapturePreview()
                RecognizeButton()
                EngineSmokeButton()
            }
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
                    actionLabel = "请求",
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
                CheckRow(
                    title = "③ 无障碍服务",
                    granted = accessibilityGranted.value,
                    actionLabel = "去开启",
                    onAction = {
                        accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
                Text(
                    "④ 屏幕捕获授权在下方「授权并启动」中一并完成（每次启动需重新授权）",
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
    private fun StartOrStopButton() {
        val captureActive by ScreenCaptureSource.active.collectAsState()
        if (captureActive) {
            Button(
                onClick = { BotForegroundService.stop(this@MainActivity) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("停止并退出悬浮窗")
            }
        } else {
            val prerequisitesMet =
                notificationsGranted.value && overlayGranted.value && accessibilityGranted.value
            Button(
                onClick = { launchProjectionConsent() },
                enabled = prerequisitesMet,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (prerequisitesMet) "授权并启动" else "请先完成 ①②③")
            }
        }
    }

    @Composable
    private fun RecognizeButton() {
        val scope = rememberCoroutineScope()
        var layoutLines by remember { mutableStateOf<List<String>>(emptyList()) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = {
                    val bitmap = ScreenCaptureSource.get().latest()
                    if (bitmap == null) {
                        LogBus.log(LogKind.WARN, "暂无可用帧，稍后再试")
                        return@OutlinedButton
                    }
                    scope.launch(Dispatchers.Default) {
                        val board = Recognizer.recognize(this@MainActivity, bitmap)
                        if (board == null) {
                            layoutLines = emptyList()
                            return@launch
                        }
                        val lines = Recognizer.formatLayout(board)
                        layoutLines = lines
                        lines.forEach { LogBus.log(LogKind.INFO, it) }
                        LogBus.log(LogKind.OK, "棋盘识别完成（详见上方 r9..r0 行）")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("识别棋盘（打印 10x9 布局）")
            }
            layoutLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }

    @Composable
    private fun EngineSmokeButton() {
        val scope = rememberCoroutineScope()
        OutlinedButton(
            onClick = {
                scope.launch(Dispatchers.Default) {
                    try {
                        val fen = com.chess.bot.game.fenOfBoard(
                            com.chess.bot.game.fullStartBoard(),
                            com.chess.bot.game.Side.RED,
                        )
                        val (move, score) =
                            com.chess.bot.engine.PikafishEngine.get().bestMove(this@MainActivity, fen)
                        LogBus.log(LogKind.OK, "引擎冒烟：bestmove=$move score=$score")
                    } catch (e: com.chess.bot.engine.EngineError) {
                        LogBus.log(LogKind.ERROR, "引擎冒烟失败：${e.message}")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("引擎冒烟（初始局面 bestmove）")
        }
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    @Composable
    private fun CapturePreview() {
        var preview by remember { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                preview = ScreenCaptureSource.get().latest()?.let { bmp ->
                    // 预览用独立副本，避免与内部共享缓冲互相干扰
                    Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
                }
                delay(500)
            }
        }
        val bmp = preview
        if (bmp == null) {
            Text("等待第一帧…", style = MaterialTheme.typography.bodySmall)
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "屏幕截图预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bmp.width.toFloat() / bmp.height),
            )
        }
    }
}
