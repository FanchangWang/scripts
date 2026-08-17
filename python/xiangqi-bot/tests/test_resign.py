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


def _both_generals_missing() -> list[list[str | None]]:
    b = make_empty_board()
    b[7][4] = "r_P"
    return b


def _one_general_missing() -> list[list[str | None]]:
    b = make_empty_board()
    b[9][4] = "r_K"
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

    # 场景1：双方将/帥同时缺失连续 3 帧 -> 确认对局结束
    s = _make_session(collector)
    both_missing = _both_generals_missing()
    normal = _normal_board()
    frames: list[dict[str, Any]] = [
        {"cells": {}, "board": both_missing, "diff": set()},
        {"cells": {}, "board": both_missing, "diff": set()},
        {"cells": {}, "board": both_missing, "diff": set()},
    ]
    queue = list(frames)
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    s._wait_for_enemy_move()
    assert s.game_over is True, f"连续 3 帧双方将帅缺失应确认结束，{collector.logs}"
    assert any("对局结束" in m for m in collector.logs), collector.logs
    assert collector.sleeps.count(1.0) == 2, f"疑似帧应延时 2 次，实际 {collector.sleeps}"

    # 场景2：瞬态 2 帧双方将帅缺失 -> 恢复正常（敌方走完），不应误判结束
    collector.clear()
    s2 = _make_session(collector)
    frames2: list[dict[str, Any]] = [
        {"cells": {}, "board": both_missing, "diff": set()},
        {"cells": {}, "board": both_missing, "diff": set()},
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
    assert s2.game_over is False, f"瞬态双方将帅缺失后恢复不应误判结束，{collector.logs}"

    # 场景3：单方将/帥缺失（走棋动画遮挡）-> 不应触发 suspect
    collector.clear()
    s3 = _make_session(collector)
    one_missing = _one_general_missing()
    frames3: list[dict[str, Any]] = [
        {"cells": {}, "board": one_missing, "diff": set()},
        {"cells": {}, "board": one_missing, "diff": set()},
        {"cells": {}, "board": normal, "diff": set()},
        {"cells": {}, "board": normal, "diff": set()},
    ]
    queue3 = list(frames3)

    def cap3() -> dict[str, Any]:
        if not queue3:
            s3._running = False
            return {"cells": {}, "board": normal, "diff": set()}
        return queue3.pop(0)

    s3._capture = cap3  # type: ignore[method-assign]
    s3._wait_for_enemy_move()
    assert s3.game_over is False, f"单方将帅缺失不应误判结束，{collector.logs}"
    assert not any("suspect" in m.lower() for m in collector.logs), (
        f"单方将帅缺失不应触发 suspect，{collector.logs}"
    )
