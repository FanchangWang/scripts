"""截图与透视矫正（mixin）。"""

import time

from numpy import ndarray

from xiangqi_bot import adb_client, vision
from xiangqi_bot.config import MOVE_SETTLE_MS
from xiangqi_bot.game._base import _SessionAttrs


class CaptureMixin(_SessionAttrs):
    """ADB 截图 + 透视矫正：_take_screenshot / _correct_from_raw / _capture / _dismiss_draw。"""

    def _take_screenshot(self) -> ndarray | None:
        """截图并返回原始图像，失败返回 None"""
        try:
            img = adb_client.screencap(self.device)
        except adb_client.AdbError as exc:
            self._log("error", str(exc))
            return None
        if img is None:
            self._log("error", "截图失败")
            return None
        return img

    def _correct_from_raw(self, img: ndarray) -> ndarray | None:
        """对已截图做透视矫正，返回矫正后棋盘图，失败返回 None"""
        h, w = img.shape[:2]
        try:
            self._homography = vision.homography(w, h)
        except RuntimeError as exc:
            self._log("error", str(exc))
            return None
        return vision.correct_board(img)

    def _capture(self) -> ndarray | None:
        """截图并透视矫正：返回矫正后的棋盘图，失败返回 None"""
        img = self._take_screenshot()
        if img is None:
            return None
        return self._correct_from_raw(img)

    def _dismiss_draw(self, img: ndarray) -> tuple[ndarray, int]:
        """检测并拒绝和棋弹窗，循环直到弹窗消失或用户主动中止。

        返回 (最终原始截图, 点击次数)。
        """
        count = 0
        while self._running and not self._interrupt.is_set() and not self.game_over:
            matches = vision.find_draw_dialog(img)
            reject = next((m for m in matches if m[0] == "拒绝"), None)
            if reject is None:
                break
            _word, x, y, _score = reject
            count += 1
            self._log("info", f"检测到和棋弹窗，点击拒绝 ({x},{y})（第 {count} 次）")
            try:
                adb_client.tap(self.device, x, y)
            except adb_client.AdbError as exc:
                self._log("error", str(exc))
                break
            time.sleep(MOVE_SETTLE_MS / 1000)
            new_img = self._take_screenshot()
            if new_img is None:
                break
            img = new_img
        return img, count
