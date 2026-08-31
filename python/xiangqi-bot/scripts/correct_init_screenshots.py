"""将 raw_screenshots/init 中的原始开局截图统一透视矫正为标准 900x1000 棋盘。

透矫正逻辑与 BOARD_CORNERS 均内联实现（不依赖 xiangqi_bot.vision / config
的对应实现），黑方在上、红方在下（与"下方是红棋"一致）。
输出到 raw_screenshots/init_new/（文件名保持原 stem，统一存为 .png）。

用法：
    python scripts/correct_init_screenshots.py
"""

import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from xiangqi_bot import config

INPUT_DIR = config.PROJECT_ROOT / "raw_screenshots" / "init"
OUTPUT_DIR = config.PROJECT_ROOT / "raw_screenshots" / "init_new"


# ---- 矫正空间参数（与 xiangqi_bot.config 保持一致）----
CORRECT_CELL = 100  # 格边长
CORRECT_W = CORRECT_CELL * 9  # 900
CORRECT_H = CORRECT_CELL * 10  # 1000
COLS, ROWS = 9, 10


def corrected_center(r: int, c: int) -> tuple[float, float]:
    """网格 -> 矫正棋盘中心坐标（矫正空间恒为 900x1000）"""
    return CORRECT_CELL * (c + 0.5), CORRECT_CELL * (r + 0.5)


# ---- 棋盘四角格中心坐标（源截图分辨率 -> (左上, 右上, 左下, 右下)）----
# 拷贝自 xiangqi_bot.config.BOARD_CORNERS。
BOARD_CORNERS: dict[tuple[int, int], tuple[tuple[float, float], ...]] = {
    (1080, 2376): ((76.0, 667.0), (1004.0, 667.0), (67.0, 1688.0), (1014.0, 1688.0)),
    (1080, 2400): ((76.0, 679.0), (1004.0, 679.0), (67.0, 1699.0), (1014.0, 1699.0)),
    (1440, 3200): ((101.5, 905.5), (1339.5, 905.5), (89.0, 2266.0), (1352.0, 2266.0)),
}

_HOMOGRAPHY_CACHE: dict[tuple[int, int], np.ndarray] = {}


def homography(w: int, h: int) -> np.ndarray:
    """按源截图分辨率取 4 角格中心 -> 矫正空间对应格中心，返回 3x3 透视矩阵"""
    key = (int(w), int(h))
    if key not in _HOMOGRAPHY_CACHE:
        corners = BOARD_CORNERS.get(key)
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
    return cv2.warpPerspective(img, homography(w, h), (CORRECT_W, CORRECT_H))


def main() -> int:
    if not INPUT_DIR.exists():
        print(f"错误: 未找到输入目录 {INPUT_DIR}")
        return 1

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    files = sorted(
        p
        for p in INPUT_DIR.iterdir()
        if p.is_file() and p.suffix.lower() in {".png", ".jpg", ".jpeg", ".bmp", ".webp"}
    )
    if not files:
        print(f"输入目录中未发现图片: {INPUT_DIR}")
        return 1

    shift_note = ""

    ok = 0
    for src in files:
        # np.fromfile 读字节，避免中文/特殊路径在 cv2.imread 上踩坑
        raw = np.fromfile(str(src), dtype=np.uint8)
        img = cv2.imdecode(raw, cv2.IMREAD_COLOR)
        if img is None:
            print(f"[跳过] 无法解码: {src.name}")
            continue

        h, w = img.shape[:2]
        try:
            corrected = correct_board(img)
        except RuntimeError as e:
            print(f"[跳过] {src.name} ({w}x{h}): {e}")
            continue

        # 校验矫正结果：尺寸应恰为 900x1000，且非全黑/全白空图
        ch, cw = corrected.shape[:2]
        if (cw, ch) != (CORRECT_W, CORRECT_H):
            print(f"[警告] {src.name} 矫正尺寸异常 {cw}x{ch}，仍输出")
        mean_b = float(corrected[:, :, 0].mean())
        mean_g = float(corrected[:, :, 1].mean())
        mean_r = float(corrected[:, :, 2].mean())
        if abs(mean_b - mean_g) < 1 and abs(mean_g - mean_r) < 1 and mean_b in (0.0, 255.0):
            print(f"[警告] {src.name} 矫正结果疑似空图（均值={mean_b:.1f}），请检查棋盘四角坐标")

        out = OUTPUT_DIR / f"{src.stem}.png"
        cv2.imencode(".png", corrected)[1].tofile(str(out))
        print(
            f"已矫正: {src.name} ({w}x{h}) -> {out.name} ({CORRECT_W}x{CORRECT_H}){shift_note}"
        )
        ok += 1

    print(f"\n完成：{ok}/{len(files)} 张图片已输出到 {OUTPUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
