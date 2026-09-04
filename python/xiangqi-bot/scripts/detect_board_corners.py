# /// script
# dependencies = [
#   "opencv-python",
#   "numpy"
# ]
# ///
"""基于棋子模板自动检测棋盘四角格中心。

不依赖颜色 blob：直接在中国象棋开局截图上，用黑車 (b_r.png) 和红俥 (r_R.png)
两个角子模板做多尺度模板匹配，定位 4 个角子的中心，即棋盘四角格中心。

用法:
    uv run python scripts/detect_board_corners.py [--save-board]
"""

from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

# 角子模板：黑車在上方两角，红俥在下方两角。
# 按截图方向自适应：y 较小的两个为上方角，y 较大的两个为下方角。
CORNER_TEMPLATES = ("b_r.png", "r_R.png")

# 模板在矫正棋盘上的基准大小（像素）
TEMPLATE_BASE_SIZE = 60

# 棋盘在矫正空间中的尺寸
CORRECT_W = 900
CORRECT_H = 1000
CORRECT_CELL = 100

# 默认匹配阈值与尺度搜索参数
DEFAULT_MATCH_THRESHOLD = 0.55
FALLBACK_MATCH_THRESHOLD = 0.45
SCALE_RANGE_FACTOR = 0.30  # 在基准尺度上下浮动 30%
MIN_SCALE = 0.40
MAX_SCALE = 2.50


def imread_unicode(path: Path) -> np.ndarray:
    """读取图片，支持中文路径。"""
    img = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(f"无法读取图片: {path}")
    return img


def imwrite_unicode(path: Path, img: np.ndarray) -> None:
    """保存图片，支持中文路径。"""
    ext = path.suffix or ".png"
    ok, buf = cv2.imencode(ext, img)
    if not ok:
        raise RuntimeError(f"无法编码图片: {path}")
    buf.tofile(str(path))


def load_corner_templates(templates_dir: Path) -> tuple[np.ndarray, np.ndarray]:
    """加载黑車、红俥两个角子模板（灰度）。"""
    templates = []
    for name in CORNER_TEMPLATES:
        path = templates_dir / name
        tpl = cv2.imdecode(np.fromfile(str(path), dtype=np.uint8), cv2.IMREAD_GRAYSCALE)
        if tpl is None:
            raise FileNotFoundError(f"无法读取模板图片: {path}")
        templates.append(tpl)
    return templates[0], templates[1]


def _estimate_scale(img_width: int) -> float:
    """根据截图宽度估计角子模板尺度。

    经验：棋盘宽约占据截图宽度的 90%，模板在 900px 宽的矫正棋盘上为 60px，
    因此初始尺度 ≈ (img_width * 0.9 / 900) ≈ img_width / 1000。
    """
    return img_width / 1000.0


def _find_template_peaks(
    gray: np.ndarray,
    tmpl: np.ndarray,
    scale_min: float,
    scale_max: float,
    n_scales: int,
    threshold: float,
    min_distance: int,
) -> list[tuple[float, float, float, float]]:
    """多尺度模板匹配，返回检测到的峰值列表 (score, cx, cy, scale)。

    每个尺度先做局部非极大值抑制，再跨尺度做全局非极大值抑制，
    最终按匹配分从高到低排序。
    """
    h, w = gray.shape
    th0, tw0 = tmpl.shape
    scales = np.linspace(scale_min, scale_max, n_scales)

    all_peaks: list[tuple[float, float, float, float]] = []
    for scale in scales:
        new_w = max(1, int(round(tw0 * scale)))
        new_h = max(1, int(round(th0 * scale)))
        if new_w >= w or new_h >= h:
            continue

        resized = cv2.resize(
            tmpl,
            (new_w, new_h),
            interpolation=cv2.INTER_AREA if scale < 1.0 else cv2.INTER_LINEAR,
        )
        result = cv2.matchTemplate(gray, resized, cv2.TM_CCOEFF_NORMED)

        # 局部非极大值抑制：只保留该尺度下的局部最高点
        kernel = np.ones((max(1, min_distance), max(1, min_distance)), np.uint8)
        dilated = cv2.dilate(result, kernel)
        local_max = (result == dilated) & (result >= threshold)
        ys, xs = np.where(local_max)
        for x, y in zip(xs.tolist(), ys.tolist(), strict=False):
            cx = x + new_w / 2.0
            cy = y + new_h / 2.0
            all_peaks.append((float(result[y, x]), float(cx), float(cy), float(scale)))

    # 全局非极大值抑制（跨尺度，避免同一个角子在不同尺度被重复记录）
    all_peaks.sort(key=lambda p: p[0], reverse=True)
    kept: list[tuple[float, float, float, float]] = []
    for score, cx, cy, scale in all_peaks:
        if all(
            abs(cx - kx) >= min_distance or abs(cy - ky) >= min_distance for _, kx, ky, _ in kept
        ):
            kept.append((score, cx, cy, scale))
    return kept


