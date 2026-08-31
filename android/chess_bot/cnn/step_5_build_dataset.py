# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "opencv-python>=4.11.0",
#   "numpy>=1.26",
# ]
# ///
"""脚本3（拆分自 step_3_cut_cells.py）：把 cells_dedup 切分成 YOLO 分类训练集。

从已经去重的小图（cells_dedup/<类>/*.png）按类别分层划分 train/val，写出最终目录：
    cnn/dataset_yolo/
      train/<class>/xxx.png
      val/<class>/xxx.png
      data.yaml            # Ultralytics 训练用
      class_names.txt      # 类键，按索引一行一个
      preprocess.json      # 推理端预处理参数（Android 用）

因 cells_dedup 已是全局去重（无任何 >=阈值 的近重复对），本步切分不会引入 train/val 泄漏。
本脚本纯文件读写 + 随机切分，极快，可随时重跑。

运行：  uv run step_5_build_dataset.py [--val-ratio 0.15] [--seed 42]
依赖：  opencv-python, numpy
"""

import argparse
import json
import random
import shutil
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from yolo_common import (  # noqa: E402
    CELLS_DEDUP_DIR, DATASET_ROOT, CLASSES, CLASS_CN, CELL_OUT,
)

IMG_EXTS = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}


def main() -> int:
    ap = argparse.ArgumentParser(description="cells_dedup 切分为 YOLO 分类集（脚本3）")
    ap.add_argument("--val-ratio", type=float, default=0.15,
                    help="验证集占比（默认 0.15）；类内样本 <4 时仍至少留 1 张 val")
    ap.add_argument("--seed", type=int, default=42, help="随机种子（默认 42，保证可复现）")
    args = ap.parse_args()

    if not CELLS_DEDUP_DIR.exists():
        print(f"未在 {CELLS_DEDUP_DIR} 找到去重后的小图。请先运行 step_4_dedup_cells.py。")
        return 1

    # 收集每类图片路径
    per_class: dict[str, list[Path]] = {c: [] for c in CLASSES}
    for c in CLASSES:
        d = CELLS_DEDUP_DIR / c
        if d.exists():
            per_class[c] = sorted(p for p in d.iterdir()
                                 if p.is_file() and p.suffix.lower() in IMG_EXTS)

    total = sum(len(v) for v in per_class.values())
    if total == 0:
        print("cells_dedup 下没有任何小图，请检查 step_3_cut_cells.py / step_4_dedup_cells.py。")
        return 1

    random.seed(args.seed)
    train_dir = DATASET_ROOT / "train"
    val_dir = DATASET_ROOT / "val"
    # 先清空上一次的输出，避免残留旧切分文件污染训练集
    # （本脚本只覆盖 0..n-1 索引，旧 run 超出范围的文件不会被覆盖）
    for d in (train_dir, val_dir):
        if d.exists():
            shutil.rmtree(d)
        for c in CLASSES:
            (d / c).mkdir(parents=True, exist_ok=True)

    counts: dict[str, dict[str, int]] = {c: {"train": 0, "val": 0} for c in CLASSES}
    for c in CLASSES:
        items = per_class[c]
        if not items:
            continue
        random.shuffle(items)
        if len(items) >= 4:
            n_val = max(1, int(len(items) * args.val_ratio))
        elif len(items) >= 2:
            n_val = 1
        else:
            n_val = 0
        n_val = min(n_val, len(items) - 1) if len(items) > 1 else 0
        val_items = items[:n_val]
        train_items = items[n_val:]
        for i, p in enumerate(train_items):
            out = train_dir / c / f"{c}_{i:05d}.png"
            cv2.imencode(".png", cv2.imdecode(np.fromfile(str(p), dtype=np.uint8),
                                              cv2.IMREAD_COLOR))[1].tofile(str(out))
            counts[c]["train"] += 1
        for i, p in enumerate(val_items):
            out = val_dir / c / f"{c}_{i:05d}.png"
            cv2.imencode(".png", cv2.imdecode(np.fromfile(str(p), dtype=np.uint8),
                                              cv2.IMREAD_COLOR))[1].tofile(str(out))
            counts[c]["val"] += 1

    # data.yaml
    yaml_lines = [
        f"path: {DATASET_ROOT.resolve()}",
        "train: train",
        "val: val",
        f"nc: {len(CLASSES)}",
        "names:",
    ]
    yaml_lines += [f"  - {c}" for c in CLASSES]
    (DATASET_ROOT / "data.yaml").write_text("\n".join(yaml_lines) + "\n", encoding="utf-8")

    # class_names.txt
    (DATASET_ROOT / "class_names.txt").write_text(
        "\n".join(CLASSES) + "\n", encoding="utf-8"
    )

    # preprocess.json —— 数据集描述（仅记录 step_5 能决定的部分）
    # 推理预处理（归一化契约）由 step_6 在训练时据实定义并写入 model_info.json，
    # step_5 不输出该字段，避免「输出又被忽略」或「声明的归一化与实际模型不符」。
    preprocess = {
        "task": "classification",
        "imgsz": CELL_OUT,
        "input_layout": "NCHW",
        "input_shape": [1, 3, CELL_OUT, CELL_OUT],
        "input_dtype": "float32",
        "color_order": "RGB（cv2 裁出的格子是 BGR，喂模型前需 cvtColor(BGR2RGB)）",
        "class_keys": CLASSES,
        "class_cn": [CLASS_CN[c] for c in CLASSES],
    }
    (DATASET_ROOT / "preprocess.json").write_text(
        json.dumps(preprocess, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    print("\n=== 数据集已生成 ===")
    print(f"总格子数: {total}（来自 cells_dedup；已去重、无 train/val 泄漏）")
    print(f"目录: {DATASET_ROOT}")
    print(f"{'class':<8}{'中文':<6}{'train':>8}{'val':>8}")
    for c in CLASSES:
        print(f"{c:<8}{CLASS_CN[c]:<6}{counts[c]['train']:>8}{counts[c]['val']:>8}")
    print("\n下一步：uv run step_6_train_yolo.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
