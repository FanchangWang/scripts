package com.chess.bot.game

import com.chess.bot.data.BotConfig
import com.chess.bot.log.LogBus
import com.chess.bot.log.LogLevel
import com.chess.bot.log.LogTag
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
 * 日志（2026-09-12 Q2）：本函数内直接落「识别明细」DEBUG 行（开关 BotConfig.data.debugVisionDetail，
 * 默认关），因此 BoardScan 不再回传纯日志字段（原 transitLifts / unconfirmedDetail / unconfirmedCells
 * 已随 2026-09-12 瘦身删除，降为本函数局部变量）。
 *
 * @param baseline   上一提交点冻结的 90 格中心小图（state.prevCellImgs），用作逐格 diff 基线
 * @param committed  已提交棋盘（state.board）；未变格直接沿用，保证 newBoard 完整
 * @param forceFull  全量模式（2026-09-13 用户批复 P5）：跳过逐格 diff 门，90 格**全部**跑 cls 复检。
 *   动因：diff 基线可能失明——落点被误提起后 committed 该格为 null，读数「空→lift」被当飞行伪影剔除
 *   （见下方 `old == null && new == LIFT` 分支），该格从此永不进 changes（真机 h6h4 卡死 39.7s）。
 *   ⚠️ 只放宽「是否进入复检」这一道门，**其余语义一律不变**：
 *   ① changes——未变格仍沿用 committed、低置信仍回退 committed、飞行伪影仍剔除；
 *   ② diffCells / driftCells——仍按「本帧截图格子图 vs prevCells 小图真有像素差」计
 *      （2026-09-13 用户批复：若让 diffCells 跟着全量走，恒 ≈90 会被下游 (d) 分支当成
 *      大面积遮挡 → occlusionStreak 每帧累积 → 第 7 帧 finishGame 误判终局）；
 *   产出结构与 diff 路径同构，调用方（verify 的 (a)~(e) 全部分支）零改动复用。
 * @return BoardScan(新布局, 相对 committed 的变动列表, 像素真变格数（与是否全量无关）,
 *   自修复格列表 driftCells——diff 触发但识别值==已提交(无真实走子)，提交点据此自愈 cellImgs，
 *   防止 baseline 永久陈旧(白点/高亮/光照漂移)导致误触发随步数累积，2026-08-30 05:49)
 */
