package com.chess.bot.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 扩展语义色（非 M3 标准 token，2026-09-07 UI 重构组A）：
 * success/danger 及棋谱图标强调色，深浅各一套，经 ChessBotTheme 的
 * LocalExtendedColors 提供，替换各处硬编码（A2/A3/A4）。
 */
data class ExtendedColors(
    val success: Color,   // 成功/开始按钮/分数为正（light #1E8E3E / dark #4ADE80）
    val danger: Color,    // 失败/中断按钮/分数为负（light #C0202E / dark #F28B82）
    val bookFg: Color,    // 📖开局库 棋谱图标（#A06BD9）
    val fishFg: Color,    // 🐟皮卡鱼 棋谱图标（light #3D6FBF / dark #5B8FD9）
    val rateFg: Color,    // 待机/扫描类琥珀色圆点（light #B06A00 / dark #E0A940）
)

val LightExtendedColors = ExtendedColors(
    success = Color(0xFF1E8E3E),
    danger = Color(0xFFC0202E),
    bookFg = Color(0xFFA06BD9),
    fishFg = Color(0xFF3D6FBF),
    rateFg = Color(0xFFB06A00),
)

val DarkExtendedColors = ExtendedColors(
    success = Color(0xFF4ADE80),
    danger = Color(0xFFF28B82),
    bookFg = Color(0xFFA06BD9),
    fishFg = Color(0xFF5B8FD9),
    rateFg = Color(0xFFE0A940),
)