def _detect_side_corners(
    gray: np.ndarray,
    tmpl: np.ndarray,
    base_scale: float,
    label: str,
) -> list[tuple[float, float, float, float]]:
    """检测一个角子模板的所有峰值，先尝试窄尺度范围，失败则扩大搜索。"""
    scale_min = max(MIN_SCALE, base_scale * (1 - SCALE_RANGE_FACTOR))
    scale_max = min(MAX_SCALE, base_scale * (1 + SCALE_RANGE_FACTOR))
    n_scales = max(15, int(round((scale_max - scale_min) / 0.03)) + 1)
    min_distance = max(10, int(round(base_scale * 40)))

    peaks = _find_template_peaks(
        gray, tmpl, scale_min, scale_max, n_scales, DEFAULT_MATCH_THRESHOLD, min_distance
    )
    if len(peaks) < 2:
        print(f"  [{label}] 窄范围只检测到 {len(peaks)} 个峰值，尝试扩大尺度范围...")
        peaks = _find_template_peaks(
            gray,
            tmpl,
            MIN_SCALE,
            MAX_SCALE,
            45,
            FALLBACK_MATCH_THRESHOLD,
            min_distance,
        )
    return peaks


def detect_board_corners(
    img: np.ndarray,
    templates_dir: Path | None = None,
) -> tuple[np.ndarray, np.ndarray, int]:
    """基于角子模板检测棋盘四角格中心。

    返回 (corners, H, matched_count)：
      - corners: 4x2 浮点数组，顺序为 左上、右上、左下、右下（屏幕像素坐标）。
      - H: 网格坐标(col,row) -> 屏幕像素 的单应矩阵。
      - matched_count: 成功匹配的角子数（应为 4）。
    """
    if templates_dir is None:
        templates_dir = Path(__file__).resolve().parent.parent / "templates"

    b_tmpl, r_tmpl = load_corner_templates(templates_dir)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    base_scale = _estimate_scale(img.shape[1])
    print(f"  估计模板尺度: {base_scale:.3f}")

    b_peaks = _detect_side_corners(gray, b_tmpl, base_scale, "黑車")
    r_peaks = _detect_side_corners(gray, r_tmpl, base_scale, "红俥")

    if len(b_peaks) < 2:
        raise ValueError(f"黑車 (b_r) 只检测到 {len(b_peaks)} 个，无法定位上方两角")
    if len(r_peaks) < 2:
        raise ValueError(f"红俥 (r_R) 只检测到 {len(r_peaks)} 个，无法定位下方两角")

    print(f"  黑車 前2匹配分: {b_peaks[0][0]:.3f}, {b_peaks[1][0]:.3f}")
    print(f"  红俥 前2匹配分: {r_peaks[0][0]:.3f}, {r_peaks[1][0]:.3f}")

    # 取每个模板匹配分最高的两个角子。
    # 由于开局时每种 rook 恰好两个，它们就是屏幕同一行的左右两角。
    centers = [(cx, cy) for _, cx, cy, _ in b_peaks[:2]] + [
        (cx, cy) for _, cx, cy, _ in r_peaks[:2]
    ]

    # 按 y 排序，前两个为上方角，后两个为下方角；再按 x 分左右。
    centers_sorted_by_y = sorted(centers, key=lambda c: c[1])
    top_left, top_right = sorted(centers_sorted_by_y[:2], key=lambda c: c[0])
    bottom_left, bottom_right = sorted(centers_sorted_by_y[2:], key=lambda c: c[0])

    corners = np.array([top_left, top_right, bottom_left, bottom_right], dtype=np.float32)

    # 由 4 个网格角点 -> 屏幕角点 计算单应矩阵。
    grid_corners = np.array([[0, 0], [8, 0], [0, 9], [8, 9]], dtype=np.float32)
    h_mat = cv2.getPerspectiveTransform(grid_corners, corners)

    return corners, h_mat, 4


# 固定识别的 3 张开局截图（位于 raw_screenshots 目录）
SAMPLE_IMAGES = (
    # "石_红_1080x2376.png",
    # "石_红_1080x2400.png",
    # "石_红_1440x3200.jpg",
    # "石_红_900x1600.png",
    "水_红_1220x2712.png",
)


