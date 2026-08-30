package com.chess.bot.game

import com.chess.bot.vision.Recognizer
import org.opencv.core.Mat

/** 对局状态与数据结构（移植 python game/state.py，含第六~九轮整改结论）。 */

enum class Side(val cn: String) {
    RED("红"),
    BLACK("黑"),
    ;

    val opponent: Side get() = if (this == RED) BLACK else RED
}

enum class Phase(val cn: String) {
    OPENING("开局"),
    ENDGAME("残局"),
}

/** 一格变动：(行, 列, 旧棋子, 新棋子)。 */
data class Change(val r: Int, val c: Int, val old: String?, val new: String?)

/** 一步棋：起子格 -> 落子格，走动棋子，被吃棋子（如有）。 */
data class Move(
    val src: Pair<Int, Int>,
    val dst: Pair<Int, Int>,
    val piece: String,
    val captured: String? = null,
)

/**
 * 我方走棋后单帧分类结论（与 EnemyFrame 对称：result 判断，selfMove/enemyMove 取移动数据）。
 * SELF_DONE=我方走棋成功 / SELF_THEN_ENEMY=我方与敌方都走棋成功 /
 * LIFTED=我方提子 / NOISY=无法判断 / SILENT=无变动。
 */
enum class SelfFrameResult {
    SELF_DONE,
    SELF_THEN_ENEMY,
    LIFTED,
    NOISY,
    SILENT,
}

data class SelfFrame(
    val result: SelfFrameResult,
    val selfMove: Move? = null,
    val enemyMove: Move? = null,
)

/** verifyForSelfMove 多帧校验后的最终结论（返回给 doMove 的编排契约，与帧分类解耦）。
 *  DONE_OK=走棋成功 / DONE_END=走棋成功且终局 / LIFTED=仅见提子（未落定）/
 *  SILENT=无变动（棋盘未动）/ NOISY=无法判断（未落定，交 doMove 重试）。 */
enum class VerifyOutcome { DONE_OK, DONE_END, LIFTED, SILENT, NOISY }

/**
 * 敌方走棋检测单帧结论（与 SelfFrame 对称：result 判断，enemyMove 取移动数据；移动成功时 enemyMove 有值）。
 * MOVED=移动成功 / LIFTED=敌方提子 / NOISY=无法判断 / SILENT=无变动。
 */
enum class EnemyFrameResult {
    MOVED,
    LIFTED,
    NOISY,
    SILENT,
}

data class EnemyFrame(
    val result: EnemyFrameResult,
    val enemyMove: Move? = null,
)

/** 认输/结束检测单帧结论。 */
enum class ResignResult { CONFIRMED, SUSPECT, NONE }

/** 最近一步着法来源：引擎 / 开局库。 */
enum class MoveSource { ENGINE, BOOK }

/**
 * 对局状态机阶段（互斥单值，2026-08-28 审计 §一.5~7）：悬浮窗状态行/收起小窗据此展示。
 */
enum class BotStatus(val cn: String) {
    WAIT_PLACEMENT("等待摆棋"),
    INITIALIZING("初始化"),
    WAIT_SELF("等待我方回合"),
    THINKING("我方思考中"),
    TAPPING("点击走子"),
    VERIFYING("校验走子"),
    WAIT_ENEMY("等待对方"),
    ENEMY_CONFIRM("对方走子确认"),
    GAMEOVER_CHECK("终局检测"),
    AUTO_NEXT("自动下一局"),
    PAUSED("已暂停"),
    ABNORMAL_PAUSED("异常暂停"),
}

/** 一局棋的全部状态；控制层读写，纯函数只读。 */
class GameState {

    var board: Board = makeEmptyBoard()
        private set

    /** 已提交棋盘的 90 格中心小图（10x10 单通道灰度），与 board 严格对齐；仅由提交点局部更新。 */
    var cellImgs: Array<Array<Mat?>> = emptyCellImgs()
        private set

    /** 变更检测基线（snapshotPrev 时克隆自 cellImgs）。 */
    var prevCellImgs: Array<Array<Mat?>> = emptyCellImgs()
        private set
    var mySide = Side.RED
    var turn = Side.RED
    var phase: Phase = Phase.OPENING
        private set
    var initialized = false
        private set
    var halfmoveClock = 0
        private set
    var gameOver = false
        private set
    var highlight: List<Pair<Int, Int>> = emptyList()

    /** 我方最近一步起止格（棋盘小窗「我方箭头」数据源；红/黑方各保留各自最新一步）。 */
    var selfHighlight: List<Pair<Int, Int>> = emptyList()

    /** 敌方最近一步起止格（棋盘小窗「敌方箭头」数据源）。 */
    var enemyHighlight: List<Pair<Int, Int>> = emptyList()

    /** 我方箭头是否处于「走棋前（已计算未落子）」阶段：true=圈标在目标格(TO)，false=圈标在起点格(FROM)。 */
    var selfPlanned: Boolean = false

    var lastMove: String? = null

