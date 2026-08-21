"""截图与透视矫正（mixin）。"""

import time

from numpy import ndarray

from xiangqi_bot import adb_client, vision
from xiangqi_bot.config import DRAW_REJECT_CP, MOVE_SETTLE_MS
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
        """检测和棋弹窗（同意+拒绝两按钮同时存在），按评估分决定同意/拒绝。

        首次命中时决策一次（弹窗期间局面不变），循环点击直到弹窗消失或中止。
        返回 (最终原始截图, 点击次数)。
        """
        count = 0
        decision: str | None = None
        while self._running and not self._interrupt.is_set() and not self.game_over:
            matches = vision.find_draw_dialog(img)
            accept = next((m for m in matches if m[0] == "和棋_同意"), None)
            reject = next((m for m in matches if m[0] == "和棋_拒绝"), None)
            if accept is None or reject is None:
                break  # 两按钮不全，不是和棋页面
            if decision is None:
                decision = self._draw_decision()
            _word, x, y, _score = accept if decision == "同意" else reject
            count += 1
            self._log("info", f"检测到和棋弹窗，点击{decision} ({x},{y})（第 {count} 次）")
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

    def _draw_decision(self) -> str:
        """根据我方最近一次走棋的引擎评估分决定同意/拒绝和棋。

        复用 _compute_move 缓存的 _last_eval_score（弹窗距我方上一步最多差敌方一步棋，
        可接受），不再额外搜索。my_score > DRAW_REJECT_CP 拒绝，否则同意。
        """
        my_score = self._last_eval_score
        if my_score > DRAW_REJECT_CP:
            self._log("info", f"我方占优（{my_score}cp），拒绝和棋")
            return "拒绝"
        self._log("info", f"均势/劣势（{my_score}cp），同意和棋")
        return "同意"
