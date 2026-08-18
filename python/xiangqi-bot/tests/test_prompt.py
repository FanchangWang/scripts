"""轮次确认弹窗（2 个）。"""

from __future__ import annotations

import cv2
import numpy as np

from xiangqi_bot import adb_client
from xiangqi_bot.board import START_SQUARES, make_empty_board
from xiangqi_bot.game import session as game

from .conftest import RAW_SCREENSHOTS, LogCollector


def _full_start_board() -> list[list[str | None]]:
    b = make_empty_board()
    for piece_id, squares in START_SQUARES.items():
        for r, c in squares:
            b[r][c] = piece_id
    return b


class _MockDevice:
    def __init__(self, path: str) -> None:
        self.path = path

    def screencap(self) -> bytes:
        img = cv2.imdecode(np.fromfile(self.path, dtype=np.uint8), cv2.IMREAD_COLOR)
        ok, buf = cv2.imencode(".png", img)
        assert ok
        return buf.tobytes()

    def input_tap(self, x: int, y: int) -> None:
        pass

    def shell(self, cmd: str) -> str:
        return ""


def _patch_adb_screencap() -> None:
    """patch adb_client.screencap 使其像真实函数一样解码设备截图"""

    def mock_screencap(device: object) -> np.ndarray | None:
        data = device.screencap()  # type: ignore[union-attr]
        if data is None:
            return None
        if isinstance(data, np.ndarray) and data.ndim >= 2:
            return data
        return cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)

    adb_client.screencap = mock_screencap  # type: ignore[assignment]


def test_prompt_turn(collector: LogCollector) -> None:
    """2 个轮次确认场景顺序执行"""
    _patch_adb_screencap()
    board = _full_start_board()

    def run(answer: str) -> tuple[game.GameSession, list[int]]:
        dev = _MockDevice(str(RAW_SCREENSHOTS / "木_红_1080x2400.png"))
        s = game.GameSession(dev, collector.log, collector.on_state, None)
        s._detect_phase = lambda: "残局"  # type: ignore[method-assign]
        s._infer_turn = lambda: None  # type: ignore[method-assign]
        s._capture = lambda: {"board": board, "diff": set()}  # type: ignore[method-assign]
        s._confirm_start = lambda: answer == "start"  # type: ignore[method-assign]
        s._flow = lambda: None  # type: ignore[method-assign]
        s.start()
        return s

    # 场景1：回答"不" -> 不开始对弈
    s = run("no")
    assert s._status() == "stopped", f"期望 stopped，实际 {s._status()}"
    assert any("未开始对弈" in m for m in collector.logs), f"应有未开始日志，{collector.logs}"

    # 场景2：回答"开始" -> 启动 flow
    collector.clear()
    s2 = run("start")
    assert s2._running is True, f"「开始」应启动 flow，实际 running={s2._running}"
