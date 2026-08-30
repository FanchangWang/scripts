package com.chess.bot

import com.chess.bot.book.TChessZobrist
import com.chess.bot.game.Side
import com.chess.bot.game.rotateBoard180
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import com.chess.bot.TestBoards as TB

/**
 * OBK 开局库 vkey 算法基准对拍（R5）。
 *
 * 基准值来自 scripts/obk_check.py 对 start.obk 的实证校验：
 * 起始局面（红先）vkey = 7101337512282506414，红先命中 9 着，Top1 h2e2（vmove=43687）。
 */
class TChessZobristTest {

    /** 棋盘左右镜像（列翻转，行不变）。 */
    private fun mirrorBoard(b: com.chess.bot.game.Board): com.chess.bot.game.Board =
        Array(10) { r -> Array(9) { c -> b[r][8 - c] } }

    @Test
    fun `起始局面红先 vkey 与 obk_check 基准一致`() {
        val start = TB.fullBoard(Side.RED)
        assertEquals(7101337512282506414L, TChessZobrist.zobrist(start, redGo = true))
    }

    @Test
    fun `起始局面黑先 vkey 与红先差行棋方键`() {
        val start = TB.fullBoard(Side.RED)
        val redGo = TChessZobrist.zobrist(start, redGo = true)
        val blackGo = TChessZobrist.zobrist(start, redGo = false)
        assertNotEquals(redGo, blackGo)
        assertEquals(TChessZobrist.ZOBRIST_PLAYER, redGo xor blackGo)
    }

    @Test
    fun `vmove 43687 对应 h2e2`() {
        assertEquals("h2e2", TChessZobrist.vmoveToIccs(43687, viaMirror = false))
    }

    @Test
    fun `iccs 与 vmove 往返转换`() {
        val vmove = TChessZobrist.iccsToVmove("h2e2", mirror = false)
        assertEquals(43687, vmove)
        assertEquals("h2e2", TChessZobrist.vmoveToIccs(vmove!!, viaMirror = false))
    }

    @Test
    fun `镜像通道 iccs 转换左右列翻转`() {
        // h2e2 镜像后应为 b2e2（h 列=7 -> 1，e 列=4 -> 4 居中不变）
        assertEquals(
            TChessZobrist.iccsToVmove("b2e2", mirror = false),
            TChessZobrist.iccsToVmove("h2e2", mirror = true),
        )
        // 镜像 vmove 再镜像回来应还原
        val mirrored = TChessZobrist.iccsToVmove("h2e2", mirror = true)!!
        assertEquals("h2e2", TChessZobrist.vmoveToIccs(mirrored, viaMirror = true))
    }

    @Test
    fun `镜像标志与镜像棋盘自洽`() {
        val start = TB.fullBoard(Side.RED)
        assertEquals(
            TChessZobrist.zobrist(start, redGo = true, mirror = true),
            TChessZobrist.zobrist(mirrorBoard(start), redGo = true, mirror = false),
        )
    }

    @Test
    fun `不对称局面镜像 vkey 与原局面不同`() {
        // 起始局面左右对称，镜像键与正常键相同（审计 §二.C：起始局面双键相同）；
        // 炮二平五（h2e2）后局面不对称，两键必须不同——否则镜像双通道查询失去意义
        val start = TB.fullBoard(Side.RED)
        assertEquals(
            TChessZobrist.zobrist(start, redGo = true),
            TChessZobrist.zobrist(start, redGo = true, mirror = true),
        )
        val moved = TB.fullBoard(Side.RED)
        TB.movePiece(moved, 7, 7, 7, 4) // 炮二平五
        assertNotEquals(
            TChessZobrist.zobrist(moved, redGo = false),
            TChessZobrist.zobrist(moved, redGo = false, mirror = true),
        )
    }

    @Test
    fun `执黑视角棋盘旋转180度后 vkey 与标准起始局面一致`() {
        // 执黑时屏幕棋盘相对 ICCS 标准方向为 180° 旋转；开局库查询前须归一化（BotSession 已接 rotateBoard180）
        val blackView = TB.fullBoard(Side.BLACK)
        assertEquals(
            TChessZobrist.zobrist(TB.fullBoard(Side.RED), redGo = true),
            TChessZobrist.zobrist(rotateBoard180(blackView), redGo = true),
        )
    }
}
