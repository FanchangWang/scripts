"""结算画面交互 + 等待摆棋（IO 类）。

扫描结算文字/按钮并点击或发返回键，等待棋盘摆棋稳定后返回矫正帧。
中断/开关状态通过回调与控制层解耦。
"""

from __future__ import annotations

import time
from collections.abc import Callable

from numpy import ndarray

from xiangqi_bot import adb_client, vision
from xiangqi_bot.adb_client import Device
from xiangqi_bot.config import (
    AUTO_NEXT_TIMEOUT_S,
    BOARD_STABLE_THRESHOLD,
    GAMEOVER_BACK_WORDS,
    GAMEOVER_BUTTON_WORDS,
    GAMEOVER_RETRY_MAX,
    GAMEOVER_SCAN_INTERVAL_MS,
)
from xiangqi_bot.game.capture import Capture, LogFn


class AutoNext:
    """结算文字交互 + 等待摆棋完毕。"""

    def __init__(
        self,
        device: Device,
        capture: Capture,
        templates: dict[str, ndarray],
        log: LogFn,
        should_continue: Callable[[], bool] | None = None,
        auto_next_enabled: Callable[[], bool] | None = None,
    ) -> None:
        self.device = device
        self.capture = capture
        self.templates = templates
        self._log = log
        self._should_continue = should_continue or (lambda: True)
        self._auto_next_enabled = auto_next_enabled or (lambda: True)

    def scan_and_wait(self) -> ndarray | None:
        """扫描结算文字 → 交互 → 等待摆棋稳定，返回矫正后棋盘帧。

        每次循环先延时，再扫结算文字：
          - 有文字：按钮点击 / 遮罩发返回键（重试上限 GAMEOVER_RETRY_MAX）
          - 无文字：截图分析棋盘，连续 BOARD_STABLE_THRESHOLD 帧棋盘相同则返回
        超过 AUTO_NEXT_TIMEOUT_S 秒未完成则中止。
        """
        self._log("info", "开始扫描结算文字……")
        last_word: str | None = None
        retry_count = 0
        prev_board: list[list[str | None]] | None = None
        stable_count = 0
        start_time = time.monotonic()
        while True:
            if not self._should_continue():
                return None
            if not self._auto_next_enabled():
                return None
            if time.monotonic() - start_time > AUTO_NEXT_TIMEOUT_S:
                self._log(
                    "error",
                    f"{AUTO_NEXT_TIMEOUT_S}秒未完成结算交互 + 摆棋，中止自动下一局，请手动处理",
                )
                return None

            time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)

            hit = self._scan_text()
            if hit is None:
                corrected = self.capture.grab()
                if corrected is None:
                    self._log("info", "未识别到结算文字")
                    continue
                board_now = vision.analyze_board(corrected, self.templates)
                count = sum(cell is not None for row in board_now for cell in row)
                if count > 0:
                    # 棋子出现 = ADB 操作已生效，清空重试状态
                    last_word = None
                    retry_count = 0
                    if count == 31:  # 31 枚棋子通常表示：敌方是红方 & 提子未落子
                        self._log("info", "识别到 31 个棋子，暂不处理")
                        prev_board = board_now
                        stable_count = 0
                        continue
                    if count == 32:
                        self._log("info", "识别到 32 个棋子，当做开局处理")
                        return corrected
                    if prev_board is not None:
                        if prev_board == board_now:
                            stable_count += 1
                            self._log(
                                "info",
                                f"等待摆棋完毕：识别到 {count} 个棋子"
                                f"（稳定 {stable_count}/{BOARD_STABLE_THRESHOLD}）",
                            )
                            if stable_count >= BOARD_STABLE_THRESHOLD:
                                return corrected
                        else:
                            prev_board = board_now
                            stable_count = 0
                            self._log(
                                "info",
                                f"等待摆棋完毕：识别到 {count} 个棋子（棋子变动，重置稳定计数）",
                            )
                    else:
                        prev_board = board_now
                        stable_count = 0
                        self._log(
                            "info",
                            f"未识别到结算文字，识别到 {count} 个棋子，开始摆棋稳定检测",
                        )
                else:
                    self._log("info", "未识别到结算文字，棋盘为空")
                continue

            # hit is not None：结算交互
            word, x, y, is_button = hit
            if word != last_word:
                last_word = word
                retry_count = 0
            retry_count += 1
            if is_button is False:
                if retry_count > GAMEOVER_RETRY_MAX:
                    self._log(
                        "error",
                        f"遮罩「{last_word}」发送返回键 {GAMEOVER_RETRY_MAX} 次仍无响应，"
                        "中止自动下一局，请手动处理",
                    )
                    return None
                prefix = "仍识别到文字" if retry_count > 1 else "识别到文字"
                self._log(
                    "info",
                    f"{prefix}「{last_word}」，发送返回键（第 {retry_count}/{GAMEOVER_RETRY_MAX} 次）",
                )
                if not self.capture.keyevent(adb_client.KEYCODE_BACK):
                    self._log("error", "自动下一局交互失败")
                    return None
                continue
            # is_button == True
            if retry_count > GAMEOVER_RETRY_MAX:
                self._log(
                    "error",
                    f"结算按钮「{last_word}」点击 {GAMEOVER_RETRY_MAX} 次仍无响应，"
                    "中止自动下一局，请手动处理",
                )
                return None
            prefix = "仍识别到结算按钮" if retry_count > 1 else "识别到结算按钮"
            self._log(
                "move",
                f"{prefix}「{last_word}」，点击 ({x},{y}) 开始下一局"
                f"（第 {retry_count}/{GAMEOVER_RETRY_MAX} 次）",
            )
            if not self.capture.tap_xy(x, y):
                self._log("error", "自动下一局交互失败")
                return None
            continue

    def _scan_text(self, img: ndarray | None = None) -> tuple[str, int, int, bool] | None:
        """模板匹配结算文字，返回 (文字, 屏幕x, 屏幕y, 是否按钮) 或 None。

        优先处理遮罩类（GAMEOVER_BACK_WORDS），再处理按钮类（GAMEOVER_BUTTON_WORDS）。
        """
        if img is None:
            img = self.capture.screenshot()
        if img is None:
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
