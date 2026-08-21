"""我方走棋后敌方在落点反吃：验证不会误判对局结束、不会调 engine.is_mate。

复现实测场景：我方红兵 e8→e9 走一步，校验帧里敌方黑將 d9→e9 吃兵（n==3 变动）。
按用户方案，只在 n==2 且 _infer_move 成功时才做绝杀探测；
其他 n==3/n==4 / 兜底等路径一律不探测，交给结算画面认输检测兜底。
本测试验证 n==3 情况 2（敌方在终点吃我落子）的分类与 board 更新正确性。
"""

from __future__ import annotations

import time
from unittest.mock import MagicMock

import numpy as np
import pytest

from xiangqi_bot.board import make_empty_board
from xiangqi_bot.game import session as game

from .conftest import LogCollector, MockDevice


def _init_board() -> list[list[str | None]]:
    """构造残局：红兵 e8 + 黑將 d9 + 双方帥在原位。

    故意凑成 n==3 变化能稳定命中 _classify_n3 情况 2，不牵扯开局/残局分析。
    """
    b = make_empty_board()
    b[9][4] = "r_K"  # 我方帥（屏幕最下行 e0 = r=9 c=4）
    b[8][4] = "r_P"  # 我方红兵在 e8（r=8 c=4）
    b[9][3] = "b_k"  # 敌方將在 d9（r=9 c=3）
    b[7][0] = "b_p"  # 无关黑卒，让棋子数 > 3（避免被极端残局误触发认输 threshold）
    b[2][0] = "r_P"  # 无关红兵
    return b


def _make_session(
    initial_board: list[list[str | None]],
    collector: LogCollector,
) -> game.GameSession:
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    s.board = [row[:] for row in initial_board]
    s.prev_board = [row[:] for row in initial_board]
    s.my_side = "red"
    s._turn = "red"
    s._running = True
    s._lift_logged = False
    s._noisy_count = 0
    s._resign_streak = 0
    s.phase = "中局"
    s.game_over = False
    s._auto_next = False
    s.pending_move = None
    s._homography = np.eye(3)
    return s


def _build_after_frame(
    initial: list[list[str | None]],
) -> tuple[list[list[str | None]], list[tuple[int, int, str | None, str | None]]]:
    """应用我方 e8→e9 落子 + 敌方 d9→e9 吃兵，得到 n==3 变动的终局 board 与 updates。

    updates 三格严格对齐 _classify_n3 情况 2 的判定：
      (r1,c1) = e8(8,4): r_P → None（我方起点提子）
      (r2,c2) = e9(9,4): None → b_k（敌方终点吃子落子）
      x 格     = d9(9,3): b_k → None（敌方起点提子）
    """
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
    # 我方 red 视角记谱（9 - r）：r=8→rank=1→e1, r=9→rank=0→e0
    best_move_seq = iter(["e1e0", "a0a1"])
    engine_cls = s.engine.__class__

    def spy_best_move(self, fen, movetime_ms=1000):  # type: ignore[no-untyped-def]
        return next(best_move_seq), 0

    def spy_is_mate(self, fen, movetime_ms=200):  # type: ignore[no-untyped-def]
        is_mate_calls.append(1)
        pytest.fail("engine.is_mate 不应被调用（用户方案：只有 n==2+_infer_move 才探测）")

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

    def fake_analyze(self_, corrected):  # type: ignore[no-untyped-def]
        analyze_calls.append(1)
        return [row[:] for row in after_board], [*updates]

    monkeypatch.setattr(type(s), "_analyze_board_with_prev_board", fake_analyze)

    s._take_screenshot = lambda: np.zeros((1000, 900, 3), np.uint8)  # type: ignore[method-assign]
    s._dismiss_draw = lambda img: (img, 0)  # type: ignore[method-assign]
    s._correct_from_raw = lambda img: img  # type: ignore[method-assign]

    # 全局 patch time.sleep（self_move/game_over 同 time 模块）
    noop_sleep = MagicMock()
    monkeypatch.setattr(time, "sleep", noop_sleep)

    result = s._do_move()

    assert result is True, (
        "_do_move 应返回 True（n==3 情况 2 命中 _apply_self_then_enemy），"
        f"最近日志：{collector.logs[-10:]}"
    )

    # 核心断言：game_over 必须 False
    assert s.game_over is False, (
        f"敌方在落点吃我方棋子不应触发对局结束\n最近日志：{collector.logs[-20:]}"
    )

    # 轮次：敌方走完回到我方
    assert s._turn == "red", f"应用敌方走棋后应轮到我方 red，实际 {s._turn}"

    # 内存 board 写对（精确 3 格）
    assert s.board[9][4] == "b_k", f"e9 应为黑將 b_k，实际 {s.board[9][4]}"
    assert s.board[8][4] is None, f"e8 应为空，实际 {s.board[8][4]}"
    assert s.board[9][3] is None, f"d9 应为空，实际 {s.board[9][3]}"
    assert s.board[9][4] == after_board[9][4], "最终 board 应与 after_board 一致"

    # 用户方案断言：is_mate 0 次
    assert not is_mate_calls, "用户方案：n!=2 且非 _infer_move 成功时不应调 engine.is_mate"

    assert len(analyze_calls) >= 1, "至少进入 1 次校验帧分类"
    assert len(attempt_calls) >= 1, "至少调 1 次 _attempt_move"
