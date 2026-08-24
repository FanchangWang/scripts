"""走棋校验测试：_verify 分类逻辑 + _do_move 重试流程。

覆盖场景：
- n==2 干净走棋（infer 命中）→ DONE_OK
- n==3 敌方在终点反吃（classifier 情况2）→ DONE_OK
- n==4 我方+敌方同时走 → DONE_OK
- n==1 提起未落（最后一帧命中）→ LIFTED_ONLY
- n==0 全帧不动 → STATIONARY（建议外层重走）
- n==1 非提子 → TRANSIENT（外层中止）
- n>4 变动过多 → TRANSIENT
- _do_move 整步重试成功（stationary → 第2次成功）
- _do_move 提起未落补点成功
- _do_move 失败中止
- _do_move 走棋成功 + 绝杀探测
"""

from __future__ import annotations

import time
from unittest.mock import MagicMock

import numpy as np
import pytest

from xiangqi_bot.board import make_empty_board
from xiangqi_bot.config import MOVE_VERIFY_COUNT
from xiangqi_bot.game import session as game
from xiangqi_bot.game.state import Phase, Side, VerifyOutcome

from .conftest import LogCollector, MockDevice


def _make_session(collector: LogCollector, board=None) -> game.GameSession:
    """构造已初始化的 session（红方残局）。"""
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    s.capture._homography = np.eye(3)
    s.state.my_side = Side.RED
    s.state.turn = Side.RED
    s.state.initialized = True
    s._running = True
    s.state.phase = Phase.ENDGAME
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
    """Mock _grab_board 返回帧队列 (board, changes)。"""
    it = iter(frames)
    monkeypatch.setattr(s, "_grab_board", lambda: next(it))


# ============================================================
# _verify 直接测试
# ============================================================


