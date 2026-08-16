# /// script
# dependencies = [
#   "opencv-python",
#   "numpy"
# ]
# ///

import itertools
from pathlib import Path

import cv2
import numpy as np

PROJECT_ROOT = Path(__file__).resolve().parent.parent
PNG_DIR = PROJECT_ROOT / "raw_screenshots"
OUT_DIR = PROJECT_ROOT / "compare_output"

# ========== 10 行 x 9 列 静态网格中心点坐标矩阵 (NumPy 数组) ==========
# 固定于屏幕（1080x2400），与 visualize_grid.py / extract_piece_templates.py 一致
GRID_CENTERS_NP = np.array(
    [
        [76.0, 680],
        [192.0, 680],
        [308.0, 680],
        [424.0, 680],
        [540.0, 680],
        [656.0, 680],
        [772.0, 680],
        [888.0, 680],
        [1004.0, 680],
        [75.0, 792],
        [191.25, 792],
        [307.5, 792],
        [423.75, 792],
        [540.0, 792],
        [656.25, 792],
        [772.5, 792],
        [888.75, 792],
        [1005.0, 792],
        [74.0, 904],
        [190.5, 904],
        [307.0, 904],
        [423.5, 904],
        [540.0, 904],
        [656.5, 904],
        [773.0, 904],
        [889.5, 904],
        [1006.0, 904],
        [73.0, 1016],
        [189.75, 1016],
        [306.5, 1016],
        [423.25, 1016],
        [540.0, 1016],
        [656.75, 1016],
        [773.5, 1016],
        [890.25, 1016],
        [1007.0, 1016],
        [72.0, 1129],
        [189.0, 1129],
        [306.0, 1129],
        [423.0, 1129],
        [540.0, 1129],
        [657.0, 1129],
        [774.0, 1129],
        [891.0, 1129],
        [1008.0, 1129],
        [71.0, 1242],
        [188.25, 1242],
        [305.5, 1242],
        [422.75, 1242],
        [540.0, 1242],
        [657.25, 1242],
        [774.5, 1242],
        [891.75, 1242],
        [1009.0, 1242],
        [70.0, 1356],
        [187.5, 1356],
        [305.0, 1356],
        [422.5, 1356],
        [540.0, 1356],
        [657.5, 1356],
        [775.0, 1356],
        [892.5, 1356],
        [1010.0, 1356],
        [69.0, 1470],
        [186.75, 1470],
        [304.5, 1470],
        [422.25, 1470],
        [540.0, 1470],
        [657.75, 1470],
        [775.5, 1470],
        [893.25, 1470],
        [1011.0, 1470],
        [68.0, 1585],
        [186.0, 1585],
        [304.0, 1585],
        [422.0, 1585],
        [540.0, 1585],
        [658.0, 1585],
        [776.0, 1585],
        [894.0, 1585],
        [1012.0, 1585],
        [67.0, 1700],
        [185.25, 1700],
        [303.5, 1700],
        [421.75, 1700],
        [540.0, 1700],
        [658.25, 1700],
        [776.5, 1700],
        [894.75, 1700],
        [1013.0, 1700],
    ],
    dtype=np.float32,
).reshape(10, 9, 2)

# 数据源：raw_screenshots/ 下 6 张截图（木/石 棋盘 × 自己红/黑方，含 1440x3200 高清）
# flip = True 表示截图棋盘相对标准方向（红在下）旋转了 180°（自己是黑方时黑在下）
SOURCES = [
    ("木_红", "木_红_1080x2400.png", False),
    ("木_黑", "木_黑_1080x2400.png", True),
    ("石_红", "石_红_1080x2400.png", False),
    ("石_黑", "石_黑_1080x2400.png", True),
    ("木_红_1440x3200", "木_红_1440x3200.jpg", False),
    ("石_红_1440x3200", "石_红_1440x3200.jpg", False),
]

