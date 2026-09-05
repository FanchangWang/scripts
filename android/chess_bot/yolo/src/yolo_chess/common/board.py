"""common/board.py —— 棋盘几何、四角坐标与透视矫正。

包含：网格维度、矫正空间尺寸、DEFAULT_CORNERS 真值、corners.json 缓存读写、
_dst_points / resolve_homography / correct_board、corrected_center / crop_cell。
"""

from __future__ import annotations

import json

import cv2
import numpy as np

from yolo_chess.common.paths import CORNERS_JSON

# ---------------- 棋盘几何 ----------------
COLS, ROWS = 9, 10
CORRECT_CELL = 100
CORRECT_W = CORRECT_CELL * COLS  # 900
CORRECT_H = CORRECT_CELL * ROWS  # 1000
CELL_OUT = 64
HALF = CELL_OUT // 2

# ---------------- 棋盘四角 ----------------
DEFAULT_CORNERS: dict[tuple[int, int], tuple[tuple[float, float], ...]] = {
    (900, 1600): ((62.0, 364.0), (838.0, 364.0), (52.5, 1219.5), (848.5, 1219.5)),
    (1080, 2376): ((76.0, 667.0), (1004.0, 667.0), (67.0, 1688.0), (1014.0, 1688.0)),
    (1080, 2400): ((76.0, 679.0), (1004.0, 679.0), (67.0, 1699.0), (1014.0, 1699.0)),
    (1220, 2712): ((85.5, 768.5), (1134.5, 768.5), (75.5, 1920.5), (1145.5, 1920.5)),
    (1440, 3200): ((101.5, 905.5), (1339.5, 905.5), (89.0, 2266.0), (1352.0, 2266.0)),
}

_H_CACHE: dict[tuple[int, int], np.ndarray] = {}


def load_corners_json() -> dict[str, list]:
    if CORNERS_JSON.exists():
        try:
            return json.loads(CORNERS_JSON.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def save_corners_json(data: dict[str, list]) -> None:
    CORNERS_JSON.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def _dst_points() -> np.ndarray:
    return np.array(
        [
            corrected_center(0, 0),
            corrected_center(0, COLS - 1),
            corrected_center(ROWS - 1, 0),
            corrected_center(ROWS - 1, COLS - 1),
        ],
        np.float32,
    )


def resolve_homography(img: np.ndarray) -> np.ndarray:
    """按源截图分辨率取 3x3 透视矩阵。

    优先查 DEFAULT_CORNERS，再查 corners.json 缓存。
    两者都无则抛出异常（不再支持交互选点）。
    """
    h, w = img.shape[:2]
    key = (int(w), int(h))
    if key in _H_CACHE:
        return _H_CACHE[key]

    data = load_corners_json()
    k = f"{w},{h}"
    if key in DEFAULT_CORNERS:
        src = np.array(DEFAULT_CORNERS[key], np.float32)
    elif k in data:
        src = np.array(data[k], np.float32)
    else:
        raise RuntimeError(
            f"分辨率 {k} 未收录于 DEFAULT_CORNERS 或 corners.json。\n"
            f'请手动编辑 {CORNERS_JSON}，添加键 "{k}": '
            "[[x1,y1],[x2,y2],[x3,y3],[x4,y4]]（顺序 左上,右上,左下,右下）后重试。"
        )

    H = cv2.getPerspectiveTransform(src, _dst_points())
    _H_CACHE[key] = H
    return H


def correct_board(img: np.ndarray) -> np.ndarray:
    """源截图 -> 矫正棋盘 (900x1000 BGR)。"""
    H = resolve_homography(img)
    return cv2.warpPerspective(img, H, (CORRECT_W, CORRECT_H))


def corrected_center(r: int, c: int) -> tuple[float, float]:
    """网格 -> 矫正棋盘中心坐标（900x1000 空间）。"""
    return CORRECT_CELL * (c + 0.5), CORRECT_CELL * (r + 0.5)


def crop_cell(corrected: np.ndarray, r: int, c: int) -> np.ndarray | None:
    """从矫正棋盘裁出 (r,c) 处 64x64 棋子格；越界返回 None。"""
    h, w = corrected.shape[:2]
    cx, cy = corrected_center(r, c)
    x1 = int(max(0, min(w - CELL_OUT, round(cx - HALF))))
    y1 = int(max(0, min(h - CELL_OUT, round(cy - HALF))))
    cell = corrected[y1 : y1 + CELL_OUT, x1 : x1 + CELL_OUT]
    if cell.shape[:2] != (CELL_OUT, CELL_OUT):
        return None
    return cell
