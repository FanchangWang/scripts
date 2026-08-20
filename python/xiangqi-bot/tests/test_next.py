"""自动下一局场景（16 个）。"""

from __future__ import annotations

from typing import Any

import pytest

from xiangqi_bot.board import START_SQUARES, make_empty_board
from xiangqi_bot.game import session as game

from .conftest import LogCollector, MockDeviceWithRecord


def _full_start_board() -> list[list[str | None]]:
    b = make_empty_board()
    for piece_id, squares in START_SQUARES.items():
        for r, c in squares:
            b[r][c] = piece_id
    return b


def _stable_frame(board: list[list[str | None]]) -> dict[str, Any]:
    return {"board": board, "diff": set()}


def _changed_frame(board: list[list[str | None]], diff: set[tuple[int, int]]) -> dict[str, Any]:
    return {"board": board, "diff": set(diff)}


def _make_session(collector: LogCollector) -> tuple[game.GameSession, MockDeviceWithRecord]:
    dev = MockDeviceWithRecord()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    s.board = make_empty_board()
    s.prev = object()
    s.my_side = "red"
    s._turn = "red"
    s._running = True
    s.game_over = True
    s._resign_streak = 0
    s._lift_logged = False
    s._noisy_count = 0
    s.engine.best_move = lambda fen: "e2e3"  # type: ignore[method-assign]
    s.engine.is_mate = lambda fen, ms: False  # type: ignore[method-assign]
    return s, dev


