package com.chess.bot.game

import com.chess.bot.vision.Recognizer
import org.opencv.core.Mat

/**
 * 棋盘识别（方案 A 变种，2026-08-30）：
 * 仅对「与 prevCellImgs 中心小图 diff 变化」的格子跑模板匹配，未变格直接沿用已提交 board。
 *
 * 这是对抗「提子/走动动画/吃子光效/敌方攻击提醒」等画面噪声的根本保证——
 * newBoard 始终反映当前帧真实布局，未变格沿用 committed board 而非上一帧识别结果，
 * 不会出现 T2 增量 diff「像素无变化⇒沿用旧子」污染 newBoard 导致卡死的问题。
 *
 * 性能：原全量 90 格 × ~14 模板 ≈ 466ms/帧；现 90 次 10x10 diff(~6ms) + 仅变化格(~2-3)全量匹配(~15ms)
 * ≈ ~20ms/帧，整轮循环 ~540ms → ~80ms，敌方落定约 1 帧内检出。
 *
 * @param baseline   上一提交点冻结的 90 格中心小图（state.prevCellImgs），用作逐格 diff 基线
 * @param committed  已提交棋盘（state.board）；未变格直接沿用，保证 newBoard 完整
 * @return BoardScan(新布局, 相对 committed 的变动列表, diff 命中格数,
 *   自修复格列表 driftCells——diff 触发但识别值==已提交(无真实走子)，提交点据此自愈 cellImgs，
 *   防止 baseline 永久陈旧(白点/高亮/光照漂移)导致误触发随步数累积，2026-08-30 05:49)
 */
fun recognizeBoardChanged(
    corrected: Mat,
    templates: Map<String, Mat>,
    baseline: Array<Array<Mat?>>,
    committed: Board,
): BoardScan {
    val board = makeEmptyBoard()
    val changes = mutableListOf<Change>()
    val driftCells = mutableListOf<Pair<Int, Int>>()
    var diffFires = 0
    for (r in 0 until ROWS) {
        for (c in 0 until COLS) {
            val patch = Recognizer.cropCellGray(corrected, r, c)
            val base = baseline[r][c]
            if (base == null || Recognizer.cellChanged(patch, base)) {
                diffFires++
                val new = Recognizer.analyzeCell(corrected, r, c, templates)
                board[r][c] = new
                val old = committed[r][c]
                if (old != new) {
                    changes.add(Change(r, c, old, new))
                } else if (base != null) {
                    // diff 触发但识别值与已提交一致（无真实走子）：画面漂移（白点/高亮/光照）。
                    // 提交点据此把 cellImgs 更新为当前干净外观，避免 baseline 永久陈旧→误触发累积。
                    driftCells.add(r to c)
                }
            } else {
                board[r][c] = committed[r][c]
            }
            patch.release()
        }
    }
    return BoardScan(board, changes, diffFires, driftCells)
}

/** 单帧识别结果（方案 A 变种自修复用）。 */
data class BoardScan(
    val board: Board,
    val changes: List<Change>,
    val diffFires: Int,
    val driftCells: List<Pair<Int, Int>>,
)
