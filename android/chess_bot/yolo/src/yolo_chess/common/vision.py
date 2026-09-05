"""common/vision.py —— 检测/姿态共用：letterbox 预处理、误差统计、角点与框绘制。

避免 det_validate 与 pose_validate 各自复制一份 letterbox / 统计 / 绘制逻辑，
所有推断期共享的视觉 helper 集中在此。det/pose 的 `_draw_*` 共用同一套角点命名与连线顺序。
"""

from __future__ import annotations

import cv2
import numpy as np

CORNER_NAMES = ["TL", "TR", "BL", "BR"]
QUAD_ORDER = [0, 1, 3, 2]


def _letterbox(
    img: np.ndarray, new: int = 1280, color: tuple = (114, 114, 114)
) -> tuple[np.ndarray, float, int, int]:
    """等比缩放 + 居中填充到正方形，返回 (填充图, 缩放比, left, top)。"""
    h, w = img.shape[:2]
    r = min(new / h, new / w)
    nw, nh = round(w * r), round(h * r)
    dw, dh = (new - nw) / 2.0, (new - nh) / 2.0
    if (nw, nh) != (w, h):
        img = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
    top, left = round(dh - 0.1), round(dw - 0.1)
    pad = np.full((new, new, 3), color, dtype=np.uint8)
    pad[top : top + nh, left : left + nw] = img
    return pad, r, left, top


def _draw_corners(img: np.ndarray, pts: np.ndarray, color: tuple) -> None:
    for i, (x, y) in enumerate(pts):
        xi, yi = round(x), round(y)
        cv2.circle(img, (xi, yi), 12, color, -1)
        cv2.putText(
            img,
            f"{i}:{CORNER_NAMES[i]}",
            (xi + 14, yi),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.5,
            color,
            2,
        )
    poly = [(round(pts[i][0]), round(pts[i][1])) for i in QUAD_ORDER]
    for a in range(len(poly)):
        cv2.line(img, poly[a], poly[(a + 1) % len(poly)], color, 2)


def _draw_boxes(img: np.ndarray, boxes: list, color: tuple) -> None:
    for x1, y1, x2, y2 in boxes:
        cv2.rectangle(
            img,
            (round(x1), round(y1)),
            (round(x2), round(y2)),
            color,
            1,
        )


def _stats(a: np.ndarray) -> dict:
    return {
        "n": int(a.size),
        "mae": float(np.mean(a)),
        "rmse": float(np.sqrt(np.mean(a**2))),
        "median": float(np.median(a)),
        "p95": float(np.percentile(a, 95)),
        "max": float(np.max(a)),
    }
