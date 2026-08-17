"""走棋校验（8 个）。"""

from __future__ import annotations

from typing import Any

import numpy as np

from xiangqi_bot.board import make_empty_board
from xiangqi_bot.game import session as game

from .conftest import LogCollector, MockDevice, frame


def _base_board() -> list[list[str | None]]:
    b = make_empty_board()
    b[0][4] = "b_k"
    b[9][4] = "r_K"
    return b


def _real_board() -> list[list[str | None]]:
    b = make_empty_board()
    b[0][4] = "b_k"
    b[9][4] = "r_K"
    b[7][3] = "r_C"
    b[0][3] = "b_a"
    b[1][4] = "b_a"
    return b


def _board_with_epawn() -> list[list[str | None]]:
    b = _real_board()
    b[6][4] = "r_P"
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
    s.engine.best_move = lambda fen: "e2e3"  # type: ignore[method-assign]
    s.engine.is_mate = lambda fen, ms: False  # type: ignore[method-assign]
    return s


def test_recheck(collector: LogCollector) -> None:
    """8 个走棋校验场景顺序执行"""
    game.time.sleep = collector.sleep  # type: ignore[assignment]

    # 场景1：终点瞬态误读延时复检恢复，不误判被吃掉
    s = _make_session(collector)
    transient = frame(
        {(7, 3): None, (0, 3): None, (1, 4): None},
        _real_board(),
        {(7, 3), (0, 3), (1, 4)},
    )
    recheck = frame(
        {(7, 3): None, (0, 3): "r_C", (1, 4): None, (0, 5): "b_a"},
        _real_board(),
        {(7, 3), (0, 3), (1, 4), (0, 5)},
    )
    queue: list[dict[str, Any]] = [transient, recheck]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    ok = s._attempt_move(7, 3, 0, 3, "r_C")
    assert ok is True, f"瞬态误读场景 _attempt_move 应成功，{collector.logs}"
    assert s.board[0][3] == "r_C", f"我方红炮应仍在 d9（未被误判吃掉），{collector.logs}"
    assert not any("对方吃掉了" in m for m in collector.logs), collector.logs
    assert any("终点复检为我方棋子" in m for m in collector.logs), collector.logs
    assert any("黑方走士：e8 -> f9" in m for m in collector.logs), collector.logs

    # 场景2：残留噪声计数 + 对局结束画面 -> 会话重置计数，认输判定收局
    collector.clear()
    s = _make_session(collector)
    s.board[3][0] = "b_r"
    s.board[2][2] = "r_N"
    s.board[4][6] = "b_p"
    s.board[5][6] = "b_n"
    s._noisy_count = 3
    over = _base_board()
    over_frame = frame(
        {(3, 0): None, (2, 2): None, (4, 6): None, (5, 6): None},
        over,
        {(3, 0), (2, 2), (4, 6), (5, 6)},
    )
    queue = [over_frame, over_frame, over_frame]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    s._wait_for_enemy_move()
    assert s.game_over is True, "对局结束画面应确认收局"
    assert not any("按实际变动提交" in m for m in collector.logs), (
        f"结束画面不应被噪声兜底提交，{collector.logs}"
    )
    assert s.board[3][0] == "b_r" and s.board[4][6] == "b_p", "结束确认不应污染内存棋盘"

    # 场景3：噪声帧攒满计数后出现结束画面 -> 不提交，交由认输判定
    collector.clear()
    s = _make_session(collector)
    s.board[3][0] = "b_r"
    s.board[2][2] = "r_N"
    s.board[4][6] = "b_p"
    s.board[5][6] = "b_n"
    full = _base_board()
    full[3][0] = "b_r"
    full[2][2] = "r_N"
    full[4][6] = "b_p"
    full[5][6] = "b_n"
    noisy2 = frame({(3, 0): None, (2, 2): None}, full, {(3, 0), (2, 2)})
    over_frame = frame(
        {(3, 0): None, (2, 2): None, (4, 6): None, (5, 6): None},
        over,
        {(3, 0), (2, 2), (4, 6), (5, 6)},
    )
    queue = [noisy2, noisy2, over_frame, over_frame, over_frame]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    s._wait_for_enemy_move()
    assert s.game_over is True, "结束画面应确认收局"
    assert not any("按实际变动提交" in m for m in collector.logs), (
        f"结束画面不应被噪声兜底提交，{collector.logs}"
    )
    assert s.board[3][0] == "b_r" and s.board[4][6] == "b_p", "不应污染内存棋盘"

    # 场景4：恢复流程"其它变化"分支打印全部变化棋子
    collector.clear()
    s = _make_session(collector)
    s.board[0][5] = "b_n"
    s.prev = object()
    other = frame(
        {(0, 3): None, (1, 4): None, (0, 5): "r_N"},
        _real_board(),
        {(0, 3), (1, 4), (0, 5)},
    )
    queue = [other]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    ok = s._recover_move_failure(7, 3, 0, 3, "r_C", [])
    assert ok is False, "其它变化无法恢复，应返回 False"
    assert any("检测到棋局发生其它变化，无法恢复走棋" in m for m in collector.logs), collector.logs
    assert any("变化：d9 黑士 -> 空" in m for m in collector.logs), collector.logs
    assert any("变化：e8 黑士 -> 空" in m for m in collector.logs), collector.logs
    assert any("变化：f9 黑馬 -> 红傌" in m for m in collector.logs), collector.logs

    # 场景5：反吃帧含瞬态误读 -> 复检后提交干净一步
    collector.clear()
    s = _make_session(collector)
    s.board[6][4] = "r_P"
    frame1 = frame(
        {(7, 3): None, (0, 3): "b_a", (1, 4): None},
        _board_with_epawn(),
        {(7, 3), (0, 3), (1, 4)},
    )
    noisy = frame(
        {(7, 3): None, (0, 3): "b_a", (1, 4): None, (6, 4): None},
        _board_with_epawn(),
        {(7, 3), (0, 3), (1, 4), (6, 4)},
    )
    clean = frame(
        {(7, 3): None, (0, 3): "b_a", (1, 4): None, (6, 4): "r_P"},
        _board_with_epawn(),
        {(7, 3), (0, 3), (1, 4)},
    )
    queue = [frame1, noisy, clean]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    ok = s._attempt_move(7, 3, 0, 3, "r_C")
    assert ok is True, f"反吃+瞬态误读 _attempt_move 应成功，{collector.logs}"
    assert s.board[0][3] == "b_a" and s.board[1][4] is None and s.board[7][3] is None, (
        collector.logs
    )
    assert s.board[6][4] == "r_P", f"e3 红兵不应被误删，{collector.logs}"
    assert not any("e3 红兵" in m for m in collector.logs), (
        f"不应提交 e3 误读变动，{collector.logs}"
    )
    assert any("黑方走士：e8 -> d9（吃红炮）" in m for m in collector.logs), collector.logs

    # 场景6：复检始终无法构成完整一步 -> 暂停自动对弈
    collector.clear()
    s = _make_session(collector)
    s.board[6][4] = "r_P"
    frame1 = frame(
        {(7, 3): None, (0, 3): "b_a", (1, 4): None},
        _board_with_epawn(),
        {(7, 3), (0, 3), (1, 4)},
    )
    stuck = frame(
        {(7, 3): None, (0, 3): "b_a", (1, 4): None, (6, 4): None},
        _board_with_epawn(),
        {(7, 3), (0, 3), (1, 4), (6, 4)},
    )
    queue = [frame1, stuck, stuck, stuck, stuck]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    ok = s._attempt_move(7, 3, 0, 3, "r_C")
    assert ok is True, "校验仍应成功（走棋已成功，只是敌方变动无法确认）"
    assert s._running is False, "敌方变动无法确认时应暂停自动对弈"
    assert s.board[6][4] == "r_P", f"e3 红兵不应被误删，{collector.logs}"
    assert any("始终无法构成完整一步" in m for m in collector.logs), collector.logs
    assert any("变化：e3 红兵 -> 空" in m for m in collector.logs), collector.logs

    # 场景7：走棋后立即出现对局结束画面 -> 校验拒绝，认输判定收局
    collector.clear()
    s = _make_session(collector)
    s.board[6][4] = "r_P"
    s.board[0][5] = "b_n"
    s.board[2][2] = "r_N"
    s.board[4][6] = "b_p"
    s._resign_streak = 0
    over_board = make_empty_board()
    over_frame = frame(
        {},
        over_board,
        {(7, 3), (0, 3), (1, 4), (6, 4), (0, 5), (2, 2), (4, 6), (0, 4), (9, 4)},
    )
    queue = [over_frame, over_frame, over_frame]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    ok = s._attempt_move(7, 3, 0, 3, "r_C")
    assert ok is True, f"结束画面 _attempt_move 应成功收局，{collector.logs}"
    assert s.game_over is True, "结束画面应确认收局"
    assert any("检测到对局结束画面" in m for m in collector.logs), collector.logs
    assert not any("对方吃掉了" in m for m in collector.logs), f"不应误报被吃，{collector.logs}"
    assert not any("走动成功" in m for m in collector.logs), (
        f"结束画面不应误判走动成功，{collector.logs}"
    )

    # 场景8：反吃复检期间对局结束 -> 认输判定收局
    collector.clear()
    s = _make_session(collector)
    s.board[6][4] = "r_P"
    s._resign_streak = 0
    frame1 = frame(
        {(7, 3): None, (0, 3): "b_a", (1, 4): None},
        _real_board(),
        {(7, 3), (0, 3), (1, 4)},
    )
    over_board = make_empty_board()
    over_frame = frame(
        {},
        over_board,
        {(7, 3), (0, 3), (1, 4), (6, 4), (0, 4), (9, 4)},
    )
    queue = [frame1, over_frame, over_frame, over_frame, over_frame]
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    ok = s._attempt_move(7, 3, 0, 3, "r_C")
    assert ok is True, f"复检期间结束 _attempt_move 应成功收局，{collector.logs}"
    assert s.game_over is True, "复检帧显示对局结束画面应确认收局"
    assert any("检测到对局结束画面" in m for m in collector.logs), collector.logs
    assert not any("始终无法构成完整一步" in m for m in collector.logs), (
        f"不应走暂停兜底，{collector.logs}"
    )
    assert s.board[1][4] == "b_a" and s.board[6][4] == "r_P", (
        f"结束确认不应污染内存，{collector.logs}"
    )
    assert s.board[0][4] == "b_k" and s.board[9][4] == "r_K", collector.logs
