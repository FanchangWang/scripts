package com.chess.bot

import com.chess.bot.game.Const
import com.chess.bot.game.SettleWaiter
import com.chess.bot.game.Side
import org.junit.Assert.assertTrue
import org.junit.Test
import com.chess.bot.TestBoards as TB

/**
 * 摆棋稳定等待器测试（R4/R5；规则与 python scan_and_wait 对齐）。
 *
 * 三分支：31 子持续等待 / 32 子按新开局快速返回 / 其余子数连续 3 帧逐值相同返回。
 */
class SettleWaiterTest {

    private val waiter = SettleWaiter()

    /** 完整布局移除 n 个棋子（保持子数可控）。 */
    private fun boardMissing(n: Int): com.chess.bot.game.Board {
        val b = TB.fullBoard(Side.RED)
        var removed = 0
        outer@ for (r in 0 until 10) {
            for (c in 0 until 9) {
                if (b[r][c] != null) {
                    b[r][c] = null
                    removed++
                    if (removed >= n) break@outer
                }
            }
        }
        return b
    }

    @Test
    fun `32 子立即按新开局返回`() {
        val feed = waiter.feed(TB.fullBoard(Side.RED))
        assertTrue(feed is SettleWaiter.Feed.Ready && feed.count == 32)
    }

    @Test
    fun `31 子持续等待不返回`() {
        repeat(10) {
            assertTrue(waiter.feed(boardMissing(1)) is SettleWaiter.Feed.Waiting)
        }
    }

    /** 标准开局移除 1 子后，再把某子挪到非初始位置 → 31 子且非全初始位置 = 残局。 */
    private fun endgame31(mySide: Side): com.chess.bot.game.Board {
        val b = boardMissing(1) // 31 子，全部仍在初始位置
        b[9][0] = null // 移走红车（原在初始位置）
        b[5][0] = "r_R" // 放到非初始空格，破坏初始性
        return b
    }

