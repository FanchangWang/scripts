"""自动下一局场景。"""

from __future__ import annotations

import itertools
import time as real_time
from typing import Any

import pytest

from xiangqi_bot.board import START_SQUARES, make_empty_board
from xiangqi_bot.game import session as game
from xiangqi_bot.game.state import Side

from .conftest import LogCollector, MockDeviceWithRecord


def _full_start_board() -> list[list[str | None]]:
    b = make_empty_board()
    for piece_id, squares in START_SQUARES.items():
        for r, c in squares:
            b[r][c] = piece_id
    return b


def _stable_frame(board: list[list[str | None]]) -> dict[str, Any]:
    return {"board": board, "diff": set()}


def _make_session(collector: LogCollector) -> tuple[game.GameSession, MockDeviceWithRecord]:
    dev = MockDeviceWithRecord()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    s.state.board = make_empty_board()
    s.state.my_side = Side.RED
    s.state.turn = Side.RED
    s._running = True
    s.state.game_over = True
    s.state.resign_streak = 0
    s.state.lift_logged = False
    s.state.noisy_count = 0
    s.engine.best_move = lambda fen, ms=1000: ("e2e3", 0)  # type: ignore[method-assign]
    s.engine.is_mate = lambda fen, ms: False  # type: ignore[method-assign]
    s.engine.newgame = lambda: None  # type: ignore[method-assign]
    return s, dev


