package com.chess.bot.game

/** 对局状态与数据结构（移植 python game/state.py，含第六~九轮整改结论）。 */

enum class Side(val cn: String) {
    RED("红"),
    BLACK("黑"),
    ;

    val opponent: Side get() = if (this == RED) BLACK else RED
}

enum class Phase(val cn: String) {
    OPENING("开局"),
    MIDDLE("中局"),
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

/** 单帧棋盘变动分类结果。 */
enum class FrameResult {
    SELF_DONE,
    SELF_THEN_ENEMY,
    LIFTED_ONLY,
    STATIONARY,
    TRANSIENT,
    RESIGN_SUSPECT,
}

data class FrameClass(
    val result: FrameResult,
    val selfMove: Move? = null,
    val enemyMove: Move? = null,
)

/** _verify 多帧校验后的最终结论。 */
enum class VerifyOutcome { DONE_OK, DONE_END, LIFTED_ONLY, STATIONARY, TRANSIENT }

/** 敌方检测单帧结论：推断出走法 / 提子 / 噪声 / 无变动。 */
sealed interface EnemyFrame {
    data class Moved(val move: Move) : EnemyFrame
    data object Lifted : EnemyFrame
    data object Noisy : EnemyFrame
    data object Silent : EnemyFrame
}

/** 认输/结束检测单帧结论。 */
enum class ResignResult { CONFIRMED, SUSPECT, NONE }

/** 一局棋的全部状态；控制层读写，纯函数只读。 */
class GameState {

    var board: Board = makeEmptyBoard()
        private set
    var prevBoard: Board? = null
        private set
    var mySide = Side.RED
    var turn = Side.RED
    var phase: Phase = Phase.MIDDLE
        private set
    var initialized = false
        private set
    var halfmoveClock = 0
        private set
    var gameOver = false
        private set
    var highlight : List<Pair<Int, Int>> = emptyList()
    var lastMove : String? = null
    var lastEvalScore = 0
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
        board = makeEmptyBoard()
        prevBoard = null
        mySide = Side.RED
        turn = Side.RED
        phase = Phase.MIDDLE
        initialized = false
        halfmoveClock = 0
        gameOver = false
        highlight = emptyList()
        lastMove = null
        lastEvalScore = 0
        resignStreak = 0
        noisyCount = 0
        liftLogged = false
    }

    fun snapshotPrev() {
        prevBoard = copyBoard(board)
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
    }

    fun applyEnemyMove(move: Move) {
        halfmoveClock = applyMove(board, move, halfmoveClock)
        turn = mySide
        highlight = listOf(move.src, move.dst)
    }

    /** 我方走棋成功 + 敌方已完成一步，轮到我方。 */
    fun applySelfThenEnemy(selfMove: Move, enemyMove: Move) {
        halfmoveClock = applyMove(board, selfMove, halfmoveClock)
        halfmoveClock = applyMove(board, enemyMove, halfmoveClock)
        turn = mySide
        highlight = listOf(enemyMove.src, enemyMove.dst)
    }

    fun markGameOver() {
        gameOver = true
    }
}
