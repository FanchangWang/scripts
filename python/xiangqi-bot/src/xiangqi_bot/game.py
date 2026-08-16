"""对局状态机：同步棋局 / 自动对弈 / 中断。

由服务端的单个后台 worker 线程调用；`interrupt()` 可从其它线程安全调用
（打断自动对弈循环与轮次确认等待）。日志与棋盘状态通过回调推给网页。
"""

import threading
import time
from collections.abc import Callable

from numpy import ndarray

from xiangqi_bot import adb_client, engine, vision
from xiangqi_bot.board import (
    COLS,
    PIECE_CN,
    ROWS,
    START_SQUARES,
    Board,
    fen_of_board,
    grid_to_square,
    make_empty_board,
    piece_color,
    piece_label,
    square_to_grid,
)
from xiangqi_bot.config import (
    AUTO_DETECT_INTERVAL_MS,
    ENDGAME_PIECE_COUNT,
    ENGINE_MATE_PROBE_MS,
    MOVE_SETTLE_MS,
    MOVE_VERIFY_COUNT,
    RECOVERY_WAIT_MS,
    RESIGN_CONFIRM_COUNT,
    RESIGN_PIECE_DROP_THRESHOLD,
    TAP_HOLD_INTERVAL_MS,
)

RED_CN = "红"
BLACK_CN = "黑"

LogFn = Callable[[str, str], None]  # (kind, 消息)，kind: info/ok/warn/error/move/enemy/gameover
StateFn = Callable[[dict], None]
AskTurnFn = Callable[[], None]  # 请求网页弹窗选择当前轮次


