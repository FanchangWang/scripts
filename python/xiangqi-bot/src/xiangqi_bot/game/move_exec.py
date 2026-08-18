# ty: ignore[unresolved-attribute]  # mixin：属性在 GameSession 中定义
"""走棋执行与校验（mixin）。"""

import time

from numpy import ndarray

from xiangqi_bot import adb_client, engine, vision
from xiangqi_bot.adb_client import Device
from xiangqi_bot.board import (
    fen_of_board,
    grid_to_square,
    piece_color,
    piece_label,
)
from xiangqi_bot.config import (
    ENEMY_NOISY_MAX,
    ENGINE_MATE_PROBE_MS,
    MOVE_SETTLE_MS,
    MOVE_VERIFY_COUNT,
    RECOVERY_WAIT_MS,
    TAP_HOLD_INTERVAL_MS,
)


class MoveExecMixin:
    """走棋计算、点击执行、截图校验、失败恢复、绝杀探测。"""

    _H: ndarray | None
    board: list[list[str | None]]
    my_side: str | None
    _turn: str | None
    pending_move: tuple[str, str] | None
    game_over: bool
    templates: dict[str, ndarray]
    device: Device
    engine: engine.Engine

    def _compute_move(self) -> tuple[str, str] | None:
        """调用引擎计算最优着法，缓存到 pending_move 并返回"""
        if self.my_side is None:
            return None
        fen = fen_of_board(self.board, self.my_side)
        self._log("info", "预计算着法...")
        try:
            move = self.engine.best_move(fen)
        except engine.EngineError as exc:
            self._log("error", f"引擎错误：{exc}")
            return None
        if move is None:
            self._log("warn", "引擎无可用着法（对局可能已结束）")
            self._finish_game("引擎判定我方无路可走，对局结束")
            return None
        self.pending_move = (fen, move)
        self._log("info", f"已预计算着法：{move}")
        return self.pending_move

    def _attempt_move(self, r1: int, c1: int, r2: int, c2: int, piece: str) -> bool:
        """点击起子+落子 → 截图校验 → 失败时绝杀探测或恢复流程"""
        if self._H is None:
            self._log("error", "尚无棋盘坐标信息，请点击「开始棋局」")
            return False
        x1, y1 = vision.tap_xy(self._H, r1, c1)
        x2, y2 = vision.tap_xy(self._H, r2, c2)
        self._log("info", f"点击 ({x1},{y1}) -> ({x2},{y2})")
        try:
            adb_client.tap(self.device, x1, y1)
            time.sleep(TAP_HOLD_INTERVAL_MS / 1000)
            adb_client.tap(self.device, x2, y2)
        except adb_client.AdbError as exc:
            self._log("error", str(exc))
            return False
        fail_frames: list[ndarray] = []
        if self._verify_move_loop(r1, c1, r2, c2, piece, fail_frames):
            return True
        if self._is_mate_by_move(r1, c1, r2, c2, piece):
            return True
        self._log("warn", f"校验失败：{MOVE_VERIFY_COUNT} 次截图均未识别到走棋成功")
        return self._recover_move_failure(r1, c1, r2, c2, piece, fail_frames)

    def _verify_move_loop(
        self,
        r1: int,
        c1: int,
        r2: int,
        c2: int,
        piece: str,
        fail_frames: list[ndarray] | None = None,
    ) -> bool:
        """落子后截图校验：最多 MOVE_VERIFY_COUNT 次，任一帧识别成功即应用结果。

        失败的帧收集到 fail_frames（供走棋失败后的对局结束判定复用，避免重复截图）。
        """
        for _ in range(MOVE_VERIFY_COUNT):
            time.sleep(MOVE_SETTLE_MS / 1000)
            corrected = self._capture()
            if corrected is None:
                continue
            if self._verify_our_move(corrected, r1, c1, r2, c2, piece):
                self._apply_move_result(corrected, r1, c1, r2, c2, piece)
                return True
            if fail_frames is not None:
                fail_frames.append(corrected)
        return False

    def _verify_our_move(
        self, corrected: ndarray, r1: int, c1: int, r2: int, c2: int, piece: str
    ) -> bool:
        """校验我方走棋是否成功。

        以走棋前的 prev 帧为基准分析变动：
        - 变动可推断为恰为我方这一步（起点->终点，含吃子）-> 成功；
        - 起点不再是我方棋子，且终点已是我方棋子 -> 成功；
        - 起点已空且已有敌方棋子离开其源格（我方走棋已完成，对方正走棋
          或已反吃终点）-> 成功；
        - 仅我方棋子离开起点（途中/提起未落）-> 未成功，等待下一帧。
        """
        changes = self._enemy_changes(corrected)
        if len(changes) > 4:
            return False
        moved = self._infer_move(changes)
        if moved is not None:
            (mr1, mc1), (mr2, mc2), mp, _ = moved
            return (mr1, mc1) == (r1, c1) and (mr2, mc2) == (r2, c2) and mp == piece
        new_from = vision.analyze_cell(corrected, r1, c1, self.templates)
        new_to = vision.analyze_cell(corrected, r2, c2, self.templates)
        if new_from == piece:
            return False
        if new_to == piece:
            return True
        if not any(
            r == r1 and c == c1 and old == piece and new is None for r, c, old, new in changes
        ):
            return False
        return any(
            (r, c) != (r1, c1) and old is not None and new is None for r, c, old, new in changes
        )

    def _apply_move_result(
        self, corrected: ndarray, r1: int, c1: int, r2: int, c2: int, piece: str
    ) -> None:
        """走棋校验成功：根据截图内容分三类处理。

        情况一：终点是我方棋子 → 正常走棋/吃子，处理敌方走棋或保存截图。
        情况二：终点不是我方棋子且复检后恢复 → 瞬态误读，按成功处理。
        情况三：终点不是我方棋子且确认被吃 → 对方吃子，推断对方走棋或检测对局结束。
        """
        side = self.my_side or "red"
        self.board[r1][c1] = vision.analyze_cell(corrected, r1, c1, self.templates)
        self.board[r2][c2] = vision.analyze_cell(corrected, r2, c2, self.templates)
        self._turn = "black" if self.my_side == "red" else "red"
        self._log("ok", "走动成功")
        # 终点复检：延时后重新截图确认是否为瞬态误读
        if self.board[r2][c2] != piece:
            time.sleep(MOVE_SETTLE_MS / 1000)
            recheck = self._capture()
            if recheck is not None:
                self.board[r1][c1] = vision.analyze_cell(recheck, r1, c1, self.templates)
                self.board[r2][c2] = vision.analyze_cell(recheck, r2, c2, self.templates)
                corrected = recheck
                if self.board[r2][c2] == piece:
                    self._log("info", "终点复检为我方棋子（校验帧为瞬态误读），按走动成功处理")
        # 情况三：确认被吃
        if self.board[r2][c2] != piece:
            self._log(
                "enemy",
                f"对方吃掉了走到 {grid_to_square(r2, c2, side)} 的我方{piece_label(piece)}",
            )
            self._resign_streak = 0
            enemy = self._enemy_changes(corrected)
            enemy.append((r2, c2, piece, self.board[r2][c2]))
            if self._infer_move(enemy) is None:
                if self._check_gameover_text(corrected):
                    self._finish_game("检测到对局结束画面，敌方可能已认输")
                    return
                self._log("warn", "对方吃子变动无法构成完整一步，延时复检确认...")
                for _ in range(ENEMY_NOISY_MAX):
                    time.sleep(MOVE_SETTLE_MS / 1000)
                    recheck = self._capture()
                    if recheck is None:
                        continue
                    corrected = recheck
                    self.board[r1][c1] = vision.analyze_cell(corrected, r1, c1, self.templates)
                    self.board[r2][c2] = vision.analyze_cell(corrected, r2, c2, self.templates)
                    if self._detect_resignation(corrected) == "confirmed":
                        self._finish_game("检测到对局结束画面，敌方可能已认输")
                        return
                    enemy = self._enemy_changes(corrected)
                    enemy.append((r2, c2, piece, self.board[r2][c2]))
                    if self._infer_move(enemy) is not None:
                        break
                    if self._check_gameover_text(corrected):
                        self._finish_game("检测到对局结束画面，敌方可能已认输")
                        return
                else:
                    self._log(
                        "warn",
                        "对方吃子后的走棋变动始终无法构成完整一步，已暂停自动对弈，请点击「开始棋局」确认",
                    )
                    for r, c, old, new in enemy:
                        old_name = piece_label(old) if old else "空"
                        new_name = piece_label(new) if new else "空"
                        self._log(
                            "warn",
                            f"变化：{grid_to_square(r, c, side)} {old_name} -> {new_name}",
                        )
                    self._running = False
                    self._emit()
                    return
            for r, c, _old, new in enemy:
                self.board[r][c] = new
            self.prev = corrected
            self._on_enemy_move(enemy)
            return
        # 情况一：终点是我方棋子，正常走棋
        enemy = self._apply_self_heal(self._enemy_changes(corrected), piece_color(piece))
        if self._infer_move(enemy) is not None:
            for r, c, _old, new in enemy:
                self.board[r][c] = new
            self.prev = corrected
            self._on_enemy_move(enemy)
            return
        self.pending_move = None
        if enemy:
            self._log("info", "检测到敌方正在走棋（棋子被提起但尚未落下），此截图不保存")
        else:
            self.prev = corrected
        self._emit()
        self._checkmate_probe()

    def _is_mate_by_move(self, r1: int, c1: int, r2: int, c2: int, piece: str) -> bool:
        """校验失败时预判：我方着法是否已绝杀对方（结束动画挡棋导致识别失败）"""
        if self.my_side is None or self._H is None:
            return False
        trial = [row[:] for row in self.board]
        trial[r1][c1] = None
        trial[r2][c2] = piece
        opp = "black" if self.my_side == "red" else "red"
        fen = fen_of_board(trial, self.my_side, to_move=opp)
        try:
            mated = self.engine.is_mate(fen, ENGINE_MATE_PROBE_MS)
        except engine.EngineError:
            return False
        if not mated:
            return False
        self.board[r1][c1] = None
        self.board[r2][c2] = piece
        self.pending_move = None
        self._finish_game(f"我方绝杀，{'黑' if opp == 'black' else '红'}方无路可走")
        self._emit()
        return True

    def _checkmate_probe(self) -> bool:
        """我方走棋后探测对手是否被绝杀"""
        if self.my_side is None:
            return False
        opp = "black" if self.my_side == "red" else "red"
        fen = fen_of_board(self.board, self.my_side, to_move=opp)
        try:
            mated = self.engine.is_mate(fen, ENGINE_MATE_PROBE_MS)
        except engine.EngineError:
            self._log("error", "绝杀探测失败")
            return False
        if mated:
            self._finish_game(f"我方绝杀，{'黑' if opp == 'black' else '红'}方无路可走")
            return True
        self._log("info", "未绝杀，继续对局")
        return False

    def _recover_move_failure(
        self,
        r1: int,
        c1: int,
        r2: int,
        c2: int,
        piece: str,
        fail_frames: list[ndarray],
    ) -> bool:
        """走棋校验全部失败后的恢复流程（仅尝试一次）。

        1) 先判定对局是否已结束（如对方认输），结束则直接收局；
        2) 否则取新帧确认走棋实际已成功（变动可推断为恰为我方这一步，含吃子），
           成功则按成功处理；
        3) 再按棋局相对走棋前是否变化分情况重试：
           - 未变化：整步重新点击（起子+落子）；
           - 仅我方原棋子被提起：延迟确认一次后只点落子；
           - 其它变化：无法恢复。
        重试后照常校验；仍失败则中止（返回 False，由 _flow 停止自动对弈）。
        """
        if self.my_side is None or self._H is None:
            return False
        self._log("info", "校验失败：检测对局是否结束……")
        for frame in fail_frames:
            if self._detect_resignation(frame) == "confirmed":
                self._finish_game("检测到对局结束画面，敌方可能已认输")
                return True
        self._log("info", "校验识别：检测棋局是否未发生变化……")
        corrected = self._capture()
        if corrected is None:
            return False
        if self._detect_resignation(corrected) == "confirmed":
            self._finish_game("检测到对局结束画面，敌方可能已认输")
            return True
        changes = self._enemy_changes(corrected)
        moved = self._infer_move(changes)
        if (
            moved is not None
            and moved[0] == (r1, c1)
            and moved[1] == (r2, c2)
            and moved[2] == piece
        ):
            self._log("info", "校验延迟后确认走棋已成功")
            self._apply_move_result(corrected, r1, c1, r2, c2, piece)
            return True
        x1, y1 = vision.tap_xy(self._H, r1, c1)
        x2, y2 = vision.tap_xy(self._H, r2, c2)
        from_sq = grid_to_square(r1, c1, self.my_side)
        to_sq = grid_to_square(r2, c2, self.my_side)
        if not changes:
            self._log("move", f"重试走棋：{piece_label(piece)} {from_sq} -> {to_sq}")
            self._log("info", f"点击 ({x1},{y1}) -> ({x2},{y2})")
            try:
                adb_client.tap(self.device, x1, y1)
                time.sleep(TAP_HOLD_INTERVAL_MS / 1000)
                adb_client.tap(self.device, x2, y2)
            except adb_client.AdbError as exc:
                self._log("error", str(exc))
                return False
        elif self._only_piece_lifted(changes, r1, c1, piece):
            self._log(
                "info",
                f"检测到{piece_label(piece)}已被提起（落子未完成），"
                f"延迟 {RECOVERY_WAIT_MS} 毫秒后再次确认",
            )
            time.sleep(RECOVERY_WAIT_MS / 1000)
            corrected = self._capture()
            if corrected is None:
                return False
            if not self._only_piece_lifted(self._enemy_changes(corrected), r1, c1, piece):
                self._log("warn", "棋子被提起后状态异常，无法恢复走棋")
                return False
            self._log("move", f"重试走棋：{to_sq}（只落子）")
            self._log("info", f"点击 ({x2},{y2})")
            try:
                adb_client.tap(self.device, x2, y2)
            except adb_client.AdbError as exc:
                self._log("error", str(exc))
                return False
        else:
            self._log("warn", "检测到棋局发生其它变化，无法恢复走棋")
            for r, c, old, new in changes:
                old_name = piece_label(old) if old else "空"
                new_name = piece_label(new) if new else "空"
                self._log(
                    "warn",
                    f"变化：{grid_to_square(r, c, self.my_side)} {old_name} -> {new_name}",
                )
            return False
        if self._verify_move_loop(r1, c1, r2, c2, piece):
            return True
        self._log("error", "重试走棋仍校验失败，自动对弈已中止")
        return False

    def _only_piece_lifted(
        self,
        changes: list[tuple[int, int, str | None, str | None]],
        r1: int,
        c1: int,
        piece: str,
    ) -> bool:
        """变化是否仅为我方原棋子被提起：起点变空、终点未落子、无其它变化"""
        if len(changes) != 1:
            return False
        r, c, old, new = changes[0]
        return r == r1 and c == c1 and old == piece and new is None
