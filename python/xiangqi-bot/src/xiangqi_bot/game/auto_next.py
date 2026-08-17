# ty: ignore[unresolved-attribute]  # mixin：属性在 GameSession 中定义
"""自动下一局流程（mixin）。"""

import threading
import time

from numpy import ndarray

from xiangqi_bot import adb_client, vision
from xiangqi_bot.adb_client import Device
from xiangqi_bot.config import (
    ENDGAME_MODE_PIECE_COUNT,
    GAMEOVER_BACK_WORDS,
    GAMEOVER_BUTTON_WORDS,
    GAMEOVER_RETRY_MAX,
    GAMEOVER_SCAN_INTERVAL_MS,
    GAMEOVER_SCAN_MAX,
    GAMEOVER_TAP_VERIFY_MS,
)

RED_CN = "红"
BLACK_CN = "黑"


class AutoNextMixin:
    """自动下一局：结算文字交互 -> 等待摆棋 -> 初始化下一局。"""

    _auto_next: bool
    _running: bool
    game_over: bool
    auto_next_game: bool
    my_side: str | None
    _turn: str | None
    phase: str | None
    board: list[list[str | None]]
    prev: ndarray | None
    pending_move: tuple[str, str] | None
    _resign_streak: int
    _lift_logged: bool
    _noisy_count: int
    _highlight: list[tuple[int, int]]
    _last_move: str | None
    templates: dict[str, ndarray]
    device: Device
    _interrupt: threading.Event

    def _auto_next_game(self) -> bool:
        """对局结束后自动开始下一局。

        阶段A 扫描结算文字并 adb 交互（按钮点击须校验+重试，文字/遮罩类发返回键后继续扫描），
        阶段B 等待摆棋完毕（棋子数稳定且无格子变动，防动画误判），
        阶段C 重新初始化并开始对弈。任一阶段超时/失败返回 False（保持结束状态）。
        流程期间 self._auto_next 置位，网页端按钮状态保持不变（仍显示"中断棋局"）。
        """
        self._log("info", "检测到对局结束，开始自动下一局")
        self._auto_next = True
        self._emit()
        try:
            if not self._scan_gameover_interact():
                return False
            corrected = self._wait_for_board_setup()
            if corrected is None:
                self._log(
                    "warn",
                    f"{GAMEOVER_SCAN_MAX} 次截图未检测到摆棋完毕，中止自动下一局，请手动处理",
                )
                return False
            return self._init_next_game(corrected)
        finally:
            self._auto_next = False
            self._emit()

    def _scan_gameover_interact(self) -> bool:
        """阶段A：扫描结算文字并交互。

        扁平循环：按钮点击、遮罩返回键共用同一层循环。
        不同文字出现时自动重置计数器（无需分别跟踪按钮/遮罩）。
        点击按钮后，下一帧若该按钮消失（无论出现其它按钮/遮罩/无内容），视为成功。
        """
        self._log("info", "开始扫描结算文字……")
        last_word: str | None = None
        retry_count = 0
        just_clicked = False
        for _attempt in range(GAMEOVER_SCAN_MAX):
            if self._interrupt.is_set():
                return False
            if not self.auto_next_game:
                return False
            hit = self._scan_gameover_text()
            if just_clicked:
                just_clicked = False
                if hit is None:
                    return True
                if retry_count >= GAMEOVER_RETRY_MAX:
                    self._log(
                        "error",
                        f"结算按钮「{last_word}」点击 {GAMEOVER_RETRY_MAX} 次仍无响应，"
                        "中止自动下一局，请手动处理",
                    )
                    return False
                self._log(
                    "warn",
                    f"点击后仍识别到结算按钮「{last_word}」，延时复检后重试",
                )
            if hit is None:
                time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)
                continue
            word, x, y, is_button = hit
            if word != last_word:
                last_word = word
                retry_count = 0
            retry_count += 1
            if not is_button:
                if retry_count > GAMEOVER_RETRY_MAX:
                    self._log(
                        "error",
                        f"遮罩「{word}」发送返回键 {GAMEOVER_RETRY_MAX} 次仍无响应，"
                        "中止自动下一局，请手动处理",
                    )
                    return False
                self._log(
                    "info",
                    f"识别到文字「{word}」，发送返回键（第 {retry_count}/{GAMEOVER_RETRY_MAX} 次）",
                )
                try:
                    adb_client.keyevent(self.device, adb_client.KEYCODE_BACK)
                except adb_client.AdbError as exc:
                    self._log("error", f"自动下一局交互失败：{exc}")
                    return False
                time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)
                continue
            if retry_count > GAMEOVER_RETRY_MAX:
                self._log(
                    "error",
                    f"结算按钮「{word}」点击 {GAMEOVER_RETRY_MAX} 次仍无响应，"
                    "中止自动下一局，请手动处理",
                )
                return False
            self._log(
                "move",
                f"识别到结算按钮「{word}」，点击 ({x},{y}) 开始下一局"
                f"（第 {retry_count}/{GAMEOVER_RETRY_MAX} 次）",
            )
            try:
                adb_client.tap(self.device, x, y)
            except adb_client.AdbError as exc:
                self._log("error", f"自动下一局交互失败：{exc}")
                return False
            just_clicked = True
            time.sleep(GAMEOVER_TAP_VERIFY_MS / 1000)
        self._log(
            "warn",
            f"{GAMEOVER_SCAN_MAX} 次截图未识别到结算文字，中止自动下一局，请手动处理",
        )
        return False

    def _scan_gameover_text(self) -> tuple[str, int, int, bool] | None:
        """截图模板匹配结算文字，返回 (文字, 屏幕x, 屏幕y, 是否按钮) 或 None"""
        try:
            img = adb_client.screencap(self.device)
        except adb_client.AdbError as exc:
            self._log("error", str(exc))
            return None
        if img is None:
            self._log("error", "截图失败")
            return None
        matches = vision.find_gameover_text(img)
        for word in GAMEOVER_BACK_WORDS:
            hits = [m for m in matches if m[0] == word]
            if hits:
                _w, x, y, _score = max(hits, key=lambda m: m[3])
                return (word, x, y, False)
        for word in GAMEOVER_BUTTON_WORDS:
            hits = [m for m in matches if m[0] == word]
            if hits:
                _w, x, y, _score = max(hits, key=lambda m: m[2])
                return (word, x, y, True)
        return None

    def _wait_for_board_setup(self) -> ndarray | None:
        """等待下一局摆棋完毕：棋子数连续 2 帧稳定且相邻帧无格子变动"""
        prev_frame: ndarray | None = None
        stable_count: int | None = None
        stable_streak = 0
        for _ in range(GAMEOVER_SCAN_MAX):
            if self._interrupt.is_set():
                return None
            corrected = self._capture()
            if corrected is None:
                time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)
                continue
            if self._interrupt.is_set():
                return None
            board_now = vision.analyze_board(corrected, self.templates)
            count = sum(cell is not None for row in board_now for cell in row)
            changed = (
                count == 0
                or prev_frame is None
                or count != stable_count
                or bool(vision.diff_cells(prev_frame, corrected))
            )
            if changed:
                stable_count, stable_streak = count, 0
            else:
                stable_streak += 1
            prev_frame = corrected
            self._log("info", f"等待摆棋完毕：识别到 {count} 个棋子（稳定 {stable_streak}/2）")
            if stable_streak >= 2:
                return corrected
            time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)
        return None

    def _init_next_game(self, corrected: ndarray) -> bool:
        """摆棋完毕后初始化下一局：判断棋局模式/红黑方/轮次，重置状态，开始对弈。

        残局模式（棋子 < ENDGAME_MODE_PIECE_COUNT，如「下一关」）固定为我方红方、
        轮到我方先走；常规新局（≥31 颗棋子）按将/帥位置判断红黑方，红方先行
        （若红方已抢先走棋，_infer_turn 会给出对方先走）。
        """
        board_now = vision.analyze_board(corrected, self.templates)
        count = sum(cell is not None for row in board_now for cell in row)
        self.board = board_now
        self.prev = corrected
        self.pending_move = None
        self.game_over = False
        self._resign_streak = 0
        self._lift_logged = False
        self._noisy_count = 0
        self._highlight = []
        self._last_move = None
        if count < ENDGAME_MODE_PIECE_COUNT:
            self.my_side = "red"
            self._turn = "red"
            self.phase = "残局"
            self._log("info", "残局模式：我方为红方、轮到我方先走，开始自动对弈")
        else:
            side = self._detect_side()
            if side is None:
                self._log("error", "无法判断我方红黑方，中止自动下一局")
                self._emit()
                return False
            self.my_side = side
            self.phase = self._detect_phase()
            self._turn = self._infer_turn() or "red"
            side_cn = RED_CN if side == "red" else BLACK_CN
            turn_cn = RED_CN if self._turn == "red" else BLACK_CN
            self._log(
                "info",
                f"下一局开始：我方为{side_cn}方、{self.phase}，轮到{turn_cn}方，开始自动对弈",
            )
        self._emit()
        return True
