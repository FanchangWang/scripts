"""从矫正后的参考棋盘重新切割 14 张棋子模板到 templates/（覆盖旧的原图模板）。

矫正棋盘为固定 900x1000 空间（格子 100px），切割出的模板与源分辨率无关：
同一游戏画面在不同分辨率下矫正后可直接匹配（已验证 90/90）。
"""

import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from xiangqi_bot import config, vision
from xiangqi_bot.board import START_SQUARES, corrected_center

REFERENCE = (
    config.PROJECT_ROOT / "raw_screenshots" / "木_红_1080x2400.png"
)  # 参考开局截图（1080x2400）
HALF = config.TEMPLATE_SIZE // 2


def main() -> int:
    if not REFERENCE.exists():
        print(f"错误: 未找到参考截图 {REFERENCE}")
        return 1
    img = cv2.imdecode(np.fromfile(str(REFERENCE), dtype=np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        print(f"错误: 无法读取 {REFERENCE}")
        return 1
    corrected = vision.correct_board(img)
    config.TEMPLATES_DIR.mkdir(parents=True, exist_ok=True)
    saved = 0
    for piece_id in sorted(START_SQUARES):
        r, c = START_SQUARES[piece_id][0]
        cx, cy = corrected_center(r, c)
        x1, y1 = int(round(cx - HALF)), int(round(cy - HALF))
        crop = corrected[y1 : y1 + config.TEMPLATE_SIZE, x1 : x1 + config.TEMPLATE_SIZE]
        out = config.TEMPLATES_DIR / f"{piece_id}.png"
        cv2.imwrite(str(out), crop)
        saved += 1
        print(f"已切割模板: {piece_id:>4} <- 矫正棋盘 ({r},{c})")
    print(f"\n共导出 {saved} 张矫正模板到 {config.TEMPLATES_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
