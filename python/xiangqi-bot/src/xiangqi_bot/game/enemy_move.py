"""敌方走棋检测与处理（mixin）。"""

import time

from xiangqi_bot.board import grid_to_square, piece_color, piece_label
from xiangqi_bot.config import ENEMY_NOISY_MAX, ENEMY_RECHECK_WAIT_MS, RESIGN_SUSPECT_WAIT_MS
from xiangqi_bot.game._base import MoveResult, _SessionAttrs


class EnemyMoveMixin(_SessionAttrs):
    """敌方走棋检测：截图对比、噪声过滤、提子识别、走棋提交。"""

    def _apply_enemy_move(self, moved: MoveResult) -> None:
        """合并敌方走棋：
        1. 把变动 updates 更新到内存 board（精确按 diff 写，避免整表覆盖污染）
        2. 切换轮次 → 高亮 → 走棋日志 → 推送状态
        （基准快照 prev_board 由 _flow 循环开头统一维护；corrected 参数保留与基类签名一致，
        当前实现未使用但后续如需基于截图做自愈/校验可直接扩展。）
        """
        (r1, c1), (r2, c2), piece, _cap = moved
        self.board[r1][c1] = None
        self.board[r2][c2] = piece
        self._turn = self.my_side
        self._highlight = [(r1, c1), (r2, c2)]
        self._log_move(moved)
        self._emit()

    def _wait_for_enemy_move(self) -> None:
        """检测敌方走棋：持续截图，直到敌方走棋完毕、对局结束或用户中断。

        使用 if/elif 链（而非 match-case）是因为 Python 不支持 case fall through：
        case 2 推断失败 / case 1 非提子 需进入同一噪声处理分支。
        """
        if self.prev_board is None:
            return
        self._resign_streak = 0  # 每个检测会话重新计数，避免残留帧导致误判
        self._noisy_count = 0
        self._lift_logged = False
        self._log("info", "检测敌方走棋")
        while self._running and not self._interrupt.is_set() and not self.game_over:
            corrected = self._capture()
            if corrected is None:
                continue
            new_board, updates = self._analyze_board_with_prev_board(corrected)
            n = len(updates)
            fallthrough_noisy = False
            if n == 2:
                moved: MoveResult | None = self._infer_move(updates)
                if moved is not None:
                    self._apply_enemy_move(moved)
                    # 敌方走棋后不再做绝杀探测（用户方案：只在我方 n=2+_infer_move 时探测，
                    # 其余场景交给结算画面认输检测兜底，减少引擎 is_mate 触发的假异常）
                    return
                # 2 格变动但不能推断走法 → 归入噪声处理（不重置计数器，继续下面的流程）
                fallthrough_noisy = True
            elif n == 1:
                if (
                    updates[0][2] is not None
                    and updates[0][3] is None
                    and piece_color(updates[0][2]) != self.my_side
                ):
                    if not self._lift_logged:
                        self._lift_logged = True
                        self._log("info", "检测到敌方提起棋子")
                    self._noisy_count = 0
                    continue
                # 1 格变动但不符合提子条件 → 归入噪声处理
                fallthrough_noisy = True
            elif n == 0:
                self._lift_logged = False
                self._noisy_count = 0
                continue
            else:
                fallthrough_noisy = True

            if fallthrough_noisy:
                resign = self._detect_resignation_board(new_board)
                if resign == "confirmed":
                    self._finish_game("检测到对局结束画面")
                    return
                if resign == "suspect":
                    time.sleep(RESIGN_SUSPECT_WAIT_MS / 1000)
                    continue
                self._lift_logged = False
                self._noisy_count += 1
                if self._noisy_count >= ENEMY_NOISY_MAX:
                    if resign == "none":
                        self._log(
                            "warn",
                            f"连续 {ENEMY_NOISY_MAX} 帧无法推断敌方完整走法，"
                            "暂停自动对弈，请点击「开始棋局」确认",
                        )
                        if updates:
                            for r, c, old, new in updates:
                                old_name = piece_label(old) if old else "空"
                                new_name = piece_label(new) if new else "空"
                                side = self.my_side or "red"
                                self._log(
                                    "warn",
                                    f"变化：{grid_to_square(r, c, side)} {old_name} -> {new_name}",
                                )
                        self._running = False
                        self._emit()
                        return
                else:
                    time.sleep(ENEMY_RECHECK_WAIT_MS / 1000)
        self._log("info", "已中断检测敌方走棋")
