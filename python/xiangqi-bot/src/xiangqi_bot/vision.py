"""图片识别：透视矫正、矫正空间模板匹配、两图对比。

所有分析均在"矫正棋盘"上进行：源截图先按分辨率查 `config.BOARD_CORNERS`
做透视矫正（warpPerspective 到固定 900x1000 空间），再在矫正空间内匹配模板，
因此匹配与源分辨率无关（同一游戏画面在任意分辨率下识别结果一致）。
"""

import cv2
import numpy as np

from xiangqi_bot import config
from xiangqi_bot.board import COLS, ROWS, corrected_center

Templates = dict[str, np.ndarray]

_HOMOGRAPHY_CACHE: dict[tuple[int, int], np.ndarray] = {}


def load_templates() -> Templates:
    """加载 templates/*.png，返回 {棋子ID: 模板图(BGR)}"""
    templates: Templates = {}
    for path in sorted(config.TEMPLATES_DIR.glob("*.png")):
        tpl = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
        if tpl is None:
            raise RuntimeError(f"无法读取模板图片: {path}")
        templates[path.stem] = tpl
    return templates


def homography(w: int, h: int) -> np.ndarray:
    """按源截图分辨率取 4 角格中心 -> 矫正空间对应格中心，返回 3x3 透视矩阵"""
    key = (int(w), int(h))
    if key not in _HOMOGRAPHY_CACHE:
        corners = config.BOARD_CORNERS.get(key)
        if corners is None:
            raise RuntimeError(f"未配置 {w}x{h} 分辨率的棋盘四角坐标")
        src = np.array(corners, np.float32)
        dst = np.array(
            [
                corrected_center(0, 0),
                corrected_center(0, COLS - 1),
                corrected_center(ROWS - 1, 0),
                corrected_center(ROWS - 1, COLS - 1),
            ],
            np.float32,
        )
        _HOMOGRAPHY_CACHE[key] = cv2.getPerspectiveTransform(src, dst)
    return _HOMOGRAPHY_CACHE[key]


def correct_board(img: np.ndarray) -> np.ndarray:
    """源截图 -> 矫正棋盘（900x1000）"""
    h, w = img.shape[:2]
    return cv2.warpPerspective(img, homography(w, h), (config.CORRECT_W, config.CORRECT_H))


def tap_xy(h_matrix: np.ndarray, r: int, c: int) -> tuple[int, int]:
    """矫正空间网格格 -> 源截图屏幕坐标（逆透视映射，用于模拟点击）"""
    x, y = corrected_center(r, c)
    src = cv2.perspectiveTransform(np.array([[[x, y]]], np.float32), np.linalg.inv(h_matrix))
    sx, sy = src[0, 0]
    return round(float(sx)), round(float(sy))


def analyze_cell(img: np.ndarray, r: int, c: int, templates: Templates) -> str | None:
    """分析矫正棋盘某格的棋子 ID，空格返回 None"""
    px, py = corrected_center(r, c)
    px, py = round(px), round(py)
    half = config.MATCH_SEARCH_HALF + config.TEMPLATE_SIZE // 2
    x1 = max(0, px - half)
    y1 = max(0, py - half)
    window = img[y1 : py + half, x1 : px + half]
    best_id: str | None = None
    best_score = -1.0
    for piece_id, tpl in templates.items():
        result = cv2.matchTemplate(window, tpl, cv2.TM_CCOEFF_NORMED)
        _, max_val, _, _ = cv2.minMaxLoc(result)
        if max_val > best_score:
            best_score = max_val
            best_id = piece_id
    if best_id is None or best_score < config.EMPTY_MATCH_THRESHOLD:
        return None
    return best_id


def analyze_board(img: np.ndarray, templates: Templates) -> list[list[str | None]]:
    """分析矫正棋盘 90 格，返回 10x9 布局"""
    return [[analyze_cell(img, r, c, templates) for c in range(COLS)] for r in range(ROWS)]


def diff_cells(prev_img: np.ndarray, cur_img: np.ndarray) -> set[tuple[int, int]]:
    """对比两张矫正棋盘中心点 10x10 区域，返回有变化的格子集合"""
    changed: set[tuple[int, int]] = set()
    half = config.DIFF_WINDOW // 2
    for r in range(ROWS):
        for c in range(COLS):
            x, y = corrected_center(r, c)
            px, py = round(x), round(y)
            prev_region = prev_img[py - half : py + half, px - half : px + half]
            cur_region = cur_img[py - half : py + half, px - half : px + half]
            diff = float(np.abs(prev_region.astype(np.int16) - cur_region.astype(np.int16)).mean())
            if diff > config.DIFF_THRESHOLD:
                changed.add((r, c))
    return changed
