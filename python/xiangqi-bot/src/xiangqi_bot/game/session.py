"""对局状态机：同步 / 自动对弈 / 中断。

由服务端的单个后台 worker 线程调用；interrupt() 可从其它线程安全调用
（打断自动对弈循环与轮次确认等待）。日志与棋盘状态通过回调推给网页。
"""

import sys
import threading
import time  # noqa: F401 — smoke tests patch game.time.sleep
import traceback
from collections.abc import Callable
from pathlib import Path

from numpy import ndarray

from xiangqi_bot import engine, vision
from xiangqi_bot.adb_client import Device
from xiangqi_bot.board import (
    COLS,
    PIECE_CN,
    ROWS,
    START_SQUARES,
    Board,
    grid_to_square,
    make_empty_board,
    piece_color,
    piece_label,
)
from xiangqi_bot.config import AUTO_NEXT_GAME, ENDGAME_PIECE_COUNT
from xiangqi_bot.game._base import Change, MoveResult
from xiangqi_bot.game.auto_next import AutoNextMixin
from xiangqi_bot.game.board_diff import BoardDiffMixin
from xiangqi_bot.game.capture import CaptureMixin
from xiangqi_bot.game.enemy_move import EnemyMoveMixin
from xiangqi_bot.game.game_over import GameOverMixin
from xiangqi_bot.game.self_move import SelfMoveMixin

RED_CN = "红"
BLACK_CN = "黑"

LogFn = Callable[[str, str], None]  # (kind, 消息)，kind: info/ok/warn/error/move/enemy/gameover
StateFn = Callable[[dict], None]
AskTurnFn = Callable[[], None]  # 请求网页弹窗选择当前轮次


