"""对局状态机：同步 / 自动对弈 / 中断。

由服务端的单个后台 worker 线程调用；interrupt() 可从其它线程安全调用
（打断自动对弈循环与轮次确认等待）。日志与棋盘状态通过回调推给网页。
"""

import threading
import time  # noqa: F401 — smoke tests patch game.time.sleep
from collections.abc import Callable

from numpy import ndarray

from xiangqi_bot import adb_client, engine, vision
from xiangqi_bot.adb_client import Device
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
from xiangqi_bot.config import AUTO_NEXT_GAME, ENDGAME_PIECE_COUNT
from xiangqi_bot.game.auto_next import AutoNextMixin
from xiangqi_bot.game.enemy_move import EnemyMoveMixin
from xiangqi_bot.game.game_over import GameOverMixin
from xiangqi_bot.game.move_exec import MoveExecMixin

RED_CN = "红"
BLACK_CN = "黑"

LogFn = Callable[[str, str], None]  # (kind, 消息)，kind: info/ok/warn/error/move/enemy/gameover
StateFn = Callable[[dict], None]
AskTurnFn = Callable[[], None]  # 请求网页弹窗选择当前轮次


class GameSession(AutoNextMixin, MoveExecMixin, EnemyMoveMixin, GameOverMixin):
    def __init__(
        self,
        device,
        log: LogFn,
        on_state: StateFn | None = None,
        ask_turn: AskTurnFn | None = None,
    ) -> None:
        self.device: Device = device
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
        self._auto_next = False  # 自动下一局流程进行中（网页端据此保持按钮状态不变）
        self.auto_next_game = AUTO_NEXT_GAME  # 对局结束后是否自动下一局（网页开关可实时修改）
        self._resign_streak = 0
        self._lift_logged = False
        self._noisy_count = 0
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

    def set_auto_next(self, enable: bool) -> None:
        """实时开关自动下一局（线程安全），对局结束判定时取最新值"""
        self.auto_next_game = bool(enable)
        self._emit()

    def close(self) -> None:
        """关闭引擎进程"""
        self.engine.close()

    def start(self) -> None:
        """开始棋局：截图同步棋盘状态，之后自动开始对弈"""
        self._interrupt.clear()
        corrected = self._capture()
        if corrected is None:
            self._emit()
            return
        self._reset()
        self._init_from_corrected(corrected)
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

    def move(self) -> bool:
        """走一步：引擎计算 + 点击落子 + 截图校验。仅由 _flow 调用。"""
        assert self.my_side is not None
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
                f"引擎着法 {move} 起点无我方棋子，棋盘数据可能已过期，请点击「开始棋局」重同步",
            )
            return False
        self._last_move = move
        self._highlight = [(r1, c1), (r2, c2)]
        captured = self.board[r2][c2]
        capture_note = f"（吃{piece_label(captured)}）" if captured else ""
        self._log(
            "move",
            f"走棋 {move}：{piece_label(piece)} "
            f"{grid_to_square(r1, c1, self.my_side)} -> "
            f"{grid_to_square(r2, c2, self.my_side)}{capture_note}",
        )
        return self._attempt_move(r1, c1, r2, c2, piece)

    # ---------- 自动对弈 ----------

    def _start_flow(self) -> None:
        """启动自动对弈：设置运行标志，进入主循环"""
        self._running = True
        self._emit()
        self._flow()

    def _flow(self) -> None:
        """自动对弈主循环：单层循环，每个分支提前退出，避免嵌套。

        流程：对方走棋 <-> 我方走棋 -> 对局结束后自动开始下一局。
        直到中断、失败或自动下一局中止时退出。
        """
        while True:
            if not self._running:
                break
            if self._interrupt.is_set():
                break
            if self.my_side is None or self._turn is None:
                break
            if self._turn != self.my_side:
                self._wait_for_enemy_move()
            else:
                if not self.move():
                    self._running = False
                    self._log("info", "走棋失败，自动对弈已暂停，可点击「开始棋局」重试")
                    break
            if self.game_over:
                if self.auto_next_game and not self._interrupt.is_set():
                    if not self._auto_next_game():
                        break
                else:
                    break
        self._emit()

    # ---------- 内部流程 ----------

    def _reset(self) -> None:
        """重置所有状态到初始值（全量同步前调用）"""
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
        self._noisy_count = 0
        self._highlight = []
        self._last_move = None

    def _capture(self) -> ndarray | None:
        """截图并透视矫正：返回矫正后的棋盘图，失败返回 None"""
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
        """全量初始化：分析棋盘、判断红黑方/阶段/轮次，输出日志"""
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

    def _detect_side(self) -> str | None:
        """判断我方红黑方：将/帥在屏幕下方（行6..9）则该方为我方"""
        red_bottom = any(self.board[r][c] == "r_K" for r in range(6, ROWS) for c in range(COLS))
        black_bottom = any(self.board[r][c] == "b_k" for r in range(6, ROWS) for c in range(COLS))
        if red_bottom:
            return "red"
        if black_bottom:
            return "black"
        return None

    def _detect_phase(self) -> str:
        """判断棋局阶段：棋子数>=20 按偏离开局默认位判断开局/中局，否则残局"""
        count = sum(cell is not None for row in self.board for cell in row)
        if count >= ENDGAME_PIECE_COUNT:
            red_dev = self._color_deviates("red")
            black_dev = self._color_deviates("black")
            if not red_dev and not black_dev:
                return "开局"
            return "中局"
        return "残局"

    def _infer_turn(self) -> str | None:
        """推断当前轮到谁走。

        残局 → None（无法推断）。
        双方均不偏离 → "red"（红先）。
        仅红方偏离 → "black"（红方已走，该黑方）。
        双方均偏离 → None（中国象棋规则中不存在黑棋先走的情况）。
        """
        if self.phase == "残局":
            return None
        red_dev = self._color_deviates("red")
        black_dev = self._color_deviates("black")
        if not red_dev and not black_dev:
            return "red"
        if red_dev and not black_dev:
            return "black"
        return None

    def _color_deviates(self, color: str) -> bool:
        """判断某颜色棋子是否偏离开局默认位置"""
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
        """该颜色在当前屏幕方向（我方红黑）下的开局默认格集合"""
        red_sq: set[tuple[int, int]] = set()
        black_sq: set[tuple[int, int]] = set()
        for piece_id, squares in START_SQUARES.items():
            (red_sq if piece_color(piece_id) == "red" else black_sq).update(squares)
        if self.my_side == "black":
            red_sq, black_sq = black_sq, red_sq
        return red_sq if color == "red" else black_sq

    def _is_fresh_one_move(self) -> bool:
        """刚开局局面：全棋子均在盘上，且仅一方走了一步（对方未走）"""
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
        self._log("info", "无法自动判断当前轮到哪一方，请确认本轮是否我方开始走棋")
        self._ask_turn_cb()
        while not self._turn_event.wait(0.2):
            if self._interrupt.is_set():
                return False
        return self._turn_answer == "start"

    def _finish_game(self, reason: str) -> None:
        """标记对局结束并通知网页端"""
        self.game_over = True
        self._log("gameover", reason)
        self._emit()

    def _log_changes(self, changes: list[tuple[int, int, str | None, str | None]]) -> None:
        """输出走棋/变动日志：能推断为一步棋则格式化输出，否则逐格输出"""
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
        """状态：idle(未开始) / red(红方走棋) / black(黑方走棋) / over(绝杀) /
        stopped(中断) / auto_next(自动下一局中，按钮状态保持不变)"""
        if self._auto_next:
            return "auto_next"
        if self.game_over:
            return "over"
        if self._turn is None:
            return "idle"
        if not self._running:
            return "stopped"
        return "red" if self._turn == "red" else "black"

    def _emit(self) -> None:
        """向网页端推送当前状态"""
        if self._on_state is None:
            return
        self._on_state(
            {
                "my_side": self.my_side,
                "turn": self._turn,
                "phase": self.phase,
                "game_over": self.game_over,
                "status": self._status(),
                "auto_next": self.auto_next_game,
                "board": [list(row) for row in self.board],
                "highlight": [[r, c] for r, c in self._highlight],
                "last_move": self._last_move,
            }
        )
