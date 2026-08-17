"""吃子校验场景（4 个）。"""

from __future__ import annotations

import numpy as np

from xiangqi_bot.board import fen_of_board, make_empty_board
from xiangqi_bot.game import session as game

from .conftest import LogCollector, MockDevice, frame


def _real_board() -> list[list[str | None]]:
    b = make_empty_board()
    b[0][4] = "b_k"
    b[9][4] = "r_K"
    b[7][3] = "r_C"
    b[0][3] = "b_a"
    b[1][4] = "b_a"
    return b


def _make_session(collector: LogCollector) -> game.GameSession:
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    b = make_empty_board()
    b[0][4] = "b_k"
    b[9][4] = "r_K"
    b[7][3] = "r_C"
    b[0][3] = "b_a"
    b[1][4] = "b_a"
    s.board = b
    s.prev = object()
    s.my_side = "red"
    s._H = np.eye(3, dtype=float)
    s._turn = "red"
    s._running = True
    s.engine.is_mate = lambda fen, ms: False
    s.engine.best_move = lambda fen: "e2e3"
    return s


def test_capture(collector: LogCollector) -> None:
    """4 个吃子场景顺序执行"""
    game.time.sleep = collector.sleep  # type: ignore[assignment]

    # 场景1：干净吃子走棋校验成功
    s = _make_session(collector)
    f = frame({(7, 3): None, (0, 3): "r_C"}, _real_board(), {(7, 3), (0, 3)})
    ok = s._verify_our_move(f, 7, 3, 0, 3, "r_C")
    assert ok is True, "干净吃子走棋应校验成功"

    # 场景2：我方吃子后被敌方反吃（黑士 e8 -> d9 吃我方红炮）
    collector.clear()
    recap = frame(
        {(7, 3): None, (0, 3): "b_a", (1, 4): None},
        _real_board(),
        {(7, 3), (0, 3), (1, 4)},
    )
    s = _make_session(collector)
    ok = s._verify_our_move(recap, 7, 3, 0, 3, "r_C")
    assert ok is True, f"吃子后被反吃也应判走棋成功，{collector.logs}"
    # 走全流程
    collector.clear()
    s = _make_session(collector)
    dev = s.device  # type: ignore[assignment]
    dev._queue = [recap, recap]
    s._capture = lambda: dev._queue.pop(0)  # type: ignore[method-assign]
    ok = s._attempt_move(7, 3, 0, 3, "r_C")
    assert ok is True, f"反吃场景 _attempt_move 应成功，{collector.logs}"
    assert s.board[7][3] is None and s.board[0][3] == "b_a" and s.board[1][4] is None, (
        "反吃后内存棋盘应：d2 空、d9 士、e8 空"
    )
    assert any("走动成功" in m for m in collector.logs), collector.logs
    assert any("对方吃掉了走到 d9 的我方红炮" in m for m in collector.logs), collector.logs
    assert any("黑方走士：e8 -> d9（吃红炮）" in m for m in collector.logs), collector.logs

    # 场景3：吃子途中（终点仍是敌方棋子）不误判成功，落子后成功
    collector.clear()
    s = _make_session(collector)
    mid_drag = frame({(7, 3): None}, _real_board(), {(7, 3)})
    ok = s._verify_our_move(mid_drag, 7, 3, 0, 3, "r_C")
    assert ok is False, "吃子途中不应判成功"
    landed = frame({(7, 3): None, (0, 3): "r_C"}, _real_board(), {(7, 3), (0, 3)})
    ok = s._verify_our_move(landed, 7, 3, 0, 3, "r_C")
    assert ok is True, "落子后应判成功"

    # 场景4：走棋日志包含吃子说明
    collector.clear()
    s = _make_session(collector)
    s.pending_move = (fen_of_board(s.board, s.my_side), "d2d9")
    dev = s.device  # type: ignore[assignment]
    dev._queue = [frame({(7, 3): None, (0, 3): "r_C"}, _real_board(), {(7, 3), (0, 3)})]
    s._capture = lambda: dev._queue.pop(0)  # type: ignore[method-assign]
    ok = s.move()
    assert ok is True, "move() 应成功"
    assert any("走棋 d2d9：红炮 d2 -> d9（吃黑士）" in m for m in collector.logs), collector.logs
