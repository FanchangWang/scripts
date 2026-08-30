package com.chess.bot.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chess.bot.game.Board
import com.chess.bot.game.PIECE_CN
import com.chess.bot.game.Side
import com.chess.bot.game.pieceColor

// 棋盘窗主题（土黄色盘底 + 黑线 + 红/黑圆底白字）
private val WinBg = Color(0xFFE9C46A)      // 土黄色盘底
private val WinBorder = Color(0x55222222)  // 窗体描边（深）
private val GridLine = Color(0xFF1A1A1A)   // 棋盘横竖线：黑
private val RedPieceBg = Color(0xFFC0392B) // 红子：红圆底
private val BlackPieceBg = Color(0xFF2B2B2B) // 黑子：黑圆底
private val PieceText = Color(0xFFFFFFFF)  // 棋子文字：白
private val ArrowRed = Color(0xFFC0392B)  // 最近一步=红方所走（对齐 HTML --rpk）
private val ArrowBlack = Color(0xFF5B7FBF) // 最近一步=黑方所走（HTML 未定义，拍板保留蓝）

/**
 * 棋盘小窗（2026-08-28 审计 §一.3）：
 * - 无标题栏、整窗拖动、不可隐藏（显示开关唯一出口=操控条「棋盘」）
 * - Canvas 直绘：PIECE_CN 汉字 + 圆底；绘制顺序=先棋子后箭头（箭头盖于棋子之上，长线不被棋子截断）
 * - 红/黑方各保留最近一步，各画一条箭头（红方红 / 黑方蓝）：
 *   我方箭头：走棋前(引擎已算未落子)圈标在目标格(TO)、走棋后圈标在起点格(FROM)，圈色=我方棋子色；
 *   敌方箭头：起点画空心圈，圈色=敌方棋子色
 * - 箭头为锥形（3dp 线身 + 宽三角头，头部半宽≈棋子半径 cell*0.44、头长 cell*0.66），方向更易读
 */
private enum class CircleAt { FROM, TO }

