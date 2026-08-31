# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "opencv-python>=4.11.0",
#   "numpy>=1.26",
# ]
# ///
"""脚本4：从 cnn/raw/1 开局截图切割 14 种棋子的平均模板。

用途：残局(endgame, raw/5) 的棋子位置不固定，无法用固定标注映射，
因此先由本脚本从「已知满盘开局图」切出每种棋子的代表性模板，
后续 step_3_cut_cells.py 处理 raw/5 时用这些模板做自动匹配标注，
把人工标注成本降到最低。

流程：
  1) 扫描 cnn/raw/1/*.png（开局满盘，32 子位置已知）。
  2) 透视矫正 -> 900x1000；按 9x10 网格切 90 个 64x64 格子。
  3) 用开局标注映射得到每格棋子 class，同类样本取平均 -> 一张模板。
  4) 写入 cnn/templates/<class>.png（仅 14 棋子；空格靠匹配阈值判定，不存模板）。

运行：  uv run step_2_cut_templates.py
依赖：  opencv-python, numpy
"""

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from yolo_common import (  # noqa: E402
    build_all_template_sets, TEMPLATE_DIR, CLASSES, CLASS_CN, state_dir,
)


def main() -> int:
    print("=== 切割棋子模板（脚本4 / 4）===")
    opening_dir = state_dir(1)
    imgs = sorted(opening_dir.glob("*.png")) if opening_dir.exists() else []
    if not imgs:
        print(f"[错误] raw/1 开局截图缺失: {opening_dir}")
        print("        请先用 step_1_collect_screenshots.py 采集若干开局满盘截图（可含多套棋子皮肤）。")
        return 1

    print(f"扫描到 {len(imgs)} 张开局截图，开始为每张切一套模板（同一皮肤不同图可复用）...")
    try:
        sets = build_all_template_sets(save=True)
    except Exception as e:  # noqa: BLE001
        print(f"[错误] 生成模板失败: {e}")
        return 1

    print(f"\n共生成 {len(sets)} 套模板，目录: {TEMPLATE_DIR}")
    for name, tset in sets.items():
        missing = [c for c in CLASSES if c not in ("empty", "lift") and c not in tset]
        status = "OK" if not missing else f"缺 {missing}"
        print(f"  {name}: {len(tset)}/14 子  {status}")

    incomplete = [n for n, t in sets.items() if len(t) < 14]
    if incomplete:
        print(f"\n[提醒] {incomplete} 套未集齐 14 子，残局匹配可能漏标。")
        return 1

    print(f"\n完成：共 {len(sets)} 套 × 14 棋子模板（不含 empty/lift）。")
    print("下一步：把任意中残局截图采集到 cnn/raw/5/，再运行 step_3_cut_cells.py 自动标注。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
