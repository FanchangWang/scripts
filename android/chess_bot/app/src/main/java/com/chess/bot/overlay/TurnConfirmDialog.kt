package com.chess.bot.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 深色主题（与悬浮窗体系统一），对齐 TURN_LOG_PREVIEW.html 的弹窗设计稿。 */
private val DialogBg = Color(0xF01B1E26)
private val DialogBorder = Color(0x24FFFFFF)
private val TextMain = Color(0xFFF2F4F7)
private val TextDim = Color(0xFFBDC1C6)
private val TextFaint = Color(0x8CFFFFFF)

private val ChipBg = Color(0x14FFFFFF)
private val ChipBorder = Color(0x1AFFFFFF)

private val IconAmber = Color(0xFFFFD54F)
private val AccentGreen = Color(0xFF69F0AE)
private val ChipRed = Color(0xFFFFAB91)

private val OptionNoBg = Color(0x0FFFFFFF)
private val OptionNoBorder = Color(0x29FFFFFF)
private val OptionYesBg = Color(0x382E7D5B)
private val OptionYesBorder = Color(0xA62E7D5B)

/**
 * 轮次无法推断时的屏幕中央确认弹窗（深色，对齐 TURN_LOG_PREVIEW.html）。
 * - 琥珀色棋子图标 + 阶段/我方信息 chips + 说明文案
 * - 左右并排选项卡：暂不开始（中性）= 中止本次开局 / 我方先走（绿色）= 立即接管
 * - 底部一行：已等待秒数 + 「点弹窗外 = 暂不开始」（仅提示，不自动选择）
 * - 点击遮罩等同「暂不开始」
 */
@Composable
fun TurnConfirmDialog(
    mySideCn: String,
    phaseCn: String,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    // 已等待秒数：仅作反馈，不触发任何自动选择
    var waitedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            waitedSeconds++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .pointerInput(Unit) {
                detectTapGestures { onDecline() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = DialogBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, DialogBorder),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 330.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 琥珀色棋子图标
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(IconAmber.copy(alpha = 0.14f), RoundedCornerShape(21.dp))
                        .border(1.dp, IconAmber.copy(alpha = 0.4f), RoundedCornerShape(21.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("♟", color = IconAmber, style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    "无法判断轮到哪一方",
                    color = TextMain,
                    style = MaterialTheme.typography.titleMedium,
                )

                // 局面信息 chips：阶段（中性）+ 我方（红）
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip("阶段：$phaseCn", ChipBg, ChipBorder, TextDim)
                    InfoChip("我方：${mySideCn}方", Color(0x29E25C49), Color(0x59E25C49), ChipRed)
                }

                Text(
                    "自动识别未能确定当前该谁走棋，\n请确认是否由我方落子开局：",
                    color = TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                // 左右并排选项卡：暂不开始（左，中性）/ 我方先走（右，绿）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OptionCard(
                        title = "暂不开始",
                        note = "中止本次开局\n稍后需重新点「开始」",
                        bg = OptionNoBg,
                        border = OptionNoBorder,
                        titleColor = TextMain,
                        noteColor = TextFaint,
                        modifier = Modifier.weight(1f),
                        onClick = onDecline,
                    )
                    OptionCard(
                        title = "我方先走",
                        note = "bot 立即计算\n并走出第一步",
                        bg = OptionYesBg,
                        border = OptionYesBorder,
                        titleColor = AccentGreen,
                        noteColor = AccentGreen.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f),
                        onClick = onConfirm,
                    )
                }

                Spacer(Modifier.height(2.dp))
                Text(
                    "已等待 $waitedSeconds 秒 · 点弹窗外任意处 = 暂不开始",
                    color = TextFaint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** 选项卡：标题 + 两行后果说明。 */
@Composable
private fun OptionCard(
    title: String,
    note: String,
    bg: Color,
    border: Color,
    titleColor: Color,
    noteColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = titleColor,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            note,
            color = noteColor,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

/** 局面信息 chip：整体一段文本（如「阶段：开局」）。 */
@Composable
private fun InfoChip(text: String, bg: Color, border: Color, textColor: Color) {
    Text(
        text,
        color = textColor,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
