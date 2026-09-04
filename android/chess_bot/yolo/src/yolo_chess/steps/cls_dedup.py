"""cls_dedup.py —— 对 cls/cells 逐类去重 -> cls/cells_dedup/。

同类内「视觉几乎一致」的格子合并为一张代表。
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

import cv2
import numpy as np

from yolo_chess.common import (
    CLASSES,
    CLS_CELLS,
    CLS_CELLS_DEDUP,
    Param,
    dedup_class,
    imread,
    interactive_args,
)

DONE_FILE = CLS_CELLS_DEDUP / ".dedup_done.txt"

PARAMS = [
    Param(
        "mode",
        "choice",
        default="incremental",
        choices=["incremental", "full"],
        cn="去重模式",
        desc="增量只处理未记录，全量清空重做",
    ),
    Param("dedup_thresh", "float", default=0.99, cn="棋子/lift类阈值", desc="相似度达此值判为重复"),
    Param(
        "dedup_thresh_empty",
        "float",
        default=0.975,
        cn="empty类阈值",
        desc="空格类专用阈值（更激进压低占比）",
    ),
    Param("min_per_class", "int", default=8, cn="每类最少保留", desc="去重后不足则回补样本"),
]


def _list_sources() -> list[tuple[str, str]]:
    """返回 cells 下所有源图标识 [(状态, 图名)]。"""
    out: list[tuple[str, str]] = []
    if not CLS_CELLS.exists():
        return out
    for state_dir in sorted(CLS_CELLS.iterdir()):
        if not state_dir.is_dir():
            continue
        for name_dir in sorted(state_dir.iterdir()):
            if name_dir.is_dir():
                out.append((state_dir.name, name_dir.name))
    return out


def _load_desc(img: np.ndarray) -> np.ndarray:
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    g = cv2.resize(g, (32, 32), interpolation=cv2.INTER_AREA)
    return g.astype(np.float32).reshape(-1) / 255.0


def _load_done() -> set[str]:
    if not DONE_FILE.exists():
        return set()
    return {
        line.strip() for line in DONE_FILE.read_text(encoding="utf-8").splitlines() if line.strip()
    }


def _write_done(done: set[str]) -> None:
    DONE_FILE.parent.mkdir(parents=True, exist_ok=True)
    DONE_FILE.write_text("\n".join(sorted(done)) + "\n", encoding="utf-8")


def main() -> int:
    """去重主函数。"""
    args = interactive_args(PARAMS)
    if args is None:
        return 0

    if not CLS_CELLS.exists() or not _list_sources():
        print(f"未在 {CLS_CELLS} 找到已切割的小图。请先运行「切割逐格小图」。")
        return 1

    mode = args.mode
    all_sources = _list_sources()

    if mode == "full":
        if CLS_CELLS_DEDUP.exists():
            shutil.rmtree(CLS_CELLS_DEDUP)
        print("[full] 已清空 cells_dedup，将全量重做。\n")
        done: set[str] = set()
    else:
        done = _load_done()
        print(f"[incremental] 已记录 {len(done)} 个源图，将只处理未记录的。\n")

    pending = [(s, n) for (s, n) in all_sources if f"{s}/{n}" not in done]
    total = len(pending)
    print(f"cells 共 {len(all_sources)} 个源图，待去重 {total} 个。\n")

    removed_total = 0
    kept_total = 0
    per_class_counts: dict[str, int] = dict.fromkeys(CLASSES, 0)
    per_class_thresh: dict[str, float] = {}
    for ci, c in enumerate(CLASSES):
        th = args.dedup_thresh_empty if c == "empty" else args.dedup_thresh
        per_class_thresh[c] = th

        old_kept_paths: list[Path] = []
        if mode == "incremental" and (CLS_CELLS_DEDUP / c).exists():
            old_kept_paths = sorted((CLS_CELLS_DEDUP / c).glob("*.png"))
        old_descs: np.ndarray | None = None
        if old_kept_paths:
            decoded = [imread(p) for p in old_kept_paths]
            good = [_load_desc(img) for img in decoded if img is not None]
            if good:
                old_descs = np.stack(good)

        cand_entries: list[tuple[str, str, str, np.ndarray]] = []
        for si, (s, n) in enumerate(pending):
            sys.stdout.write(
                f"\r  [{ci + 1}/{len(CLASSES)}] {c}  源图 {si + 1}/{total}  {s}/{n}   "
            )
            sys.stdout.flush()
            d = CLS_CELLS / s / n / c
            if not d.exists():
                continue
            for f in sorted(d.glob("*.png")):
                img = imread(f)
                if img is not None:
                    cand_entries.append((s, n, f.name, img))
        sys.stdout.write(
            f"\r  [{ci + 1}/{len(CLASSES)}] {c}  源图 {total}/{total}  完成          \n"
        )
        sys.stdout.flush()

        if not cand_entries:
            kept_total += len(old_kept_paths)
            per_class_counts[c] = len(old_kept_paths)
            continue

        cand_imgs = [e[3] for e in cand_entries]
        name_of = {id(e[3]): f"{e[0]}__{e[1]}__{e[2]}" for e in cand_entries}

        def _progress(cur: int, tot: int, _ci: int = ci, _c: str = c) -> None:
            sys.stdout.write(f"\r  [{_ci + 1}/{len(CLASSES)}] {_c}  去重 {cur}/{tot}   ")
            sys.stdout.flush()

        if mode == "full" or not old_kept_paths:
            kept_imgs = dedup_class(
                cand_imgs, thresh=th, min_per_class=args.min_per_class, on_progress=_progress
            )
            print(
                f"\r  [{ci + 1}/{len(CLASSES)}] {c}  去重完成：保留 {len(kept_imgs)} 张        \n"
            )
            removed_total += len(cand_imgs) - len(kept_imgs)
            kept_total += len(kept_imgs)
            per_class_counts[c] = len(kept_imgs)
            out_dir = CLS_CELLS_DEDUP / c
            if mode == "full" and out_dir.exists():
                shutil.rmtree(out_dir)
            out_dir.mkdir(parents=True, exist_ok=True)
            for img in kept_imgs:
                fn = name_of[id(img)]
                cv2.imencode(".png", img)[1].tofile(str(out_dir / fn))
        else:
            new_reps = dedup_class(
                cand_imgs, thresh=th, min_per_class=args.min_per_class, on_progress=_progress
            )
            print(f"\r  [{ci + 1}/{len(CLASSES)}] {c}  去重完成：保留 {len(new_reps)} 张        \n")
            to_add: list[np.ndarray] = []
            for img in new_reps:
                if old_descs is not None:
                    sim = 1.0 - np.abs(old_descs - _load_desc(img)).mean(axis=1)
                    if sim.max() >= th:
                        continue
                to_add.append(img)
            removed_total += len(cand_imgs) - len(to_add)
            kept_total += len(old_kept_paths) + len(to_add)
            per_class_counts[c] = len(old_kept_paths) + len(to_add)
            out_dir = CLS_CELLS_DEDUP / c
            out_dir.mkdir(parents=True, exist_ok=True)
            for img in to_add:
                fn = name_of[id(img)]
                cv2.imencode(".png", img)[1].tofile(str(out_dir / fn))

    for s, n in pending:
        done.add(f"{s}/{n}")
    _write_done(done)

    print("\n=== 去重完成 ===")
    print(
        f"总保留: {kept_total} 张（本轮剔除约 {removed_total} 张重复）；累计已记录 {len(done)} 个源图。"
    )
    print(f"目录: {CLS_CELLS_DEDUP}\n")
    print(f"  {'class':<8}{'thresh':>8}{'保留':>8}")
    for c in sorted(CLASSES, key=lambda x: -per_class_counts[x]):
        warn = "  ⚠ 偏少(<min)" if per_class_counts[c] < args.min_per_class else ""
        print(f"  {c:<8}{per_class_thresh[c]:>8.3f}{per_class_counts[c]:>8}{warn}")
    return 0
