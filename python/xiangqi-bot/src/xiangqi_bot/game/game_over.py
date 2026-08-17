# ty: ignore[unresolved-attribute]  # mixin：属性在 GameSession 中定义
"""对局结束检测与处理（mixin）。"""

from numpy import ndarray

from xiangqi_bot import vision
from xiangqi_bot.config import RESIGN_CONFIRM_COUNT, RESIGN_PIECE_DROP_THRESHOLD


class GameOverMixin:
    """认输/对局结束检测（棋子计数连续帧 + 双方将帥缺失快判）。"""

    _resign_streak: int
    my_side: str | None
    board: list[list[str | None]]

    def _detect_resignation(self, corrected: ndarray) -> str:
        """检测对局结束/敌方认输画面。

        返回值：
        - "confirmed"：连续 RESIGN_CONFIRM_COUNT 帧稳定出现
        - "suspect"：本帧疑似（调用方应延时 RESIGN_SUSPECT_WAIT_MS 再采下一帧）
        - "none"：无嫌疑

        将/帥永远不会被吃掉：双方同时缺失大概率是结束画面（需多帧确认），
        只有一方缺失是走棋动画遮挡（不计入 streak，直接重置）。
        """
        if self.my_side is None:
            self._resign_streak = 0
            return "none"
        board_now = vision.analyze_board(corrected, self.templates)
        my_general = "r_K" if self.my_side == "red" else "b_k"
        has_my = any(my_general in row for row in board_now)
        enemy_general = "b_k" if self.my_side == "red" else "r_K"
        has_enemy = any(enemy_general in row for row in board_now)
        expected = sum(cell is not None for row in self.board for cell in row)
        actual = sum(cell is not None for row in board_now for cell in row)
        dropped = expected - actual
        # 一方将/帥缺失 → 走棋动画遮挡，不计入 streak
        if not has_my or not has_enemy:
            if not has_my and not has_enemy:
                # 双方将/帥同时缺失 → 疑似结束画面，需连续帧确认
                self._resign_streak += 1
            else:
                self._resign_streak = 0
        elif dropped >= RESIGN_PIECE_DROP_THRESHOLD:
            # 棋子大幅减少（≥3枚）→ 疑似结束画面，需连续帧确认
            self._resign_streak += 1
        else:
            self._resign_streak = 0
        if self._resign_streak >= RESIGN_CONFIRM_COUNT:
            return "confirmed"
        return "suspect" if self._resign_streak > 0 else "none"

    def _check_gameover_text(self, corrected: ndarray) -> bool:
        """弹窗/结算文字一帧检测（一帧即确认对局结束）"""
        return bool(vision.find_gameover_text(corrected))