SRC_ASCII = {
    "木_红": "mu_hong",
    "木_黑": "mu_hei",
    "石_红": "shi_hong",
    "石_黑": "shi_hei",
    "木_红_1440x3200": "mu_hong_3200",
    "石_红_1440x3200": "shi_hong_3200",
}


def imread_unicode(path: Path) -> np.ndarray | None:
    """读取图片（兼容中文路径：先 fromfile 再 imdecode）"""
    data = np.fromfile(str(path), dtype=np.uint8)
    if data.size == 0:
        return None
    return cv2.imdecode(data, cv2.IMREAD_COLOR)


def imwrite_unicode(path: Path, img: np.ndarray) -> bool:
    """写图片（兼容中文路径：先 imencode 再 tofile）"""
    ok, buf = cv2.imencode(path.suffix, img)
    if ok:
        buf.tofile(str(path))
    return ok


# 还原棋盘：每格边长（像素）
CELL = 100
BOARD_W, BOARD_H = 9 * CELL, 10 * CELL
HALF_CROP = 30  # 棋子切片半径（60x60）
CROP = 2 * HALF_CROP
SEARCH_HALF = 3  # 相似度对比时 ±3px 对齐搜索窗口
NCC_THRESHOLD = 0.85  # 相似度低于此值判定“存在差异”

# ========== 开局布局（标准方向：黑在上、红在下）==========
BACK_RANK = ["b_r", "b_n", "b_b", "b_a", "b_k", "b_a", "b_b", "b_n", "b_r"]
RED_RANK = ["r_R", "r_N", "r_B", "r_A", "r_K", "r_A", "r_B", "r_N", "r_R"]


def start_layout(flip: bool) -> dict[tuple[int, int], str]:
    """按开局布局返回网格坐标 -> 棋子 ID 映射；flip=True 时整盘旋转 180°（黑在下）"""
    lay: dict[tuple[int, int], str] = {}
    for c, pid in enumerate(BACK_RANK):
        lay[(0, c)] = pid
    lay[(2, 1)] = "b_c"
    lay[(2, 7)] = "b_c"
    for c in (0, 2, 4, 6, 8):
        lay[(3, c)] = "b_p"
    for c in (0, 2, 4, 6, 8):
        lay[(6, c)] = "r_P"
    lay[(7, 1)] = "r_C"
    lay[(7, 7)] = "r_C"
    for c, pid in enumerate(RED_RANK):
        lay[(9, c)] = pid
    if flip:
        return {(9 - r, 8 - c): pid for (r, c), pid in lay.items()}
    return lay


def build_homography() -> np.ndarray:
    """由 GRID_CENTERS_NP 四角推导透视四边形 -> 正规矩形的单应矩阵。

    源为四个角格中心（第 0/9 行左右两格），目标为还原网格的对应角格中心；
    还原网格每格边长 CELL，输出尺寸 9*CELL x 10*CELL。
    """
    src = np.array(
        [
            GRID_CENTERS_NP[0, 0],
            GRID_CENTERS_NP[0, 8],
            GRID_CENTERS_NP[9, 0],
            GRID_CENTERS_NP[9, 8],
        ],
        dtype=np.float32,
    )
    dst = np.array(
        [
            [0.5 * CELL, 0.5 * CELL],
            [8.5 * CELL, 0.5 * CELL],
            [0.5 * CELL, 9.5 * CELL],
            [8.5 * CELL, 9.5 * CELL],
        ],
        dtype=np.float32,
    )
    return cv2.getPerspectiveTransform(src, dst)


def extract_pieces(
    board: np.ndarray, layout: dict[tuple[int, int], str], src: str, src_ascii: str
) -> dict[str, list[tuple[str, np.ndarray]]]:
    """在还原棋盘上按布局切出全部棋子模板（每枚一个文件，含位置命名）"""
    piece_dir = OUT_DIR / "pieces" / src
    piece_dir.mkdir(parents=True, exist_ok=True)
    samples: dict[str, list[tuple[str, np.ndarray]]] = {}
    for (r, c), pid in sorted(layout.items()):
        cx = int(round((c + 0.5) * CELL))
        cy = int(round((r + 0.5) * CELL))
        crop = board[cy - HALF_CROP : cy + HALF_CROP, cx - HALF_CROP : cx + HALF_CROP]
        name = f"{src_ascii}_r{r}c{c}"
        imwrite_unicode(piece_dir / f"{pid}_r{r}c{c}.png", crop)
        samples.setdefault(pid, []).append((name, crop))
    return samples