class GameSession(
    AutoNextMixin,
    CaptureMixin,
    BoardDiffMixin,
    SelfMoveMixin,
    EnemyMoveMixin,
    GameOverMixin,
):
    def __init__(
        self,
        device,
        log: LogFn,
        on_state: StateFn | None = None,
        ask_turn: AskTurnFn | None = None,
    ) -> None:
        self.device: Device = device  # ADB 设备实例
        self._log = log  # 日志回调 (kind, msg)
        self._on_state = on_state  # 状态推送回调
        self._ask_turn_cb = ask_turn  # 请求网页确认轮次的回调
        self.templates = vision.load_templates()  # 棋子模板字典（14 张 60x60）
        self.engine = engine.Engine()  # pikafish UCI 引擎客户端
        self._homography: ndarray | None = None  # 透视矫正单应矩阵
        self.board: Board = make_empty_board()  # 棋盘布局 10x9
        self.prev_board: Board | None = None  # 上一轮次开始时的棋盘布局快照（变动对比基准）
        self.my_side: str | None = None  # 我方红黑方（"red"/"black"）
        self._turn: str | None = None  # 当前轮到哪方走棋
        self.phase: str | None = None  # 棋局阶段（开局/中局/残局）
        self.game_over = False  # 对局是否结束
        self._running = False  # 自动对弈循环是否进行中
        self._auto_next = False  # 自动下一局流程进行中（网页端据此保持按钮状态不变）
        self.auto_next_game = AUTO_NEXT_GAME  # 对局结束后是否自动下一局（网页开关可实时修改）
        self._resign_streak = 0  # 连续疑似对局结束的帧计数
        self._lift_logged = False  # 是否已提示过敌方提起棋子（防重复）
        self._noisy_count = 0  # 连续噪声帧计数
        self.halfmove_clock = 0  # 自上次吃子以来的半回合数（单方走一步+1，吃子归零）
        self._highlight: list[tuple[int, int]] = []  # 走棋高亮格 [(r, c), ...]
        self._last_move: str | None = None  # 最近一次着法的记谱表示
        self._interrupt = threading.Event()  # 中断自动对弈的事件
        self._turn_answer: str | None = None  # 网页弹窗返回的轮次确认答案
        self._turn_event = threading.Event()  # 等待轮次确认的事件

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
        """开始棋局：截图同步棋盘状态，之后自动开始对弈。

        整个初始化流程包 try/except：任何阶段（截图、识别、判方、弹窗确认）抛异常，
        都打印 error + 堆栈，并推送 stopped 状态，前端可再点「开始棋局」重试。
        """
        self._interrupt.clear()
        try:
            corrected = self._capture()
            if corrected is None:
                self._emit()
                return
            self._reset()
            if not self._init_from_corrected(corrected):
                self._emit()  # 判方失败
                return

            # 弹窗确认（轮次无法推断时）
            start_now = False
            if self._turn is None:
                self._turn = self.my_side
                start_now = self._confirm_start()
                if not start_now:
                    side_cn = RED_CN if self.my_side == "red" else BLACK_CN
                    self._log("ok", f"我方为{side_cn}方，当前棋盘为{self.phase}，未开始对弈")
                    self._emit()
                    return

            # 日志输出 + 推送状态
            side_cn = RED_CN if self.my_side == "red" else BLACK_CN
            turn_cn = RED_CN if self._turn == "red" else BLACK_CN
            self._log("ok", f"我方为{side_cn}方，当前棋盘为{self.phase}，轮到{turn_cn}方")
            self._emit()

            # 自动开始对弈（开局自动 或 弹窗确认）
            if start_now or (self.phase == "开局" and self._turn is not None):
                self._start_flow()
        except Exception as exc:  # noqa: BLE001 — 初始化阶段异常统一兜底
            self._log(
                "error",
                f"启动棋局异常：{exc!r}",
            )
            traceback.print_exc()
            self._running = False
            self._auto_next = False
            self._emit()

    # ---------- 自动对弈 ----------

    def _start_flow(self) -> None:
        """启动自动对弈：设置运行标志，进入主循环。

        外层 try/except/finally 保证：
        - 任何业务异常（引擎报错、ADB 故障、坐标断言失败…）都不击穿到上层 worker，
          仅打印 error 日志并把堆栈打到本地 stderr；
        - finally 里必然把 _running 置 False + _emit() 推送状态，
          前端按钮自动回到"开始棋局"可用态，用户可一键重开，不"卡死"。
        """
        self._running = True
        self._emit()
        try:
            self.engine.newgame()
            self._flow()
        except Exception as exc:  # noqa: BLE001 — 顶层兜所有业务异常
            self._log(
                "error",
                f"自动对弈异常终止：{exc!r}",
            )
            traceback.print_exc()
        finally:
            self._running = False
            self._auto_next = False
            self._emit()
        self._log("info", "flow 流程结束！")

    def _flow(self) -> None:
        """自动对弈主循环：单层循环，每个分支提前退出，避免嵌套。

        - 每轮循环开头维护 prev_board = board 快照，作为该轮次变动对比基准。
        - 敌方走棋 <-> 我方走棋交替；对局结束后若 auto_next_game 开启则自动结算+摆棋，
          返回摆棋完毕帧后在此处统一调用 _init_from_corrected 初始化下一轮。
        """
        while True:
            if not self._running:
                break
            if self._interrupt.is_set():
                break
            if self.my_side is None or self._turn is None:
                break
            # 每轮次快照：进入敌方检测或我方走棋前，把当前 board 固定为对比基准
            # （prev_board 为 None 时由首次初始化保证，此处不做额外兜底）
            if self.prev_board is not None:
                self.prev_board = [row[:] for row in self.board]
            if self._turn != self.my_side:
                self._wait_for_enemy_move()
            else:
                if not self._do_move():
                    # _do_move 返回 False 有两种含义：
                    # 1. 对局正常结束（认输/绝杀）—— _finish_game 已置 game_over=True，
                    #    不应 break，需继续走到下面的自动下一局分支；
                    # 2. 真正的走棋失败（ADB 无响应、引擎异常等）—— game_over 仍为 False，
                    #    暂停 flow 等用户手动重开。
                    if self.game_over:
                        self._log("info", "我方走棋阶段检测到对局结束")
                    else:
                        self._running = False
                        self._log("info", "走棋失败，自动对弈已暂停，可点击「开始棋局」重试")
                        break
            if self.game_over:
                if self.auto_next_game:
                    corrected = self._auto_next_game()
                    if corrected is None:
                        break
                else:
                    self._log("warn", "自动下一局未开启")
                    break
        self._emit()

    # ---------- 内部流程 ----------

    def _reset(self) -> None:
        """重置所有状态到初始值（全量同步前调用）"""
        self.board = make_empty_board()
        self.prev_board = None
        self.my_side = None
        self._turn = None
        self.phase = None
        self.game_over = False
        self._running = False
        self._auto_next = False
        self._resign_streak = 0
        self._lift_logged = False
        self._noisy_count = 0
        self.halfmove_clock = 0
        self._highlight = []
        self._last_move = None

    def _init_from_corrected(self, corrected: ndarray) -> bool:
        """全量初始化：分析棋盘、判断红黑方/阶段/轮次。返回是否成功"""
        self.board = vision.analyze_board(corrected, self.templates)
        side = self._detect_side()
        if side is None:
            self._log("error", "无法判断我方红黑方（未识别到将/帥），请检查棋盘画面后重新同步")
            return False
        self.my_side = side
        self.prev_board = [row[:] for row in self.board]  # 深拷贝当前布局
        self.phase, self._turn = self._analyze_opening()
        return True

    def _detect_side(self) -> str | None:
        """判断我方红黑方：将/帥在屏幕下方（行6..9）则该方为我方"""
        red_bottom = any(self.board[r][c] == "r_K" for r in range(6, ROWS) for c in range(COLS))
        black_bottom = any(self.board[r][c] == "b_k" for r in range(6, ROWS) for c in range(COLS))
        if red_bottom:
            return "red"
        if black_bottom:
            return "black"
        return None

    def _analyze_opening(self) -> tuple[str, str | None]:
        """一次性分析棋局阶段和轮次（消除 _detect_phase/_infer_turn/_is_fresh_one_move 重复检测）。

        返回 (phase, turn):
        - 残局：(残局, None) — 棋子数 < 20，无法推断轮次
        - 开局未走：(开局, "red") — 32棋子双方不偏离，红方先走
        - 开局对方走一步：(开局, 对方颜色) — 32棋子恰一方偏离且单步移动，轮到我方
        - 中局：(中局, None) — 棋子≥20但<32，或32棋子但走多步，需弹窗确认
        """
        count = sum(cell is not None for row in self.board for cell in row)
        if count < ENDGAME_PIECE_COUNT:
            return "残局", None

        red_dev = self._color_deviates("red")
        black_dev = self._color_deviates("black")

        # 开局：32棋子未走或仅走一步
        if count == 32:
            if not red_dev and not black_dev:
                return "开局", "red"  # 双方均未走，红先
            if red_dev != black_dev:  # 恰一方偏离
                moved = "red" if red_dev else "black"
                if self._single_piece_moved(moved):
                    # 对方走一步，轮到我方
                    other = "black" if moved == "red" else "red"
                    return "开局", other

        # 中局：双方均偏离或棋子不足32但≥20
        return "中局", None

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
        """标记对局结束并通知网页端。

        额外打印调用路径给前端面板：避免之前用 traceback.format_stack 打出的
        "File / line / in func" 标准异常格式让用户误以为是报错。
        详细完整 traceback 仍然写本地 stderr（排错用），不污染前端面板。
        """
        self._log("info", f"[结束触发点] {reason}")
        # 前端面板：简洁路径（每帧 → 函数名/文件名/行号，不含 Exception 风格 "File ... in"）
        frames = traceback.extract_stack()[:-1]  # 去掉 _finish_game 自身
        parts = [f"→ {f.name} ({Path(f.filename).name}:{f.lineno})" for f in frames]
        # 只保留项目内的最后 8 帧（截断 threading / worker 启动等噪音）
        project_frames = [p for p in parts if "xiangqi_bot" in p or "server.py" in p]
        tail = project_frames[-8:] if project_frames else parts[-8:]
        self._log("info", "调用路径：\n  " + "\n  ".join(tail))
        # 本地 stderr：完整详细 traceback（含 threading.py 等外层帧），开发者排错用
        print(
            f"[finish_game] {reason}\n{''.join(traceback.format_stack()[:-1])}",
            file=sys.stderr,
            flush=True,
        )
        self.game_over = True
        self._log("gameover", reason)
        self._emit()

    def _log_move(self, moved: MoveResult) -> None:
        """输出敌方走棋日志：已推断为一步棋，格式化输出（红/黑方 + 棋子 + 记谱 + 吃子）"""
        if self.my_side is None:
            return
        (r1, c1), (r2, c2), piece, captured = moved
        color_cn = RED_CN if piece_color(piece) == "red" else BLACK_CN
        from_sq = grid_to_square(r1, c1, self.my_side)
        to_sq = grid_to_square(r2, c2, self.my_side)
        capture_note = f"（吃{piece_label(captured)}）" if captured else ""
        self._last_move = f"{from_sq}-{to_sq}"
        self._log("enemy", f"{color_cn}方走{PIECE_CN[piece]}：{from_sq} -> {to_sq}{capture_note}")

    def _log_updates(self, updates: list[Change], level: str = "info") -> None:
        """打印逐格变动日志。"""
        side = self.my_side or "red"
        self._log(level, f"棋子变动（{len(updates)} 格）")
        for r, c, old, new in updates:
            old_name = piece_label(old) if old else "空"
            new_name = piece_label(new) if new else "空"
            self._log(level, f"变化：{grid_to_square(r, c, side)} {old_name} -> {new_name}")

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
