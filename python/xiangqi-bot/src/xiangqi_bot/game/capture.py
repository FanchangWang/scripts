"""ADB 截图 + 透视矫正 + 和棋弹窗处理（IO 类）。

持有 homography 状态；截图/点击/弹窗决策通过回调与控制层解耦。
"""

from __future__ import annotations

import time
from collections.abc import Callable

from numpy import ndarray

from xiangqi_bot import adb_client, vision
from xiangqi_bot.adb_client import Device
from xiangqi_bot.config import MOVE_SETTLE_MS
from xiangqi_bot.game.state import DrawDecision

LogFn = Callable[[str, str], None]


class Capture:
    """ADB 截图/矫正/点击，和棋弹窗检测与点击。"""

    def __init__(
        self,
        device: Device,
        templates: dict[str, ndarray],
        log: LogFn,
        decide_draw: Callable[[], DrawDecision],
        should_continue: Callable[[], bool] | None = None,
    ) -> None:
        self.device = device
        self.templates = templates
        self._log = log
        self._decide_draw = decide_draw
        self._should_continue = should_continue or (lambda: True)
        self._homography: ndarray | None = None

    # ---------- 公开接口 ----------

    def grab(self) -> ndarray | None:
        """截图 → 处理和棋弹窗 → 矫正，返回矫正后棋盘图。"""
        img = self.screenshot()
        if img is None:
            return None
        img, _drawn = self._dismiss_draw(img)
        return self._correct(img)

    def screenshot(self) -> ndarray | None:
        """原始截图（供文字识别使用）。"""
        try:
            img = adb_client.screencap(self.device)
        except adb_client.AdbError as exc:
            self._log("error", str(exc))
            return None
        if img is None:
            self._log("error", "截图失败")
            return None
        return img

    def tap(self, r: int, c: int) -> bool:
        """点击网格格心（逆透视映射）。返回是否成功点击。"""
        if self._homography is None:
            self._log("error", "尚无棋盘坐标信息，请点击「开始棋局」")
            return False
        x, y = vision.tap_xy(self._homography, r, c)
        self._log("info", f"点击 ({x},{y})")
        return self.tap_xy(x, y)

    def tap_xy(self, x: int, y: int) -> bool:
        """点击屏幕绝对坐标。"""
        try:
            adb_client.tap(self.device, x, y)
        except adb_client.AdbError as exc:
            self._log("error", str(exc))
            return False
        return True

    def keyevent(self, keycode: int) -> bool:
        """发送 Android 按键事件。"""
        try:
            adb_client.keyevent(self.device, keycode)
        except adb_client.AdbError as exc:
            self._log("error", str(exc))
            return False
        return True

    # ---------- 内部 ----------

    def _correct(self, img: ndarray) -> ndarray | None:
        """对已截图做透视矫正（缓存 homography）。"""
        h, w = img.shape[:2]
        try:
            self._homography = vision.homography(w, h)
        except RuntimeError as exc:
            self._log("error", str(exc))
            return None
        return vision.correct_board(img)

    def _dismiss_draw(self, img: ndarray) -> tuple[ndarray, int]:
        """检测和棋弹窗（同意+拒绝两按钮同时存在），按决策回调点击，直到弹窗消失。

        返回 (最终截图, 点击次数)。
        """
        count = 0
        decision: DrawDecision | None = None
        while self._should_continue():
            matches = vision.find_draw_dialog(img)
            accept = next((m for m in matches if m[0] == "和棋_同意"), None)
            reject = next((m for m in matches if m[0] == "和棋_拒绝"), None)
            if accept is None or reject is None:
                break  # 两按钮不全，不是和棋页面
            if decision is None:
                decision = self._decide_draw()
            chosen = accept if decision == "accept" else reject
            _word, x, y, _score = chosen
            count += 1
            action_cn = "同意" if decision == "accept" else "拒绝"
            self._log("info", f"检测到和棋弹窗，点击{action_cn} ({x},{y})（第 {count} 次）")
            if not self.tap_xy(x, y):
                break  # 点击失败（如 ADB 断开），交由上层流程处理
            time.sleep(MOVE_SETTLE_MS / 1000)
            new_img = self.screenshot()
            if new_img is None:
                break
            img = new_img
        return img, count
