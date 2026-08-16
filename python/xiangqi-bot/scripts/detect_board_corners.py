# /// script
# dependencies = [
#   "opencv-python",
#   "numpy"
# ]
# ///
"""自动检测棋盘四角格中心。

不依赖已知分辨率或棋盘位置：通过红/黑棋子 blob 的颜色检测 + 行带结构分配，
自动拟合"开局布局网格 -> 屏幕坐标"的单应矩阵，输出四角格中心（row 0/9 x col 0/8）。

用法:
    python scripts/detect_board_corners.py <图片路径> [--save-board]
"""

from pathlib import Path
import argparse
import cv2
import numpy as np

ROW_PATTERN = (9, 2, 5, 5, 2, 9)  # 开局布局各行棋子数（从上到下：row0/2/3/6/7/9）
PAWN_COLS = (0, 2, 4, 6, 8)  # 兵/卒行列号
CANNON_COLS = (1, 7)  # 炮/砲行列号


def imread_unicode(path: Path) -> np.ndarray:
    img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(f"无法读取图片: {path}")
    return img


def _candidates(img: np.ndarray) -> list[tuple[float, float, float]]:
    """检测红、黑棋子，返回 [(cx, cy, area), ...]（外接圆中心，字符不影响）"""
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    h, s, v = hsv[:, :, 0], hsv[:, :, 1], hsv[:, :, 2]

    # 红子：红色调高饱和；黑子：低饱和圆盘（木盘 S~113，黑子盘 S~60）
    red_mask = (((h < 8) | (h > 175)) & (s > 100) & (v > 70)).astype(np.uint8) * 255
    black_mask = ((s < 85) & (v > 70)).astype(np.uint8) * 255

    out: list[tuple[float, float, float]] = []
    for mask, area_min, area_max in ((red_mask, 1500, 9000), (black_mask, 2500, 15000)):
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8))
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((7, 7), np.uint8))
        cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        for c in cnts:
            area = float(cv2.contourArea(c))
            if not (area_min <= area <= area_max):
                continue
            (cx, cy), _r = cv2.minEnclosingCircle(c)
            out.append((cx, cy, area))
    return out


def _row_bands(cands: list[tuple[float, float, float]]) -> list[list[tuple[float, float, float]]]:
    """按 y 聚成行带；容差取 0.6 x 中位棋子直径（与分辨率无关）"""
    if not cands:
        return []
    median_area = float(np.median([c[2] for c in cands]))
    diameter = 2.0 * np.sqrt(median_area / np.pi)
    tol = 0.6 * diameter
    ordered = sorted(cands, key=lambda c: c[1])
    bands: list[list[tuple[float, float, float]]] = [[ordered[0]]]
    for c in ordered[1:]:
        if c[1] - bands[-1][-1][1] <= tol:
            bands[-1].append(c)
        else:
            bands.append([c])
    return bands


def _select_core(
    bands: list[list[tuple[float, float, float]]],
) -> list[list[tuple[float, float, float]]]:
    """在候选行带中滑动窗口，选取最符合开局布局的 6 个连续行带（过滤 UI 噪声）"""
    best_i, best_score = -1, 10**9
    for i in range(len(bands) - 5):
        counts = tuple(len(b) for b in bands[i : i + 6])
        score = sum(abs(a - b) for a, b in zip(counts, ROW_PATTERN, strict=True))
        if score < best_score:
            best_score, best_i = score, i
    core = bands[best_i : best_i + 6]
    if best_score > 0:
        print(
            f"警告: 行带棋子数与开局布局不完全一致（偏差 {best_score}），"
            f"取偏差最小的窗口: {tuple(len(b) for b in core)}"
        )
    return core


def _assign(rows: list[list[tuple[float, float, float]]]) -> tuple[np.ndarray, np.ndarray]:
    """将开局布局映射到检测到的行带，返回 (网格点, 屏幕点) 对应集"""
    counts = tuple(len(r) for r in rows)
    if counts != ROW_PATTERN:
        raise ValueError(
            f"检测到 {len(rows)} 个行带，棋子数 {counts}，不符合开局布局 {ROW_PATTERN}；"
            "请确认截图是开局位置（黑/红初始布局）"
        )
    grid_rows = (0, 2, 3, 6, 7, 9)  # 行带 -> 网格行
    pts_grid: list[np.ndarray] = []
    pts_img: list[np.ndarray] = []
    for band, g_row in zip(rows, grid_rows, strict=True):
        band_sorted = sorted(band, key=lambda c: c[0])
        if len(band) == 9:
            cols = range(9)
        elif len(band) == 5:
            cols = PAWN_COLS
        else:
            cols = CANNON_COLS
        for (cx, cy, _), col in zip(band_sorted, cols, strict=True):
            pts_grid.append(np.array([col, g_row], dtype=np.float32))
            pts_img.append(np.array([cx, cy], dtype=np.float32))
    return np.array(pts_grid), np.array(pts_img)


def detect_board_corners(img: np.ndarray) -> tuple[np.ndarray, np.ndarray, int]:
    """检测棋盘四角格中心（row 0/9 x col 0/8，原图像素坐标）。

    返回 (corners, H, inliers)：corners 为 4x2（左上/右上/左下/右下），
    H 为网格坐标(col,row) -> 屏幕像素 的单应矩阵。
    """
    cands = _candidates(img)
    if len(cands) < 4:
        raise ValueError(f"只检测到 {len(cands)} 个棋子候选，无法定位棋盘")
    bands = _row_bands(cands)
    core = _select_core(bands)
    pts_grid, pts_img = _assign(core)
    h_mat, mask = cv2.findHomography(pts_grid, pts_img, cv2.RANSAC, 4.0)
    if h_mat is None:
        raise ValueError("单应矩阵拟合失败")
    inliers = int(mask.sum())
    corners = np.array([[0, 0], [8, 0], [0, 9], [8, 9]], dtype=np.float32)
    corners_img = cv2.perspectiveTransform(corners.reshape(-1, 1, 2), h_mat).reshape(4, 2)
    return corners_img, h_mat, inliers


def main() -> None:
    parser = argparse.ArgumentParser(description="自动检测棋盘四角格中心")
    parser.add_argument("image", help="截图路径")
    parser.add_argument("--save-board", action="store_true", help="保存矫正后的棋盘预览")
    args = parser.parse_args()

    img = imread_unicode(Path(args.image))
    print(f"图片尺寸: {img.shape[1]}x{img.shape[0]}")
    corners, _h_mat, inliers = detect_board_corners(img)
    print(f"单应拟合内点数: {inliers}/32")
    print("四角格中心（row0/9 x col0/8）:")
    for tag, (x, y) in zip(("左上", "右上", "左下", "右下"), corners, strict=True):
        print(f"  {tag} = ({x:.1f}, {y:.1f})")
    if args.save_board:
        dst = np.array([[50, 50], [850, 50], [50, 950], [850, 950]], dtype=np.float32)
        m_mat = cv2.getPerspectiveTransform(corners.astype(np.float32), dst)
        board = cv2.warpPerspective(img, m_mat, (900, 1000), flags=cv2.INTER_CUBIC)
        out = Path(args.image).with_name(f"{Path(args.image).stem}_board.png")
        cv2.imencode(".png", board)[1].tofile(str(out))
        print(f"矫正棋盘已保存: {out}")


if __name__ == "__main__":
    main()