    @Test
    fun `31 子残局稳定后返回而非无限等待`() {
        val b = endgame31(Side.RED)
        // 第 1 帧进入残局分支（稳定 1）；第 2/3 帧走稳定计数分支累计到阈值
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting)
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting)
        val ready = waiter.feed(b)
        assertTrue(ready is SettleWaiter.Feed.Ready && ready.count == 31)
    }

    @Test
    fun `将帅未同时存在不计稳定永不返回`() {
        // 2026-09-06 真机日志 bug：大厅/过渡帧识别出 2 个棋子曾进入稳定计数（稳定 1/3）。
        // 门控要求：将/帥缺任一格一律视为过渡帧，无论多少帧相同都不返回 Ready。
        val b = TB.fullBoard(Side.RED)
        b[0][4] = null // 黑將
        b[9][4] = null // 红帥
        repeat(5) {
            assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting)
        }
    }

    @Test
    fun `非31非32子数连续三帧稳定后返回`() {
        val b = boardMissing(3) // 29 子
        // 第 1 帧记录 prevBoard，第 2/3 帧累计稳定计数 1/2，第 4 帧稳定计数 3 触发
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting)
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting)
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting)
        val ready = waiter.feed(b)
        assertTrue(ready is SettleWaiter.Feed.Ready && ready.count == 29)
    }

    @Test
    fun `棋盘变化会重置稳定计数`() {
        val a = boardMissing(3)
        val b = boardMissing(4)
        assertTrue(waiter.feed(a) is SettleWaiter.Feed.Waiting) // 记录 prev=a
        assertTrue(waiter.feed(a) is SettleWaiter.Feed.Waiting) // 稳定 1
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting) // 变化，重置计数
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting) // 稳定 1
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting) // 稳定 2
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Ready) // 稳定 3 达阈值
    }

    @Test
    fun `内容不同但引用相同需逐值比较`() {
        // Kotlin 数组 == 是引用比较：两次构造的相同布局也必须判等（M6 补丁① 回归）
        val b1 = boardMissing(3)
        val b2 = boardMissing(3)
        assertTrue(waiter.feed(b1) is SettleWaiter.Feed.Waiting)
        assertTrue(waiter.feed(b2) is SettleWaiter.Feed.Waiting) // 稳定 1（逐值相等）
        assertTrue(waiter.feed(b1) is SettleWaiter.Feed.Waiting) // 稳定 2
        assertTrue(waiter.feed(TB.copy(b1)) is SettleWaiter.Feed.Ready) // 稳定 3
    }

    // ---------- 2026-09-08 lift 感知 + 32 子布局校验（game_start_lift_recovery_plan） ----------

    /** 32 子双方各偏 1 子（如重摆动画瞬态/上局残留）→ 非开局形态。 */
    private fun board32Deviated(): com.chess.bot.game.Board {
        val b = TB.fullBoard(Side.RED)
        TB.movePiece(b, 0, 0, 4, 0) // 黑車离位
        TB.movePiece(b, 9, 0, 5, 0) // 红車离位
        return b
    }

    @Test
    fun `32子非开局形态需3帧稳定_Q1修复`() {
        val b = board32Deviated()
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting)
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting)
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting)
        val ready = waiter.feed(b)
        assertTrue(ready is SettleWaiter.Feed.Ready && ready.count == 32)
    }

    @Test
    fun `32子仅红方走一子立即就绪_D2严格版`() {
        val b = TB.fullBoard(Side.BLACK) // 我方黑，红方在上
        TB.movePiece(b, 2, 7, 2, 4) // 红炮走一步
        val feed = waiter.feed(b)
        assertTrue(feed is SettleWaiter.Feed.Ready && feed.count == 32)
    }

    @Test
    fun `32子仅黑方走一子需3帧稳定_D2严格版`() {
        val b = TB.fullBoard(Side.RED) // 我方红，黑方在上
        TB.movePiece(b, 0, 1, 3, 1) // 黑馬走一步
        repeat(3) { assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting) }
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Ready)
    }

    @Test
    fun `我方提子确认后返回OwnLift`() {
        val b = TB.fullBoard(Side.RED)
        b[6][4] = Const.LIFT // 红兵提起（我方半区 r>=5）
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting) // 确认 1/2
        val f = waiter.feed(b)
        assertTrue(f is SettleWaiter.Feed.OwnLift && f.liftPos == (6 to 4))
    }

    @Test
    fun `我方提子位置变化重置确认计数`() {
        val a = TB.fullBoard(Side.RED).also { it[6][4] = Const.LIFT }
        val b = TB.fullBoard(Side.RED).also { it[6][3] = Const.LIFT }
        assertTrue(waiter.feed(a) is SettleWaiter.Feed.Waiting) // 确认 1
        assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting) // 位置变，重置为 1
        val f = waiter.feed(b)
        assertTrue(f is SettleWaiter.Feed.OwnLift && f.liftPos == (6 to 3))
    }

    @Test
    fun `敌方提子等待落子永不就绪_D3无超时`() {
        val b = TB.fullBoard(Side.RED)
        b[3][4] = Const.LIFT // 黑卒提起（敌方半区）
        repeat(5) { assertTrue(waiter.feed(b) is SettleWaiter.Feed.Waiting) }
    }

    @Test
    fun `我方提子被排除后恢复普通稳定计数`() {
        val lifted = TB.fullBoard(Side.RED).also { it[6][4] = Const.LIFT }
        assertTrue(waiter.feed(lifted) is SettleWaiter.Feed.Waiting) // 确认 1/2
        assertTrue(waiter.feed(lifted) is SettleWaiter.Feed.OwnLift) // 确认 2/2 → 交恢复流程
        // 提子落定（棋子归位）→ 32 子开局形态立即就绪
        val ready = waiter.feed(TB.fullBoard(Side.RED))
        assertTrue(ready is SettleWaiter.Feed.Ready && ready.count == 32)
    }
}
