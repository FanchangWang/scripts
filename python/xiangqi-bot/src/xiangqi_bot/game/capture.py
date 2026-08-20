"""截图与透视矫正（mixin）。"""

from numpy import ndarray

from xiangqi_bot import adb_client, vision
from xiangqi_bot.game._base import _SessionAttrs


class CaptureMixin(_SessionAttrs):
    """ADB 截图 + 透视矫正：_take_screenshot / _correct_from_raw / _capture。"""

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
