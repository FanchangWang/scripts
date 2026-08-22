"""走法推断、应用与格式化（纯函数）。

输入棋盘/变动数据，输出走法或新状态；不做 IO、不访问 self。
"""

from __future__ import annotations

from xiangqi_bot.board import PIECE_CN, Board, grid_to_square, piece_color, piece_label
from xiangqi_bot.game.state import Change, Move, Side


def infer(changes: list[Change]) -> Move | None:
    """从 2 格变动推断一步棋：一起一落、棋子相同。"""
    if len(changes) != 2:
        return None
    left: tuple[int, int, str] | None = None
    arrived: tuple[int, int, str | None, str] | None = None
    for r, c, old, new in changes:
        if old is not None and new is None:
            left = (r, c, old)
        elif new is not None:
            arrived = (r, c, old, new)
    if left is not None and arrived is not None and arrived[3] == left[2]:
        return Move((left[0], left[1]), (arrived[0], arrived[1]), left[2], arrived[2])
    return None


def apply(board: Board, move: Move, clock: int) -> int:
    """把走法写入 board（起子格清空、落子格写棋子），返回新的 halfmove_clock。

    吃子归零，非吃 +1。我方/敌方走棋共用同一个函数。
    """
    (r1, c1), (r2, c2) = move.src, move.dst
    board[r1][c1] = None
    board[r2][c2] = move.piece
    return 0 if move.captured is not None else clock + 1


def matches(move: Move, expected: Move) -> bool:
    """走法是否与引擎着法完全吻合（起点/终点/棋子）。"""
    return move.src == expected.src and move.dst == expected.dst and move.piece == expected.piece


def format_move(move: Move, my_side: Side) -> str:
    """格式化走棋日志（红/黑方 + 棋子 + 记谱 + 吃子）。"""
    piece, captured = move.piece, move.captured
    color_cn = "红" if piece_color(piece) == Side.RED else "黑"
    from_sq = grid_to_square(*move.src, my_side)
    to_sq = grid_to_square(*move.dst, my_side)
    capture_note = f"（吃{piece_label(captured)}）" if captured else ""
    return f"{color_cn}方走{PIECE_CN[piece]}：{from_sq} -> {to_sq}{capture_note}"


def format_changes(changes: list[Change], my_side: Side) -> list[str]:
    """格式化逐格变动日志（含标题行）。"""
    lines = [f"棋子变动（{len(changes)} 格）"]
    for r, c, old, new in changes:
        old_name = piece_label(old) if old else "空"
        new_name = piece_label(new) if new else "空"
        lines.append(f"变化：{grid_to_square(r, c, my_side)} {old_name} -> {new_name}")
    return lines
