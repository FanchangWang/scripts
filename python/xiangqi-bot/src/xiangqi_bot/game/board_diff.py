"""棋盘对比与变动校验（mixin）。

从 enemy_move 与 move/recovery 抽取：前/当前帧对比、自愈、变动推断、走棋校验，
使各 mixin 职责更单一。
"""

from numpy import ndarray

from xiangqi_bot import vision
from xiangqi_bot.board import COLS, ROWS, Board
from xiangqi_bot.game._base import Change, _SessionAttrs


class BoardDiffMixin(_SessionAttrs):
    """棋盘对比/变动校验：前帧对比、变动分析、走棋成功校验。"""

    def _analyze_board_with_prev_board(self, corrected: ndarray) -> tuple[Board, list[Change]]:
        """逐格识别当前帧棋盘（优先匹配 prev_board），返回 (新布局, 与 prev_board 的变动列表)。

        一次遍历同时完成识别+对比，比分开调用 analyze_board + _board_changes_v2 更高效。
        当 prev_board 为 None 时，updates 为空，返回完整的新布局。
        """
        board: Board = [[None] * COLS for _ in range(ROWS)]
        changes: list[Change] = []
        for r in range(ROWS):
            for c in range(COLS):
                old = self.prev_board[r][c] if self.prev_board is not None else None
                new = vision.analyze_cell_with_priority(corrected, r, c, self.templates, old)
                board[r][c] = new
                if old != new:
                    changes.append((r, c, old, new))
        return board, changes

    def _infer_move(
        self, changes: list[Change]
    ) -> tuple[tuple[int, int], tuple[int, int], str, str | None] | None:
        """从变动列表推断一步棋：恰好2格变动，一格起子（旧值非空新值为空），一格落子（新值非空），棋子相同"""
        if len(changes) != 2:
            return None
        (r1, c1, old1, new1), (r2, c2, old2, new2) = changes
        left: tuple[int, int, str] | None = None
        arrived: tuple[int, int, str | None, str] | None = None
        for r, c, old, new in ((r1, c1, old1, new1), (r2, c2, old2, new2)):
            if old is not None and new is None:
                left = (r, c, old)
            elif new is not None:
                arrived = (r, c, old, new)
        if left is not None and arrived is not None and arrived[3] == left[2]:
            return (left[0], left[1]), (arrived[0], arrived[1]), left[2], arrived[2]
        return None
