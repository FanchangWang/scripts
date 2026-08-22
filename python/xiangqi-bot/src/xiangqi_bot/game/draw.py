"""和棋弹窗决策（纯函数）。

弹窗的检测（模板匹配）和点击（ADB）在 IO 层 Capture 类，决策（分数判断）在此。
"""

from __future__ import annotations

from xiangqi_bot.game.state import DrawDecision


def decide(score: int, reject_cp: int) -> DrawDecision:
    """score 为我方评估分（正=我方占优）；超过 reject_cp 拒绝，否则同意。"""
    return "reject" if score > reject_cp else "accept"