def _detect_and_print(
    img: np.ndarray,
    img_path: Path,
    save_board: bool,
) -> tuple[np.ndarray, tuple[int, int]]:
    corners, _h_mat, matched = detect_board_corners(img)
    print(f"成功匹配角子数: {matched}/4")
    print("四角格中心（顺序：左上、右上、左下、右下）:")
    for tag, (x, y) in zip(("左上", "右上", "左下", "右下"), corners, strict=True):
        print(f"  {tag} = ({x:.1f}, {y:.1f})")

    if save_board:
        dst = np.array(
            [
                (CORRECT_CELL / 2, CORRECT_CELL / 2),
                (CORRECT_W - CORRECT_CELL / 2, CORRECT_CELL / 2),
                (CORRECT_CELL / 2, CORRECT_H - CORRECT_CELL / 2),
                (CORRECT_W - CORRECT_CELL / 2, CORRECT_H - CORRECT_CELL / 2),
            ],
            dtype=np.float32,
        )
        m_mat = cv2.getPerspectiveTransform(corners.astype(np.float32), dst)
        board = cv2.warpPerspective(img, m_mat, (CORRECT_W, CORRECT_H), flags=cv2.INTER_CUBIC)
        out = img_path.with_name(f"{img_path.stem}_board.png")
        imwrite_unicode(out, board)
        print(f"矫正棋盘已保存: {out}")

    return corners, (int(img.shape[1]), int(img.shape[0]))


# 你手动标注的四角坐标（部分分辨率），用于对比脚本输出偏差。
MANUAL_REFERENCE: dict[tuple[int, int], tuple[tuple[float, float], ...]] = {
    (900, 1600): ((62.0, 364.0), (838.0, 364.0), (52.5, 1219.5), (848.5, 1219.5)),
    (1080, 2376): ((76.0, 667.0), (1004.0, 667.0), (67.0, 1688.0), (1014.0, 1688.0)),
    (1080, 2400): ((76.0, 679.0), (1004.0, 679.0), (67.0, 1699.0), (1014.0, 1699.0)),
    (1220, 2712): ((85.5, 768.5), (1134.5, 768.5), (75.5, 1920.5), (1145.5, 1920.5)),
    (1440, 3200): ((101.5, 905.5), (1339.5, 905.5), (89.0, 2266.0), (1352.0, 2266.0)),
}


def _format_corners(corners: np.ndarray) -> str:
    pts = ", ".join(f"({x:.1f}, {y:.1f})" for x, y in corners)
    return f"({pts})"


def _print_integrated(results: dict[tuple[int, int], np.ndarray]) -> None:
    """循环结束后，按 config.BOARD_CORNERS 可直接粘贴的格式整合输出。"""
    print("\n" + "=" * 60)
    print("整合结果（可直接粘贴到 config.BOARD_CORNERS）")
    print("=" * 60)
    print("BOARD_CORNERS = {")
    # 按分辨率宽度、再高度排序，输出稳定
    for w, h in sorted(results, key=lambda k: (k[0], k[1])):
        corners = results[(w, h)]
        print(f"    ({w}, {h}): {_format_corners(corners)},")
    print("}")

    if MANUAL_REFERENCE:
        print("\n与手动标注数据对比（最大像素偏差）：")
        for (w, h), manual in MANUAL_REFERENCE.items():
            if (w, h) not in results:
                continue
            corners = results[(w, h)]
            max_dx = max(abs(corners[i, 0] - manual[i][0]) for i in range(4))
            max_dy = max(abs(corners[i, 1] - manual[i][1]) for i in range(4))
            print(f"  ({w}, {h}): dx={max_dx:.1f}px, dy={max_dy:.1f}px")


def main() -> None:
    parser = argparse.ArgumentParser(description="基于棋子模板自动检测棋盘四角格中心")
    parser.add_argument("--save-board", action="store_true", help="保存矫正后的棋盘预览")
    args = parser.parse_args()

    raw_dir = Path(__file__).resolve().parent.parent / "raw_screenshots"
    results: dict[tuple[int, int], np.ndarray] = {}
    for name in SAMPLE_IMAGES:
        img_path = raw_dir / name
        print(f"\n===== {name} =====")
        img = imread_unicode(img_path)
        print(f"图片尺寸: {img.shape[1]}x{img.shape[0]}")
        corners, dims = _detect_and_print(img, img_path, args.save_board)
        results[dims] = corners

    _print_integrated(results)


if __name__ == "__main__":
    main()
