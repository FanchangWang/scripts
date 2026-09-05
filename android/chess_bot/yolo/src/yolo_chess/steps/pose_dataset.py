"""pose_dataset.py —— 构建「棋盘4角定位」的 YOLO-Pose 数据集。

与 det_dataset 的区别：
- det：4 个角点检测框（TL/TR/BL/BR，4 类）。
- pose：1 个 `chessboard` 主目标框 + 4 个关键点（TL/TR/BL/BR），关键点坐标即 DEFAULT_CORNERS
  角点（不扩），关键点可见性由 corner_visibility_for_state() 按状态判定（v=1 遮挡 / v=2 可见）。

输出：pose/dataset/images/{train,val}/ + pose/dataset/labels/{train,val}/ + data.yaml（task: pose）
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from yolo_chess.common import (
    DEFAULT_CORNERS,
    POSE_DATASET,
    SHARED_RAW,
    STATE_LIFT,
    corner_visibility_for_state,
    imread,
    pose_bbox_from_corners,
)

# 仅用 opening/mate/endgame 三态；lift 与 mate 高度相似且占比过大，对训练不利
SEED = 42
VAL_RATIO = 0.2


def _ascii_stem(nskey: str) -> str:
    import hashlib
    import re

    h = hashlib.md5(nskey.encode("utf-8")).hexdigest()[:12]
    return re.sub(r"[^A-Za-z0-9]", "_", nskey)[:40] + "_" + h


def _pose_states() -> list[Path]:
    if not SHARED_RAW.exists():
        return []
    return [d for d in sorted(SHARED_RAW.iterdir()) if d.is_dir() and d.name != STATE_LIFT]


def main() -> int:
    """构建 pose 四角数据集主函数。"""
    import random

    random.seed(SEED)
    _POSE_STATES = _pose_states()
    samples: list[tuple[Path, np.ndarray, str]] = []
    skipped: list[tuple[Path, str]] = []
    res_counter: dict[tuple[int, int], int] = {}

    for d in _POSE_STATES:
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
        f"[收集] 纳入 {len(samples)} 张，跳过 {len(skipped)} 张"
        f"（已排除 {STATE_LIFT}，仅用 opening/mate/endgame）"
    )
    for f, r in skipped:
        print(f"  跳过 {f.parent.name}/{f.name}: {r}")

    if not samples:
        print("[中止] 无可用样本")
        return 1

    for split in ("train", "val"):
        (POSE_DATASET / "images" / split).mkdir(parents=True, exist_ok=True)
        (POSE_DATASET / "labels" / split).mkdir(parents=True, exist_ok=True)

    random.shuffle(samples)
    n_val = max(1, round(len(samples) * VAL_RATIO))
    splits = ["val"] * n_val + ["train"] * (len(samples) - n_val)

    nsmap: dict[str, str] = {}
    sample_label: str | None = None
    clamp_worst: dict[tuple[int, int], list[bool]] = {}
    for (f, _pts, sub), split in zip(samples, splits, strict=False):
        img = imread(f)
        if img is None:
            continue
        h, w = img.shape[:2]

        # 1) 主目标框：4 角包围盒四边各外扩 POSE_MARGIN 后 clamp
        x1, y1, x2, y2 = pose_bbox_from_corners(_pts, w, h)
        clamped = [
            x1 == 0.0,
            y1 == 0.0,
            abs(x2 - w) < 1e-3,
            abs(y2 - h) < 1e-3,
        ]
        clamp_worst.setdefault((w, h), clamped)

        cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
        bw, bh = (x2 - x1), (y2 - y1)
        bbox = f"{cx / w:.6f} {cy / h:.6f} {bw / w:.6f} {bh / h:.6f}"

        # 2) 4 关键点（TL,TR,BL,BR）+ 可见性
        vis = corner_visibility_for_state(sub)
        kpt_parts: list[str] = []
        for (kx, ky), v in zip(_pts, vis, strict=True):
            kpt_parts.append(f"{kx / w:.6f} {ky / h:.6f} {v}")
        line = f"0 {bbox} " + " ".join(kpt_parts)

        if sample_label is None:
            sample_label = line

        nskey = f"{sub}_{f.stem}"
        img_stem = _ascii_stem(nskey)
        nsmap[img_stem] = nskey
        (POSE_DATASET / "labels" / split / (img_stem + ".txt")).write_text(
            line + "\n", encoding="utf-8"
        )
        dst_path = POSE_DATASET / "images" / split / (img_stem + f.suffix)
        if dst_path.exists():
            continue
        try:
            import os

            os.link(str(f), str(dst_path))
        except OSError:
            import shutil

            shutil.copy(str(f), str(dst_path))

    (POSE_DATASET / "data.yaml").write_text(
        "# 棋盘4角定位 (YOLO-Pose / 1 类：chessboard + 4 关键点)\n"
        f"path: {POSE_DATASET.as_posix()}\n"
        "train: images/train\n"
        "val: images/val\n"
        "nc: 1\n"
        "names: ['chessboard']\n"
        "kpt_shape: [4, 3]\n"
        "# 关键点顺序: 0=TL 1=TR 2=BL 3=BR（与 yolo_chess.DEFAULT_CORNERS / _dst_points 一致）\n"
        "# 关键点可见性: 2=可见 1=遮挡但位置已知（按状态查 label_map 判定）\n",
        encoding="utf-8",
    )

    (POSE_DATASET / "_nsmap.json").write_text(
        json.dumps(nsmap, ensure_ascii=False, indent=0), encoding="utf-8"
    )

    print(f"[完成] 数据集 -> {POSE_DATASET}")
    print(f"  train={len(samples) - n_val}  val={n_val}")
    print("  标签格式: class cx cy w h kpt1(x y v) kpt2(x y v) kpt3(x y v) kpt4(x y v)")
    print("  主目标框 = 4 角包围盒四边外扩 100px 后 clamp（越界用 0/宽高）")
    if sample_label:
        print("  示例标签(归一化):\n" + sample_label)

    bad = False
    for (w, h), clamped in sorted(clamp_worst.items()):
        hit = [(n, b) for n, b in zip(("左", "上", "右", "下"), clamped, strict=True) if b]
        if hit:
            bad = True
            print(
                f"  ⚠ {w}x{h} 主目标框触边（clamp 生效，该边 padding < 100px）: "
                + " ".join(n for n, _ in hit)
                + " → 角点距图像边界 < 100px，框被裁到图像边缘；"
                "训练仍可接受（pose 支持部分框），但 padding 不足可能略降定位精度。"
            )
    if not bad:
        print("  ✅ 所有主目标框四周 padding 充足（≥100px，未触边）")

    return 0
