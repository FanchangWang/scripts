"""绝杀探测测试：_checkmate_probe。

覆盖场景：
- is_mate=True → game_over=True
- is_mate=False → game_over=False
- 引擎抛异常 → 降级为未绝杀
"""

from __future__ import annotations

import pytest

from xiangqi_bot.board import make_empty_board
from xiangqi_bot.game import session as game
from xiangqi_bot.game.state import Side

from .conftest import LogCollector, MockDevice


def _make_session(collector: LogCollector) -> game.GameSession:
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    s.state.my_side = Side.RED
    s.state.turn = Side.BLACK
    s._running = True
    s.state.game_over = False
    s.state.board = make_empty_board()
    s.state.board[9][4] = "r_K"
    s.state.board[0][4] = "b_k"
    s.state.prev_board = [row[:] for row in s.state.board]
    return s


def test_checkmate_true(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """is_mate=True → game_over=True。"""
    s = _make_session(collector)
    engine_cls = s.engine.__class__
    monkeypatch.setattr(engine_cls, "is_mate", lambda self, fen, ms: True)

    result = s._checkmate_probe()
    assert result is True
    assert s.state.game_over is True


def test_checkmate_false(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """is_mate=False → game_over=False。"""
    s = _make_session(collector)
    engine_cls = s.engine.__class__
    monkeypatch.setattr(engine_cls, "is_mate", lambda self, fen, ms: False)

    result = s._checkmate_probe()
    assert result is False
    assert s.state.game_over is False


def test_checkmate_engine_error(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """引擎抛异常 → 降级为未绝杀。"""
    s = _make_session(collector)
    engine_cls = s.engine.__class__

    def raise_is_mate(self, fen, ms):
        raise RuntimeError("引擎假异常")

    monkeypatch.setattr(engine_cls, "is_mate", raise_is_mate)

    result = s._checkmate_probe()
    assert result is False
    assert s.state.game_over is False
    assert any("引擎绝杀探测失败" in line for line in collector.logs)
