"""step_11_build_corner_dataset.py
================================
构建「棋盘4角定位」的 YOLO-Detection(检测) 数据集。

建模方式（与「图上找 4 种动物」完全同构）：
  - 真值：yolo_common.DEFAULT_CORNERS[(w,h)]，顺序 (TL, TR, BL, BR) 像素坐标
  - 类别：4 个（tl / tr / bl / br），每个角作为一个独立检测目标（小框）
  - 标注：每个角用一个「以角点为中心」的小正方形框标注，框心即角点坐标。
         这样检测输出的 4 个框心就是 4 个角，可直接喂 homography 矫正。

为什么用检测而不是关键点：两者自由度都足够（det 4 框 = 16 DOF ≥ 8），
且都能给出 4 角坐标；本任务实测精度天花板由有效分辨率决定（≈11px），
与 head 类型无关。det 4 类更直观、无需「点无延展需发明框尺寸」之外的额外假设，
且每类直接对应一个确定角，无需角匹配/去重。

角框尺寸 CORNER_HALF 是标注超参：框心严格等于角点。框越大检测越容易、
框心仍精确（训练标签的框心就是角点），故取 32px（框 64×64）以缓解小目标难度——
在 640 letterbox 下约 17 网络 px，属可控小目标；且角间距 ~900px，框不会互相重叠。

多分辨率天然支持——按每张图的 (w,h) 查 DEFAULT_CORNERS；未收录的分辨率会被跳过并报告，
待用户在 raw/ 补充对应截图后重跑本脚本即可纳入。

运行：uv run step_11_build_corner_dataset.py
"""
from __future__ import annotations

import hashlib
import os
import random
import re
import shutil
import sys
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from yolo_common import DEFAULT_CORNERS  # (w,h) -> (TL,TR,BL,BR)


def _imread(path) -> np.ndarray | None:
    """Unicode 安全读取（cv2.imread 在部分 Windows 构建上对中文路径返回 None）。"""
    b = np.fromfile(str(path), dtype=np.uint8)
    if b.size == 0:
        return None
    return cv2.imdecode(b, cv2.IMREAD_COLOR)


def _ascii_stem(nskey: str) -> str:
    """将命名空间键（可能含中文）映射为确定且唯一的 ASCII 文件名茎。

    直接剔除非 ASCII 会撞名（如 木_红 / 木_黑 都变成 ____），故对原始键取稳定哈希。
    """
    h = hashlib.md5(nskey.encode("utf-8")).hexdigest()[:12]
    return re.sub(r"[^A-Za-z0-9]", "_", nskey)[:40] + "_" + h

RAW = HERE / "raw"
OUT = HERE / "corner" / "dataset"
SEED = 42
VAL_RATIO = 0.2

# 角点框半边长（像素）。框心严格落在角点上；框越大越易检测、框心仍精确。
# 130×130（half=65）比早期 64×64 包含更多棋盘角附近的网格线/边线上下文，更鲁棒。
CORNER_HALF = 65
# 4 个角的类别顺序，必须与 DEFAULT_CORNERS / _dst_points() 完全一致：0=TL 1=TR 2=BL 3=BR
CORNER_CLASSES = ["tl", "tr", "bl", "br"]


def _hardlink_or_copy(src: Path, dst: Path) -> None:
    """优先硬链接（同卷零额外空间，无需管理员权限），失败回退复制。"""
    if dst.exists():
        return
    try:
        os.link(str(src), str(dst))
    except OSError:
        shutil.copy(str(src), str(dst))


