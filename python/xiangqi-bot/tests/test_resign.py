"""认输计数（2 个）。"""

from __future__ import annotations

from typing import Any

from xiangqi_bot.board import make_empty_board
from xiangqi_bot.game import session as game

from .conftest import LogCollector, MockDevice


def _normal_board() -> list[list[str | None]]:
    b = make_empty_board()
    b[0][4] = "b_k"
    b[9][4] = "r_K"
    b[7][4] = "r_P"
    return b


def _build(drop_my_general: bool = False) -> list[list[str | None]]:
    b = make_empty_board()
    if not drop_my_general:
        b[9][4] = "r_K"
    b[0][4] = "b_k"
    b[7][4] = "r_P"
    return b


def _make_session(collector: LogCollector) -> game.GameSession:
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    s.board = _normal_board()
    s.my_side = "red"
    s._turn = "red"
    s._running = True
    s._lift_logged = False
    s.prev = object()
    return s


def test_resign_detection(collector: LogCollector) -> None:
    """2 个认输计数场景顺序执行"""
    game.time.sleep = collector.sleep  # type: ignore[assignment]

    # 场景1：瞬态 2 帧疑似结束 -> 恢复 -> 再连续 3 帧真结束
    s = _make_session(collector)
    over = _build(drop_my_general=True)
    normal = _normal_board()
    frames: list[dict[str, Any]] = [
        {"cells": {}, "board": over, "diff": set()},
        {"cells": {}, "board": over, "diff": set()},
        {"cells": {}, "board": normal, "diff": set()},
        {"cells": {}, "board": over, "diff": set()},
        {"cells": {}, "board": over, "diff": set()},
        {"cells": {}, "board": over, "diff": set()},
    ]
    queue = list(frames)
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    s._wait_for_enemy_move()
    assert s.game_over is True, f"连续疑似后应确认结束，{collector.logs}"
    assert any("对局结束" in m for m in collector.logs), collector.logs
    assert collector.sleeps.count(1.0) == 4, f"疑似帧应延时 4 次，实际 {collector.sleeps}"

    # 场景2：瞬态疑似 -> 恢复正常（敌方走完），不应误判结束
    collector.clear()
    s2 = _make_session(collector)
    frames2: list[dict[str, Any]] = [
        {"cells": {}, "board": over, "diff": set()},
        {"cells": {}, "board": over, "diff": set()},
        {"cells": {}, "board": normal, "diff": set()},
        {"cells": {}, "board": normal, "diff": set()},
        {"cells": {}, "board": normal, "diff": set()},
        {"cells": {}, "board": normal, "diff": set()},
        {"cells": {}, "board": normal, "diff": set()},
    ]
    queue2 = list(frames2)

    def cap2() -> dict[str, Any]:
        if not queue2:
            s2._running = False
            return {"cells": {}, "board": normal, "diff": set()}
        return queue2.pop(0)

    s2._capture = cap2  # type: ignore[method-assign]
    s2._wait_for_enemy_move()
    assert s2.game_over is False, f"瞬态疑似后恢复不应误判结束，{collector.logs}"
