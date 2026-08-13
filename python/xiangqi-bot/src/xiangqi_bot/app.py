"""主流程 / 菜单 / 子流程编排。"""

import time
from collections.abc import Iterable

from numpy import ndarray

from xiangqi_bot import adb_client, console, engine, printer, vision
from xiangqi_bot.board import (
    PIECE_CN,
    ROWS,
    Board,
    center_screen,
    fen_of_board,
    grid_to_square,
    make_empty_board,
    piece_color,
    piece_label,
    square_to_grid,
)
from xiangqi_bot.config import (
    AUTO_DETECT_INTERVAL_MS,
    AUTO_DETECT_MAX_COUNT,
    ENGINE_MATE_PROBE_MS,
    MOVE_SETTLE_MS,
    MOVE_VERIFY_COUNT,
    TAP_HOLD_INTERVAL_MS,
)

RED_CN = "红"
BLACK_CN = "黑"


class App:
    def __init__(self, device) -> None:
        self.device = device
        self.my_side: str | None = None
        self.board: Board = make_empty_board()
        self.prev_screenshot: ndarray | None = None
        self.templates = vision.load_templates()
        self.has_moved = False
        self.pending_move: tuple[str, str] | None = None
        self.auto_move = True
        self.auto_detect = True
        self.game_over = False

    def run(self) -> None:
        if self._main_menu() == "q":
            return
        while True:
            if not self.init_game():
                print("初始化失败。请调整手机画面后，回车=重试，q=退出")
                if console.ask().strip().lower() in ("q", "quit"):
                    return
                continue
            result = self._side_loop()
            if result == "quit":
                return
            if result == "gameover":
                print("\n本局已结束，请选择下一步操作")
                if self._main_menu() == "q":
                    return

    # ---------- 菜单 ----------

    def _main_menu(self) -> str:
        while True:
            print("\n==== 中国象棋自动脚本 ====")
            print("[回车] 初始化对局")
            print(f"[1]    自动走棋：{'开' if self.auto_move else '关'}")
            print(f"[2]    自动检测敌方走棋：{'开' if self.auto_detect else '关'}")
            print("[q]    退出脚本")
            raw = console.ask("> ").strip().lower()
            if raw == "":
                return "init"
            if raw in ("q", "quit", "exit"):
                return "q"
            if raw == "1":
                self.auto_move = not self.auto_move
                print(f"自动走棋已{'开启' if self.auto_move else '关闭'}")
                continue
            if raw == "2":
                self.auto_detect = not self.auto_detect
                print(f"自动检测敌方走棋已{'开启' if self.auto_detect else '关闭'}")
                continue

    def _side_menu(self) -> str:
        side_cn = RED_CN if self.my_side == "red" else BLACK_CN
        default_is_move = self.my_side == "red" and not self.has_moved
        print(f"\n==== 我方为{side_cn}方 ====")
        print(f"[回车] {'走棋' if default_is_move else '拉取新棋盘数据'}")
        print("[1]    走棋")
        print("[2]    拉取新棋盘数据")
        print("[3]    初始化对局")
        print("[q]    退出脚本")
        raw = console.ask("> ").strip().lower()
        if raw == "":
            return "move" if default_is_move else "pull"
        if raw in ("q", "quit", "exit"):
            return "quit"
        if raw in ("1", "走棋"):
            return "move"
        if raw in ("2", "拉取"):
            return "pull"
        if raw in ("3", "初始化"):
            return "reinit"
        return "move" if default_is_move else "pull"

    def _side_loop(self) -> str:
        while True:
            if self.game_over:
                return "gameover"
            action = self._side_menu()
            if action == "quit":
                return "quit"
            if action == "reinit":
                return "reinit"
            if action == "move":
                self.do_move()
            else:
                self.pull_new_data()

    # ---------- 子流程 ----------

    def init_game(self) -> bool:
        print("\n[初始化对局] 清除历史状态...")
        self.game_over = False
        self.prev_screenshot = None
        self.my_side = None
        self.board = make_empty_board()
        img = adb_client.screencap(self.device)
        if img is None:
            return False
        return self._init_from_screenshot(img)

    def _init_from_screenshot(self, img: ndarray) -> bool:
        self.board = vision.analyze_board(img, self.templates)
        side = self._detect_side()
        if side is None:
            print("无法判断我方红黑方（未识别到将/帥），请检查棋盘画面后重新初始化")
            return False
        self.my_side = side
        self.prev_screenshot = img
        self.has_moved = False
        self.pending_move = None
        print(f"我方为{RED_CN if side == 'red' else BLACK_CN}方")
        self.print_current()
        if side == "red" and self._compute_move() is not None and self.auto_move:
            self.do_move()
        return True

    def _detect_side(self) -> str | None:
        red_general_row: int | None = None
        black_general_row: int | None = None
        for r in range(ROWS):
            for c in range(9):
                piece = self.board[r][c]
                if piece == "r_K":
                    red_general_row = r
                elif piece == "b_k":
                    black_general_row = r
        if red_general_row is not None and red_general_row > 5:
            return "red"
        if black_general_row is not None and black_general_row > 5:
            return "black"
        return None

    def print_current(self, highlight: Iterable[tuple[int, int]] = ()) -> None:
        printer.print_board(self.board, self.my_side, highlight)

    def pull_new_data(self) -> None:
        print("\n[拉取新棋盘数据]")
        img = adb_client.screencap(self.device)
        if img is None:
            return
        if self.prev_screenshot is None:
            print("无历史截图，按初始化对局处理")
            self._init_from_screenshot(img)
            return
        changed = vision.diff_cells(self.prev_screenshot, img)
        changes: list[tuple[int, int, str | None, str | None]] = []
        for r, c in sorted(changed):
            old = self.board[r][c]
            new = vision.analyze_cell(img, r, c, self.templates)
            self.board[r][c] = new
            if old != new:
                changes.append((r, c, old, new))
        self.prev_screenshot = img
        if not changes:
            self.print_current()
            print("棋盘无变化")
            return
        self._on_enemy_move(changes)

    def _on_enemy_move(self, changes: list[tuple[int, int, str | None, str | None]]) -> None:
        """敌方已走棋（调用前棋盘须已按变化更新）：打印棋局与日志、预计算我方着法并自动走棋"""
        self.has_moved = False
        moved = self._infer_move(changes)
        if moved is not None:
            (r1, c1), (r2, c2), _piece, _captured = moved
            highlight = [(r1, c1), (r2, c2)]
        else:
            highlight = [(r, c) for r, c, _old, _new in changes]
        self.print_current(highlight=highlight)
        self._log_changes(changes)
        if self._compute_move() is not None and self.auto_move:
            self.do_move()

    def _wait_for_enemy_move(self) -> None:
        """自动检测敌方走棋：每 500ms 截图一次，最多 20 次（10 秒）；未检测到则回菜单"""
        if self.prev_screenshot is None:
            return
        for attempt in range(1, AUTO_DETECT_MAX_COUNT + 1):
            time.sleep(AUTO_DETECT_INTERVAL_MS / 1000)
            print(f"[自动检测敌方走棋] 第 {attempt}/{AUTO_DETECT_MAX_COUNT} 次检测……")
            img = adb_client.screencap(self.device)
            if img is None:
                continue
            result = self._detect_enemy(img)
            if result == "moved":
                return
            if result == "lifted":
                print("[自动检测敌方走棋] 检测到敌方提起棋子但尚未放下，继续等待……")
            else:
                print("[自动检测敌方走棋] 敌方尚未走棋，继续等待……")
        total = AUTO_DETECT_MAX_COUNT * AUTO_DETECT_INTERVAL_MS / 1000
        print(
            f"[自动检测敌方走棋] {total:.0f} 秒内未检测到敌方走棋，"
            "请确认敌方走棋后执行“拉取新棋盘数据”"
        )

    def _detect_enemy(self, img: ndarray) -> str:
        """检测敌方是否走棋：'moved'（已走棋并已更新）/ 'lifted'（提起未放下）/ 'none'。

        己方走动过的格子（起止格）在棋盘布局中已反映，`_enemy_changes` 比较时
        `old == new` 会被自动忽略，只统计敌方棋子的变动。
        """
        if self.prev_screenshot is None:
            return "none"
        updates = self._enemy_changes(img)
        if not updates:
            return "none"
        if self._infer_move(updates) is not None:
            for r, c, _old, new in updates:
                self.board[r][c] = new
            self.prev_screenshot = img
            self._on_enemy_move(updates)
            return "moved"
        if all(old is not None and new is None for _r, _c, old, new in updates):
            return "lifted"
        return "none"

    def _checkmate_probe(self) -> bool:
        """我方走棋后判断对手是否无路可走（绝杀/困毙）；是则返回 True"""
        if self.my_side is None:
            return False
        opp = "black" if self.my_side == "red" else "red"
        fen = fen_of_board(self.board, self.my_side, to_move=opp)
        try:
            move = engine.best_move(fen, ENGINE_MATE_PROBE_MS)
        except engine.EngineError:
            return False
        if move is None:
            opp_cn = BLACK_CN if opp == "black" else RED_CN
            print(f"[对局结束] 我方绝杀，{opp_cn}方无路可走")
            return True
        return False

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
            print(f"{color_cn}方走{PIECE_CN[piece]}：{from_sq}->{to_sq}{capture_note}")
            return
        for r, c, old, new in changes:
            old_name = piece_label(old) if old else "空"
            new_name = piece_label(new) if new else "空"
            print(f"{grid_to_square(r, c, self.my_side)} {old_name}->{new_name}")

    def _compute_move(self) -> tuple[str, str] | None:
        if self.my_side is None:
            return None
        fen = fen_of_board(self.board, self.my_side)
        print("[走棋] 预计算着法...")
        try:
            move = engine.best_move(fen)
        except engine.EngineError as exc:
            print(f"引擎错误：{exc}")
            return None
        if move is None:
            print("引擎无可用着法（对局可能已结束）")
            return None
        self.pending_move = (fen, move)
        print(f"[走棋] 已预计算着法：{move}")
        return self.pending_move

    def do_move(self) -> None:
        if self.my_side is None:
            print("尚未初始化对局，请先执行初始化对局")
            return
        fen = fen_of_board(self.board, self.my_side)
        if self.pending_move is not None and self.pending_move[0] == fen:
            move = self.pending_move[1]
            print(f"\n[走棋] 使用预计算着法：{move}")
        else:
            print(f"\n[走棋] 当前局面 FEN: {fen}")
            pending = self._compute_move()
            if pending is None:
                return
            move = pending[1]
        r1, c1 = square_to_grid(move[0:2], self.my_side)
        r2, c2 = square_to_grid(move[2:4], self.my_side)
        piece = self.board[r1][c1]
        if piece is None:
            print(f"引擎着法 {move} 起点无我方棋子，棋盘数据可能已过期，请先拉取新棋盘数据")
            return
        x1, y1 = center_screen(r1, c1)
        x2, y2 = center_screen(r2, c2)
        print(
            f"引擎着法：{move}  {piece_label(piece)} "
            f"{grid_to_square(r1, c1, self.my_side)}({x1},{y1}) -> "
            f"{grid_to_square(r2, c2, self.my_side)}({x2},{y2})"
        )
        if self._attempt_move(r1, c1, r2, c2, piece):
            return
        self._move_failed_menu(r1, c1, r2, c2, piece)

    def _attempt_move(self, r1: int, c1: int, r2: int, c2: int, piece: str) -> bool:
        x1, y1 = center_screen(r1, c1)
        x2, y2 = center_screen(r2, c2)
        if not adb_client.tap(self.device, x1, y1):
            return False
        time.sleep(TAP_HOLD_INTERVAL_MS / 1000)
        if not adb_client.tap(self.device, x2, y2):
            return False
        waits = [MOVE_SETTLE_MS / 1000] * MOVE_VERIFY_COUNT
        for delay in waits:
            time.sleep(delay)
            img = adb_client.screencap(self.device)
            if img is None:
                continue
            if self._verify_our_move(img, r1, c1, r2, c2, piece):
                self._apply_move_result(img, r1, c1, r2, c2, piece)
                return True
        if self._is_mate_by_move(r1, c1, r2, c2, piece):
            return True
        print("校验失败：三次截图均未识别到走棋成功")
        return False

    def _is_mate_by_move(self, r1: int, c1: int, r2: int, c2: int, piece: str) -> bool:
        """校验失败时预判：我方着法是否已绝杀对方。

        绝杀后游戏进入结束动画，棋子无法识别导致校验失败。此时按内存布局假设着法已
        生效，用引擎探测对方是否无着法（绝杀/困毙）；确认后按走棋成功 + 对局结束处理。
        """
        if self.my_side is None:
            return False
        trial = [row[:] for row in self.board]
        trial[r1][c1] = None
        trial[r2][c2] = piece
        opp = "black" if self.my_side == "red" else "red"
        fen = fen_of_board(trial, self.my_side, to_move=opp)
        print("[对局结束判断] 校验失败，探测该着法是否绝杀对方……")
        try:
            move = engine.best_move(fen, ENGINE_MATE_PROBE_MS)
        except engine.EngineError:
            return False
        if move is not None:
            return False
        self.board[r1][c1] = None
        self.board[r2][c2] = piece
        self.has_moved = True
        self.pending_move = None
        self.game_over = True
        opp_cn = BLACK_CN if opp == "black" else RED_CN
        self.print_current(highlight=[(r1, c1), (r2, c2)])
        print(f"[对局结束] 我方绝杀，{opp_cn}方无路可走")
        return True

    def _verify_our_move(
        self, img: ndarray, r1: int, c1: int, r2: int, c2: int, piece: str
    ) -> bool:
        """校验我方走棋是否成功（终点可能已被对方吃掉）"""
        new_from = vision.analyze_cell(img, r1, c1, self.templates)
        new_to = vision.analyze_cell(img, r2, c2, self.templates)
        if new_from == piece:
            return False
        if new_to == piece:
            return True
        return new_to is not None and new_to != self.board[r2][c2]

    def _apply_move_result(
        self, img: ndarray, r1: int, c1: int, r2: int, c2: int, piece: str
    ) -> None:
        """走棋校验成功：根据截图内容分三类处理"""
        side = self.my_side or "red"
        self.board[r1][c1] = vision.analyze_cell(img, r1, c1, self.templates)
        self.board[r2][c2] = vision.analyze_cell(img, r2, c2, self.templates)
        print("走动成功")
        self.has_moved = True
        enemy = self._enemy_changes(img)
        if self.board[r2][c2] != piece:
            # 我方终点被吃：对方已完成吃子（完整走棋），按"我方+敌方完整走棋"处理
            print(f"对方吃掉了走到 {grid_to_square(r2, c2, side)} 的我方{piece_label(piece)}")
            enemy.append((r2, c2, piece, self.board[r2][c2]))
            for r, c, _old, new in enemy:
                self.board[r][c] = new
            self.prev_screenshot = img
            self._on_enemy_move(enemy)
            return
        if self._infer_move(enemy) is not None:
            # 我方走棋 + 敌方完整走棋：保存截图，分析走棋方案，自动走棋或显示菜单
            for r, c, _old, new in enemy:
                self.board[r][c] = new
            self.prev_screenshot = img
            self._on_enemy_move(enemy)
            return
        self.pending_move = None
        if enemy:
            # 我方走棋 + 敌方提子未落子：提示成功但不保存此截图
            print("检测到敌方正在走棋（棋子被提起但尚未落下），此截图不保存")
        else:
            # 只我方走棋：保存截图
            self.prev_screenshot = img
        self.print_current(highlight=[(r1, c1), (r2, c2)])
        if self._checkmate_probe():
            self.game_over = True
            return
        if self.auto_detect:
            self._wait_for_enemy_move()

    def _enemy_changes(self, img: ndarray) -> list[tuple[int, int, str | None, str | None]]:
        """相对上一张历史截图的布局变化（己方起止格已反映在布局中，比较时自动忽略）"""
        changes: list[tuple[int, int, str | None, str | None]] = []
        if self.prev_screenshot is None:
            return changes
        for r, c in sorted(vision.diff_cells(self.prev_screenshot, img)):
            old = self.board[r][c]
            new = vision.analyze_cell(img, r, c, self.templates)
            if old != new:
                changes.append((r, c, old, new))
        return changes

    def _move_failed_menu(self, r1: int, c1: int, r2: int, c2: int, piece: str) -> None:
        while True:
            print("重新走棋（回车） / 拉取新棋盘数据(2) / 初始化对局(3) / q 退出")
            raw = console.ask("> ").strip().lower()
            if raw == "":
                if self._attempt_move(r1, c1, r2, c2, piece):
                    return
                continue
            if raw in ("2", "拉取"):
                self.pull_new_data()
                return
            if raw in ("3", "初始化"):
                self.init_game()
                return
            if raw in ("q", "quit", "exit"):
                print("退出")
                raise SystemExit(0)


def main() -> None:
    printer.enable_vt()
    device = adb_client.select_device()
    if device is None:
        return
    if not adb_client.check_resolution(device):
        return
    App(device).run()
