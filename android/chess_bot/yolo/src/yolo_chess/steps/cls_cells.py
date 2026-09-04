"""cls_cells.py —— 把 raw 截图切割成 64x64 逐格小图并标注。

输出：cls/cells/<状态英文名>/<图名>/<类>/<图名>_rXXcYY.png
"""

from __future__ import annotations

import shutil
from collections.abc import Iterator
from pathlib import Path

import cv2

from yolo_chess.common import (
    CLS_CELLS,
    COLS,
    CORRECT_H,
    CORRECT_W,
    ROWS,
    SHARED_RAW,
    STATE_ENDGAME,
    STATE_LIFT,
    VALID_STATES,
    Param,
    correct_board,
    crop_cell,
    ensure_template_sets,
    imread,
    interactive_args,
    label_map_for_lift,
    label_map_for_state,
    load_lift_labels,
    match_board_with_best_set,
    state_dir,
)

IMG_EXTS = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}
DONE_FILE = CLS_CELLS / ".cut_done.txt"

PARAMS = [
    Param(
        "mode",
        "choice",
        default="incremental",
        choices=["incremental", "full"],
        cn="切割模式",
        desc="增量只切未记录，全量清空重切",
    ),
]


def _find_sources() -> list[tuple[str, Path]]:
    """返回 [(状态英文 key, 源图路径)]。"""
    out: list[tuple[str, Path]] = []
    if not SHARED_RAW.exists():
        return out
    for p in SHARED_RAW.rglob("*"):
        if not p.is_file() or p.suffix.lower() not in IMG_EXTS:
            continue
        st = p.parent.name
        if st not in VALID_STATES:
            continue
        out.append((st, p))
    out.sort(key=lambda x: (x[0], x[1].name))
    return out


def _load_done() -> set[str]:
    if not DONE_FILE.exists():
        return set()
    return {
        line.strip() for line in DONE_FILE.read_text(encoding="utf-8").splitlines() if line.strip()
    }


def _write_done(done: set[str]) -> None:
    DONE_FILE.parent.mkdir(parents=True, exist_ok=True)
    DONE_FILE.write_text("\n".join(sorted(done)) + "\n", encoding="utf-8")


def _cell_name(src_stem: str, r: int, c: int) -> str:
    return f"{src_stem}_r{r:02d}c{c:02d}.png"


def main() -> int:
    """切割逐格小图主函数。"""
    args = interactive_args(PARAMS)
    if args is None:
        return 0

    mode = args.mode
    sources = _find_sources()
    if not sources:
        print(f"未在 {SHARED_RAW} 下找到截图。请先运行「采集截图」。")
        return 1

    if mode == "full":
        if CLS_CELLS.exists():
            shutil.rmtree(CLS_CELLS)
        print("[full] 已清空 cells，将重切全部源图。\n")
        done: set[str] = set()
    else:
        done = _load_done()
        print(f"[incremental] 已记录 {len(done)} 张，将只切未记录的源图。\n")

    pending = [(st, p) for st, p in sources if f"{st}/{p.stem}" not in done]
    need_templates = any(st == STATE_ENDGAME for st, _ in pending)
    template_sets = None
    if need_templates:
        try:
            template_sets = ensure_template_sets()
        except Exception as e:
            print(f"[错误] 残局模板准备失败: {e}")
            return 1
        nset = len(template_sets)
        print(f"[模板] 已加载 {nset} 套棋子模板；残局将自动选匹配率最高的一套。\n")

    lift_labels_3 = load_lift_labels(state_dir(STATE_LIFT))

    print(f"发现 {len(sources)} 张截图，待处理 {len(pending)} 张...\n")
    cut_total = 0
    skipped = 0
    for st, p in pending:
        img = imread(p)
        if img is None:
            print(f"[跳过] 解码失败: {p.name}")
            skipped += 1
            continue
        try:
            corrected = correct_board(img)
        except Exception as e:
            print(f"[跳过] 矫正失败 {p.name}: {e}")
            skipped += 1
            continue

        cw, ch = corrected.shape[1], corrected.shape[0]
        if (cw, ch) != (CORRECT_W, CORRECT_H):
            print(f"[警告] {p.name} 矫正尺寸异常 {cw}x{ch}，仍处理")

        out_root = CLS_CELLS / st / p.stem
        out_root.mkdir(parents=True, exist_ok=True)

        if st == STATE_ENDGAME and template_sets is not None:
            best_name, cells = match_board_with_best_set(corrected, template_sets)
            if best_name is None:
                print(f"[跳过] {p.name}: 任何皮肤模板都无 0.8+ 棋子匹配")
                skipped += 1
                continue
            label_iter = iter(cells)

            def next_label(_it: Iterator = label_iter) -> tuple[int, int, str]:
                r, c, key, _ = next(_it)  # type: ignore[misc]
                return r, c, key
        else:
            if st == STATE_LIFT:
                lr, lc = lift_labels_3.get(p.stem, (6, 4))
                lmap = label_map_for_lift(lr, lc)
            else:
                lmap = label_map_for_state(st)
            items = [(r, c, lmap.get((r, c), "empty")) for r in range(ROWS) for c in range(COLS)]
            label_iter = iter(items)

            def next_label(_it: Iterator = label_iter) -> tuple[int, int, str]:
                return next(_it)  # type: ignore[misc]

        n_this = 0
        try:
            while True:
                r, c, key = next_label()
                cell = crop_cell(corrected, r, c)
                if cell is None:
                    continue
                cls_dir = out_root / key
                cls_dir.mkdir(parents=True, exist_ok=True)
                out_path = cls_dir / _cell_name(p.stem, r, c)
                cv2.imencode(".png", cell)[1].tofile(str(out_path))
                n_this += 1
        except StopIteration:
            pass

        cut_total += n_this
        done.add(f"{st}/{p.stem}")
        print(f"  [{st}] {p.name}: 切出 {n_this} 格 -> {out_root}")

    _write_done(done)
    print("\n=== 切割完成 ===")
    print(f"本次新增 {cut_total} 格；跳过 {skipped} 张；累计已记录 {len(done)} 张。")
    print(f"缓存目录: {CLS_CELLS}")
    return 0
