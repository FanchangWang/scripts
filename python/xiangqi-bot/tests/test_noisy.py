"""噪声/提起/认输画面/噪声上限（4 个）。"""

from __future__ import annotations

from typing import Any

from xiangqi_bot.board import make_empty_board
from xiangqi_bot.game import session as game

from .conftest import LogCollector, MockDevice, frame


def _base_board() -> list[list[str | None]]:
    b = make_empty_board()
    b[0][4] = "b_k"
    b[9][4] = "r_K"
    return b


def _make_session(collector: LogCollector) -> game.GameSession:
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    s.board = _base_board()
    s.prev = object()
    s.my_side = "red"
    s._turn = "red"
    s._running = True
    s._lift_logged = False
    s._noisy_count = 0
    s.engine.best_move = lambda fen: "e2e3"  # type: ignore[method-assign]
    return s


def test_noisy_detection(collector: LogCollector) -> None:
    """4 个噪声/认输场景顺序执行"""
    game.time.sleep = collector.sleep  # type: ignore[assignment]

    # 场景1：多格伪变动不提交，延时复检后干净一步正常走棋
    s = _make_session(collector)
    b = s.board
    b[3][0] = "b_r"
    b[2][2] = "r_N"
    b[4][6] = "b_p"
    real = frame({(3, 0): None, (0, 0): "b_r"}, _base_board(), {(3, 0), (0, 0)})
    noisy = frame(
        {(3, 0): None, (0, 0): "b_r", (2, 2): None, (4, 6): None},
        _base_board(),
        {(3, 0), (0, 0), (2, 2), (4, 6)},
    )
    r = s._detect_enemy(noisy)
    assert r == "noisy", f"多格变动应判定 noisy，实际 {r}"
    assert s.board[3][0] == "b_r" and s.board[0][0] is None, "噪声帧不应提交任何变动"
    assert s.board[2][2] == "r_N" and s.board[4][6] == "b_p", "噪声帧不应误删我方傌/黑卒"
    r = s._detect_enemy(real)
    assert r == "moved", f"干净一步应判定 moved，实际 {r}"
    assert s.board[3][0] is None and s.board[0][0] == "b_r", "应提交真实一步"
    assert s.board[2][2] == "r_N" and s.board[4][6] == "b_p", "伪变动不应污染棋盘"

    # 场景2：单枚敌方棋子消失 -> 提示提起棋子（仅一次），不提交
    collector.clear()
    s = _make_session(collector)
    s.board[3][0] = "b_r"
    full = _base_board()
    full[3][0] = "b_r"
    frames: list[dict[str, Any]] = [
        frame({(3, 0): None}, full, {(3, 0)}),
        frame({(3, 0): None}, full, {(3, 0)}),
        frame({(3, 0): None, (0, 0): "b_r"}, full, {(3, 0), (0, 0)}),
    ]
    queue = list(frames)
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    s._wait_for_enemy_move()
    lift_msgs = [m for m in collector.logs if "检测到敌方提起棋子" in m]
    assert len(lift_msgs) == 1, f"应仅提示一次提起棋子，实际 {lift_msgs}"
    assert s.board[3][0] is None and s.board[0][0] == "b_r", "应提交真实一步"
    assert s._noisy_count == 0, "lifted/none 应复位噪声计数"

    # 场景3：多枚棋子消失（对局结束画面）-> 不提示提起棋子
    collector.clear()
    s = _make_session(collector)
    s.board[3][0] = "b_r"
    s.board[4][6] = "b_p"
    s.board[5][6] = "b_n"
    over_real = _base_board()
    frames = [
        frame({(3, 0): None, (4, 6): None, (5, 6): None}, over_real, {(3, 0), (4, 6), (5, 6)}),
        frame({(3, 0): None, (4, 6): None, (5, 6): None}, over_real, {(3, 0), (4, 6), (5, 6)}),
        frame({(3, 0): None, (4, 6): None, (5, 6): None}, over_real, {(3, 0), (4, 6), (5, 6)}),
    ]
    queue = list(frames)
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    s._wait_for_enemy_move()
    assert s.game_over is True, "多枚棋子消失连续帧应确认对局结束"
    assert not any("检测到敌方提起棋子" in m for m in collector.logs), (
        f"结束画面不应提示提起棋子，{collector.logs}"
    )
    assert s.board[3][0] == "b_r" and s.board[4][6] == "b_p", "结束确认不应污染内存棋盘"

    # 场景4：连续噪声帧达上限 -> 兜底按实际变动提交
    collector.clear()
    s = _make_session(collector)
    s.board[3][0] = "b_r"
    s.board[2][2] = "r_N"
    full = _base_board()
    full[3][0] = "b_r"
    full[2][2] = "r_N"
    frames = [
        frame({(3, 0): None, (2, 2): None}, full, {(3, 0), (2, 2)}),
        frame({(3, 0): None, (2, 2): None}, full, {(3, 0), (2, 2)}),
        frame({(3, 0): None, (2, 2): None}, full, {(3, 0), (2, 2)}),
    ]
    queue = list(frames)
    s._capture = lambda: queue.pop(0)  # type: ignore[method-assign]
    s._wait_for_enemy_move()
    assert any("无法推断敌方完整走法" in m for m in collector.logs), collector.logs
    assert s.board[3][0] is None and s.board[2][2] is None, "兜底应提交实际变动"
    assert collector.sleeps.count(0.5) == 2, f"前两帧应各延时 0.5s 复检，实际 {collector.sleeps}"