def test_auto_next_game(collector: LogCollector, monkeypatch: pytest.MonkeyPatch) -> None:
    """23 个自动下一局场景顺序执行"""
    from xiangqi_bot import config as cfg
    from xiangqi_bot.game import auto_next as an

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
    s.capture.grab = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue: list[tuple[str, int, int, bool] | None] = [
        ("下一关", 712, 2198, True),
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is True, collector.logs
    assert dev.taps == [(712, 2198)], f"应点击右侧下一关按钮，实际 {dev.taps}"
    assert not dev.shells, f"下一关无需返回键，{dev.shells}"
    assert s.state.game_over is False, "下一局应已开始"
    assert s.state.my_side == Side.RED and s.state.turn == Side.RED, (
        f"新开局红先，{s.state.my_side}/{s.state.turn}"
    )
    assert s.state.phase == "开局", s.state.phase
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
    s.capture.grab = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("段位提升", 540, 1000, False),
        ("再来一局", 542, 2237, True),
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is True, collector.logs
    assert dev.shells == ["input keyevent 4"], f"段位提升应发返回键，{dev.shells}"
    assert dev.taps == [(542, 2237)], f"返回后再点再来一局按钮，实际 {dev.taps}"
    assert s.state.game_over is False
    assert any("识别到文字「段位提升」，发送返回键" in m for m in collector.logs), collector.logs
    assert any("识别到结算按钮「再来一局」" in m for m in collector.logs), collector.logs

    # 场景3：残局模式（下一关，棋子<24）-> 固定红先
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
    s.capture.grab = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("下一关", 712, 2198, True),
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is True, collector.logs
    assert s.state.game_over is False
    assert s.state.my_side == Side.RED and s.state.turn == Side.RED, (
        f"残局固定红先，{s.state.my_side}/{s.state.turn}"
    )
    assert s.state.phase == "残局", s.state.phase
    assert any("残局模式：轮到红方走棋" in m for m in collector.logs), collector.logs

    # 场景4：扫描超时（一直无结算文字）-> 返回 False
    collector.clear()
    old_timeout = cfg.AUTO_NEXT_TIMEOUT_S
    monkeypatch.setattr(cfg, "AUTO_NEXT_TIMEOUT_S", 0)
    monkeypatch.setattr(an, "AUTO_NEXT_TIMEOUT_S", 0)
    s, dev = _make_session(collector)
    s.capture.grab = lambda: None  # type: ignore[method-assign]
    s.auto_next_handler._scan_text = lambda img=None: None  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is False, "超时应中止"
    assert not dev.taps and not dev.shells, f"不应点击，{dev.taps}/{dev.shells}"
    assert any("未完成结算交互" in m for m in collector.logs), collector.logs
    monkeypatch.setattr(cfg, "AUTO_NEXT_TIMEOUT_S", old_timeout)
    monkeypatch.setattr(an, "AUTO_NEXT_TIMEOUT_S", old_timeout)

    # 场景5：扫描期间被中断 -> 返回 False
    collector.clear()
    s, dev = _make_session(collector)
    s._interrupt.set()
    ok = s._auto_next_game()
    assert ok is False, "中断应中止自动下一局"

    # 场景6：摆棋动画一直未稳定 -> 超时返回 False
    collector.clear()
    call_count = 0

    def fake_monotonic() -> float:
        nonlocal call_count
        call_count += 1
        return call_count * 10

    monkeypatch.setattr(game.time, "monotonic", fake_monotonic)
    board_a = _full_start_board()
    board_a[3][0] = None
    board_a[3][2] = None
    board_b = _full_start_board()
    board_b[6][0] = None
    board_b[6][2] = None
    capture_gen = itertools.cycle([_stable_frame(board_a), _stable_frame(board_b)])
    s, dev = _make_session(collector)
    s.capture.grab = lambda: next(capture_gen)  # type: ignore[method-assign]
    hit_queue = [
        ("下一关", 712, 2198, True),
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is False, "摆棋未稳定应超时中止"
    assert dev.taps == [(712, 2198)], f"应已点击下一关，实际 {dev.taps}"
    assert any("未完成结算交互" in m for m in collector.logs), collector.logs
    monkeypatch.setattr(game.time, "monotonic", real_time.monotonic)

    # 场景7：_flow 循环——对局结束触发自动下一局，失败则停止
    collector.clear()
    s, _dev = _make_session(collector)
    s.state.game_over = True
    s.state.prev_board = [row[:] for row in s.state.board]
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
    s.state.game_over = True
    s.state.prev_board = [row[:] for row in s.state.board]
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
    s.capture.grab = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("下一关", 712, 2198, True),
        ("下一关", 712, 2198, True),
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is True, collector.logs
    assert dev.taps == [(712, 2198), (712, 2198)], f"应点击两次，实际 {dev.taps}"
    assert any("仍识别到结算按钮「下一关」" in m for m in collector.logs), collector.logs
    assert s.state.game_over is False

    # 场景10：点击三次按钮仍存在 -> 中止自动下一局
    collector.clear()
    hit_queue = [
        ("下一关", 712, 2198, True),
        ("下一关", 712, 2198, True),
        ("下一关", 712, 2198, True),
        ("下一关", 712, 2198, True),
    ]
    s, dev = _make_session(collector)
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is False, "按钮一直存在应中止"
    assert dev.taps == [(712, 2198), (712, 2198), (712, 2198)], f"应至多点三次，实际 {dev.taps}"
    assert any("仍无响应" in m for m in collector.logs), collector.logs
    assert s.state.game_over is True, "中止后保持结束状态"

    # 场景11：自动下一局期间状态为 auto_next（按钮保持），结束后恢复
    collector.clear()
    status_seen: list[str] = []
    s, dev = _make_session(collector)
    s.capture.grab = lambda: None  # type: ignore[method-assign]

    def fake_scan() -> Any:
        status_seen.append(s._status())
        return _stable_frame(_full_start_board())

    s.auto_next_handler.scan_and_wait = fake_scan  # type: ignore[method-assign]
    ok = s._auto_next_game()
    assert ok is True, collector.logs
    assert status_seen == ["auto_next"], f"自动下一局期间状态应为 auto_next，实际 {status_seen}"
    assert s._auto_next is False, "流程结束应清除 _auto_next"
    assert s._status() == "red", f"流程结束后应恢复对弈状态，实际 {s._status()}"

    # 场景12：_flow 对局结束后开关关闭时不自动下一局
    collector.clear()
    s, _dev = _make_session(collector)
    s.state.game_over = True
    s.state.prev_board = [row[:] for row in s.state.board]
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
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is False, "开关关闭应中止自动下一局"
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
    s.capture.grab = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("领取", 540, 1000, False),
        ("再来一局", 542, 2237, True),
        ("再来一局", 542, 2237, True),
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is True, collector.logs
    assert dev.shells == ["input keyevent 4"], f"应发返回键消除遮罩，{dev.shells}"
    assert dev.taps == [(542, 2237), (542, 2237)], f"应点击两次再来一局，实际 {dev.taps}"
    assert s.state.game_over is False
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
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is False, "遮罩连续返回键超限应中止"
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
    s.capture.grab = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("铜钱", 477, 1175, False),
        ("再来一局", 542, 2237, True),
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is True, collector.logs
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
    s.capture.grab = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("铜钱", 477, 1175, False),
        ("领取", 540, 1000, False),
        ("再来一局", 542, 2237, True),
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is True, collector.logs
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
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is False, "铜钱连续超限应中止"
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
    s.capture.grab = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("再来一局", 542, 2237, True),
        ("领取", 540, 1000, False),
        ("再来一局", 542, 2237, True),
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is True, collector.logs
    assert dev.taps == [(542, 2237), (542, 2237)], f"应点击两次再来一局，实际 {dev.taps}"
    assert dev.shells == ["input keyevent 4"], f"应发一次返回键消除遮罩，{dev.shells}"
    assert any("识别到文字「领取」，发送返回键" in m for m in collector.logs), collector.logs

    # 场景21：点击按钮后棋子出现又消失（点击未生效）-> 文字重现 -> 重试点击 -> 第二次成功
    collector.clear()
    setup_board = _full_start_board()
    setup_board[3][0] = None
    setup_board[3][2] = None
    empty = make_empty_board()
    capture_queue: list[dict[str, Any]] = [
        _stable_frame(setup_board),
        _stable_frame(empty),
        _stable_frame(setup_board),
        _stable_frame(setup_board),
        _stable_frame(setup_board),
    ]
    s, dev = _make_session(collector)
    s.capture.grab = lambda: capture_queue.pop(0) if capture_queue else None  # type: ignore[method-assign]
    hit_queue: list[tuple[str, int, int, bool] | None] = [
        ("再来一局", 542, 2237, True),
        None,
        None,
        ("再来一局", 542, 2237, True),
        None,
        None,
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is True, collector.logs
    assert dev.taps == [(542, 2237), (542, 2237)], f"应点击两次再来一局，实际 {dev.taps}"
    assert any("棋盘为空" in m for m in collector.logs), collector.logs
    assert s.state.game_over is False, "第二次点击成功后应开始新局"

    # 场景22：count==32 开局位置 -> 识别到即可返回，无需等稳定
    collector.clear()
    capture_queue = [
        _stable_frame(_full_start_board()),
    ]
    s, dev = _make_session(collector)
    s.capture.grab = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("下一关", 712, 2198, True),
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is True, collector.logs
    assert s.state.phase == "开局", s.state.phase
    assert any("下一局开始" in m for m in collector.logs), collector.logs

    # 场景23：count==32 仅一方偏离一步 -> 同样快速返回
    collector.clear()
    one_move_board = _full_start_board()
    one_move_board[7][1] = None
    one_move_board[7][4] = "r_C"
    capture_queue = [
        _stable_frame(one_move_board),
    ]
    s, dev = _make_session(collector)
    s.capture.grab = lambda: capture_queue.pop(0)  # type: ignore[method-assign]
    hit_queue = [
        ("下一关", 712, 2198, True),
        None,
    ]
    s.auto_next_handler._scan_text = (  # type: ignore[method-assign]
        lambda img=None: hit_queue.pop(0) if hit_queue else None
    )
    ok = s._auto_next_game()
    assert ok is True, collector.logs
    assert any("下一局开始" in m for m in collector.logs), collector.logs
