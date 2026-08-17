"""失败恢复流程（5 个）。"""

from __future__ import annotations

from typing import Any

import numpy as np

from xiangqi_bot.board import make_empty_board
from xiangqi_bot.game import session as game

from .conftest import LogCollector, MockDevice, frame


def _build_board(dropped_general: bool = False, drop_piece: bool = False) -> list[list[str | None]]:
    b = make_empty_board()
    if not dropped_general:
        b[0][4] = "b_k"
        b[9][4] = "r_K"
    b[7][4] = "r_P"
    if drop_piece:
        b[7][4] = None
    return b


def _make_session(collector: LogCollector) -> game.GameSession:
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    b = make_empty_board()
    b[0][4] = "b_k"
    b[9][4] = "r_K"
    b[7][4] = "r_P"
    s.board = b
    s.prev = object()
    s.my_side = "red"
    s._H = np.eye(3, dtype=float)
    s._turn = "red"
    s._running = True
    s.engine.is_mate = lambda fen, ms: False  # type: ignore[method-assign]
    return s


def test_recover_move_failure(collector: LogCollector) -> None:
    """5 个失败恢复场景顺序执行"""
    game.time.sleep = collector.sleep  # type: ignore[assignment]

    # 场景A：棋局未变化 -> 整步重试成功
    s = _make_session(collector)
    prev_board = _build_board()
    queue: list[dict[str, Any]] = [
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): None, (7, 5): "r_P"}, _build_board()),
    ]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    ok = s._attempt_move(7, 4, 7, 5, "r_P")
    assert ok is True, f"场景A 应重试成功，{collector.logs}"
    assert any("重试走棋：红兵 e2 -> f2" in m for m in collector.logs), collector.logs
    assert s.board[7][4] is None and s.board[7][5] == "r_P", "棋盘应已更新"
    assert s._turn == "black", "走后应轮到对方"

    # 场景B：棋子被提起 -> 只落子重试成功
    collector.clear()
    s = _make_session(collector)
    lifted_board = _build_board(drop_piece=True)
    queue = [
        frame({(7, 4): None}, lifted_board),
        frame({(7, 4): None}, lifted_board),
        frame({(7, 4): None}, lifted_board),
        frame({(7, 4): None}, lifted_board, {(7, 4)}),
        frame({(7, 4): None}, lifted_board, {(7, 4)}),
        frame({(7, 4): None, (7, 5): "r_P"}, _build_board()),
    ]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    ok = s._attempt_move(7, 4, 7, 5, "r_P")
    assert ok is True, f"场景B 应只落子重试成功，{collector.logs}"
    assert any("只落子" in m for m in collector.logs), collector.logs
    assert s.board[7][4] is None and s.board[7][5] == "r_P", "棋盘应已更新"

    # 场景C：对局已结束（我方将消失）-> 自动收局
    collector.clear()
    s = _make_session(collector)
    over_board = _build_board(dropped_general=True)
    queue = [
        frame({(7, 4): "r_P"}, over_board),
        frame({(7, 4): "r_P"}, over_board),
        frame({(7, 4): "r_P"}, over_board),
    ]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    ok = s._attempt_move(7, 4, 7, 5, "r_P")
    assert ok is True, "场景C 对局结束应返回 True"
    assert s.game_over is True, "场景C 应判定对局结束"
    assert any("检测对局是否结束" in m for m in collector.logs), collector.logs

    # 场景D：整步重试仍失败 -> 中止
    collector.clear()
    s = _make_session(collector)
    queue = [
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): "r_P"}, prev_board),
    ]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    ok = s._attempt_move(7, 4, 7, 5, "r_P")
    assert ok is False, f"场景D 重试仍失败应中止，{collector.logs}"
    assert any("仍校验失败" in m for m in collector.logs), collector.logs

    # 场景E：棋局有其它变化 -> 无法恢复
    collector.clear()
    s = _make_session(collector)
    changed_board = _build_board(drop_piece=True)
    changed_board[0][0] = "b_r"
    queue = [
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): "r_P"}, prev_board),
        frame({(7, 4): None, (0, 0): "b_r"}, changed_board, {(7, 4), (0, 0)}),
    ]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    ok = s._attempt_move(7, 4, 7, 5, "r_P")
    assert ok is False, f"场景E 其它变化应无法恢复，{collector.logs}"
    assert any("其它变化" in m for m in collector.logs), collector.logs
