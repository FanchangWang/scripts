"""中国象棋自动脚本（网页版）。"""

import warnings

warnings.filterwarnings("ignore", category=SyntaxWarning)

from xiangqi_bot.main import main  # noqa: E402

__all__ = ["main"]
