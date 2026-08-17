# ty: ignore[unresolved-attribute]  # mixin：属性在 GameSession 中定义
"""敌方走棋检测与处理（mixin）。"""

import time

from numpy import ndarray

from xiangqi_bot import vision
from xiangqi_bot.board import piece_color
from xiangqi_bot.config import (
    AUTO_DETECT_INTERVAL_MS,
    ENEMY_NOISY_MAX,
    ENEMY_RECHECK_WAIT_MS,
    RESIGN_SUSPECT_WAIT_MS,
)


class EnemyMoveMixin:
    """敌方走棋检测：截图对比、噪声过滤、提子识别、走棋提交。"""

    _noisy_count: int
    _lift_logged: bool
    prev: ndarray | None
    board: list[list[str | None]]
    my_side: str | None
    _running: bool
    _turn: str | None
    game_over: bool
    templates: dict[str, ndarray]
    device: object

    def _enemy_changes(self, corrected: ndarray) -> list[tuple[int, int, str | None, str | None]]:
        """对比前帧与当前帧，返回有实际变化的格子列表（行, 列, 旧棋子, 新棋子）"""
        changes: list[tuple[int, int, str | None, str | None]] = []
        if self.prev is None:
            return changes
        for r, c in sorted(vision.diff_cells(self.prev, corrected)):
            old = self.board[r][c]
            new = vision.analyze_cell(corrected, r, c, self.templates)
            if old != new:
                changes.append((r, c, old, new))
        return changes

    def _apply_self_heal(
        self, changes: list[tuple[int, int, str | None, str | None]], my_color: str
    ) -> list[tuple[int, int, str | None, str | None]]:
        """把"我方棋子重新出现"的变化视为自愈（修正布局，不算敌方走棋）"""
        rest: list[tuple[int, int, str | None, str | None]] = []
        for r, c, old, new in changes:
            if new is not None and piece_color(new) == my_color:
                self.board[r][c] = new
            else:
                rest.append((r, c, old, new))
        return rest

    def _analyze_enemy(self, corrected: ndarray) -> list[tuple[int, int, str | None, str | None]]:
        """分析敌方变动（相对内存布局，已过滤/自愈我方棋子重识别），返回变动列表"""
        if self.prev is None:
            return []
        updates = self._enemy_changes(corrected)
        if not updates:
            return []
        my_color = "red" if self.my_side == "red" else "black"
        return self._apply_self_heal(updates, my_color)

    def _detect_enemy(self, corrected: ndarray) -> str:
        """检测敌方是否走棋。

        返回值：
        - "moved"：可推断完整一步，已更新布局
        - "lifted"：恰一枚敌方棋子被提起，未落子
        - "noisy"：多格变动或无法构成完整一步，疑似瞬态噪声，不提交
        - "none"：无变动
        """
        updates = self._analyze_enemy(corrected)
        if not updates:
            return "none"
        if self._infer_move(updates) is not None:
            self._commit_enemy(updates, corrected)
            return "moved"
        my_color = "red" if self.my_side == "red" else "black"
        if (
            len(updates) == 1
            and updates[0][2] is not None
            and updates[0][3] is None
            and piece_color(updates[0][2]) != my_color
        ):
            return "lifted"
        return "noisy"

    def _commit_enemy(
        self, updates: list[tuple[int, int, str | None, str | None]], corrected: ndarray
    ) -> None:
        """提交敌方变动到内存布局并保存截图、记录日志"""
        for r, c, _old, new in updates:
            self.board[r][c] = new
        self.prev = corrected
        self._on_enemy_move(updates)

    def _on_enemy_move(self, changes: list[tuple[int, int, str | None, str | None]]) -> None:
        """敌方走棋后处理：切换轮次、设置高亮、记录日志、预计算我方着法"""
        self._turn = self.my_side
        moved = self._infer_move(changes)
        if moved is not None:
            (r1, c1), (r2, c2), _piece, _captured = moved
            self._highlight = [(r1, c1), (r2, c2)]
        else:
            self._highlight = [(r, c) for r, c, _old, _new in changes]
        self._last_move = None
        self._emit()
        self._log_changes(changes)
        self._compute_move()

    def _infer_move(
        self, changes: list[tuple[int, int, str | None, str | None]]
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

    def _wait_for_enemy_move(self) -> None:
        """检测敌方走棋：持续截图，直到敌方走棋完毕、对局结束或用户中断"""
        if self.prev is None:
            return
        self._resign_streak = 0  # 每个检测会话重新计数，避免残留帧导致误判
        self._noisy_count = 0
        self._lift_logged = False
        self._log("info", "检测敌方走棋")
        while self._running and not self._interrupt.is_set() and not self.game_over:
            if AUTO_DETECT_INTERVAL_MS > 0:
                time.sleep(AUTO_DETECT_INTERVAL_MS / 1000)
            corrected = self._capture()
            if corrected is None:
                continue
            result = self._detect_enemy(corrected)
            if result == "moved":
                return
            resign = self._detect_resignation(corrected)
            if resign == "confirmed":
                self._finish_game("检测到对局结束画面，敌方可能已认输")
                return
            if resign == "suspect":
                time.sleep(RESIGN_SUSPECT_WAIT_MS / 1000)
            if result == "lifted":
                if not self._lift_logged:
                    self._lift_logged = True
                    self._log("info", "检测到敌方提起棋子")
                self._noisy_count = 0
            elif result == "none":
                self._lift_logged = False
                self._noisy_count = 0
            elif result == "noisy":
                self._lift_logged = False
                self._noisy_count += 1
                if self._noisy_count >= ENEMY_NOISY_MAX:
                    if resign in ("suspect", "confirmed"):
                        self._noisy_count = 0
                    else:
                        updates = self._analyze_enemy(corrected)
                        if updates:
                            self._log(
                                "warn",
                                f"连续 {ENEMY_NOISY_MAX} 帧无法推断敌方完整走法，按实际变动提交",
                            )
                            self._commit_enemy(updates, corrected)
                            return
                        self._noisy_count = 0
                else:
                    time.sleep(ENEMY_RECHECK_WAIT_MS / 1000)
        self._log("info", "已中断检测敌方走棋")
