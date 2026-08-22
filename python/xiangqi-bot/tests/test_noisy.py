"""敌方走棋检测测试：_wait_for_enemy_move 噪声过滤与认输兜底。

覆盖场景：
- n==2 敌方完整走棋 → 提交并返回
- n==1 敌方提子 → 提示一次"检测到敌方提起棋子"
- n==0 → 继续循环
- n==1 非提子（我方棋子变化）→ 噪声，连续 ENEMY_NOISY_MAX 帧暂停
- 认输 confirmed（双方将帅缺失连续 RESIGN_CONFIRM_COUNT 帧）→ 对局结束
- 瞬态将帅缺失（1 帧后恢复）→ 不误判
"""

from __future__ import annotations

import time
from unittest.mock import MagicMock

import pytest

from xiangqi_bot.board import START_SQUARES, make_empty_board
from xiangqi_bot.config import ENEMY_NOISY_MAX, RESIGN_CONFIRM_COUNT
from xiangqi_bot.game import session as game
from xiangqi_bot.game.state import Phase, Side

from .conftest import LogCollector, MockDevice


def _make_session(collector: LogCollector, board=None) -> game.GameSession:
    """构造已初始化的 session（红方中局，轮到黑方走）。"""
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    s.state.my_side = Side.RED
    s.state.turn = Side.BLACK
    s._running = True
    s.state.phase = Phase.MIDDLE
    s.state.game_over = False
    s._auto_next = False
    s.state.resign_streak = 0
    s.state.lift_logged = False
    s.state.noisy_count = 0
    b = board if board is not None else make_empty_board()
    s.state.board = [row[:] for row in b]
    s.state.prev_board = [row[:] for row in b]
    return s


def _patch_sleep(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(time, "sleep", MagicMock())


def _queue_frames(
    monkeypatch: pytest.MonkeyPatch,
    s: game.GameSession,
    frames: list[tuple[list, list]],
) -> None:
    it = iter(frames)
    monkeypatch.setattr(s, "_grab_board", lambda: next(it))


def _full_board() -> list[list[str | None]]:
    b = make_empty_board()
    for pid, sqs in START_SQUARES.items():
        for r, c in sqs:
            b[r][c] = pid
    return b


def test_enemy_moved_n2(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """n==2 敌方完整走棋 → 提交并返回。"""
    b = _full_board()
    s = _make_session(collector, b)
    _patch_sleep(monkeypatch)

    after = [row[:] for row in b]
    after[7][7] = None
    after[7][4] = "b_c"
    updates = [(7, 7, "b_c", None), (7, 4, None, "b_c")]
    _queue_frames(monkeypatch, s, [(after, updates)])

    s._wait_for_enemy_move()

    assert s.state.board[7][7] is None
    assert s.state.board[7][4] == "b_c"
    assert s.state.turn == Side.RED
    assert not any("敌方提起" in line for line in collector.logs)


def test_enemy_lifted_n1(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """n==1 敌方提子 → 提示一次，下一帧恢复后复位。"""
    b = _full_board()
    s = _make_session(collector, b)
    _patch_sleep(monkeypatch)

    lifted = [row[:] for row in b]
    lifted[7][7] = None
    lifted_updates = [(7, 7, "b_c", None)]
    same = [row[:] for row in b]
    after = [row[:] for row in b]
    after[7][7] = None
    after[7][4] = "b_c"
    moved_updates = [(7, 7, "b_c", None), (7, 4, None, "b_c")]
    _queue_frames(
        monkeypatch,
        s,
        [
            (lifted, lifted_updates),
            (same, []),
            (after, moved_updates),
        ],
    )

    s._wait_for_enemy_move()

    lift_logs = [line for line in collector.logs if "敌方提起" in line]
    assert len(lift_logs) == 1, f"应只提示一次提起，实际 {len(lift_logs)} 次"
    assert s.state.board[7][4] == "b_c"
    assert s.state.turn == Side.RED


def test_n0_continues(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """n==0 继续循环，不中止。"""
    b = _full_board()
    s = _make_session(collector, b)
    _patch_sleep(monkeypatch)

    same = [row[:] for row in b]
    after = [row[:] for row in b]
    after[7][7] = None
    after[7][4] = "b_c"
    _queue_frames(
        monkeypatch,
        s,
        [
            (same, []),
            (same, []),
            (after, [(7, 7, "b_c", None), (7, 4, None, "b_c")]),
        ],
    )

    s._wait_for_enemy_move()
    assert s.state.turn == Side.RED


def test_noisy_max_pauses(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """连续 ENEMY_NOISY_MAX 帧噪声（我方棋子变化，非敌方提子）→ 暂停自动对弈。"""
    b = _full_board()
    s = _make_session(collector, b)
    _patch_sleep(monkeypatch)

    noisy = [row[:] for row in b]
    noisy[5][5] = None
    noisy_updates = [(5, 5, "r_P", None)]
    _queue_frames(monkeypatch, s, [(noisy, noisy_updates)] * ENEMY_NOISY_MAX)

    s._wait_for_enemy_move()

    assert s._running is False
    assert any("暂停" in line for line in collector.logs)


def test_resign_confirmed(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """双方将帅缺失连续 RESIGN_CONFIRM_COUNT 帧（n>0 触发认输检测）→ 对局结束。"""
    b = _full_board()
    s = _make_session(collector, b)
    _patch_sleep(monkeypatch)

    empty = make_empty_board()
    empty[5][5] = "b_p"
    empty[5][6] = "b_p"
    empty[5][7] = "b_p"
    updates = [
        (5, 5, None, "b_p"),
        (5, 6, None, "b_p"),
        (5, 7, None, "b_p"),
    ]
    _queue_frames(monkeypatch, s, [(empty, updates)] * RESIGN_CONFIRM_COUNT)

    s._wait_for_enemy_move()

    assert s.state.game_over is True
    assert any("结束" in line for line in collector.logs)


def test_resign_transient_not_triggered(
    collector: LogCollector, monkeypatch: pytest.MonkeyPatch
) -> None:
    """瞬态将帅缺失（1 帧后恢复）→ 不误判对局结束。"""
    b = _full_board()
    s = _make_session(collector, b)
    _patch_sleep(monkeypatch)

    empty = make_empty_board()
    empty[5][5] = "b_p"
    empty[5][6] = "b_p"
    empty[5][7] = "b_p"
    suspect_updates = [(5, 5, None, "b_p"), (5, 6, None, "b_p"), (5, 7, None, "b_p")]

    same = [row[:] for row in b]
    same[5][5] = None
    none_updates = [(5, 5, "r_P", None)]

    after = [row[:] for row in b]
    after[7][7] = None
    after[7][4] = "b_c"
    moved_updates = [(7, 7, "b_c", None), (7, 4, None, "b_c")]

    _queue_frames(
        monkeypatch,
        s,
        [
            (empty, suspect_updates),
            (same, none_updates),
            (after, moved_updates),
        ],
    )

    s._wait_for_enemy_move()
    assert s.state.game_over is False
    assert s.state.turn == Side.RED
