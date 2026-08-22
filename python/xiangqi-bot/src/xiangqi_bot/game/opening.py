"""开局局面分析：判阵营、判阶段、推断轮次（纯函数）。

输入棋盘布局，输出阵营/阶段/轮次；不做 IO、不访问 self。
"""

from __future__ import annotations

from xiangqi_bot.board import COLS, ROWS, START_SQUARES, Board, piece_color
from xiangqi_bot.config import ENDGAME_PIECE_COUNT
from xiangqi_bot.game.state import Phase, Side


def detect_side(board: Board) -> Side | None:
    """判断我方红黑方：将/帥在屏幕下方（行 6..9）则该方为我方。"""
    red_bottom = any(board[r][c] == "r_K" for r in range(6, ROWS) for c in range(COLS))
    black_bottom = any(board[r][c] == "b_k" for r in range(6, ROWS) for c in range(COLS))
    if red_bottom:
        return Side.RED
    if black_bottom:
        return Side.BLACK
    return None


def detect_phase(board: Board, my_side: Side) -> Phase:
    """判断对局阶段：残局 / 开局（32 子未走或恰一方走一步）/ 中局。"""
    count = sum(cell is not None for row in board for cell in row)
    if count < ENDGAME_PIECE_COUNT:
        return Phase.ENDGAME

    if count == 32:
        red_dev = _color_deviates(board, my_side, Side.RED)
        black_dev = _color_deviates(board, my_side, Side.BLACK)
        if not red_dev and not black_dev:
            return Phase.OPENING  # 双方均未走
        if red_dev != black_dev:  # 恰一方偏离
            moved = Side.RED if red_dev else Side.BLACK
            if _single_piece_moved(board, my_side, moved):
                return Phase.OPENING

    return Phase.MIDDLE


def infer_turn(board: Board, my_side: Side, phase: Phase) -> Side | None:
    """推断轮次：仅开局可判（全默认位红先；对方刚走一步则轮到我方），其余返回 None。"""
    if phase is not Phase.OPENING:
        return None
    red_dev = _color_deviates(board, my_side, Side.RED)
    black_dev = _color_deviates(board, my_side, Side.BLACK)
    if not red_dev and not black_dev:
        return Side.RED
    if red_dev != black_dev:
        moved = Side.RED if red_dev else Side.BLACK
        if _single_piece_moved(board, my_side, moved):
            return moved.opponent
    return None


def _color_deviates(board: Board, my_side: Side, color: Side) -> bool:
    """判断某颜色棋子是否偏离开局默认位置。"""
    expected = _expected_start_squares(my_side, color)
    for r in range(ROWS):
        for c in range(COLS):
            p = board[r][c]
            if p is not None and piece_color(p) == color and (r, c) not in expected:
                return True
    for r, c in expected:
        p = board[r][c]
        if p is None or piece_color(p) != color:
            return True
    return False


def _expected_start_squares(my_side: Side, color: Side) -> set[tuple[int, int]]:
    """该颜色在当前屏幕方向（我方红黑）下的开局默认格集合。"""
    red_sq: set[tuple[int, int]] = set()
    black_sq: set[tuple[int, int]] = set()
    for piece_id, squares in START_SQUARES.items():
        (red_sq if piece_color(piece_id) == Side.RED else black_sq).update(squares)
    if my_side == Side.BLACK:
        red_sq, black_sq = black_sq, red_sq
    return red_sq if color == Side.RED else black_sq


def _single_piece_moved(board: Board, my_side: Side, color: Side) -> bool:
    """该颜色相对开局默认格恰有一枚棋子移动：1 个默认格空出 + 1 个非默认格落子。"""
    expected = _expected_start_squares(my_side, color)
    missing = 0
    extra = 0
    for r in range(ROWS):
        for c in range(COLS):
            p = board[r][c]
            if p is not None and piece_color(p) == color:
                if (r, c) not in expected:
                    extra += 1
            elif (r, c) in expected:
                missing += 1
    return missing == 1 and extra == 1
