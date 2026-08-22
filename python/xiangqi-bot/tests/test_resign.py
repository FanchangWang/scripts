"""认输检测测试：_update_resign。

覆盖场景：
- 双方将帅缺失 → suspect → 连续 RESIGN_CONFIRM_COUNT 帧 → confirmed
- 单方将帅缺失 → none（streak=0）
- 瞬态缺失（suspect 后恢复）→ streak 清零
"""

from __future__ import annotations

from xiangqi_bot.board import START_SQUARES, make_empty_board
from xiangqi_bot.config import RESIGN_CONFIRM_COUNT
from xiangqi_bot.game import session as game
from xiangqi_bot.game.state import Side

from .conftest import LogCollector, MockDevice


def _make_session(collector: LogCollector) -> game.GameSession:
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    s.state.my_side = Side.RED
    s.state.resign_streak = 0
    return s


def _full_board() -> list[list[str | None]]:
    b = make_empty_board()
    for pid, sqs in START_SQUARES.items():
        for r, c in sqs:
            b[r][c] = pid
    return b


def test_both_generals_missing_suspect(
    collector: LogCollector,
) -> None:
    """单帧双方将帅缺失 → suspect。"""
    s = _make_session(collector)
    empty = make_empty_board()

    result = s._update_resign(empty)
    assert result == "suspect"
    assert s.state.resign_streak == 1


def test_confirmed_after_streak(collector: LogCollector) -> None:
    """连续 RESIGN_CONFIRM_COUNT 帧 suspect → confirmed。"""
    s = _make_session(collector)
    empty = make_empty_board()

    for i in range(RESIGN_CONFIRM_COUNT - 1):
        result = s._update_resign(empty)
        assert result == "suspect", f"第 {i + 1} 帧应 suspect，实际 {result}"

    result = s._update_resign(empty)
    assert result == "confirmed"
    assert s.state.resign_streak == RESIGN_CONFIRM_COUNT


def test_one_general_present_none(collector: LogCollector) -> None:
    """单方将帅缺失（另一方在）→ none。"""
    s = _make_session(collector)
    board = make_empty_board()
    board[9][4] = "r_K"

    result = s._update_resign(board)
    assert result == "none"
    assert s.state.resign_streak == 0


def test_transient_clears_streak(collector: LogCollector) -> None:
    """suspect 后恢复 → streak 清零。"""
    s = _make_session(collector)
    empty = make_empty_board()
    full = _full_board()

    s._update_resign(empty)
    assert s.state.resign_streak == 1

    result = s._update_resign(full)
    assert result == "none"
    assert s.state.resign_streak == 0
