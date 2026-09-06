package com.chess.bot.vision

import com.chess.bot.game.Const
import com.chess.bot.game.GameState
import com.chess.bot.game.PIECE_CN
import com.chess.bot.game.makeEmptyBoard
import com.chess.bot.game.pieceCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * cls 16 类映射与 lift 语义测试：
 * - CLASS_KEYS 必须与 cls/export/model_info.json 的 class_keys 顺序严格一致（索引即模型输出通道）；
 * - empty/lift 位置固定（7/8）；14 棋子 key 与 PIECE_CN 完全一致；
 * - lift 为帧分类瞬时态：pieceCount 不计入、GameState.replaceBoard 归一化为 null。
 */
class ClsClassMapTest {

    @Test
    fun classKeys_16类与模型顺序一致() {
        assertEquals(
            listOf(
                "b_a", "b_b", "b_c", "b_k", "b_n", "b_p", "b_r",
                "empty", "lift",
                "r_A", "r_B", "r_C", "r_K", "r_N", "r_P", "r_R",
            ),
            PieceClsModel.CLASS_KEYS,
        )
    }

    @Test
    fun empty与lift索引固定() {
        assertEquals(7, PieceClsModel.CLASS_KEYS.indexOf("empty"))
        assertEquals(8, PieceClsModel.CLASS_KEYS.indexOf("lift"))
    }

    @Test
    fun 十四种棋子key与PIECE_CN一致() {
        val pieces = PieceClsModel.CLASS_KEYS.filter { it != "empty" && it != Const.LIFT }
        assertEquals(14, pieces.size)
        assertEquals(PIECE_CN.keys, pieces.toSet())
    }

    @Test
    fun pieceCount_lift不计入() {
        val b = makeEmptyBoard().also {
            it[0][0] = "r_R"
            it[0][1] = Const.LIFT
            it[9][8] = "b_p"
        }
        assertEquals(2, pieceCount(b))
        assertEquals(3, b.sumOf { row -> row.count { it != null } })
    }

    @Test
    fun replaceBoard_lift归一化为null() {
        val state = GameState()
        val b = makeEmptyBoard().also {
            it[0][0] = "r_R"
            it[0][1] = Const.LIFT
        }
        state.replaceBoard(b)
        assertEquals("r_R", state.board[0][0])
        assertNull(state.board[0][1])
    }

    @Test
    fun isLiftAmbiguous_仅棋子且lift概率达阈值时触发() {
        // 真实棋子 + lift 概率显著 → 动画帧
        assert(PieceClsModel.isLiftAmbiguous("b_c", 0.30f))
        assert(PieceClsModel.isLiftAmbiguous("b_c", 0.9f))
        // lift 概率低 → 正常返回棋子
        assert(!PieceClsModel.isLiftAmbiguous("b_c", 0.05f))
        // empty（null）与 lift 本身不门控
        assert(!PieceClsModel.isLiftAmbiguous(null, 0.9f))
        assert(!PieceClsModel.isLiftAmbiguous(Const.LIFT, 0.9f))
    }
}