@Composable
fun BoardWindowContent(
    board: Board?,
    selfMoveCells: Pair<Pair<Int, Int>, Pair<Int, Int>>?,
    enemyMoveCells: Pair<Pair<Int, Int>, Pair<Int, Int>>?,
    mySideIsRed: Boolean,
    selfPlanned: Boolean = false,
    cellDp: Dp = 24.dp,
    padDp: Dp = 8.dp,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    val density = LocalDensity.current
    val cell = with(density) { cellDp.toPx() }
    val pad = with(density) { padDp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    // 棋子文字随格大小动态缩放，确保不超出圆形棋子范围
    val pieceStyle = remember(cell) {
        TextStyle(
            fontSize = with(density) { (cell * 0.58f).toSp() },
            fontWeight = FontWeight.Bold,
            color = PieceText,
        )
    }

    Canvas(
        modifier = Modifier
            .size(cellDp * 9 + padDp * 2, cellDp * 10 + padDp * 2)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(12.dp.toPx())

        // ---------- 底板 ----------
        drawRoundRect(color = WinBg, cornerRadius = corner, size = size)
        drawRoundRect(
            color = WinBorder,
            cornerRadius = corner,
            size = size,
            style = Stroke(width = 1.dp.toPx()),
        )

        fun center(r: Int, c: Int) = Offset(pad + (c + 0.5f) * cell, pad + (r + 0.5f) * cell)

        // ---------- 网格（中间 4 行竖线断开 = 楚河汉界） ----------
        val lineW = 1.dp.toPx() * 0.6f
        for (r in 0..10) {
            drawLine(
                GridLine,
                center(r, 0) - Offset(cell / 2, 0f),
                center(r, 8) + Offset(cell / 2, 0f),
                strokeWidth = lineW,
            )
        }
        for (c in 0..8) {
            val x = center(0, c).x
            if (c == 0 || c == 8) {
                drawLine(GridLine, Offset(x, pad), Offset(x, h - pad), strokeWidth = lineW)
            } else {
                // 楚河汉界：竖线只画上半与下半
                drawLine(
                    GridLine,
                    Offset(x, pad),
                    Offset(x, center(4, c).y + cell / 2),
                    strokeWidth = lineW
                )
                drawLine(
                    GridLine,
                    Offset(x, center(5, c).y - cell / 2),
                    Offset(x, h - pad),
                    strokeWidth = lineW
                )
            }
        }

        // ---------- 棋子（汉字 + 圆底）先画 ----------
        // 先绘制棋子，再绘制箭头（见下方）：确保箭头（尤其炮隔子吃子等长线）不被棋子覆盖、保持连贯；
        // 同时标记圈绘制在棋子之上，套在棋子外围清晰可见。
        val b = board
        if (b != null) {
            for (r in 0..9) {
                for (c in 0..8) {
                    val piece = b[r][c] ?: continue
                    val centerNow = center(r, c)
                    val radius = cell * 0.44f
                    val bg = if (pieceColor(piece) == Side.RED) RedPieceBg else BlackPieceBg
                    drawCircle(bg, radius = radius, center = centerNow)
                    drawCircle(
                        Color.Black.copy(alpha = 0.25f),
                        radius = radius,
                        center = centerNow,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    val label = PIECE_CN[piece] ?: "?"
                    val measured = textMeasurer.measure(label, pieceStyle)
                    drawText(
                        measured,
                        color = PieceText,
                        topLeft = Offset(
                            centerNow.x - measured.size.width / 2f,
                            centerNow.y - measured.size.height / 2f,
                        ),
                    )
                }
            }
        }

        // ---------- 红/黑方各一条箭头（锥形：细尾 + 宽三角头），画在棋子之上 ----------
        // 我方箭头：终点画圈（圈色=我方棋子色）；敌方箭头：起点画圈（圈色=敌方棋子色）
        // 箭头尖端停在起/终子圆边外，圈为空心描边圆环套在棋子外围；绘制于棋子之上确保不被覆盖
        val selfArrowColor = if (mySideIsRed) ArrowRed else ArrowBlack
        val enemyArrowColor = if (mySideIsRed) ArrowBlack else ArrowRed
        val selfCircleColor = if (mySideIsRed) RedPieceBg else BlackPieceBg
        val enemyCircleColor = if (mySideIsRed) BlackPieceBg else RedPieceBg

        fun drawMoveArrow(
            from: Pair<Int, Int>,
            to: Pair<Int, Int>,
            arrowColor: Color,
            circleAt: CircleAt,
            circleColor: Color,
        ) {
            val fromC = center(from.first, from.second)
            val toC = center(to.first, to.second)
            val dir = toC - fromC
            val len = dir.getDistance()
            if (len <= 1f) return
            val unit = Offset(dir.x / len, dir.y / len)
            val perp = Offset(-unit.y, unit.x)
            val startGap = cell * 0.42f   // 起点圆边起笔
            val tipGap = cell * 0.46f     // 尖端停在终点棋子圆边外（不被棋子盖住）
            // 头部目标≈棋子半径(cell*0.44)，但短步按比例收敛，避免越过起/终子
            val headLenDesired = cell * 0.66f
            val headHalfDesired = cell * 0.44f
            val maxHeadLen = kotlin.math.max(0f, len - startGap - tipGap)
            val headLen = kotlin.math.min(headLenDesired, maxHeadLen)
            val headHalf = kotlin.math.min(headHalfDesired, headLen * 0.66f)
            val tip = toC - unit * tipGap
            val headBase = tip - unit * headLen
            val bodyStart = fromC + unit * startGap
            // 线身（略加粗，3dp）
            drawLine(arrowColor, bodyStart, headBase, strokeWidth = 3.dp.toPx())
            // 头部（宽三角）
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(headBase.x + perp.x * headHalf, headBase.y + perp.y * headHalf)
                lineTo(headBase.x - perp.x * headHalf, headBase.y - perp.y * headHalf)
                close()
            }
            drawPath(path, arrowColor)
            // 标记圈（空心描边圆环，半径略大于棋子，套在棋子外围）：我方在终点、敌方在起点
            val circC = if (circleAt == CircleAt.FROM) fromC else toC
            drawCircle(
                circleColor,
                radius = cell * 0.48f,
                center = circC,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        if (enemyMoveCells != null) {
            val (ef, et) = enemyMoveCells
            drawMoveArrow(ef, et, enemyArrowColor, CircleAt.FROM, enemyCircleColor)
        }
        if (selfMoveCells != null) {
            val (sf, st) = selfMoveCells
            // 走棋前(已计算未落子)：圈标在目标格(TO)；走棋后：圈标在起点格(FROM)
            drawMoveArrow(
                sf,
                st,
                selfArrowColor,
                if (selfPlanned) CircleAt.TO else CircleAt.FROM,
                selfCircleColor
            )
        }
    }
}
