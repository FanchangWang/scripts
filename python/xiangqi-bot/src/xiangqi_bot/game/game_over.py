"""对局结束检测与处理（mixin）。"""

from xiangqi_bot.board import Board, fen_of_board
from xiangqi_bot.config import ENGINE_MATE_PROBE_MS, RESIGN_CONFIRM_COUNT
from xiangqi_bot.game._base import _SessionAttrs

RED_CN = "红"
BLACK_CN = "黑"


class GameOverMixin(_SessionAttrs):
    """认输/对局结束检测（棋子计数连续帧 + 双方将帥缺失快判）。"""

    def _detect_resignation_board(self, new_board: Board) -> str:
        """检测对局结束/敌方认输（基于已识别的 new_board，避免重复识别）。

        判据：双方将/帥同时缺失 → 单帧疑似。连续 RESIGN_CONFIRM_COUNT 帧疑似 → confirmed；
        否则 streak 清零。每次 streak 增长打印进度日志（疑似结束画面 x/3）。
        """
        my_general = "r_K" if self.my_side == "red" else "b_k"
        has_my = any(my_general in row for row in new_board)
        enemy_general = "b_k" if self.my_side == "red" else "r_K"
        has_enemy = any(enemy_general in row for row in new_board)
        suspect = not has_my and not has_enemy
        prev_streak = self._resign_streak
        if suspect:
            self._resign_streak += 1
        else:
            self._resign_streak = 0
        if self._resign_streak != prev_streak and self._resign_streak > 0:
            self._log(
                "info",
                f"疑似对局结束画面（{self._resign_streak}/{RESIGN_CONFIRM_COUNT}）",
            )
        if self._resign_streak >= RESIGN_CONFIRM_COUNT:
            return "confirmed"
        return "suspect" if self._resign_streak > 0 else "none"

    def _checkmate_probe(self) -> bool:
        """我方走棋成功后（仅限 n==2 + _infer_move 场景调用）探测对方是否被绝杀。

        调用时机限定：只在我方走完一步棋（self._apply_self_move 之后）立即调用，
        此时 self._turn 已切换为对方行棋方，因此 FEN to_move 显式设为 opp（对方），
        不再泛化支持「敌方走完一步后探测我方被绝杀」的场景（用户新方案：敌方走后
        不再调此方法，交给结算画面认输检测兜底，减少引擎调用避免假异常）。
        """
        if self.my_side is None:
            return False
        opp = "black" if self.my_side == "red" else "red"
        opp_cn = BLACK_CN if opp == "black" else RED_CN
        fen = fen_of_board(self.board, self.my_side, to_move=opp)
        self._log("info", f"绝杀探测 FEN（{opp_cn}方行棋）：{fen}")
        try:
            mated = self.engine.is_mate(fen, ENGINE_MATE_PROBE_MS)
        except Exception as exc:  # noqa: BLE001 — 引擎假异常降级当未绝杀，不影响主流程
            self._log(
                "warn",
                f"引擎绝杀探测失败，当作未绝杀继续：{exc!r}",
            )
            return False
        if not mated:
            self._log("info", "未绝杀，继续对局")
            return False
        self._finish_game(f"我方绝杀，{opp_cn}方无路可走")
        return True
