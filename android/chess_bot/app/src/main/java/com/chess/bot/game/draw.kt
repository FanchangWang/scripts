package com.chess.bot.game

/** 和棋弹窗决策（纯函数，移植 python draw.py）。 */

/** score 为我方评估分（正=我方占优）；超过 rejectCp 拒绝（true），否则同意。 */
fun decideDraw(score: Int, rejectCp: Int): Boolean = score > rejectCp
