"""common/paths.py —— 目录约定与模型权重解析。

所有路径常量与 `resolve_model()` 集中于此，供 board/classes/io_utils/templates/pose 等子模块复用。
"""

from __future__ import annotations

from pathlib import Path

# ---------------- 目录约定 ----------------
# 本文件位于 <root>/src/yolo_chess/common/paths.py
# 项目根为 src 的上一级：common -> yolo_chess -> src -> <root>
PROJECT_ROOT = Path(__file__).resolve().parents[3]
SHARED_ROOT = PROJECT_ROOT / "shared"
SHARED_RAW = SHARED_ROOT / "raw"
SHARED_TEMPLATES = SHARED_ROOT / "templates"
CORNERS_JSON = PROJECT_ROOT / "corners.json"
WEIGHTS_DIR = PROJECT_ROOT / "weights"

CLS_ROOT = PROJECT_ROOT / "cls"
CLS_CELLS = CLS_ROOT / "cells"
CLS_CELLS_DEDUP = CLS_ROOT / "cells_dedup"
CLS_DATASET = CLS_ROOT / "dataset"
CLS_RUNS = CLS_ROOT / "runs"
CLS_EXPORT = CLS_ROOT / "export"

DET_ROOT = PROJECT_ROOT / "det"
DET_DATASET = DET_ROOT / "dataset"
DET_RUNS = DET_ROOT / "runs"
DET_EXPORT = DET_ROOT / "export"

POSE_ROOT = PROJECT_ROOT / "pose"
POSE_DATASET = POSE_ROOT / "dataset"
POSE_RUNS = POSE_ROOT / "runs"
POSE_EXPORT = POSE_ROOT / "export"


def resolve_model(name: str) -> str:
    """把 Ultralytics 模型名解析为本地权重路径。

    优先用 weights/（=Ultralytics 的 WEIGHTS_DIR）下的同名文件（避免从 github 下载）；
    用户给的是已存在路径或本地无同名文件时按原样返回（让 Ultralytics 回退下载）。
    """
    if Path(name).exists():
        return name
    local = WEIGHTS_DIR / Path(name).name
    if local.exists():
        return str(local)
    return name
