package com.chess.bot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 统一子页面脚手架（Google Material 3 规范）：
 * - 顶部 TopAppBar，左侧标准返回箭头 IconButton，标题 + 可选副标题
 * - 首页进入的各个子页面（设置 / 校准步骤 / 识别结果 / 手动微调）统一采用，消除样式差异
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPageScaffold(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        // 背景 = background(#F3EDF7)，与 HTML .screen 背景一致；TopAppBar 默认 surface 形成层次
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // 20sp SemiBold 对齐 HTML .tb-title（默认 titleLarge 22sp 偏大）
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding -> content(innerPadding) }
}
