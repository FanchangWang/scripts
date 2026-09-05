"""common/io_utils.py —— 中文路径安全读图、输出目录准备与状态图片遍历。"""

from __future__ import annotations

import shutil
from collections.abc import Iterator
from pathlib import Path

import cv2
import numpy as np

from yolo_chess.common.classes import state_dir


def imread(path: Path) -> np.ndarray | None:
    """中文路径安全的读图。"""
    b = np.fromfile(str(path), dtype=np.uint8)
    if b.size == 0:
        return None
    return cv2.imdecode(b, cv2.IMREAD_COLOR)


def prepare_output_dir(path: Path) -> None:
    """清空已存在的输出目录并重建。"""
    if path.exists():
        shutil.rmtree(path)
        print(f"[清空] 已删除旧输出目录: {path}")
    path.mkdir(parents=True, exist_ok=True)


def iter_state_images(
    states: list[str], output_root: Path
) -> Iterator[tuple[str, list[Path], Path]]:
    """按状态遍历截图，产出 (st, files, out_dir)；自动跳过不存在的空状态。

    - files: 该状态目录下的 png 文件列表（已排序）
    - out_dir: 对应输出子目录（自动创建）
    """
    for st in states:
        sdir = state_dir(st)
        if not sdir.exists():
            print(f"[跳过] 未找到 {sdir}")
            continue
        files = sorted(sdir.glob("*.png"))
        if not files:
            print(f"[跳过] {sdir} 下无 .png")
            continue
        out_dir = output_root / st
        out_dir.mkdir(parents=True, exist_ok=True)
        yield st, files, out_dir
