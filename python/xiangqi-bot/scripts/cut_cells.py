"""将 init_new 中 900x1000 的矫正棋盘切割成 9x10=90 个 60x60 格子图。

每格以矫正空间中心点 (corrected_center) 为中心裁 60x60，保存到
raw_screenshots/init_new_cells/<board>_r{rr}_c{cc}.png。
同时输出 labels.csv：每个格子对应的棋子ID或 empty（按开局标准位置标注，
便于后续 CNN 训练）。

用法：
    python scripts/cut_cells.py
"""

import csv
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from xiangqi_bot import config
from xiangqi_bot.board import START_SQUARES

CORRECT_CELL = 100  # 格边长
COLS, ROWS = 9, 10  # 10 行 9 列
CELL_OUT = 60  # 输出格子图边长
HALF = CELL_OUT // 2

INPUT_DIR = config.PROJECT_ROOT / "raw_screenshots" / "init_new"
OUTPUT_DIR = config.PROJECT_ROOT / "raw_screenshots" / "init_new_cells"


def corrected_center(r: int, c: int) -> tuple[float, float]:
    """网格 -> 矫正棋盘中心坐标（与 vision/board 定义一致）"""
    return CORRECT_CELL * (c + 0.5), CORRECT_CELL * (r + 0.5)


def main() -> int:
    if not INPUT_DIR.exists():
        print(f"错误: 未找到输入目录 {INPUT_DIR}")
        return 1
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    boards = sorted(INPUT_DIR.glob("*.png"))
    if not boards:
        print("未找到矫正棋盘图片")
        return 1

    # 开局标准位置 -> 格子标注 (r,c) -> 棋子ID
    label_map: dict[tuple[int, int], str] = {}
    for pid, squares in START_SQUARES.items():
        for r, c in squares:
            label_map[(r, c)] = pid

    rows: list[tuple[str, int, int, str]] = []
    total = 0
    for b in boards:
        img = cv2.imdecode(np.fromfile(str(b), dtype=np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            print(f"[跳过] 无法解码: {b.name}")
            continue
        h, w = img.shape[:2]
        if (w, h) != (CORRECT_CELL * COLS, CORRECT_CELL * ROWS):
            print(f"[警告] {b.name} 尺寸 {w}x{h} 非 900x1000，跳过")
            continue
        stem = b.stem
        for r in range(ROWS):
            for c in range(COLS):
                cx, cy = corrected_center(r, c)
                x1, y1 = int(round(cx - HALF)), int(round(cy - HALF))
                cell = img[y1 : y1 + CELL_OUT, x1 : x1 + CELL_OUT]
                label = label_map.get((r, c), "empty")
                out = OUTPUT_DIR / f"{stem}_r{r:02d}_c{c}.png"
                cv2.imencode(".png", cell)[1].tofile(str(out))
                rows.append((out.name, r, c, label))
                total += 1
        print(f"已切割: {b.name} -> {ROWS * COLS} 格")

    csv_path = OUTPUT_DIR / "labels.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["file", "row", "col", "label"])
        w.writerows(rows)

    print(f"\n完成：{total} 个格子图 -> {OUTPUT_DIR}")
    print(f"标注文件: {csv_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
