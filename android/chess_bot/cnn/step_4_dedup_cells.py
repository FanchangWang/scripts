# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "opencv-python>=4.11.0",
#   "numpy>=1.26",
# ]
# ///
"""脚本2（拆分自 step_3_cut_cells.py）：对 cells_cut 逐类去重 -> cells_dedup/。

把同类内「视觉几乎一致」的格子合并为一张代表，避免训练集冗余与 train/val 泄漏。
去重用连通分量聚类（yolo_common.dedup_class），与切分顺序无关，增量也安全。

输入：  cnn/cells_cut/<状态>/<图名>/<类>/*.png   （脚本1 产出）
输出：  cnn/cells_dedup/<类>/<图名>_rXXcYY.png    （已去重，全局无 >=阈值 的近重复对）

模式：
  --mode full        清空 cells_dedup 后由 cells_cut 全量重做（改去重阈值后务必用此）。
  --mode incremental 只处理 .dedup_done.txt 中未记录的源图（默认，未指定时交互询问）。

阈值：
  --dedup-thresh        棋子/lift 类阈值（默认 0.99）
  --dedup-thresh-empty  empty 类阈值（默认 0.98，比棋子更激进以压低 empty 占比）
  --min-per-class       去重后每类最小保留张数（默认 8，防删崩）

运行：  uv run step_4_dedup_cells.py [--mode full|incremental] [--dedup-thresh 0.99] [--dedup-thresh-empty 0.98]
依赖：  opencv-python, numpy
"""

import argparse
import shutil
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from yolo_common import (  # noqa: E402
    CELLS_CUT_DIR, CELLS_DEDUP_DIR, CLASSES, dedup_class,
)

DONE_FILE = CELLS_DEDUP_DIR / ".dedup_done.txt"


def resolve_mode(arg: str | None) -> str:
    if arg in ("full", "incremental"):
        return arg
    while True:
        inp = input("选择模式 [1]全量 [2]增量 (默认增量): ").strip()
        if inp in ("", "2"):
            return "incremental"
        if inp == "1":
            return "full"
        print("请输入 1 或 2")


def list_sources() -> list[tuple[str, str]]:
    """返回 cells_cut 下所有源图标识 [(状态, 图名)]。"""
    out: list[tuple[str, str]] = []
    if not CELLS_CUT_DIR.exists():
        return out
    for state_dir in sorted(CELLS_CUT_DIR.iterdir()):
        if not state_dir.is_dir():
            continue
        for name_dir in sorted(state_dir.iterdir()):
            if name_dir.is_dir():
                out.append((state_dir.name, name_dir.name))
    return out


def load_desc(img: np.ndarray) -> np.ndarray:
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    g = cv2.resize(g, (32, 32), interpolation=cv2.INTER_AREA)
    return g.astype(np.float32).reshape(-1) / 255.0


def load_done() -> set[str]:
    if not DONE_FILE.exists():
        return set()
    return {line.strip() for line in DONE_FILE.read_text(encoding="utf-8").splitlines() if line.strip()}


