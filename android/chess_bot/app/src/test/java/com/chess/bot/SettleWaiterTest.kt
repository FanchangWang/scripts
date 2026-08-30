package com.chess.bot

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
}