def main() -> None:
    random.seed(SEED)
    samples: list[tuple[Path, np.ndarray, str]] = []   # (img_path, corners_np_4x2, subdir)
    skipped: list[tuple[Path, str]] = []
    res_counter: dict[tuple[int, int], int] = {}

    for d in sorted(RAW.iterdir()):
        if not d.is_dir():
            continue
        for f in sorted(d.glob("*.png")):
            img = _imread(f)
            if img is None:
                skipped.append((f, "imread 失败"))
                continue
            h, w = img.shape[:2]
            key = (int(w), int(h))
            res_counter[key] = res_counter.get(key, 0) + 1
            if key not in DEFAULT_CORNERS:
                skipped.append((f, f"分辨率{key}无 DEFAULT_CORNERS"))
                continue
            pts = np.array(DEFAULT_CORNERS[key], dtype=np.float32)  # (4,2) TL,TR,BL,BR
            samples.append((f, pts, d.name))

    print(f"[收集] 分辨率分布: {res_counter}")
    print(f"[收集] 纳入 {len(samples)} 张，跳过 {len(skipped)} 张")
    for f, r in skipped:
        print(f"  跳过 {f.parent.name}/{f.name}: {r}")

    if not samples:
        print("[中止] 无可用样本")
        return

    for split in ("train", "val"):
        (OUT / "images" / split).mkdir(parents=True, exist_ok=True)
        (OUT / "labels" / split).mkdir(parents=True, exist_ok=True)

    random.shuffle(samples)
    n_val = max(1, int(round(len(samples) * VAL_RATIO)))
    splits = ["val"] * n_val + ["train"] * (len(samples) - n_val)

    sample_label: str | None = None
    # 贴边诊断：框越界会被 Ultralytics 裁剪 → 框心偏移 → 该类标签带噪 → 置信度塌陷。
    # 即使未越界，余量过小的框在 translate/scale 增强下仍会被推出画面而裁剪。
    # 实测(1080x2400, CORNER_HALF=65)：BR 余量仅 1px，其中位置信度 0.157，
    # 而余量 11px 的 TL/TR 达 0.82~0.85 —— 余量与置信度强相关。
    margin_worst: dict[tuple[int, int], list[float]] = {}
    nsmap: dict[str, str] = {}   # dataset 图像茎(ASCII) -> raw 命名空间键(可能含中文)
    for (f, pts, sub), split in zip(samples, splits):
        img = _imread(f)
        if img is None:
            continue
        h, w = img.shape[:2]
        margin_worst.setdefault((w, h), [
            min(cx - CORNER_HALF, w - (cx + CORNER_HALF),
                cy - CORNER_HALF, h - (cy + CORNER_HALF)) for cx, cy in pts
        ])
        lines: list[str] = []
        for ci, (cx, cy) in enumerate(pts):
            # 框心严格等于角点；框尺寸固定 2*CORNER_HALF，归一化到整图
            xc = cx / w
            yc = cy / h
            bw = (2 * CORNER_HALF) / w
            bh = (2 * CORNER_HALF) / h
            lines.append(f"{ci} {xc:.6f} {yc:.6f} {bw:.6f} {bh:.6f}")
        if sample_label is None:
            sample_label = "\n".join(lines)
        nskey = f"{sub}_{f.stem}"          # 命名空间键：raw/6/木_红_1080x2400 -> 6_木_红_1080x2400
        img_stem = _ascii_stem(nskey)      # ASCII 安全茎，供 Ultralytics(cv2) 训练读取
        nsmap[img_stem] = nskey
        (OUT / "labels" / split / (img_stem + ".txt")).write_text(
            "\n".join(lines) + "\n", encoding="utf-8")
        _hardlink_or_copy(f, OUT / "images" / split / (img_stem + f.suffix))

    (OUT / "data.yaml").write_text(
        "# 棋盘4角定位 (YOLO-Detection / 4 类：tl,tr,bl,br)\n"
        f"path: {OUT.as_posix()}\n"
        "train: images/train\n"
        "val: images/val\n"
        "nc: 4\n"
        "names: ['tl', 'tr', 'bl', 'br']\n"
        "# 类别顺序: 0=TL 1=TR 2=BL 3=BR（角点框心 = 角点坐标）\n",
        encoding="utf-8")

    # 命名空间映射：dataset 图像茎(ASCII) -> raw 文件命名空间键（step_13 --split val/train 反查用）
    import json
    (OUT / "_nsmap.json").write_text(
        json.dumps(nsmap, ensure_ascii=False, indent=0), encoding="utf-8")

    print(f"[完成] 数据集 -> {OUT}")
    print(f"  train={len(samples) - n_val}  val={n_val}")
    print(f"  每张图标签行数={len(CORNER_CLASSES)}（4 角各 1 行: class x y w h）")
    print(f"  角框半边长 CORNER_HALF={CORNER_HALF}px（框心=角点）")
    if sample_label:
        print("  示例标签(归一化):\n" + sample_label)

    # --- 贴边告警 ---
    names = [c.upper() for c in CORNER_CLASSES] if CORNER_CLASSES else ["TL", "TR", "BL", "BR"]
    bad = False
    for (w, h), ms in sorted(margin_worst.items()):
        tight = [(names[i], m) for i, m in enumerate(ms) if m < 20]
        over = [(names[i], m) for i, m in enumerate(ms) if m < 0]
        if over:
            bad = True
            print(f"  ❌ {w}x{h} 角框越界: "
                  + " ".join(f"{n}({m:+.0f}px)" for n, m in over)
                  + f" → 标签归一化 >1 会被裁剪、框心偏移。请把 CORNER_HALF 降到 "
                    f"{int(min(m for _, m in over) + CORNER_HALF - 1)} 以下")
        elif tight:
            bad = True
            print(f"  ⚠ {w}x{h} 角框贴边（余量<20px）: "
                  + " ".join(f"{n}({m:.0f}px)" for n, m in tight)
                  + " → 训练时 translate/scale 增强会把该框推出画面而裁剪，"
                    "使该类标签带噪、置信度塌陷（实测 BR 余量1px → 中位conf 0.157）。"
                    "对策：step_12 用 --translate 0 --scale 0，或调小 CORNER_HALF。")
    if not bad:
        print("  ✅ 所有角框距图像边界余量充足（≥20px）")


if __name__ == "__main__":
    main()