def write_done(done: set[str]) -> None:
    DONE_FILE.parent.mkdir(parents=True, exist_ok=True)
    DONE_FILE.write_text("\n".join(sorted(done)) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description="cells_cut 逐类去重（脚本2）")
    ap.add_argument("--mode", choices=["full", "incremental"], default=None,
                    help="full=清空重做全部；incremental=只处理未记录的（默认交互询问）")
    ap.add_argument("--dedup-thresh", type=float, default=0.99,
                    help="棋子/lift 类去重阈值（>=该值判重复）；默认 0.99")
    ap.add_argument("--dedup-thresh-empty", type=float, default=0.98,
                    help="empty 类专用阈值（默认 0.98，比棋子更激进）；其余类用 --dedup-thresh")
    ap.add_argument("--min-per-class", type=int, default=8,
                    help="去重后每类最小保留张数；默认 8")
    args = ap.parse_args()

    if not CELLS_CUT_DIR.exists() or not list_sources():
        print(f"未在 {CELLS_CUT_DIR} 找到已切割的小图。请先运行 step_3_cut_cells.py。")
        return 1

    mode = resolve_mode(args.mode)
    all_sources = list_sources()

    if mode == "full":
        if CELLS_DEDUP_DIR.exists():
            shutil.rmtree(CELLS_DEDUP_DIR)
        print("[full] 已清空 cells_dedup，将全量重做。\n")
        done: set[str] = set()
    else:
        done = load_done()
        print(f"[incremental] 已记录 {len(done)} 个源图，将只处理未记录的。\n")

    pending = [(s, n) for (s, n) in all_sources if f"{s}/{n}" not in done]
    print(f"cells_cut 共 {len(all_sources)} 个源图，待去重 {len(pending)} 个。\n")

    removed_total = 0
    kept_total = 0
    per_class_counts: dict[str, int] = {c: 0 for c in CLASSES}
    per_class_thresh: dict[str, float] = {}
    for c in CLASSES:
        th = args.dedup_thresh_empty if c == "empty" else args.dedup_thresh
        per_class_thresh[c] = th

        # 已保留（增量模式下沿用上次的，作为不动锚点）
        old_kept_paths: list[Path] = []
        if mode == "incremental" and (CELLS_DEDUP_DIR / c).exists():
            old_kept_paths = sorted((CELLS_DEDUP_DIR / c).glob("*.png"))
        old_descs: np.ndarray | None = None
        if old_kept_paths:
            old_descs = np.stack([load_desc(cv2.imdecode(np.fromfile(str(p), dtype=np.uint8),
                                                         cv2.IMREAD_COLOR)) for p in old_kept_paths])

        # 候选：本次待处理源图中属于该类的格子
        # 注意：输出文件名必须带 state+name 前缀，否则不同 state 下同名源图
        # (如各 state 都有的 0001) 会生成相同 basename，写盘时相互覆盖 -> 静默丢数据。
        cand_entries: list[tuple[str, str, str, np.ndarray]] = []  # (state, name, 文件名, BGR图)
        for s, n in pending:
            d = CELLS_CUT_DIR / s / n / c
            if not d.exists():
                continue
            for f in sorted(d.glob("*.png")):
                img = cv2.imdecode(np.fromfile(str(f), dtype=np.uint8), cv2.IMREAD_COLOR)
                if img is not None:
                    cand_entries.append((s, n, f.name, img))

        if not cand_entries:
            # 增量且无新候选：原 cells_dedup/<c> 保持不变
            kept_total += len(old_kept_paths)
            per_class_counts[c] = len(old_kept_paths)
            continue

        cand_imgs = [e[3] for e in cand_entries]
        # 全局唯一键：state__name__原文件名（原文件名已含 cell 坐标）
        name_of = {id(e[3]): f"{e[0]}__{e[1]}__{e[2]}" for e in cand_entries}

        if mode == "full" or not old_kept_paths:
            kept_imgs = dedup_class(cand_imgs, thresh=th, min_per_class=args.min_per_class)
            removed_total += len(cand_imgs) - len(kept_imgs)
            kept_total += len(kept_imgs)
            per_class_counts[c] = len(kept_imgs)
            out_dir = CELLS_DEDUP_DIR / c
            if mode == "full":
                if out_dir.exists():
                    shutil.rmtree(out_dir)
            out_dir.mkdir(parents=True, exist_ok=True)
            for img in kept_imgs:
                fn = name_of[id(img)]
                cv2.imencode(".png", img)[1].tofile(str(out_dir / fn))
        else:
            # 增量：新候选内部先连通分量聚类，再逐张与旧保留比对剔除近重复
            new_reps = dedup_class(cand_imgs, thresh=th, min_per_class=args.min_per_class)
            to_add: list[np.ndarray] = []
            for img in new_reps:
                if old_descs is not None:
                    sim = 1.0 - np.abs(old_descs - load_desc(img)).mean(axis=1)
                    if sim.max() >= th:
                        continue  # 与旧保留近重复 -> 丢弃
                to_add.append(img)
            removed_total += len(cand_imgs) - len(to_add)
            kept_total += len(old_kept_paths) + len(to_add)
            per_class_counts[c] = len(old_kept_paths) + len(to_add)
            out_dir = CELLS_DEDUP_DIR / c
            out_dir.mkdir(parents=True, exist_ok=True)
            for img in to_add:
                fn = name_of[id(img)]
                cv2.imencode(".png", img)[1].tofile(str(out_dir / fn))

    # 记录已处理源图
    for s, n in pending:
        done.add(f"{s}/{n}")
    write_done(done)

    print(f"\n=== 去重完成 ===")
    print(f"总保留: {kept_total} 张（本轮剔除约 {removed_total} 张重复）；"
          f"累计已记录 {len(done)} 个源图。")
    print(f"目录: {CELLS_DEDUP_DIR}\n")
    print(f"  {'class':<8}{'thresh':>8}{'保留':>8}  说明")
    for c in sorted(CLASSES, key=lambda x: -per_class_counts[x]):
        warn = "  ⚠ 偏少(<min_per_class)" if per_class_counts[c] < args.min_per_class else ""
        print(f"  {c:<8}{per_class_thresh[c]:>8.3f}{per_class_counts[c]:>8}{warn}")
    print("\n下一步：uv run step_5_build_dataset.py  （切分 train/val -> dataset_yolo/）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