class GameSession:
    def __init__(
        self,
        device,
        log: LogFn,
        on_state: StateFn | None = None,
        ask_turn: AskTurnFn | None = None,
    ) -> None:
        self.device = device
        self._log = log
        self._on_state = on_state
        self._ask_turn_cb = ask_turn
        self.templates = vision.load_templates()
        self.engine = engine.Engine()
        self._H: ndarray | None = None
        self.board: Board = make_empty_board()
        self.prev: ndarray | None = None
        self.my_side: str | None = None
        self._turn: str | None = None
        self.phase: str | None = None
        self.pending_move: tuple[str, str] | None = None
        self.game_over = False
        self._running = False  # 自动对弈循环是否进行中
        self._resign_streak = 0
        self._lift_logged = False
        self._highlight: list[tuple[int, int]] = []
        self._last_move: str | None = None
        self._interrupt = threading.Event()
        self._turn_answer: str | None = None
        self._turn_event = threading.Event()

    # ---------- 公共接口（worker 线程调用） ----------

    def interrupt(self) -> None:
        """中断自动对弈（线程安全，从任意线程可调）"""
        self._running = False
        self._interrupt.set()

    def answer_turn(self, answer: str | None) -> None:
        """网页弹窗确认（answer 为 "start"/"no"）"""
        self._turn_answer = answer
        self._turn_event.set()

    def close(self) -> None:
        self.engine.close()

    def sync(self) -> None:
        """同步棋局：无历史或已结束 -> 全量初始化；否则增量拉取。之后自动开始对弈"""
        self._interrupt.clear()
        corrected = self._capture()
        if corrected is None:
            self._emit()
            return
        if self.prev is None or self.game_over:
            self._reset()
            self._init_from_corrected(corrected)
        else:
            self._pull(corrected)
        fresh = self._turn is not None and self._turn == self.my_side and self._is_fresh_one_move()
        if (
            not self._running
            and not self.game_over
            and self._turn is not None
            and (self.phase == "开局" or fresh)
        ):
            if fresh:
                self._log("info", "检测到刚开局局面（对方仅走一步），自动开始对弈")
            self._start_flow()

    def start(self) -> None:
        """开始棋局：用当前棋盘数据直接开始自动对弈（不重新拉取棋盘）"""
        if self.game_over:
            self._log("error", "本局已结束，请先「同步棋局」")
            return
        if self.my_side is None or self._turn is None:
            self._log("error", "尚未同步棋局，请先点击「同步棋局」")
            return
        self._interrupt.clear()
        self._log("info", "开始棋局（使用当前棋盘数据）")
        self._start_flow()

    def move(self) -> bool:
        """走一步：用内存布局 + 引擎计算，点击落子，3 次截图校验。失败返回 False"""
        if self.my_side is None:
            self._log("error", "尚未同步棋局，请先点击「同步棋局」")
            return False
        if self.game_over:
            self._log("error", "本局已结束")
            return False
        if self._turn != self.my_side:
            self._log("warn", "当前不是我方回合")
            return False
        fen = fen_of_board(self.board, self.my_side)
        if self.pending_move is not None and self.pending_move[0] == fen:
            move = self.pending_move[1]
            self._log("info", f"使用预计算着法：{move}")
        else:
            pending = self._compute_move()
            if pending is None:
                return False
            move = pending[1]
        r1, c1 = square_to_grid(move[0:2], self.my_side)
        r2, c2 = square_to_grid(move[2:4], self.my_side)
        piece = self.board[r1][c1]
        if piece is None:
            self._log(
                "warn",
                f"引擎着法 {move} 起点无我方棋子，棋盘数据可能已过期，请先「同步棋局」",
            )
            return False
        self._last_move = move
        self._highlight = [(r1, c1), (r2, c2)]
        self._log(
            "move",
            f"走棋 {move}：{piece_label(piece)} "
            f"{grid_to_square(r1, c1, self.my_side)} -> {grid_to_square(r2, c2, self.my_side)}",
        )
        return self._attempt_move(r1, c1, r2, c2, piece)

    # ---------- 自动对弈 ----------

    def _start_flow(self) -> None:
        self._running = True
        self._emit()
        self._flow()

    def _flow(self) -> None:
        """自动对弈主循环：我方走棋 <-> 检测敌方走棋，直到中断或对局结束"""
        while self._running and not self.game_over and not self._interrupt.is_set():
            if self._turn != self.my_side:
                self._wait_for_enemy_move()
                continue
            # 我方走棋后若敌方已在走棋校验期间走完（turn 回到我方），立即继续我方走棋
            if not self.move():
                self._running = False
                self._log("info", "走棋失败，自动对弈已暂停，可点击「开始棋局」重试")
                break
        self._emit()

    # ---------- 内部流程 ----------

    def _reset(self) -> None:
        self.board = make_empty_board()
        self.prev = None
        self.my_side = None
        self._turn = None
        self.phase = None
        self.pending_move = None
        self.game_over = False
        self._running = False
        self._resign_streak = 0
        self._lift_logged = False
        self._highlight = []
        self._last_move = None

    def _capture(self) -> ndarray | None:
        try:
            img = adb_client.screencap(self.device)
        except adb_client.AdbError as exc:
            self._log("error", str(exc))
            return None
        if img is None:
            self._log("error", "截图失败")
            return None
        h, w = img.shape[:2]
        try:
            self._H = vision.homography(w, h)
        except RuntimeError as exc:
            self._log("error", str(exc))
            return None
        return vision.correct_board(img)

    def _init_from_corrected(self, corrected: ndarray) -> None:
        self.board = vision.analyze_board(corrected, self.templates)
        side = self._detect_side()
        if side is None:
            self._log("error", "无法判断我方红黑方（未识别到将/帥），请检查棋盘画面后重新同步")
            self._emit()
            return
        self.my_side = side
        self.prev = corrected
        self.phase = self._detect_phase()
        self._turn = self._infer_turn()
        side_cn = RED_CN if side == "red" else BLACK_CN
        start_now = False
        if self._turn is None:
            # 无法推断轮次时默认我方走棋，弹窗询问是否开始对弈
            self._turn = side
            start_now = self._confirm_start()
            if not start_now:
                self._log("ok", f"我方为{side_cn}方，当前棋盘为{self.phase}，未开始对弈")
                self._emit()
                return
        turn_cn = RED_CN if self._turn == "red" else BLACK_CN
        self._log("ok", f"我方为{side_cn}方，当前棋盘为{self.phase}，轮到{turn_cn}方")
        self._emit()
        if start_now:
            self._start_flow()

    def _pull(self, corrected: ndarray) -> None:
        """增量拉取：认输检测 -> 差异分析 -> 敌方走棋处理"""
        if self.prev is None:
            return
        if self._detect_resignation(corrected):
            self._finish_game("检测到对局结束画面，敌方可能已认输")
            return
        changed = vision.diff_cells(self.prev, corrected)
        changes: list[tuple[int, int, str | None, str | None]] = []
        for r, c in sorted(changed):
            old = self.board[r][c]
            new = vision.analyze_cell(corrected, r, c, self.templates)
            self.board[r][c] = new
            if old != new:
                changes.append((r, c, old, new))
        self.prev = corrected
        if not changes:
            self._highlight = []
            self._last_move = None
            self._emit()
            self._log("info", "棋盘无变化")
            return
        self._on_enemy_move(changes)

    def _detect_side(self) -> str | None:
        red_bottom = any(self.board[r][c] == "r_K" for r in range(6, ROWS) for c in range(COLS))
        black_bottom = any(self.board[r][c] == "b_k" for r in range(6, ROWS) for c in range(COLS))
        if red_bottom:
            return "red"
        if black_bottom:
            return "black"
        return None

    def _detect_phase(self) -> str:
        count = sum(cell is not None for row in self.board for cell in row)
        if count >= ENDGAME_PIECE_COUNT:
            red_dev = self._color_deviates("red")
            black_dev = self._color_deviates("black")
            if not red_dev and not black_dev:
                return "开局"
            return "中局"
        return "残局"

    def _infer_turn(self) -> str | None:
        """推断当前轮到谁走：全在默认位 -> 红先；仅红偏离 -> 黑走；仅黑偏离 -> 红走；
        双方均偏离或残局 -> None（需用户在网页确认）"""
        if self.phase == "残局":
            return None
        red_dev = self._color_deviates("red")
        black_dev = self._color_deviates("black")
        if not red_dev and not black_dev:
            return "red"
        if red_dev and not black_dev:
            return "black"
        if black_dev and not red_dev:
            return "red"
        return None

    def _color_deviates(self, color: str) -> bool:
        expected = self._expected_start_squares(color)
        for r in range(ROWS):
            for c in range(COLS):
                p = self.board[r][c]
                if p is not None and piece_color(p) == color and (r, c) not in expected:
                    return True
        for r, c in expected:
            p = self.board[r][c]
            if p is None or piece_color(p) != color:
                return True
        return False

    def _expected_start_squares(self, color: str) -> set[tuple[int, int]]:
        """该颜色在当前屏幕方向（我方红黑）下的开局默认格集合。

        网格固定于屏幕：我方为红时红方占据下方默认格；我方为黑时屏幕翻转，
        黑方占据原红方默认格、红方占据原黑方默认格。
        """
        red_sq: set[tuple[int, int]] = set()
        black_sq: set[tuple[int, int]] = set()
        for piece_id, squares in START_SQUARES.items():
            (red_sq if piece_color(piece_id) == "red" else black_sq).update(squares)
        if self.my_side == "black":
            red_sq, black_sq = black_sq, red_sq
        return red_sq if color == "red" else black_sq

    def _is_fresh_one_move(self) -> bool:
        """刚开局局面：全棋子均在盘上，且仅一方走了一步（对方未走）。

        行棋回合严格交替，故「对方走一步、我方未走」等价于：我方全在默认格、
        对方偏离默认格。再加总子数=32 与「恰 1 格空出 + 1 个非默认格落子」两个
        校验排除误判；只要棋局已走超过一步（我方也偏离、或对方偏离不止一格）
        都不满足，避免中局/残局误自动开局。
        """
        if sum(cell is not None for row in self.board for cell in row) != 32:
            return False
        red_dev = self._color_deviates("red")
        black_dev = self._color_deviates("black")
        if red_dev == black_dev:
            return False
        moved = "red" if red_dev else "black"
        return self._single_piece_moved(moved)

    def _single_piece_moved(self, color: str) -> bool:
        """该颜色相对开局默认格恰有一枚棋子移动：1 个默认格空出 + 1 个非默认格落子"""
        expected = self._expected_start_squares(color)
        missing = 0
        extra = 0
        for r in range(ROWS):
            for c in range(COLS):
                p = self.board[r][c]
                if p is not None and piece_color(p) == color:
                    if (r, c) not in expected:
                        extra += 1
                elif (r, c) in expected:
                    missing += 1
        return missing == 1 and extra == 1

    def _confirm_start(self) -> bool:
        """无法推断轮次时弹窗确认：是否以我方先走开始对弈（返回 True 表示开始）"""
        if self._ask_turn_cb is None:
            return False
        self._turn_event.clear()
        self._turn_answer = None
        self._log("info", "无法自动判断当前轮到哪一方，默认我方走棋，请确认是否开始")
        self._ask_turn_cb()
        while not self._turn_event.wait(0.2):
            if self._interrupt.is_set():
                return False
        return self._turn_answer == "start"

    def _on_enemy_move(self, changes: list[tuple[int, int, str | None, str | None]]) -> None:
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

    def _wait_for_enemy_move(self) -> None:
        """检测敌方走棋：持续截图，直到敌方走棋完毕、对局结束或用户中断"""
        if self.prev is None:
            return
        self._resign_streak = 0  # 每个检测会话重新计数，避免残留帧导致误判
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
            if self._detect_resignation(corrected):
                self._finish_game("检测到对局结束画面，敌方可能已认输")
                return
            if result == "lifted":
                if not self._lift_logged:
                    self._lift_logged = True
                    self._log("info", "检测到敌方提起棋子")
            elif result == "none":
                self._lift_logged = False
        self._log("info", "已中断检测敌方走棋")

    def _finish_game(self, reason: str) -> None:
        self.game_over = True
        self._log("gameover", reason)
        self._emit()

    def _detect_resignation(self, corrected: ndarray) -> bool:
        """检测对局结束/敌方认输画面（需连续 RESIGN_CONFIRM_COUNT 帧稳定出现）"""
        if self.my_side is None:
            self._resign_streak = 0
            return False
        board_now = vision.analyze_board(corrected, self.templates)
        my_general = "r_K" if self.my_side == "red" else "b_k"
        enemy_general = "b_k" if self.my_side == "red" else "r_K"
        has_my = any(my_general in row for row in board_now)
        has_enemy = any(enemy_general in row for row in board_now)
        expected = sum(cell is not None for row in self.board for cell in row)
        actual = sum(cell is not None for row in board_now for cell in row)
        dropped = expected - actual
        if ((not has_my or not has_enemy) and dropped >= 1) or (
            dropped >= RESIGN_PIECE_DROP_THRESHOLD
        ):
            self._resign_streak += 1
        else:
            self._resign_streak = 0
        return self._resign_streak >= RESIGN_CONFIRM_COUNT

    def _detect_enemy(self, corrected: ndarray) -> str:
        """检测敌方是否走棋：'moved'（已走棋并已更新）/ 'lifted'（提起未放下）/ 'none'"""
        if self.prev is None:
            return "none"
        updates = self._enemy_changes(corrected)
        if not updates:
            return "none"
        my_color = "red" if self.my_side == "red" else "black"
        updates = self._apply_self_heal(updates, my_color)
        if not updates:
            return "none"
        if self._infer_move(updates) is not None or any(
            new is not None for _r, _c, _old, new in updates
        ):
            for r, c, _old, new in updates:
                self.board[r][c] = new
            self.prev = corrected
            self._on_enemy_move(updates)
            return "moved"
        if all(old is not None and new is None for _r, _c, old, new in updates):
            return "lifted"
        return "none"

    def _enemy_changes(self, corrected: ndarray) -> list[tuple[int, int, str | None, str | None]]:
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

    def _compute_move(self) -> tuple[str, str] | None:
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
        if self._H is None:
            self._log("error", "尚无棋盘坐标信息，请先同步棋局")
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
        2) 否则按棋局相对走棋前是否变化分情况重试：
           - 未变化：整步重新点击（起子+落子）；
           - 仅我方原棋子被提起：延迟确认一次后只点落子；
           - 其它变化：无法恢复。
        重试后照常校验；仍失败则中止（返回 False，由 _flow 停止自动对弈）。
        """
        if self.my_side is None or self._H is None:
            return False
        self._log("info", "校验失败：检测对局是否结束……")
        for frame in fail_frames:
            if self._detect_resignation(frame):
                self._finish_game("检测到对局结束画面，敌方可能已认输")
                return True
        self._log("info", "校验识别：检测棋局是否未发生变化……")
        corrected = self._capture()
        if corrected is None:
            return False
        if self._detect_resignation(corrected):
            self._finish_game("检测到对局结束画面，敌方可能已认输")
            return True
        changes = self._enemy_changes(corrected)
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

    def _verify_our_move(
        self, corrected: ndarray, r1: int, c1: int, r2: int, c2: int, piece: str
    ) -> bool:
        new_from = vision.analyze_cell(corrected, r1, c1, self.templates)
        new_to = vision.analyze_cell(corrected, r2, c2, self.templates)
        if new_from == piece:
            return False
        if new_to == piece:
            return True
        return new_to is not None and new_to != self.board[r2][c2]

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

    def _apply_move_result(
        self, corrected: ndarray, r1: int, c1: int, r2: int, c2: int, piece: str
    ) -> None:
        """走棋校验成功：根据截图内容分三类处理"""
        side = self.my_side or "red"
        self.board[r1][c1] = vision.analyze_cell(corrected, r1, c1, self.templates)
        self.board[r2][c2] = vision.analyze_cell(corrected, r2, c2, self.templates)
        self._turn = "black" if self.my_side == "red" else "red"
        self._log("ok", "走动成功")
        enemy = self._enemy_changes(corrected)
        if self.board[r2][c2] != piece:
            self._log(
                "enemy",
                f"对方吃掉了走到 {grid_to_square(r2, c2, side)} 的我方{piece_label(piece)}",
            )
            enemy.append((r2, c2, piece, self.board[r2][c2]))
            for r, c, _old, new in enemy:
                self.board[r][c] = new
            self.prev = corrected
            self._on_enemy_move(enemy)
            return
        enemy = self._apply_self_heal(enemy, piece_color(piece))
        if self._infer_move(enemy) is not None or any(
            new is not None for _r, _c, _old, new in enemy
        ):
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

    def _infer_move(
        self, changes: list[tuple[int, int, str | None, str | None]]
    ) -> tuple[tuple[int, int], tuple[int, int], str, str | None] | None:
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

    def _log_changes(self, changes: list[tuple[int, int, str | None, str | None]]) -> None:
        if self.my_side is None:
            return
        moved = self._infer_move(changes)
        if moved is not None:
            (r1, c1), (r2, c2), piece, captured = moved
            color_cn = RED_CN if piece_color(piece) == "red" else BLACK_CN
            from_sq = grid_to_square(r1, c1, self.my_side)
            to_sq = grid_to_square(r2, c2, self.my_side)
            capture_note = f"（吃{piece_label(captured)}）" if captured else ""
            self._last_move = f"{from_sq}-{to_sq}"
            self._log(
                "enemy", f"{color_cn}方走{PIECE_CN[piece]}：{from_sq} -> {to_sq}{capture_note}"
            )
            return
        for r, c, old, new in changes:
            old_name = piece_label(old) if old else "空"
            new_name = piece_label(new) if new else "空"
            self._log("enemy", f"{grid_to_square(r, c, self.my_side)} {old_name} -> {new_name}")

    # ---------- 状态回传 ----------

    def _status(self) -> str:
        """状态：idle(未开始) / red(红方走棋) / black(黑方走棋) / over(绝杀) / stopped(中断)"""
        if self.game_over:
            return "over"
        if self._turn is None:
            return "idle"
        if not self._running:
            return "stopped"
        return "red" if self._turn == "red" else "black"

    def _emit(self) -> None:
        if self._on_state is None:
            return
        self._on_state(
            {
                "my_side": self.my_side,
                "turn": self._turn,
                "phase": self.phase,
                "game_over": self.game_over,
                "status": self._status(),
                "board": [list(row) for row in self.board],
                "highlight": [[r, c] for r, c in self._highlight],
                "last_move": self._last_move,
            }
        )
