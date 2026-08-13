"""图片识别：模板匹配、整盘分析、两图对比。"""

import cv2
import numpy as np

from xiangqi_bot import config
from xiangqi_bot.board import COLS, GRID_CENTERS_NP, ROWS, center_screen

Templates = dict[str, np.ndarray]


def load_templates() -> Templates:
    """加载 templates/*.png，返回 {棋子ID: 模板图(BGR)}"""
    templates: Templates = {}
    for path in sorted(config.TEMPLATES_DIR.glob("*.png")):
        tpl = cv2.imread(str(path))
        if tpl is None:
            raise RuntimeError(f"无法读取模板图片: {path}")
        templates[path.stem] = tpl
    return templates


def analyze_cell(img: np.ndarray, r: int, c: int, templates: Templates) -> str | None:
    """分析某格的棋子 ID，空格返回 None"""
    px, py = center_screen(r, c)
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
    """分析整盘 90 格，返回 10x9 布局"""
    return [[analyze_cell(img, r, c, templates) for c in range(COLS)] for r in range(ROWS)]


def diff_cells(prev_img: np.ndarray, cur_img: np.ndarray) -> set[tuple[int, int]]:
    """对比两张截图中心点 10x10 区域，返回有变化的格子集合"""
    changed: set[tuple[int, int]] = set()
    half = config.DIFF_WINDOW // 2
    for r in range(ROWS):
        for c in range(COLS):
            x, y = GRID_CENTERS_NP[r, c]
            px, py = round(float(x)), round(float(y))
            prev_region = prev_img[py - half : py + half, px - half : px + half]
            cur_region = cur_img[py - half : py + half, px - half : px + half]
            diff = float(np.abs(prev_region.astype(np.int16) - cur_region.astype(np.int16)).mean())
            if diff > config.DIFF_THRESHOLD:
                changed.add((r, c))
    return changed
