from __future__ import annotations

from importlib.metadata import PackageNotFoundError, version

from yolo_chess.common import PROJECT_ROOT

try:
    __version__ = version("yolo-chess")
except PackageNotFoundError:
    __version__ = "0.1.0"

__all__ = ["PROJECT_ROOT", "__version__"]
