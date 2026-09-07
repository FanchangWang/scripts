package com.chess.bot.game

import com.chess.bot.vision.Recognizer
import org.opencv.core.Mat

/**
 * 棋盘识别（方案 A 变种，2026-08-30；2026-09-05 起变化格改走 YOLO cls 分类）：
 * 仅对「与 prevCellImgs 中心小图 diff 变化」的格子跑 cls 推理，未变格直接沿用已提交 board。
 *
 * 这是对抗「提子/走动动画/吃子光效/敌方攻击提醒」等画面噪声的根本保证——
 * newBoard 始终反映当前帧真实布局，未变格沿用 committed board 而非上一帧识别结果，
 * 不会出现 T2 增量 diff「像素无变化⇒沿用旧子」污染 newBoard 导致卡死的问题。
 *
 * 性能：原全量 90 格 × ~14 模板 ≈ 466ms/帧；现 90 次 10x10 diff(~6ms) + 仅变化格(~2-3)cls 推理
 * ≈ ~20ms/帧，整轮循环 ~540ms → ~80ms，敌方落定约 1 帧内检出。
 *
 * @param baseline   上一提交点冻结的 90 格中心小图（state.prevCellImgs），用作逐格 diff 基线
 * @param committed  已提交棋盘（state.board）；未变格直接沿用，保证 newBoard 完整
 * @return BoardScan(新布局, 相对 committed 的变动列表, diff 命中格数,
 *   自修复格列表 driftCells——diff 触发但识别值==已提交(无真实走子)，提交点据此自愈 cellImgs，
 *   防止 baseline 永久陈旧(白点/高亮/光照漂移)导致误触发随步数累积，2026-08-30 05:49,
 *   transitLifts——「空格→lift」飞行途经伪影剔除格数，2026-09-06 03:02)
 */
fun recognizeBoardChanged(
    corrected: Mat,
    baseline: Array<Array<Mat?>>,
    committed: Board,
    mySide: Side = Side.RED,
): BoardScan {
    val board = makeEmptyBoard()
    val changes = mutableListOf<Change>()
    val driftCells = mutableListOf<Pair<Int, Int>>()
    val clsDetails = mutableListOf<String>()
    var diffCells = 0
    var transitLifts = 0
    var unconfirmed = 0
    // 2026-09-07 GC 优化：90 格循环复用同一 10x10 patch 缓冲（单 worker 管线串行，
    // cropCellGrayInto + cellChanged 均为写入式/scratch 版；持久基线仍走分配版 cropCellGray）
    val patch = Recognizer.newCellPatchScratch()
    try {
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                Recognizer.cropCellGrayInto(corrected, r, c, patch)
                val base = baseline[r][c]
                if (base == null || Recognizer.cellChanged(patch, base)) {
                    diffCells++
                    // gateLift=true：变化格启用 lift 混淆门控（走子动画/选中高亮的棋子判提起，不判错子）
                    val (new, res) = Recognizer.analyzeCellEx(corrected, r, c, gateLift = true)
                    val old = committed[r][c]
                    if (old == null && new == Const.LIFT) {
                        // 空格不可能被提起（2026-09-06 语义约定）：这是飞行棋子途经相邻格的动画
                        // 伪影——裁剪窗拍到带阴影/运动模糊的悬空棋子 → cls lift 类误触发。
                        // 视为无变化：不进 changes（不参与帧分类/噪声计数/提交），board 写回
                        // committed 值，基线保持空格——伪影随棋子落定自然消失，无需刷新。
                        board[r][c] = old
                        transitLifts++
                    } else {
                        board[r][c] = new
                        if (old != new) {
                            if (res.top1Prob < Const.CLS_TRUST_MIN) {
                                // 置信度确认门（2026-09-07 真机实测 0.98）：低置信读数多为飞行中
                                // 动画帧（实测 0.96 且错判），不进 changes（不参与敌着两帧确认/
                                // 提交）、board 写回 committed、基线不刷新——下帧 diff 自动复检。
                                board[r][c] = old
                                unconfirmed++
                                clsDetails.add(
                                    "${gridToSquare(r, c, mySide)}=${new ?: "空"}" +
                                            "(${("%.2f".format(res.top1Prob))})未确认"
                                )
                            } else {
                                changes.add(Change(r, c, old, new))
                                // 变化格 cls 置信度明细（2026-09-07 诊断用）：排查 transit 帧
                                // 「棋子在飞但 cls 照样高置信读出落点子」（res 为抑制前原始 top1）
                                clsDetails.add(
                                    "${gridToSquare(r, c, mySide)}=${new ?: "空"}" +
                                            "(${("%.2f".format(res.top1Prob))},lift${
                                                "%.2f".format(
                                                    res.liftProb
                                                )
                                            })"
                                )
                            }
                        } else if (base != null) {
                            // diff 触发但识别值与已提交一致（无真实走子）：画面漂移（白点/高亮/光照）。
                            // 提交点据此把 cellImgs 更新为当前干净外观，避免 baseline 永久陈旧→误触发累积。
                            driftCells.add(r to c)
                        }
                    }
                } else {
                    board[r][c] = committed[r][c]
                }
            }
        }
    } finally {
        patch.release()
    }
    return BoardScan(
        board, changes, diffCells, driftCells, transitLifts,
        clsDetails.takeIf { it.isNotEmpty() }?.joinToString(", "),
        unconfirmed,
    )
}

/** 单帧识别结果（方案 A 变种自修复用）。 */
data class BoardScan(
    val board: Board,
    val changes: List<Change>,
    val diffCells: Int,
    val driftCells: List<Pair<Int, Int>>,
    val transitLifts: Int = 0,
    /** 变化格 cls 置信度明细（2026-09-07 诊断用）：「格=读数(top1,liftX)」逗号拼接，无变化格为 null。 */
    val clsDetail: String? = null,
    /** 低置信未确认格数（< CLS_TRUST_MIN，不进 changes 待下帧复检，2026-09-07）。 */
    val unconfirmedCells: Int = 0,
)