fun recognizeBoardChanged(
    corrected: Mat,
    baseline: Array<Array<Mat?>>,
    committed: Board,
    mySide: Side = Side.RED,
    forceFull: Boolean = false,
): BoardScan {
    val board = makeEmptyBoard()
    val changes = mutableListOf<Change>()
    val driftCells = mutableListOf<Pair<Int, Int>>()
    val unconfirmedDetails = mutableListOf<String>()
    var diffCells = 0
    var unconfirmed = 0
    // 2026-09-07 GC 优化：90 格循环复用同一 10x10 patch 缓冲（单 worker 管线串行，
    // cropCellGrayInto + cellChanged 均为写入式/scratch 版；持久基线仍走分配版 cropCellGray）
    val patch = Recognizer.newCellPatchScratch()
    try {
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                Recognizer.cropCellGrayInto(corrected, r, c, patch)
                val base = baseline[r][c]
                // 2026-09-13 用户批复修正：forceFull 只放宽「本格是否进入复检」这一道门，
                // diffCells **必须仍由「像素真的变了」决定**（本帧截图格子图 vs prevCells 小图）。
                // 若照旧写成 `base == null || forceFull || cellChanged` 再无条件 ++，全量模式下
                // diffCells 恒 ≈90 > VERIFY_OCR_DIFF_CELLS(30) → verify/(d) 与 waitForEnemyMove/(d)
                // 会把每一帧都当「大面积遮挡」→ occlusionStreak 每帧累积 → 第 7 帧直接 finishGame
                // **误判对局结束**（不只是日志数字失真）。
                // 非全量时 forceFull=false，`forceFull || pixelChanged` ≡ `pixelChanged`，与旧实现逐字等价。
                val pixelChanged = base == null || Recognizer.cellChanged(patch, base)
                if (forceFull || pixelChanged) {
                    if (pixelChanged) diffCells++
                    val (new, res) = Recognizer.analyzeCellEx(corrected, r, c)
                    val old = committed[r][c]
                    if (old == null && new == Const.LIFT) {
                        // 空格不可能被提起（2026-09-06 语义约定）：这是飞行棋子途经相邻格的动画
                        // 伪影——裁剪窗拍到带阴影/运动模糊的悬空棋子 → cls lift 类误触发。
                        // 视为无变化：不进 changes（不参与帧分类/噪声计数/提交），board 写回
                        // committed 值，基线保持空格——伪影随棋子落定自然消失，无需刷新。
                        // 该计数原供 grabBoard 耗时行「剔除 N」使用，2026-09-12 Q1 取消，不再记账。
                        board[r][c] = old
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
                                // 未确认明细：old 显示已提交棋盘的实际棋子（board 里明明有值）；
                                // 与确认格同一表述格式（changeText），由「识别明细」开关日志承载
                                unconfirmedDetails.add(
                                    changeText(r, c, old, new, res.top1Prob, mySide)
                                )
                            } else {
                                changes.add(Change(r, c, old, new, res.top1Prob))
                            }
                        } else if (pixelChanged && base != null) {
                            // diff 触发但识别值与已提交一致（无真实走子）：画面漂移（白点/高亮/光照）。
                            // 提交点据此把 cellImgs 更新为当前干净外观，避免 baseline 永久陈旧→误触发累积。
                            // 漂移只服务基线自愈、与棋子变更无关，不落日志（2026-09-12 Q2）
                            // pixelChanged 门（2026-09-13 用户批复）：全量模式下「未变格」也在复检内，
                            // 但它们不属于漂移——不加此门会把 90 格全塞进 driftCells，令提交点把整盘基线
                            // 无条件刷成当前帧外观（含正处动画中的误读格，会把误读固化成新基线）。
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
    logVisionDetail(changes, diffCells, unconfirmed, unconfirmedDetails, mySide)
    return BoardScan(board, changes, diffCells, driftCells)
}

/**
 * 单格变更的中文表述：「格 旧->新[置信]」（如 `e7 黑將->空[1.00]`）。
 * 棋盘变化行与识别明细行（确认/未确认）共用同一格式，便于逐格对照。
 */
internal fun changeText(
    r: Int,
    c: Int,
    old: String?,
    new: String?,
    top1Prob: Float,
    mySide: Side,
): String =
    "${gridToSquare(r, c, mySide)} ${old?.let(::pieceLabel) ?: "空"}->" +
            "${new?.let(::pieceLabel) ?: "空"}[${"%.2f".format(top1Prob)}]"

/**
 * 识别明细日志（2026-09-12 Q2）：开关 `BotConfig.data.debugVisionDetail`（设置页「调试日志」分组，默认关）。
 *
 * 只报三类计数 + 明细：
 * - 光影变化格数：像素 diff 触发的格子数（含动画遮挡/伪影/漂移）——非全量模式下即「本帧有多少格
 *   进入复检」；全量模式下复检面扩到 90 格，但此计数**仍只算像素真变的格**（2026-09-13 用户批复：
 *   它同时是下游 (d) 大面积遮挡分支的判据，跟着全量走会让每帧都被误判成遮挡）
 * - 确认格：diff 且置信度达标 → 真实变更，附「格 旧->新[置信]」清单
 * - 未确认格：diff 但置信度不足 → 本帧丢弃、待下帧复检，附同格式清单
 *
 * 刻意不报（2026-09-12 Q2 用户裁定）：① 剔除——空格被 cls 误读成 lift 的飞行途经伪影，本帧即丢弃，
 * 无诊断价值；② 漂移——光效导致基线自愈，与棋子变更无关。
 *
 * 触发门（2026-09-12 Q2 二次裁定 A）：仅当本帧有确认格或未确认格时才打整块（含汇总行）——
 * 纯漂移帧（`光影=N 确认=0 未确认=0`：UI 光效致像素 diff 触发，棋子一个没变）整块静默。
 * 真机 log.txt 实测该类占汇总行 60%（834/1390）、占全日志 24%，收紧后识别明细行 2027 → 1193。
 */
private fun logVisionDetail(
    changes: List<Change>,
    diffCells: Int,
    unconfirmed: Int,
    unconfirmedDetails: List<String>,
    mySide: Side,
) {
    if (!BotConfig.data.debugVisionDetail) return
    if (changes.isEmpty() && unconfirmed == 0) return
    LogBus.log(
        LogLevel.DEBUG,
        LogTag.VISION,
        "识别明细 光影=$diffCells 确认=${changes.size} 未确认=$unconfirmed"
    )
    if (changes.isNotEmpty()) {
        LogBus.log(
            LogLevel.DEBUG,
            LogTag.VISION,
            "识别明细·确认 " + changes.joinToString(", ") {
                changeText(it.r, it.c, it.old, it.new, it.top1Prob, mySide)
            }
        )
    }
    if (unconfirmedDetails.isNotEmpty()) {
        LogBus.log(
            LogLevel.DEBUG,
            LogTag.VISION,
            "识别明细·未确认 " + unconfirmedDetails.joinToString(", ")
        )
    }
}

/** 单帧识别结果（方案 A 变种自修复用）。
 *  2026-09-12 瘦身：删 3 个纯日志字段（transitLifts / unconfirmedDetail / unconfirmedCells，
 *  明细日志已内聚到 recognizeBoardChanged），余下 4 个字段均有功能消费者。 */
data class BoardScan(
    val board: Board,
    val changes: List<Change>,
    val diffCells: Int,
    val driftCells: List<Pair<Int, Int>>,
)
