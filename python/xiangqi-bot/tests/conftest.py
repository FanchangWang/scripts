"""共享 fixture：MockDevice、日志收集器、棋盘构造、截图读取。"""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import TYPE_CHECKING, Any

import cv2
import numpy as np
import pytest

from xiangqi_bot import vision
from xiangqi_bot.board import START_SQUARES, make_empty_board

if TYPE_CHECKING:
    pass

PROJECT_ROOT = Path(__file__).resolve().parent.parent
RAW_SCREENSHOTS = PROJECT_ROOT / "raw_screenshots"

DICT_VISION_PATCHES: dict[str, Callable[..., Any]] = {}


def _make_dict_vision() -> dict[str, Callable[..., Any]]:
    return {
        "analyze_cell": lambda corrected, r, c, templates: corrected["cells"].get((r, c)),
        "analyze_cell_with_priority": lambda corrected, r, c, templates, priority_id=None: (
            corrected["cells"].get((r, c), priority_id)
        ),
        "diff_cells": lambda prev, corrected: corrected["diff"],
        "analyze_board": lambda corrected, templates: corrected["board"],
        "find_gameover_text": lambda img, w=0, h=0: [],
        "tap_xy": lambda h, r, c: (50 + c * 100, 50 + r * 100),
    }


def raw_shot(name: str) -> np.ndarray:
    """读取 raw_screenshots/ 下的原始截图"""
    img = cv2.imdecode(np.fromfile(str(RAW_SCREENSHOTS / name), dtype=np.uint8), cv2.IMREAD_COLOR)
    assert img is not None, f"无法读取 {name}"
    return img


def full_board(side: str) -> list[list[str | None]]:
    """生成完整开局棋盘（按 my_side 翻转）"""
    b = make_empty_board()
    for pid, sqs in START_SQUARES.items():
        for r, c in sqs:
            tr, tc = (9 - r, c) if side == "black" else (r, c)
            b[tr][tc] = pid
    return b


def move_piece(b: list[list[str | None]], r1: int, c1: int, r2: int, c2: int) -> None:
    b[r2][c2] = b[r1][c1]
    b[r1][c1] = None


def frame(
    cells: dict[tuple[int, int], str | None],
    board: list[list[str | None]],
    diff: set[tuple[int, int]] | None = None,
) -> dict[str, Any]:
    """构造 dict-based mock 帧"""
    return {"cells": cells, "board": board, "diff": set(diff) if diff is not None else set()}


class LogCollector:
    """收集日志/状态/点击/shell/sleep 的容器"""

    def __init__(self) -> None:
        self.logs: list[str] = []
        self.state: dict[str, Any] = {}
        self.taps: list[tuple[int, int]] = []
        self.shells: list[str] = []
        self.sleeps: list[float] = []

    def log(self, kind: str, msg: str) -> None:
        self.logs.append(f"[{kind}] {msg}")

    def on_state(self, s: dict[str, Any]) -> None:
        self.state.clear()
        self.state.update(s)

    def sleep(self, sec: float) -> None:
        self.sleeps.append(sec)

    def clear(self) -> None:
        self.logs.clear()
        self.state.clear()
        self.taps.clear()
        self.shells.clear()
        self.sleeps.clear()


@pytest.fixture
def collector() -> LogCollector:
    """每次测试独立的日志/状态收集器"""
    return LogCollector()


class MockDevice:
    """Mock ADB 设备"""

    def __init__(self) -> None:
        self._queue: list[Any] = []

    def screencap(self) -> Any:
        assert self._queue, "不应再请求截图"
        return self._queue.pop(0)

    def input_tap(self, x: int, y: int) -> None:
        pass

    def shell(self, cmd: str) -> str:
        return ""


class MockDeviceWithRecord(MockDevice):
    """Mock ADB 设备（记录 tap/shell）"""

    def __init__(self) -> None:
        super().__init__()
        self.taps: list[tuple[int, int]] = []
        self.shells: list[str] = []

    def input_tap(self, x: int, y: int) -> None:
        self.taps.append(((x), (y)))

    def shell(self, cmd: str) -> str:
        self.shells.append(cmd)
        return ""


@pytest.fixture
def device() -> MockDevice:
    return MockDevice()


@pytest.fixture
def device_record() -> MockDeviceWithRecord:
    return MockDeviceWithRecord()


@pytest.fixture(autouse=True)
def _patch_dict_vision(monkeypatch: pytest.MonkeyPatch) -> None:
    """自动 patch vision 模块为 dict-based mock（测试结束后恢复）"""
    patches = _make_dict_vision()
    for attr, fn in patches.items():
        monkeypatch.setattr(vision, attr, fn)
