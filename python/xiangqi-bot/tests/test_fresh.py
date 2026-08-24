"""刚开局局面自动对弈（5 个）。"""

from __future__ import annotations

import numpy as np

from xiangqi_bot.board import START_SQUARES, make_empty_board
from xiangqi_bot.game import opening
from xiangqi_bot.game import session as game
from xiangqi_bot.game.state import Phase, Side

from .conftest import LogCollector, MockDevice


def _full_board(side: str) -> list[list[str | None]]:
    b = make_empty_board()
    for pid, sqs in START_SQUARES.items():
        for r, c in sqs:
            tr, tc = (9 - r, c) if side == "black" else (r, c)
            b[tr][tc] = pid
    return b


def _move_piece(b: list[list[str | None]], r1: int, c1: int, r2: int, c2: int) -> None:
    b[r2][c2] = b[r1][c1]
    b[r1][c1] = None


def _make_session(
    board: list[list[str | None]], side: str, collector: LogCollector
) -> tuple[game.GameSession, list[int]]:
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)

    def fake_init(corrected: np.ndarray) -> bool:
        my_side = Side(side)
        s.state.board = [row[:] for row in board]
        s.state.prev_board = [row[:] for row in board]
        s.state.my_side = my_side
        s.state.phase = opening.detect_phase(board, my_side)
        return True

    s.capture.grab = lambda: np.zeros((1000, 900, 3), np.uint8)  # type: ignore[method-assign]
    s._initialize = fake_init  # type: ignore[method-assign]
    started: list[int] = []
    s._start_flow = lambda: started.append(1)  # type: ignore[method-assign]
    return s, started


def test_fresh_one_move(collector: LogCollector) -> None:
    """5 个刚开局场景顺序执行"""

    # 场景1：我方黑方，红方（上方）只走一步 h2e2，应自动开局
    b = _full_board("black")
    _move_piece(b, 2, 7, 2, 4)
    s, started = _make_session(b, "black", collector)
    s.start()
    assert started, "场景1：对方走一步应自动开局"
    assert s.state.phase == "开局", f"场景1：应判为开局，实际 {s.state.phase}"
    assert s.state.turn == Side.BLACK, f"场景1：应轮到黑方，实际 {s.state.turn}"

    # 场景2：我方红方，无人走棋（开局局面），应自动开局
    collector.clear()
    b = _full_board("red")
    s, started = _make_session(b, "red", collector)
    s.start()
    assert started, "场景2：开局局面应自动开局"

    # 场景3：双方各走一步，不应自动开局
    collector.clear()
    b = _full_board("black")
    _move_piece(b, 2, 7, 2, 4)
    _move_piece(b, 7, 7, 7, 4)
    s, started = _make_session(b, "black", collector)
    s.start()
    assert not started, "场景3：双方各走一步不应自动开局"

    # 场景4：残局（棋子 < 24），不应自动开局
    collector.clear()
    b = make_empty_board()
    b[0][4] = "b_k"
    b[9][4] = "r_K"
    b[0][0] = "b_r"
    b[9][0] = "r_R"
    s, started = _make_session(b, "black", collector)
    s.start()
    assert not started, "场景4：残局不应自动开局"

    # 场景5：对方走了多步（偏离不止一格），不应自动开局
    collector.clear()
    b = _full_board("black")
    _move_piece(b, 2, 7, 2, 4)
    _move_piece(b, 0, 7, 2, 6)
    assert opening.detect_phase(b, Side.BLACK) != Phase.OPENING, "场景5：对方走多步不满足刚开局"


def test_endgame_24_pieces() -> None:
    """24 子双将俱全（残局关卡摆棋中）应判残局——回归旧三态「中局」误判事故场景"""
    b = _full_board("red")
    # 双方各撤 4 兵卒 -> 24 子，将帅俱全（闯关排局典型形态）
    for r, c in ((3, 0), (3, 2), (3, 4), (3, 6), (6, 0), (6, 2), (6, 4), (6, 6)):
        b[r][c] = None
    assert sum(cell is not None for row in b for cell in row) == 24
    assert opening.detect_phase(b, Side.RED) == Phase.ENDGAME
    assert opening.infer_turn(b, Side.RED, Phase.ENDGAME) is None
