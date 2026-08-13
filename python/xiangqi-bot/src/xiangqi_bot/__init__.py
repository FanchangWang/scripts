"""中国象棋自动脚本。"""

import warnings

warnings.filterwarnings("ignore", category=SyntaxWarning)

from xiangqi_bot.app import main  # noqa: E402

__all__ = ["main"]
