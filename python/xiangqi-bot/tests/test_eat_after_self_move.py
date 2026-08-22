"""我方走棋后敌方在落点反吃：验证不会误判对局结束、不会调 engine.is_mate。

复现实测场景：我方红兵 e8→e9 走一步，校验帧里敌方黑將 d9→e9 吃兵（n==3 变动）。
按用户方案，只在 n==2 且 infer 成功时才做绝杀探测；
其他 n==3/n==4 / 兜底等路径一律不探测，交给结算画面认输检测兜底。
"""

from __future__ import annotations

import time
from unittest.mock import MagicMock

import pytest

from xiangqi_bot.board import make_empty_board
from xiangqi_bot.game import session as game
from xiangqi_bot.game.state import Phase, Side

from .conftest import LogCollector, MockDevice


def _init_board() -> list[list[str | None]]:
    """构造残局：红兵 e8 + 黑將 d9 + 双方帥在原位。"""
    b = make_empty_board()
    b[9][4] = "r_K"
    b[8][4] = "r_P"
    b[9][3] = "b_k"
    b[7][0] = "b_p"
    b[2][0] = "r_P"
    return b


def _make_session(
    initial_board: list[list[str | None]],
    collector: LogCollector,
) -> game.GameSession:
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    s.state.board = [row[:] for row in initial_board]
    s.state.prev_board = [row[:] for row in initial_board]
    s.state.my_side = Side.RED
    s.state.turn = Side.RED
    s.state.initialized = True
    s._running = True
    s.state.lift_logged = False
    s.state.noisy_count = 0
    s.state.resign_streak = 0
    s.state.phase = Phase.MIDDLE
    s.state.game_over = False
    s._auto_next = False
    return s


def _build_after_frame(
    initial: list[list[str | None]],
) -> tuple[list[list[str | None]], list[tuple[int, int, str | None, str | None]]]:
    """应用我方 e8→e9 落子 + 敌方 d9→e9 吃兵，得到 n==3 变动的终局 board 与 updates。"""
    after = [row[:] for row in initial]
    updates: list[tuple[int, int, str | None, str | None]] = []

    updates.append((8, 4, after[8][4], None))
    after[8][4] = None

    updates.append((9, 3, after[9][3], None))
    after[9][3] = None

    updates.append((9, 4, initial[9][4], "b_k"))
    after[9][4] = "b_k"

    return after, updates


def test_self_eat_then_enemy_r2_eat_no_finish(
    collector: LogCollector,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """我方 A→B，敌方 C→B 吃我落点（n==3）：不应触发 game_over、不应调 engine.is_mate。"""

    initial = _init_board()
    after_board, updates = _build_after_frame(initial)
    s = _make_session(initial, collector)
    r1, c1 = 8, 4
    r2, c2 = 9, 4

    is_mate_calls: list[int] = []
    best_move_seq = iter(["e1e0", "a0a1"])
    engine_cls = s.engine.__class__

    def spy_best_move(self, fen, movetime_ms=1000):  # type: ignore[no-untyped-def]
        return next(best_move_seq), 0

    def spy_is_mate(self, fen, movetime_ms=200):  # type: ignore[no-untyped-def]
        is_mate_calls.append(1)
        pytest.fail("engine.is_mate 不应被调用（只有 n==2+infer 才探测）")

    monkeypatch.setattr(engine_cls, "best_move", spy_best_move)
    monkeypatch.setattr(engine_cls, "is_mate", spy_is_mate)

    attempt_calls: list[int] = []

    def fake_attempt(self_, sr, sc, dr, dc):  # type: ignore[no-untyped-def]
        attempt_calls.append(1)
        assert (sr, sc) == (r1, c1)
        assert (dr, dc) == (r2, c2)
        return True

    monkeypatch.setattr(type(s), "_attempt_move", fake_attempt)

    analyze_calls: list[int] = []

    def fake_grab():  # type: ignore[no-untyped-def]
        analyze_calls.append(1)
        return [row[:] for row in after_board], [*updates]

    monkeypatch.setattr(s, "_grab_board", fake_grab)

    noop_sleep = MagicMock()
    monkeypatch.setattr(time, "sleep", noop_sleep)

    result = s._do_move()

    assert result is True, (
        "_do_move 应返回 True（n==3 情况 2 命中 self_then_enemy），"
        f"最近日志：{collector.logs[-10:]}"
    )

    assert s.state.game_over is False, (
        f"敌方在落点吃我方棋子不应触发对局结束\n最近日志：{collector.logs[-20:]}"
    )

    assert s.state.turn == Side.RED, f"应用敌方走棋后应轮到我方 red，实际 {s.state.turn}"

    assert s.state.board[9][4] == "b_k", f"e9 应为黑將 b_k，实际 {s.state.board[9][4]}"
    assert s.state.board[8][4] is None, f"e8 应为空，实际 {s.state.board[8][4]}"
    assert s.state.board[9][3] is None, f"d9 应为空，实际 {s.state.board[9][3]}"
    assert s.state.board[9][4] == after_board[9][4], "最终 board 应与 after_board 一致"

    assert not is_mate_calls, "n!=2 且非 infer 成功时不应调 engine.is_mate"

    assert len(analyze_calls) >= 1, "至少进入 1 次校验帧分类"
    assert len(attempt_calls) >= 1, "至少调 1 次 _attempt_move"
