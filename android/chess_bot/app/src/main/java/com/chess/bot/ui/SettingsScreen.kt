package com.chess.bot.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.chess.bot.data.BotConfig
import com.chess.bot.data.BotConfigData
import com.chess.bot.data.ThinkMode
import com.chess.bot.log.FileLogger
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogKind
import com.chess.bot.log.LogTag
import kotlinx.coroutines.launch

/**
 * 运行设置页：
 * - 子页 TopAppBar + 返回（SubPageScaffold）已统一；
 * - 无卡片容器，改用「小灰大写分组标签」(引擎/开局库/悬浮窗/其他)；
 * - 每一项均为单行左右结构：左侧文字、右侧控件（下拉框 / 开关 / ›导航）；
 * - 下拉框统一为 ExposedDropdownMenu（替代滑块），选项集与标签严格对齐预览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(BotConfigData()) }
    LaunchedEffect(Unit) {
        BotConfig.load(context)
        cfg = BotConfig.data
    }

    fun update(transform: (BotConfigData) -> BotConfigData) {
        cfg = transform(cfg)
        val snapshot = cfg
        scope.launch { BotConfig.save(context, snapshot) }
    }

    SubPageScaffold(
        title = "运行设置",
        subtitle = "引擎 · 开局库 · 悬浮窗",
        onBack = onBack,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            GroupLabel("引擎")
            DropdownRow(
                title = "思考模式",
                options = ThinkMode.entries.toList(),
                selected = cfg.thinkMode,
                label = { it.cn },
            ) { v -> update { it.copy(thinkMode = v) } }
            DropdownRow(
                title = "思考时间",
                options = MOVETIME_OPTIONS,
                selected = cfg.movetimeMs,
                label = { "$it ms" },
            ) { v -> update { it.copy(movetimeMs = v) } }
            DropdownRow(
                title = "思考层数",
                options = DEPTH_OPTIONS,
                selected = cfg.depth,
                label = { "$it 层" },
            ) { v -> update { it.copy(depth = v) } }
            DropdownRow(
                title = "线程数",
                options = THREADS_OPTIONS,
                selected = cfg.threads,
                label = { "$it" },
            ) { v -> update { it.copy(threads = v) } }
            DropdownRow(
                title = "置换表 Hash",
                options = HASH_OPTIONS,
                selected = cfg.hashMb,
                label = { "$it MB" },
            ) { v -> update { it.copy(hashMb = v) } }

            GroupLabel("开局库")
            SwitchRow(
                title = "启用开局库",
                checked = cfg.bookEnabled,
            ) { v -> update { it.copy(bookEnabled = v) } }
            DropdownRow(
                title = "最大使用步数",
                options = BOOK_MOVES_OPTIONS,
                selected = cfg.bookMaxMoves,
                label = { "$it" },
                hint = "下拉选择 6~20",
            ) { v -> update { it.copy(bookMaxMoves = v) } }

            GroupLabel("对弈")
            DropdownRow(
                title = "落子间隔",
                options = TAP_HOLD_OPTIONS,
                selected = cfg.tapHoldMs,
                label = { "$it ms" },
            ) { v -> update { it.copy(tapHoldMs = v) } }
            DropdownRow(
                title = "走棋动画",
                options = VERIFY_ANIM_OPTIONS,
                selected = cfg.verifyAnimBaseMs,
                label = { "$it ms" },
            ) { v -> update { it.copy(verifyAnimBaseMs = v) } }
            DropdownRow(
                title = "走棋检测间隔",
                options = VERIFY_NEXT_FRAME_OPTIONS,
                selected = cfg.verifyNextFrameMs,
                label = { "$it ms" },
            ) { v -> update { it.copy(verifyNextFrameMs = v) } }

            GroupLabel("悬浮窗")
            SwitchRow(
                title = "自动下一局",
                checked = cfg.autoNext,
            ) { v -> update { it.copy(autoNext = v) } }
            SwitchRow(
                title = "棋盘绘制",
                checked = cfg.boardDraw,
            ) { v -> update { it.copy(boardDraw = v) } }

            GroupLabel("其他")
            DropdownRow(
                title = "文件日志级别",
                options = listOf(LogKind.DEBUG, LogKind.INFO, LogKind.WARN, LogKind.ERROR),
                selected = cfg.fileLogLevel,
                label = { it.name },
            ) { v -> update { it.copy(fileLogLevel = v) } }
            NavRow("导出日志（分享）") { exportLog(context) }
        }
    }
}

// ---------- 控件 ----------

/** 分组标签：小号灰字、大写、加字距（对齐 HTML .group-label）。 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp),
    )
}

/** 开关行：左文字，右 M3 Switch（对齐 HTML list-row 左右结构，无副标题）。 */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 导航行：左文字，右 ›（对齐 HTML list-row 的 chevron 行，如「导出日志（分享）」）。 */
@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            "›",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 下拉选择行（单行左右结构：左侧文字、右侧下拉框，min-width 128dp 对齐 HTML .dd-box）。 */
private val MOVETIME_OPTIONS = listOf(300, 500, 800, 1000, 1500, 2000, 3000, 5000)
private val DEPTH_OPTIONS = listOf(10, 15, 20, 25, 30, 35, 40)
private val TAP_HOLD_OPTIONS = listOf(50, 80, 100, 150, 200)
private val VERIFY_ANIM_OPTIONS = listOf(300, 350, 400, 450, 500)
private val VERIFY_NEXT_FRAME_OPTIONS = listOf(30, 50, 80, 100, 150)
private val THREADS_OPTIONS = listOf(4, 6, 8)
private val HASH_OPTIONS = listOf(128, 256, 512, 1024, 1536, 2048, 4096)
private val BOOK_MOVES_OPTIONS = listOf(8, 10, 12, 14, 16, 18, 20)

@Composable
private fun <T> DropdownRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    hint: String? = null,
    onChange: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box {
            OutlinedButton(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // 注意：此处不能 fillMaxWidth，否则按钮被撑满整行（HTML .dd-box 仅 min-width 128）
                Row(
                    Modifier.widthIn(min = 104.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label(selected), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "▾",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    val isSelected = option == selected
                    DropdownMenuItem(
                        text = {
                            Text(
                                label(option),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else null,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = { onChange(option); expanded = false },
                    )
                }
            }
        }
    }
}

/** 导出全部保留的会话日志（最多 10 个）：FileProvider + 系统分享面板（不写公共存储）。 */
private fun exportLog(context: Context) {
    val files = FileLogger.retainedFiles(context)
    if (files.isEmpty()) {
        LogBus.log(LogKind.WARN, LogTag.SYSTEM, "暂无可导出的会话日志")
        return
    }
    runCatching {
        val uris = ArrayList<Uri>(
            files.map {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    it
                )
            },
        )
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(intent, "导出运行日志（${files.size} 个会话）"))
        LogBus.log(LogKind.OK, LogTag.SYSTEM, "已调起日志导出：${files.size} 个会话文件")
    }.onFailure { e ->
        LogBus.log(
            LogKind.ERROR,
            LogTag.SYSTEM,
            "日志导出失败：${e::class.java.simpleName}: ${e.message}"
        )
    }
}
