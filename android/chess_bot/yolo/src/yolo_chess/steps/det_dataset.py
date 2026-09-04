"""det_dataset.py —— 构建「棋盘4角定位」的 YOLO-Detection 数据集。

输出：det/dataset/images/{train,val}/ + det/dataset/labels/{train,val}/ + data.yaml
"""

from __future__ import annotations

import hashlib
import random
import re
import shutil
from pathlib import Path

import numpy as np

from yolo_chess.common import (
    DEFAULT_CORNERS,
    DET_DATASET,
    SHARED_RAW,
    STATE_LIFT,
    imread,
)

# 仅用 opening/mate/endgame 三态；lift 与 mate 高度相似且占比过大，对训练不利
_DET_STATES = [d for d in sorted(SHARED_RAW.iterdir()) if d.is_dir() and d.name != STATE_LIFT]
SEED = 42
VAL_RATIO = 0.2
CORNER_HALF = 65
CORNER_CLASSES = ["tl", "tr", "bl", "br"]


def _ascii_stem(nskey: str) -> str:
    h = hashlib.md5(nskey.encode("utf-8")).hexdigest()[:12]
    return re.sub(r"[^A-Za-z0-9]", "_", nskey)[:40] + "_" + h


def main() -> int:
    """构建四角数据集主函数。"""
    random.seed(SEED)
    samples: list[tuple[Path, np.ndarray, str]] = []
    skipped: list[tuple[Path, str]] = []
    res_counter: dict[tuple[int, int], int] = {}

    for d in _DET_STATES:
        for f in sorted(d.glob("*.png")):
            img = imread(f)
            if img is None:
                skipped.append((f, "imread 失败"))
                continue
            h, w = img.shape[:2]
            key = (int(w), int(h))
            res_counter[key] = res_counter.get(key, 0) + 1
            if key not in DEFAULT_CORNERS:
                skipped.append((f, f"分辨率{key}无 DEFAULT_CORNERS"))
                continue
            pts = np.array(DEFAULT_CORNERS[key], dtype=np.float32)
            samples.append((f, pts, d.name))

    print(f"[收集] 分辨率分布: {res_counter}")
    print(
        f"[收集] 纳入 {len(samples)} 张，跳过 {len(skipped)} 张（已排除 {STATE_LIFT}，仅用 opening/mate/endgame）"
    )
    for f, r in skipped:
        print(f"  跳过 {f.parent.name}/{f.name}: {r}")

    if not samples:
        print("[中止] 无可用样本")
        return 1

    for split in ("train", "val"):
        (DET_DATASET / "images" / split).mkdir(parents=True, exist_ok=True)
        (DET_DATASET / "labels" / split).mkdir(parents=True, exist_ok=True)

    random.shuffle(samples)
    n_val = max(1, round(len(samples) * VAL_RATIO))
    splits = ["val"] * n_val + ["train"] * (len(samples) - n_val)

    nsmap: dict[str, str] = {}
    sample_label: str | None = None
    margin_worst: dict[tuple[int, int], list[float]] = {}
    for (f, _pts, sub), split in zip(samples, splits, strict=False):
        img = imread(f)
        if img is None:
            continue
        h, w = img.shape[:2]
        margin_worst.setdefault(
            (w, h),
            [
                min(
                    cx - CORNER_HALF,
                    w - (cx + CORNER_HALF),
                    cy - CORNER_HALF,
                    h - (cy + CORNER_HALF),
                )
                for cx, cy in _pts
            ],
        )
        lines: list[str] = []
        for ci, (cx, cy) in enumerate(_pts):
            xc = cx / w
            yc = cy / h
            bw = (2 * CORNER_HALF) / w
            bh = (2 * CORNER_HALF) / h
            lines.append(f"{ci} {xc:.6f} {yc:.6f} {bw:.6f} {bh:.6f}")
        if sample_label is None:
            sample_label = "\n".join(lines)
        nskey = f"{sub}_{f.stem}"
        img_stem = _ascii_stem(nskey)
        nsmap[img_stem] = nskey
        (DET_DATASET / "labels" / split / (img_stem + ".txt")).write_text(
            "\n".join(lines) + "\n", encoding="utf-8"
        )
        src_path = f
        dst_path = DET_DATASET / "images" / split / (img_stem + f.suffix)
        if dst_path.exists():
            continue
        try:
            import os

            os.link(str(src_path), str(dst_path))
        except OSError:
            shutil.copy(str(src_path), str(dst_path))

    (DET_DATASET / "data.yaml").write_text(
        "# 棋盘4角定位 (YOLO-Detection / 4 类：tl,tr,bl,br)\n"
        f"path: {DET_DATASET.as_posix()}\n"
        "train: images/train\n"
        "val: images/val\n"
        "nc: 4\n"
        "names: ['tl', 'tr', 'bl', 'br']\n"
        "# 类别顺序: 0=TL 1=TR 2=BL 3=BR（角点框心 = 角点坐标）\n",
        encoding="utf-8",
    )

    import json

    (DET_DATASET / "_nsmap.json").write_text(
        json.dumps(nsmap, ensure_ascii=False, indent=0), encoding="utf-8"
    )

    print(f"[完成] 数据集 -> {DET_DATASET}")
    print(f"  train={len(samples) - n_val}  val={n_val}")
    print(f"  每张图标签行数={len(CORNER_CLASSES)}（4 角各 1 行: class x y w h）")
    print(f"  角框半边长 CORNER_HALF={CORNER_HALF}px（框心=角点）")
    if sample_label:
        print("  示例标签(归一化):\n" + sample_label)

    names = [c.upper() for c in CORNER_CLASSES] if CORNER_CLASSES else ["TL", "TR", "BL", "BR"]
    bad = False
    for (w, h), ms in sorted(margin_worst.items()):
        tight = [(names[i], m) for i, m in enumerate(ms) if m < 20]
        over = [(names[i], m) for i, m in enumerate(ms) if m < 0]
        if over:
            bad = True
            print(
                f"  ❌ {w}x{h} 角框越界: "
                + " ".join(f"{n}({m:+.0f}px)" for n, m in over)
                + f" → 标签归一化 >1 会被裁剪、框心偏移。请把 CORNER_HALF 降到 "
                f"{int(min(m for _, m in over) + CORNER_HALF - 1)} 以下"
            )
        elif tight:
            bad = True
            print(
                f"  ⚠ {w}x{h} 角框贴边（余量<20px）: "
                + " ".join(f"{n}({m:.0f}px)" for n, m in tight)
                + " → 训练时 translate/scale 增强会把该框推出画面而裁剪，"
                "使该类标签带噪、置信度塌陷。对策：det_train 用 --translate 0 --scale 0，"
                "或调小 CORNER_HALF。"
            )
    if not bad:
        print("  ✅ 所有角框距图像边界余量充足（≥20px）")

    return 0
