"""绝杀探测（2 个）。"""

from __future__ import annotations

import numpy as np

from xiangqi_bot import vision
from xiangqi_bot.game import session as game

from .conftest import LogCollector, MockDevice


def test_checkmate_probe(collector: LogCollector) -> None:
    """2 个绝杀探测场景顺序执行"""

    r1, c1, r2, c2, piece = 8, 4, 7, 4, "r_P"

    # 场景1：提子分支探测（未绝杀）
    dev = MockDevice()
    s = game.GameSession(dev, collector.log, collector.on_state, None)
    s.prev = np.zeros((1000, 900, 3), np.uint8)
    s.my_side = "red"
    vision.analyze_cell = lambda corrected, r, c, templates: (  # type: ignore[assignment]
        piece if (r, c) in ((r1, c1), (r2, c2)) else None
    )
    s._enemy_changes = lambda corrected: [(2, 2, "b_c", None)]  # type: ignore[method-assign]
    s._infer_move = lambda changes: None  # type: ignore[method-assign]
    probed: list[str] = []
    s.engine.is_mate = lambda fen, ms: probed.append(fen) or False  # type: ignore[method-assign]
    s._apply_move_result(s.prev, r1, c1, r2, c2, piece)
    assert len(probed) == 1, f"提子分支也应绝杀探测，实际 {probed}"
    assert not any("检测是否绝杀" in m for m in collector.logs), "检测前不再输出提示日志"
    assert any("未绝杀，继续对局" in m for m in collector.logs)
    assert s.game_over is False
    assert any("棋子被提起" in m for m in collector.logs)

    # 场景2：提子分支绝杀
    collector.clear()
    probed.clear()
    dev2 = MockDevice()
    s2 = game.GameSession(dev2, collector.log, collector.on_state, None)
    s2.prev = np.zeros((1000, 900, 3), np.uint8)
    s2.my_side = "red"
    vision.analyze_cell = lambda corrected, r, c, templates: (  # type: ignore[assignment]
        piece if (r, c) in ((r1, c1), (r2, c2)) else None
    )
    s2._enemy_changes = lambda corrected: [(2, 2, "b_c", None)]  # type: ignore[method-assign]
    s2._infer_move = lambda changes: None  # type: ignore[method-assign]
    s2.engine.is_mate = lambda fen, ms: True  # type: ignore[method-assign]
    s2._apply_move_result(s2.prev, r1, c1, r2, c2, piece)
    assert s2.game_over is True, "绝杀应结束棋局"
    assert any("我方绝杀" in m for m in collector.logs)
