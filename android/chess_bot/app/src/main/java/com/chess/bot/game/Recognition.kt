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
                    val (new, res) = Recognizer.analyzeCellEx(corrected, r, c)
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
                            // 置信度确认门（2026-09-07 真机实测 0.98 → 2026-09-11 D1 批复 0.95；2026-09-10 D2=A 分类别）：
                            // 低置信读数多为动画帧（实测 0.96 且错判），不进 changes（不参与敌着
                            // 两帧确认/提交）、board 写回 committed、基线不刷新——下帧 diff 自动复检。
                            // empty 分档 0.95：empty 训练采样动画遮挡图少 → 概率摊薄（log.txt 实测
                            // 未确认格 new=empty 105 格、[0.95,0.98) 18 格，为清盘动画渐进遮盖主力）。
                            // ⚠️ lift 无独立分档，走 CLS_TRUST_MIN → D1 后阈值同为 0.95（误确认成本上升）。
                            val trustMin =
                                if (new == null) Const.CLS_TRUST_MIN_EMPTY else Const.CLS_TRUST_MIN
                            if (res.top1Prob < trustMin) {
                                board[r][c] = old
                                unconfirmed++
                                // 未确认明细（2026-09-09 D5 中文化；Q3 修正 18:13）：old 显示已提交
                                // 棋盘的实际棋子（原「?」占位误导——board 里明明有值），grabBoard 变化行并入
                                clsDetails.add(
                                    "${
                                        gridToSquare(
                                            r,
                                            c,
                                            mySide
                                        )
                                    } ${old?.let(::pieceLabel) ?: "空"}->" +
                                            "${new?.let(::pieceLabel) ?: "空"}(${("%.2f".format(res.top1Prob))})未确认"
                                )
                            } else {
                                changes.add(Change(r, c, old, new, res.top1Prob))
                                // 变化格 cls 置信度改由 Change 结构化携带（2026-09-09 日志拆分 D1=A），
                                // grabBoard 变化行内联显示——消除「变化段 + cls 段」一格打两遍的冗余
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
        unconfirmedDetail = clsDetails.takeIf { it.isNotEmpty() }?.joinToString(", "),
        unconfirmedCells = unconfirmed,
    )
}

/** 单帧识别结果（方案 A 变种自修复用）。 */
data class BoardScan(
    val board: Board,
    val changes: List<Change>,
    val diffCells: Int,
    val driftCells: List<Pair<Int, Int>>,
    val transitLifts: Int = 0,
    /** 低置信未确认格明细（2026-09-09 D1=A/D5：「格 红兵->黑X(置信)未确认」逗号拼接，无则 null）。
     *  已确认变化格的置信度改由 [Change] 结构化携带，grabBoard 变化行统一拼装。 */
    val unconfirmedDetail: String? = null,
    /** 低置信未确认格数（< 分档阈值 CLS_TRUST_MIN / CLS_TRUST_MIN_EMPTY，不进 changes 待下帧复检）。 */
    val unconfirmedCells: Int = 0,
)
