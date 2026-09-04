"""cls_dataset.py —— 把 cls/cells_dedup 切分为 YOLO 分类训练集。

输出：cls/dataset/train/<class>/ + cls/dataset/val/<class>/ + data.yaml
"""

from __future__ import annotations

import json
import random
import shutil
from pathlib import Path

import cv2
import numpy as np

from yolo_chess.common import (
    CELL_OUT,
    CLASS_CN,
    CLASSES,
    CLS_CELLS_DEDUP,
    CLS_DATASET,
    Param,
    interactive_args,
)

IMG_EXTS = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}

PARAMS = [
    Param("val_ratio", "float", default=0.15, cn="验证集比例", desc="每类划分到 val 的比例"),
    Param("seed", "int", default=42, cn="随机种子", desc="用于可复现的划分"),
]


def main() -> int:
    """构建分类数据集主函数。"""
    args = interactive_args(PARAMS)
    if args is None:
        return 0

    if not CLS_CELLS_DEDUP.exists():
        print(f"未在 {CLS_CELLS_DEDUP} 找到去重后的小图。请先运行「去重」。")
        return 1

    per_class: dict[str, list[Path]] = {c: [] for c in CLASSES}
    for c in CLASSES:
        d = CLS_CELLS_DEDUP / c
        if d.exists():
            per_class[c] = sorted(
                p for p in d.iterdir() if p.is_file() and p.suffix.lower() in IMG_EXTS
            )

    total = sum(len(v) for v in per_class.values())
    if total == 0:
        print("cells_dedup 下没有任何小图。")
        return 1

    random.seed(args.seed)
    train_dir = CLS_DATASET / "train"
    val_dir = CLS_DATASET / "val"
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
            img = cv2.imdecode(np.fromfile(str(p), dtype=np.uint8), cv2.IMREAD_COLOR)
            if img is not None:
                cv2.imencode(".png", img)[1].tofile(str(out))
            counts[c]["train"] += 1
        for i, p in enumerate(val_items):
            out = val_dir / c / f"{c}_{i:05d}.png"
            img = cv2.imdecode(np.fromfile(str(p), dtype=np.uint8), cv2.IMREAD_COLOR)
            if img is not None:
                cv2.imencode(".png", img)[1].tofile(str(out))
            counts[c]["val"] += 1

    yaml_lines = [
        f"path: {CLS_DATASET.resolve()}",
        "train: train",
        "val: val",
        f"nc: {len(CLASSES)}",
        "names:",
    ]
    yaml_lines += [f"  - {c}" for c in CLASSES]
    (CLS_DATASET / "data.yaml").write_text("\n".join(yaml_lines) + "\n", encoding="utf-8")

    (CLS_DATASET / "class_names.txt").write_text("\n".join(CLASSES) + "\n", encoding="utf-8")

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
    (CLS_DATASET / "preprocess.json").write_text(
        json.dumps(preprocess, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    print("\n=== 数据集已生成 ===")
    print(f"总格子数: {total}")
    print(f"目录: {CLS_DATASET}")
    print(f"{'class':<8}{'中文':<6}{'train':>8}{'val':>8}")
    for c in CLASSES:
        print(f"{c:<8}{CLASS_CN[c]:<6}{counts[c]['train']:>8}{counts[c]['val']:>8}")
    return 0
