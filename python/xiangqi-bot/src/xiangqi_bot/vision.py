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

_gameover_text_cache: dict[str, np.ndarray] | None = None


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


def load_gameover_text_templates() -> dict[str, np.ndarray]:
    """加载 templates/text/*.png 结算文字模板（灰度），返回 {文字: 模板}"""
    global _gameover_text_cache
    if _gameover_text_cache is None:
        templates: dict[str, np.ndarray] = {}
        for path in sorted(config.GAMEOVER_TEXT_DIR.glob("*.png")):
            tpl = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_GRAYSCALE)
            if tpl is None:
                raise RuntimeError(f"无法读取结算文字模板: {path}")
            templates[path.stem] = tpl
        _gameover_text_cache = templates
    return _gameover_text_cache


def find_gameover_text(
    img: np.ndarray, w: int = 0, h: int = 0
) -> list[tuple[str, int, int, float]]:
    """在原始截图上模板匹配结算文字。

    游戏 UI 随分辨率线性缩放（3200 = 1080 等比 x1.3333），故先把截图等比缩放到
    GAMEOVER_TEMPLATE_W 宽度再匹配，坐标还原到源分辨率。返回所有高于阈值的
    [(文字, 屏幕x, 屏幕y, 匹配分)]（中心点坐标），按分降序。
    """
    if w == 0 or h == 0:
        h, w = img.shape[:2]
    templates = load_gameover_text_templates()
    if not templates:
        return []
    scale = w / config.GAMEOVER_TEMPLATE_W
    if w != config.GAMEOVER_TEMPLATE_W:
        target_h = max(1, round(h / scale))
        interp = cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR
        img = cv2.resize(img, (config.GAMEOVER_TEMPLATE_W, target_h), interpolation=interp)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    matches: list[tuple[str, int, int, float]] = []
    for word, tpl in templates.items():
        result = cv2.matchTemplate(gray, tpl, cv2.TM_CCOEFF_NORMED)
        ys, xs = np.where(result >= config.GAMEOVER_TEXT_THRESHOLD)
        for x, y in zip(xs, ys, strict=False):
            if x > gray.shape[1] - tpl.shape[1] or y > gray.shape[0] - tpl.shape[0]:
                continue
            cx = round((x + tpl.shape[1] / 2) * scale)
            cy = round((y + tpl.shape[0] / 2) * scale)
            matches.append((word, cx, cy, float(result[y, x])))
    return sorted(matches, key=lambda m: m[3], reverse=True)