def circular_mask(size: int = CROP) -> np.ndarray:
    """圆形遮罩：只保留棋子圆形区域，排除四角棋盘背景"""
    mask = np.zeros((size, size), np.uint8)
    cv2.circle(mask, (size // 2, size // 2), size // 2 - 3, 255, -1)
    return mask


def masked_ncc(a: np.ndarray, b: np.ndarray, mask: np.ndarray) -> float:
    """圆形遮罩区域的彩色皮尔逊相关系数"""
    m = (mask > 0).astype(np.float64)
    a = a.astype(np.float64)
    b = b.astype(np.float64)
    n = m.sum()
    if n < 10:
        return -1.0
    am = (a * m[..., None]).sum(axis=(0, 1)) / n
    bm = (b * m[..., None]).sum(axis=(0, 1)) / n
    ac = (a - am) * m[..., None]
    bc = (b - bm) * m[..., None]
    den = np.sqrt((ac**2).sum() * (bc**2).sum())
    if den == 0:
        return -1.0
    return float((ac * bc).sum() / den)


def best_ncc(a: np.ndarray, b: np.ndarray, mask: np.ndarray) -> float:
    """±SEARCH_HALF 小窗口内滑动取最大相似度（容忍 1-2px 对齐误差）"""
    best = -1.0
    for dy in range(-SEARCH_HALF, SEARCH_HALF + 1):
        for dx in range(-SEARCH_HALF, SEARCH_HALF + 1):
            if dy == 0 and dx == 0:
                bb = b
            else:
                bb = cv2.warpAffine(
                    b,
                    np.array([[1.0, 0.0, dx], [0.0, 1.0, dy]], dtype=np.float32),
                    (b.shape[1], b.shape[0]),
                    borderMode=cv2.BORDER_REPLICATE,
                )
            best = max(best, masked_ncc(a, bb, mask))
    return best


def compare_pair(a: np.ndarray, b: np.ndarray, mask: np.ndarray) -> tuple[float, int]:
    """两模板相似度：取 0 度与 180 度（红/黑方视角翻转）中较高者，返回 (分数, 角度)"""
    s0 = best_ncc(a, b, mask)
    s180 = best_ncc(a, cv2.rotate(b, cv2.ROTATE_180), mask)
    if s180 > s0:
        return s180, 180
    return s0, 0


def save_montage(pid: str, samples: list[tuple[str, np.ndarray]]) -> None:
    """把同一棋子类型的全部样本拼成一张大图，便于人工校验"""
    montage_dir = OUT_DIR / "montages"
    montage_dir.mkdir(parents=True, exist_ok=True)
    scale = 2
    cell_h = CROP * scale
    label_h = 22
    gap = 6
    cols = max(1, int(np.ceil(np.sqrt(len(samples)))))
    rows = int(np.ceil(len(samples) / cols))
    canvas = np.full((rows * (cell_h + label_h), cols * (cell_h + gap) - gap, 3), 235, np.uint8)
    for idx, (name, img) in enumerate(samples):
        r, c = divmod(idx, cols)
        small = cv2.resize(img, (cell_h, cell_h), interpolation=cv2.INTER_NEAREST)
        x = c * (cell_h + gap)
        y = r * (cell_h + label_h)
        canvas[y : y + cell_h, x : x + cell_h] = small
        cv2.putText(
            canvas,
            name,
            (x + 2, y + cell_h + 16),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.42,
            (0, 0, 0),
            1,
            cv2.LINE_AA,
        )
    imwrite_unicode(montage_dir / f"{pid}.png", canvas)


def main() -> None:
    boards_dir = OUT_DIR / "boards"
    boards_dir.mkdir(parents=True, exist_ok=True)
    homography = build_homography()
    print("透视四边形（四角格中心，源）:")
    for tag, (x, y) in zip(
        ("左上", "右上", "左下", "右下"),
        [
            GRID_CENTERS_NP[0, 0],
            GRID_CENTERS_NP[0, 8],
            GRID_CENTERS_NP[9, 0],
            GRID_CENTERS_NP[9, 8],
        ],
        strict=True,
    ):
        print(f"  {tag} = ({x:g}, {y:g})")
    print(
        f"  首行宽度 {GRID_CENTERS_NP[0, 8, 0] - GRID_CENTERS_NP[0, 0, 0]:g}px，"
        f"末行宽度 {GRID_CENTERS_NP[9, 8, 0] - GRID_CENTERS_NP[9, 0, 0]:g}px"
        "（下宽上窄 = 近大远小的梯形透视）"
    )

    all_samples: dict[str, list[tuple[str, np.ndarray]]] = {}
    for src, fname, flip in SOURCES:
        img = imread_unicode(PNG_DIR / fname)
        if img is None:
            raise FileNotFoundError(f"无法读取图片: {PNG_DIR / fname}")
        board = correct_board(img, homography)
        imwrite_unicode(boards_dir / f"{src}.png", board)
        layout = start_layout(flip)
        samples = extract_pieces(board, layout, src, SRC_ASCII[src])
        total = sum(len(v) for v in samples.values())
        print(f"[{src}] {fname}: 还原棋盘已保存（{BOARD_W}x{BOARD_H}），切出 {total} 枚棋子")
        for pid, items in samples.items():
            all_samples.setdefault(pid, []).extend(items)

    mask = circular_mask()
    print("\n==== 同一棋子类型跨 4 张图相似度对比 ====")
    verdict_lines: list[str] = []
    for pid in sorted(all_samples):
        samples = all_samples[pid]
        labels = [name for name, _ in samples]
        n = len(samples)
        matrix = np.eye(n)
        angles = {}
        min_score = 1.0
        min_pair = None
        for i, j in itertools.combinations(range(n), 2):
            score, ang = compare_pair(samples[i][1], samples[j][1], mask)
            matrix[i, j] = matrix[j, i] = score
            angles[(i, j)] = ang
            if score < min_score:
                min_score = score
                min_pair = (labels[i], labels[j])
        mean_score = sum(matrix[i, j] for i, j in itertools.combinations(range(n), 2)) / (
            n * (n - 1) / 2
        )
        flag = "一致" if min_score >= NCC_THRESHOLD else "存在差异"
        print(f"\n[{pid}] {n} 个样本，均值 {mean_score:.3f}，最低 {min_score:.3f} -> {flag}")
        if min_pair is not None:
            print(f"  最低相似度样本对: {min_pair[0]} / {min_pair[1]}")
        print("  样本: " + "  ".join(labels))
        header = "      " + " ".join(f"{lab[:11]:>11}" for lab in labels)
        print(header)
        for i, lab in enumerate(labels):
            row = f"{lab[:11]:>11} "
            row += " ".join(f"{matrix[i, j]:>11.3f}" for j in range(n))
            print(row)
        save_montage(pid, samples)
        verdict_lines.append(f"[{pid}] 均值 {mean_score:.3f} / 最低 {min_score:.3f} -> {flag}")

    print("\n==== 汇总 ====")
    for line in verdict_lines:
        print("  " + line)


def correct_board(img: np.ndarray, homography: np.ndarray) -> np.ndarray:
    """透视校正：梯形棋盘 -> 正规矩形"""
    return cv2.warpPerspective(img, homography, (BOARD_W, BOARD_H), flags=cv2.INTER_LINEAR)


if __name__ == "__main__":
    main()