def test_n2_clean_move(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """n==2 干净走棋：infer 命中 → DONE_OK。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[0][3] = "b_r"
    s = _make_session(collector, b)
    _patch_sleep(monkeypatch)

    after = [row[:] for row in b]
    after[7][3] = None
    after[0][3] = "r_R"
    updates = [(7, 3, "r_R", None), (0, 3, "b_r", "r_R")]
    _queue_frames(monkeypatch, s, [(after, updates)])

    result = s._verify(7, 3, 0, 3, "r_R")
    assert result == VerifyOutcome.DONE_OK, f"期望 DONE_OK，实际 {result}"
    assert s.state.board[7][3] is None
    assert s.state.board[0][3] == "r_R"
    assert s.state.turn == Side.BLACK


def test_n3_enemy_eat_at_dest(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """n==3 情况2：敌方在终点吃我落子 → DONE_OK。"""
    b = make_empty_board()
    b[8][4] = "r_P"
    b[9][3] = "b_k"
    b[7][0] = "b_p"
    b[2][0] = "r_P"
    s = _make_session(collector, b)
    s.state.highlight = [(8, 4), (9, 4)]
    _patch_sleep(monkeypatch)

    after = [row[:] for row in b]
    after[8][4] = None
    after[9][3] = None
    after[9][4] = "b_k"
    updates = [
        (8, 4, "r_P", None),
        (9, 3, "b_k", None),
        (9, 4, None, "b_k"),
    ]
    _queue_frames(monkeypatch, s, [(after, updates)])

    result = s._verify(8, 4, 9, 4, "r_P")
    assert result == VerifyOutcome.DONE_OK, f"期望 DONE_OK，实际 {result}"
    assert s.state.board[8][4] is None
    assert s.state.board[9][3] is None
    assert s.state.board[9][4] == "b_k"
    assert s.state.turn == Side.RED


def test_n4_self_plus_enemy(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """n==4：我方走棋 + 敌方走棋 → DONE_OK。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[7][7] = "b_c"
    b[9][4] = "r_K"
    b[0][4] = "b_k"
    s = _make_session(collector, b)
    s.state.highlight = [(7, 3), (0, 3)]
    _patch_sleep(monkeypatch)

    after = [row[:] for row in b]
    after[7][3] = None
    after[0][3] = "r_R"
    after[7][7] = None
    after[7][4] = "b_c"
    updates = [
        (7, 3, "r_R", None),
        (0, 3, None, "r_R"),
        (7, 7, "b_c", None),
        (7, 4, None, "b_c"),
    ]
    _queue_frames(monkeypatch, s, [(after, updates)])

    result = s._verify(7, 3, 0, 3, "r_R")
    assert result == VerifyOutcome.DONE_OK, f"期望 DONE_OK，实际 {result}"
    assert s.state.board[7][3] is None
    assert s.state.board[0][3] == "r_R"
    assert s.state.board[7][7] is None
    assert s.state.board[7][4] == "b_c"
    assert s.state.turn == Side.RED


def test_lifted_only(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """最后一帧 n==1 提起未落 → LIFTED_ONLY。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[9][4] = "r_K"
    b[0][4] = "b_k"
    s = _make_session(collector, b)
    _patch_sleep(monkeypatch)

    same = [row[:] for row in b]
    lifted = [row[:] for row in b]
    lifted[7][3] = None
    lifted_updates = [(7, 3, "r_R", None)]
    frames = [(same, [])] * (MOVE_VERIFY_COUNT - 1) + [(lifted, lifted_updates)]
    _queue_frames(monkeypatch, s, frames)

    result = s._verify(7, 3, 0, 3, "r_R")
    assert result == VerifyOutcome.LIFTED_ONLY, f"期望 LIFTED_ONLY，实际 {result}"


def test_all_stationary(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """全帧 n==0 → STATIONARY（建议外层重走）。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[9][4] = "r_K"
    b[0][4] = "b_k"
    s = _make_session(collector, b)
    _patch_sleep(monkeypatch)

    same = [row[:] for row in b]
    _queue_frames(monkeypatch, s, [(same, [])] * MOVE_VERIFY_COUNT)

    result = s._verify(7, 3, 0, 3, "r_R")
    assert result == VerifyOutcome.STATIONARY, f"期望 STATIONARY，实际 {result}"


def test_n1_not_lifted_transient(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """n==1 非提子（无关格子变化）→ TRANSIENT。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[5][5] = "b_r"
    s = _make_session(collector, b)
    _patch_sleep(monkeypatch)

    after = [row[:] for row in b]
    after[5][5] = None
    updates = [(5, 5, "b_r", None)]
    _queue_frames(monkeypatch, s, [(after, updates)] * MOVE_VERIFY_COUNT)

    result = s._verify(7, 3, 0, 3, "r_R")
    assert result == VerifyOutcome.TRANSIENT, f"期望 TRANSIENT，实际 {result}"


def test_n_over4_transient(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """n>4 变动过多（但有将帅，非结算画面）→ TRANSIENT。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[9][4] = "r_K"
    b[0][4] = "b_k"
    s = _make_session(collector, b)
    _patch_sleep(monkeypatch)

    after = [row[:] for row in b]
    updates = [(i, 0, None, "b_p") for i in range(6)]
    for i in range(6):
        after[i][0] = "b_p"
    _queue_frames(monkeypatch, s, [(after, updates)] * MOVE_VERIFY_COUNT)

    result = s._verify(7, 3, 0, 3, "r_R")
    assert result == VerifyOutcome.TRANSIENT, f"期望 TRANSIENT，实际 {result}"


# ============================================================
# _do_move 完整流程测试
# ============================================================


def _setup_do_move(
    monkeypatch: pytest.MonkeyPatch,
    s: game.GameSession,
    best_moves: list[str],
    frames_per_verify: list[list[tuple[list, list]]],
    is_mate: bool = False,
) -> None:
    """配置 _do_move 所需的全部 mock。"""
    _patch_sleep(monkeypatch)
    engine_cls = s.engine.__class__

    move_iter = iter(best_moves)
    monkeypatch.setattr(engine_cls, "best_move", lambda self, fen, ms=1000: (next(move_iter), 0))
    monkeypatch.setattr(engine_cls, "is_mate", lambda self, fen, ms: is_mate)
    monkeypatch.setattr(type(s), "_attempt_move", lambda self, r1, c1, r2, c2: True)

    all_frames: list[tuple[list, list]] = []
    for frames in frames_per_verify:
        all_frames.extend(frames)
    frame_iter = iter(all_frames)
    monkeypatch.setattr(s, "_grab_board", lambda: next(frame_iter))


def test_do_move_success(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """完整走棋成功：engine 算着法 → 点击 → n==2 校验命中。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[9][4] = "r_K"
    b[0][4] = "b_k"
    s = _make_session(collector, b)

    after = [row[:] for row in b]
    after[7][3] = None
    after[0][3] = "r_R"
    updates = [(7, 3, "r_R", None), (0, 3, None, "r_R")]
    _setup_do_move(
        monkeypatch,
        s,
        best_moves=["d2d9"],
        frames_per_verify=[[(after, updates)]],
    )

    assert s._do_move() is True
    assert s.state.board[7][3] is None
    assert s.state.board[0][3] == "r_R"
    assert s.state.turn == Side.BLACK


def test_do_move_retry_success(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """第1次 STATIONARY（全 n==0）→ 第2次成功。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[9][4] = "r_K"
    b[0][4] = "b_k"
    s = _make_session(collector, b)

    same = [row[:] for row in b]
    after = [row[:] for row in b]
    after[7][3] = None
    after[0][3] = "r_R"
    _setup_do_move(
        monkeypatch,
        s,
        best_moves=["d2d9", "d2d9"],
        frames_per_verify=[
            [(same, [])] * MOVE_VERIFY_COUNT,
            [(after, [(7, 3, "r_R", None), (0, 3, None, "r_R")])],
        ],
    )

    assert s._do_move() is True
    assert s.state.board[0][3] == "r_R"


def test_do_move_lifted_retry_success(
    collector: LogCollector, monkeypatch: pytest.MonkeyPatch
) -> None:
    """提起未落 → 补点 → 重试成功。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[9][4] = "r_K"
    b[0][4] = "b_k"
    s = _make_session(collector, b)

    same = [row[:] for row in b]
    lifted = [row[:] for row in b]
    lifted[7][3] = None
    after = [row[:] for row in b]
    after[7][3] = None
    after[0][3] = "r_R"
    _setup_do_move(
        monkeypatch,
        s,
        best_moves=["d2d9"],
        frames_per_verify=[
            [(same, [])] * (MOVE_VERIFY_COUNT - 1) + [(lifted, [(7, 3, "r_R", None)])],
            [(after, [(7, 3, "r_R", None), (0, 3, None, "r_R")])],
        ],
    )

    assert s._do_move() is True
    assert s.state.board[0][3] == "r_R"


def test_do_move_fail_abort(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """有变化但没命中分类 → TRANSIENT → 中止。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[5][5] = "b_r"
    b[9][4] = "r_K"
    b[0][4] = "b_k"
    s = _make_session(collector, b)

    after = [row[:] for row in b]
    after[5][5] = None
    _setup_do_move(
        monkeypatch,
        s,
        best_moves=["d2d9"],
        frames_per_verify=[[(after, [(5, 5, "b_r", None)])] * MOVE_VERIFY_COUNT],
    )

    assert s._do_move() is False
    assert any("走棋尝试失败" in line for line in collector.logs)


def test_do_move_checkmate(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """n==2 命中 + 绝杀探测 → game_over=True。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[9][4] = "r_K"
    b[0][4] = "b_k"
    s = _make_session(collector, b)

    after = [row[:] for row in b]
    after[7][3] = None
    after[0][3] = "r_R"
    updates = [(7, 3, "r_R", None), (0, 3, None, "r_R")]
    _setup_do_move(
        monkeypatch,
        s,
        best_moves=["d2d9"],
        frames_per_verify=[[(after, updates)]],
        is_mate=True,
    )

    assert s._do_move() is True
    assert s.state.game_over is True


def test_do_move_enemy_resign_n_over4(
    collector: LogCollector, monkeypatch: pytest.MonkeyPatch
) -> None:
    """我方走棋时敌方投降/结算画面：n>4 变动过多且双方将帅缺失 → 检测到对局结束。"""
    b = make_empty_board()
    b[7][3] = "r_R"
    b[9][4] = "r_K"
    b[0][4] = "b_k"
    b[0][0] = "b_r"
    b[9][0] = "r_R"
    s = _make_session(collector, b)

    empty = make_empty_board()
    updates = [
        (7, 3, "r_R", None),
        (9, 4, "r_K", None),
        (0, 4, "b_k", None),
        (0, 0, "b_r", None),
        (9, 0, "r_R", None),
    ]
    _setup_do_move(
        monkeypatch,
        s,
        best_moves=["d2d9"],
        frames_per_verify=[[(empty, updates)] * MOVE_VERIFY_COUNT],
    )

    assert s._do_move() is False
    assert s.state.game_over is True
    assert any("检测到对局结束画面" in line for line in collector.logs)
