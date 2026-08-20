"""自动下一局流程（mixin）。"""

import time

from numpy import ndarray

from xiangqi_bot import adb_client, vision
from xiangqi_bot.config import (
    AUTO_NEXT_TIMEOUT_S,
    BOARD_STABLE_THRESHOLD,
    GAMEOVER_BACK_WORDS,
    GAMEOVER_BUTTON_WORDS,
    GAMEOVER_RETRY_MAX,
    GAMEOVER_SCAN_INTERVAL_MS,
)
from xiangqi_bot.game._base import _SessionAttrs

RED_CN = "红"
BLACK_CN = "黑"


class AutoNextMixin(_SessionAttrs):
    """自动下一局：结算文字交互 -> 等待摆棋 -> 初始化下一局。"""

    def _auto_next_game(self) -> ndarray | None:
        """对局结束后自动下一局：结算文字交互 + 等待摆棋完毕（合并流程）。

        返回摆棋完毕的矫正图（用于 _flow 里调用 _init_from_corrected）；
        中断 / 结算失败 / 摆棋超时返回 None。流程期间：
        - self._auto_next 置位，网页按钮仍显示"中断棋局"
        - **不做**阶段C（红黑方判断/重置状态），一律下沉到 _flow 统一处理。
        """
        if self._interrupt.is_set():
            return None
        self._log("info", "开始自动下一局")
        self._auto_next = True
        self._emit()
        try:
            corrected = self._scan_gameover_interact()
            if corrected is None:
                return None
            keep_running = self._running
            self._reset()
            self._running = keep_running  # 保留 flow 循环运行状态（_reset 默认清零）
            if not self._init_from_corrected(corrected):
                return None
            if self.phase == "残局":
                self._turn = "red"
                side_cn = RED_CN if self.my_side == "red" else BLACK_CN
                self._log("ok", f"残局模式：我方为{side_cn}方、轮到我方先走")
            else:
                side_cn = RED_CN if self.my_side == "red" else BLACK_CN
                self._log("ok", f"下一局开始：我方为{side_cn}方，{self.phase}")
            return corrected
        finally:
            self._auto_next = False
            self._emit()

    def _scan_gameover_interact(self) -> ndarray | None:
        """结算交互 + 等待摆棋（单循环，无模式切换）。

        每次循环先延时，再扫结算文字：
          - 有文字：按钮点击 / 遮罩发返回键（重试上限 GAMEOVER_RETRY_MAX）
          - 无文字：截图分析棋盘，连续 BOARD_STABLE_THRESHOLD 帧棋盘相同则返回
        棋子出现时清空 last_word/retry_count（界面从文字→棋子 = ADB 操作已生效）。
        超过 AUTO_NEXT_TIMEOUT_S 秒未完成则中止。
        """
        self._log("info", "开始扫描结算文字……")
        last_word: str | None = None
        retry_count = 0
        prev_board: list[list[str | None]] | None = None
        stable_count = 0
        start_time = time.monotonic()
        while True:
            if self._interrupt.is_set():
                return None
            if not self.auto_next_game:
                return None
            if time.monotonic() - start_time > AUTO_NEXT_TIMEOUT_S:
                self._log(
                    "warn",
                    f"{AUTO_NEXT_TIMEOUT_S}秒未完成结算交互 + 摆棋，中止自动下一局，请手动处理",
                )
                return None

            time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)

            hit = self._scan_gameover_text()
            if hit is None:
                corrected = self._capture()
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
                try:
                    adb_client.keyevent(self.device, adb_client.KEYCODE_BACK)
                except adb_client.AdbError as exc:
                    self._log("error", f"自动下一局交互失败：{exc}")
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
            try:
                adb_client.tap(self.device, x, y)
            except adb_client.AdbError as exc:
                self._log("error", f"自动下一局交互失败：{exc}")
                return None
            continue

    def _scan_gameover_text(
        self,
        img: ndarray | None = None,
    ) -> tuple[str, int, int, bool] | None:
        """模板匹配结算文字，返回 (文字, 屏幕x, 屏幕y, 是否按钮) 或 None。

        优先处理遮罩类（GAMEOVER_BACK_WORDS），再处理按钮类（GAMEOVER_BUTTON_WORDS）。
        同一帧中若遮罩和按钮同时存在，优先返回遮罩（外层会先发返回键消除遮罩，
        下一帧再扫描到按钮时点击）。

        无 img 参数时自行截图（用于 `_scan_gameover_interact` 的扫描循环）。
        """
        if img is None:
            img = self._take_screenshot()
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