    /** 最近一次引擎评估分（我方视角，正=我方占优）；0 = 均势（含开局未跑引擎）。 */
    var lastEvalScore = 0

    /** 最近一步着法来源与引擎思考层数（开局库命中时 depth 无意义）。 */
    var lastMoveSource: MoveSource = MoveSource.ENGINE
    var lastMoveDepth = 0

    /** 本会话已走的半回合数（开局库「最大使用步数」判断用）。 */
    var moveCount = 0
    var resignStreak = 0
    var noisyCount = 0
    var liftLogged = false

    fun boardAt(r: Int, c: Int): String? = board[r][c]

    /** 仅供识别/测试写入整盘布局。 */
    fun replaceBoard(newBoard: Board) {
        board = newBoard
    }

    // ---------- 控制层写入接口（BotSession 使用） ----------

    fun reset() {
        releaseCellImgs(cellImgs)
        releaseCellImgs(prevCellImgs)
        cellImgs = emptyCellImgs()
        prevCellImgs = emptyCellImgs()
        board = makeEmptyBoard()
        mySide = Side.RED
        turn = Side.RED
        phase = Phase.OPENING
        initialized = false
        halfmoveClock = 0
        gameOver = false
        highlight = emptyList()
        selfHighlight = emptyList()
        enemyHighlight = emptyList()
        selfPlanned = false
        lastMove = null
        lastEvalScore = 0
        lastMoveSource = MoveSource.ENGINE
        lastMoveDepth = 0
        moveCount = 0
        resignStreak = 0
        noisyCount = 0
        liftLogged = false
    }

    fun snapshotPrev() {
        releaseCellImgs(prevCellImgs)
        prevCellImgs = cloneCellImgs(cellImgs)
    }

    /** 提交点：全量重建 cellImgs（仅开局/自动下一局，此时无动画中间帧风险）。 */
    fun resetCellImgs(corrected: Mat) {
        releaseCellImgs(cellImgs)
        cellImgs =
            Array(ROWS) { r -> Array(COLS) { c -> Recognizer.cropCellGray(corrected, r, c) } }
    }

    /**
     * 提交点：局部更新 cellImgs（与 board 局部更新保持一致，避免污染未变动格）。
     * - changes：真实走子格（old!=new），必更新。
     * - driftCells：自修复格（diff 触发但识别值==已提交，画面漂移如白点/高亮/光照），
     *   一并更新，防止 baseline 永久陈旧导致误触发随步数累积（2026-08-30 05:49 方案）。
     * 仅在提交点调用（不在每帧等待循环里），故等待期最多 1 格陈旧、可接受。
     */
    fun updateCellImgs(
        corrected: Mat,
        changes: List<Change>,
        driftCells: List<Pair<Int, Int>> = emptyList(),
    ) {
        val toRefresh = (changes.map { it.r to it.c } + driftCells).toSet()
        for ((r, c) in toRefresh) {
            cellImgs[r][c]?.release()
            cellImgs[r][c] = Recognizer.cropCellGray(corrected, r, c)
        }
    }

    fun markInitialized(mySide: Side, phase: Phase) {
        this.mySide = mySide
        this.phase = phase
        initialized = true
    }

    fun applySelfMove(move: Move) {
        halfmoveClock = applyMove(board, move, halfmoveClock)
        turn = mySide.opponent
        highlight = listOf(move.src, move.dst)
        selfHighlight = listOf(move.src, move.dst)
        selfPlanned = false
    }

    fun applyEnemyMove(move: Move) {
        halfmoveClock = applyMove(board, move, halfmoveClock)
        turn = mySide
        highlight = listOf(move.src, move.dst)
        enemyHighlight = listOf(move.src, move.dst)
    }

    /** 我方走棋成功 + 敌方已完成一步，轮到我方。 */
    fun applySelfThenEnemy(selfMove: Move, enemyMove: Move) {
        halfmoveClock = applyMove(board, selfMove, halfmoveClock)
        halfmoveClock = applyMove(board, enemyMove, halfmoveClock)
        turn = mySide
        highlight = listOf(enemyMove.src, enemyMove.dst)
        selfHighlight = listOf(selfMove.src, selfMove.dst)
        selfPlanned = false
        enemyHighlight = listOf(enemyMove.src, enemyMove.dst)
    }

    fun markGameOver() {
        gameOver = true
    }
}

/** cellImgs / prevCellImgs 的数组辅助（90 格 Mat 的创建/克隆/释放，防 native 内存泄漏）。 */
private fun emptyCellImgs(): Array<Array<Mat?>> = Array(ROWS) { Array(COLS) { null } }
private fun cloneCellImgs(src: Array<Array<Mat?>>): Array<Array<Mat?>> =
    Array(ROWS) { r -> Array(COLS) { c -> src[r][c]?.clone() } }

private fun releaseCellImgs(imgs: Array<Array<Mat?>>) {
    for (r in 0 until ROWS) for (c in 0 until COLS) imgs[r][c]?.release()
}
