"""自动下一局流程（mixin）。"""

import time

from numpy import ndarray

from xiangqi_bot import adb_client, vision
from xiangqi_bot.config import (
    GAMEOVER_BACK_WORDS,
    GAMEOVER_BUTTON_WORDS,
    GAMEOVER_RETRY_MAX,
    GAMEOVER_SCAN_INTERVAL_MS,
    GAMEOVER_SCAN_MAX,
    GAMEOVER_TAP_VERIFY_MS,
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
        """结算交互 + 等待摆棋（合并）。

        内部两模式：
          mode="scan"：扁平循环扫结算文字，按钮点击 / 遮罩返回键；
                       识别到「曾有结算文字退出 + 棋子出现」→ 切 mode="setup"。
          mode="setup"：沿用原 _wait_for_board_setup 逻辑——连续 2 帧 count 相同且相邻帧 diff_cells 无变动
                       才返回 corrected，避免摆棋动画半截误判。
                       setup 过程中若 count 变回 0（结算画面又回来 / 新弹窗），立即退回 "scan"
                       （重新扫描文字 + 重置 setup streak，避免把「结算瞬态空白」当摆棋）。
        """
        self._log("info", "开始扫描结算文字……")
        last_word: str | None = None
        retry_count = 0
        mode: str = "scan"
        # setup 模式状态
        setup_prev: ndarray | None = None
        setup_stable_count: int | None = None
        setup_streak = 0
        for _attempt in range(GAMEOVER_SCAN_MAX):
            if self._interrupt.is_set():
                return None
            if not self.auto_next_game:
                return None
            if mode == "scan":
                hit = self._scan_gameover_text()
                if hit is None:
                    if last_word is None:
                        self._log("info", "未识别到结算文字")
                        time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)
                        continue
                    corrected = self._capture()
                    if corrected is None:
                        self._log("info", "未识别到结算文字")
                        time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)
                        continue
                    board_now = vision.analyze_board(corrected, self.templates)
                    count = sum(cell is not None for row in board_now for cell in row)
                    if count > 0:
                        self._log(
                            "info",
                            f"未识别到结算文字，识别到 {count} 个棋子，切换为摆棋稳定检测",
                        )
                        mode = "setup"
                        setup_prev = corrected
                        setup_stable_count = count
                        setup_streak = 0
                        # 这是摆棋稳定第 1 帧（与 setup 首帧处理对齐）：
                        #   作为 prev_frame，下一帧和它比较 count 相同 + diff 空 == 2 帧稳定。
                    else:
                        self._log("info", "未识别到结算文字，棋盘为空")
                    time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)
                    continue
                # hit is not None：继续走结算交互分支
                word, x, y, is_button = hit
                if word != last_word:
                    last_word = word
                    retry_count = 0
                else:
                    last_word = word
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
                    time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)
                    continue
                # is_button == True：retry_count 已在前面 word == last_word 判断处累加
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
                time.sleep(GAMEOVER_TAP_VERIFY_MS / 1000)
                continue
            # mode == "setup"：摆棋稳定检测（完全同原 _wait_for_board_setup）
            corrected = self._capture()
            if corrected is None:
                time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)
                continue
            board_now = vision.analyze_board(corrected, self.templates)
            count = sum(cell is not None for row in board_now for cell in row)
            if count == 0:
                self._log(
                    "info", f"摆棋过程中棋子数归零（{setup_stable_count}→0），退回结算文字扫描"
                )
                mode = "scan"
                setup_prev = None
                setup_stable_count = None
                setup_streak = 0
                time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)
                continue
            changed = (
                setup_prev is None
                or count != setup_stable_count
                or bool(vision.diff_cells(setup_prev, corrected))
            )
            if changed:
                setup_stable_count = count
                setup_streak = 0
            else:
                setup_streak += 1
            setup_prev = corrected
            self._log(
                "info",
                f"等待摆棋完毕：识别到 {count} 个棋子（稳定 {setup_streak}/2）",
            )
            if setup_streak >= 2:
                return corrected
            time.sleep(GAMEOVER_SCAN_INTERVAL_MS / 1000)
        self._log(
            "warn",
            f"{GAMEOVER_SCAN_MAX} 次截图未完成结算交互 + 摆棋，"
            "未检测到摆棋完毕，中止自动下一局，请手动处理",
        )
        return None

    def _scan_gameover_text(
        self,
        img: ndarray | None = None,
    ) -> tuple[str, int, int, bool] | None:
        """模板匹配结算文字，返回 (文字, 屏幕x, 屏幕y, 是否按钮) 或 None。

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