def test_auto_next_game(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """20 个自动下一局场景顺序执行"""
    from xiangqi_bot import config as cfg
    from xiangqi_bot.game import auto_next as an

    monkeypatch.setattr(cfg, "GAMEOVER_SCAN_MAX", 7)
    monkeypatch.setattr(an, "GAMEOVER_SCAN_MAX", 7)
    monkeypatch.setattr(game.time, "sleep", collector.sleep)

    # 场景1：下一关对话框 -> 点击右侧按钮 -> 开局自动对弈
    collector.clear()
    capture_queue: list[dict[str, Any]] = [
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
    ]
    s, dev = _make_session(collector)
    s._capture = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue: list[tuple[str, int, int, bool] | None] = [
        ("下一关", 712, 2198, True),
        None,
    ]
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is not None, collector.logs
    assert dev.taps == [(712, 2198)], f"应点击右侧下一关按钮，实际 {dev.taps}"
    assert not dev.shells, f"下一关无需返回键，{dev.shells}"
    assert s.game_over is False, "下一局应已开始"
    assert s.my_side == "red" and s._turn == "red", f"新开局红先，{s.my_side}/{s._turn}"
    assert s.phase == "开局", s.phase
    assert any("下一局开始" in m for m in collector.logs), collector.logs

    # 场景2：段位提升 -> 返回键 -> 再来一局 -> 点击 -> 开局
    collector.clear()
    capture_queue = [
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
    ]
    s, dev = _make_session(collector)
    s._capture = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("段位提升", 540, 1000, False),
        ("再来一局", 542, 2237, True),
        None,
    ]
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is not None, collector.logs
    assert dev.shells == ["input keyevent 4"], f"段位提升应发返回键，{dev.shells}"
    assert dev.taps == [(542, 2237)], f"返回后再点再来一局按钮，实际 {dev.taps}"
    assert s.game_over is False
    assert any("识别到文字「段位提升」，发送返回键" in m for m in collector.logs), collector.logs
    assert any("识别到结算按钮「再来一局」" in m for m in collector.logs), collector.logs

    # 场景3：残局模式（下一关，棋子<20）-> 固定红先
    collector.clear()
    endgame = make_empty_board()
    endgame[0][4] = "b_k"
    endgame[9][4] = "r_K"
    endgame[7][3] = "r_C"
    endgame[0][3] = "b_a"
    capture_queue = [
        _stable_frame(endgame),
        _stable_frame(endgame),
        _stable_frame(endgame),
        _stable_frame(endgame),
        _stable_frame(endgame),
        _stable_frame(endgame),
    ]
    s, dev = _make_session(collector)
    s._capture = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("下一关", 712, 2198, True),
        None,
    ]
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is not None, collector.logs
    assert s.game_over is False
    assert s.my_side == "red" and s._turn == "red", f"残局固定红先，{s.my_side}/{s._turn}"
    assert s.phase == "残局", s.phase
    assert any("残局模式：我方为红方、轮到我方先走" in m for m in collector.logs), collector.logs

    # 场景4：扫描超时（一直无结算文字）-> 返回 False
    collector.clear()
    hit_queue = [None] * (cfg.GAMEOVER_SCAN_MAX + 1)
    s, dev = _make_session(collector)
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is None, "无结算文字应中止"
    assert not dev.taps and not dev.shells, f"不应点击，{dev.taps}/{dev.shells}"
    assert any("未识别到结算文字" in m for m in collector.logs), collector.logs

    # 场景5：扫描期间被中断 -> 返回 False
    collector.clear()
    s, dev = _make_session(collector)
    s._interrupt.set()
    ok = s._auto_next_game()
    assert ok is None, "中断应中止自动下一局"

    # 场景6：摆棋动画一直未稳定 -> 返回 False
    collector.clear()
    board = _full_start_board()
    anim1 = _changed_frame(board, {(0, 4), (9, 4)})
    anim2 = _changed_frame(board, {(2, 1), (2, 7)})
    capture_queue = [anim1, anim2] * (cfg.GAMEOVER_SCAN_MAX // 2 + 1)
    s, dev = _make_session(collector)
    s._capture = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("下一关", 712, 2198, True),
        None,
    ]
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is None, "摆棋未稳定应中止"
    assert dev.taps == [(712, 2198)], f"应已点击下一关，实际 {dev.taps}"
    assert any("未检测到摆棋完毕" in m for m in collector.logs), collector.logs

    # 场景7：_flow 循环——对局结束触发自动下一局，失败则停止
    collector.clear()
    s, _dev = _make_session(collector)
    s.game_over = True
    s.prev_board = [row[:] for row in s.board]
    called: list[int] = []

    def fake_auto() -> None:
        called.append(1)
        return None

    s._auto_next_game = fake_auto  # type: ignore[method-assign]
    s._do_move = lambda: True  # type: ignore[method-assign]
    s._running = True
    s._flow()
    assert called == [1], "对局结束且未中断时应调用自动下一局"
    assert s._running is True, "自动下一局中止不应清除 _running（保留给手动开始）"

    # 场景8：_flow 在中断时不做自动下一局
    collector.clear()
    s, _dev = _make_session(collector)
    s.game_over = True
    s.prev_board = [row[:] for row in s.board]
    s._interrupt.set()
    s._auto_next_game = lambda: (_ for _ in ()).throw(AssertionError("不应调用自动下一局"))  # type: ignore[method-assign]
    s._do_move = lambda: True  # type: ignore[method-assign]
    s._running = True
    s._flow()

    # 场景9：点击后按钮仍在（动画未结束）-> 复检重试第二次成功
    collector.clear()
    capture_queue = [
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
    ]
    s, dev = _make_session(collector)
    s._capture = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("下一关", 712, 2198, True),
        ("下一关", 712, 2198, True),
        None,
    ]
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is not None, collector.logs
    assert dev.taps == [(712, 2198), (712, 2198)], f"应点击两次，实际 {dev.taps}"
    assert any("仍识别到结算按钮「下一关」" in m for m in collector.logs), collector.logs
    assert s.game_over is False

    # 场景10：点击三次按钮仍存在 -> 中止自动下一局
    collector.clear()
    hit_queue = [
        ("下一关", 712, 2198, True),
        ("下一关", 712, 2198, True),
        ("下一关", 712, 2198, True),
        ("下一关", 712, 2198, True),
    ]
    s, dev = _make_session(collector)
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is None, "按钮一直存在应中止"
    assert dev.taps == [(712, 2198), (712, 2198), (712, 2198)], f"应至多点三次，实际 {dev.taps}"
    assert any("仍无响应" in m for m in collector.logs), collector.logs
    assert s.game_over is True, "中止后保持结束状态"

    # 场景11：自动下一局期间状态为 auto_next（按钮保持），结束后恢复
    collector.clear()
    status_seen: list[str] = []
    s, dev = _make_session(collector)
    s._capture = lambda: None  # type: ignore[method-assign]

    def fake_scan() -> Any:
        status_seen.append(s._status())
        return _stable_frame(_full_start_board())

    s._scan_gameover_interact = fake_scan  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is not None, collector.logs
    assert status_seen == ["auto_next"], f"自动下一局期间状态应为 auto_next，实际 {status_seen}"
    assert s._auto_next is False, "流程结束应清除 _auto_next"
    assert s._status() == "red", f"流程结束后应恢复对弈状态，实际 {s._status()}"

    # 场景12：_flow 对局结束后开关关闭时不自动下一局
    collector.clear()
    s, _dev = _make_session(collector)
    s.game_over = True
    s.prev_board = [row[:] for row in s.board]
    s.auto_next_game = False
    s._auto_next_game = lambda: (_ for _ in ()).throw(AssertionError("不应调用自动下一局"))  # type: ignore[method-assign]
    s._do_move = lambda: True  # type: ignore[method-assign]
    s._running = True
    s._flow()

    # 场景13：set_auto_next 更新标志并随 state 广播
    collector.clear()
    s, _dev = _make_session(collector)
    emitted: list[dict[str, Any]] = []
    s._on_state = lambda st: emitted.append(st)  # type: ignore[method-assign]
    s.set_auto_next(False)
    assert s.auto_next_game is False, "标志应更新为 False"
    assert emitted and emitted[-1]["auto_next"] is False, emitted
    s.set_auto_next(True)
    assert s.auto_next_game is True
    assert emitted[-1]["auto_next"] is True, emitted
    assert len([e for e in emitted if e["auto_next"] is False]) == 1

    # 场景14：自动下一局扫描期间开关关闭 -> 中止
    collector.clear()
    hit_queue = [
        ("下一关", 712, 2198, True),
    ]
    s, dev = _make_session(collector)
    s.auto_next_game = False
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is None, "开关关闭应中止自动下一局"
    assert not dev.taps and not dev.shells, f"不应点击，{dev.taps}/{dev.shells}"
    assert s._auto_next is False, "流程结束应清除 _auto_next"

    # 场景15：领取悬浮遮罩 -> 返回键消除 -> 再来一局 -> 点击 -> OK
    collector.clear()
    capture_queue = [
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
    ]
    s, dev = _make_session(collector)
    s._capture = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("领取", 540, 1000, False),
        ("再来一局", 542, 2237, True),
        ("再来一局", 542, 2237, True),
        None,
    ]
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is not None, collector.logs
    assert dev.shells == ["input keyevent 4"], f"应发返回键消除遮罩，{dev.shells}"
    assert dev.taps == [(542, 2237), (542, 2237)], f"应点击两次再来一局，实际 {dev.taps}"
    assert s.game_over is False
    assert any("识别到文字「领取」，发送返回键（第 1/3 次）" in m for m in collector.logs), (
        collector.logs
    )
    assert any("识别到结算按钮「再来一局」" in m for m in collector.logs), collector.logs

    # 场景16：领取遮罩连续返回键超限 -> 中止自动下一局
    collector.clear()
    hit_queue = [
        ("领取", 540, 1000, False),
        ("领取", 540, 1000, False),
        ("领取", 540, 1000, False),
        ("领取", 540, 1000, False),
    ]
    s, dev = _make_session(collector)
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is None, "遮罩连续返回键超限应中止"
    assert dev.shells == ["input keyevent 4"] * 3, f"应发3次返回键，{dev.shells}"
    assert not dev.taps, f"不应点击按钮，{dev.taps}"
    assert s._auto_next is False, "中止后清除 _auto_next"
    assert any("遮罩「领取」发送返回键 3 次仍无响应" in m for m in collector.logs), collector.logs

    # 场景17：铜钱遮罩 -> 返回键 -> 再来一局 -> OK
    collector.clear()
    capture_queue = [
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
    ]
    s, dev = _make_session(collector)
    s._capture = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("铜钱", 477, 1175, False),
        ("再来一局", 542, 2237, True),
        None,
    ]
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is not None, collector.logs
    assert dev.shells == ["input keyevent 4"], f"铜钱应发返回键，{dev.shells}"
    assert dev.taps == [(542, 2237)], f"应点击再来一局，实际 {dev.taps}"
    assert any("识别到文字「铜钱」，发送返回键（第 1/3 次）" in m for m in collector.logs), (
        collector.logs
    )

    # 场景18：铜钱 -> 领取（叠加遮罩，计数重置）-> 再来一局 -> OK
    collector.clear()
    capture_queue = [
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
    ]
    s, dev = _make_session(collector)
    s._capture = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("铜钱", 477, 1175, False),
        ("领取", 540, 1000, False),
        ("再来一局", 542, 2237, True),
        None,
    ]
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is not None, collector.logs
    assert dev.shells == ["input keyevent 4"] * 2, f"铜钱+领取各发一次返回键，{dev.shells}"
    assert dev.taps == [(542, 2237)], f"应点击再来一局，实际 {dev.taps}"
    assert any("识别到文字「铜钱」，发送返回键（第 1/3 次）" in m for m in collector.logs), (
        collector.logs
    )
    assert any("识别到文字「领取」，发送返回键（第 1/3 次）" in m for m in collector.logs), (
        collector.logs
    )

    # 场景19：铜钱连续超限 -> 中止（不同遮罩出现才重置计数）
    collector.clear()
    hit_queue = [
        ("铜钱", 477, 1175, False),
        ("铜钱", 477, 1175, False),
        ("铜钱", 477, 1175, False),
        ("铜钱", 477, 1175, False),
    ]
    s, dev = _make_session(collector)
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is None, "铜钱连续超限应中止"
    assert dev.shells == ["input keyevent 4"] * 3, f"应发3次返回键，{dev.shells}"
    assert any("遮罩「铜钱」发送返回键 3 次仍无响应" in m for m in collector.logs), collector.logs

    # 场景20：点击按钮后弹出遮罩（领取），须先处理遮罩再继续
    collector.clear()
    capture_queue = [
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
        _stable_frame(_full_start_board()),
    ]
    s, dev = _make_session(collector)
    s._capture = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("再来一局", 542, 2237, True),
        ("领取", 540, 1000, False),
        ("再来一局", 542, 2237, True),
        None,
    ]
    s._scan_gameover_text = lambda: hit_queue.pop(0) if hit_queue else None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is not None, collector.logs
    assert dev.taps == [(542, 2237), (542, 2237)], f"应点击两次再来一局，实际 {dev.taps}"
    assert dev.shells == ["input keyevent 4"], f"应发一次返回键消除遮罩，{dev.shells}"
    assert any("识别到文字「领取」，发送返回键" in m for m in collector.logs), collector.logs
