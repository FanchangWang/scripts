"""棋盘识别：矫正图 -> (布局, 变动列表)。薄封装 vision，纯函数。"""

from __future__ import annotations

from numpy import ndarray

from xiangqi_bot import vision
from xiangqi_bot.board import COLS, ROWS, Board
from xiangqi_bot.game.state import Change


def analyze(
    corrected: ndarray,
    templates: dict[str, ndarray],
    prev_board: Board | None,
) -> tuple[Board, list[Change]]:
    """逐格识别当前帧棋盘（优先匹配 prev_board），返回 (新布局, 与 prev_board 的变动列表)。

    一次遍历同时完成识别+对比。prev_board 为 None 时 changes 为空，返回完整新布局。
    """
    board: Board = [[None] * COLS for _ in range(ROWS)]
    changes: list[Change] = []
    for r in range(ROWS):
        for c in range(COLS):
            old = prev_board[r][c] if prev_board is not None else None
            new = vision.analyze_cell_with_priority(corrected, r, c, templates, old)
            board[r][c] = new
            if old != new:
                changes.append(Change(r, c, old, new))
    return board, changes
