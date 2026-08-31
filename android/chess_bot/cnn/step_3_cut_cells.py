# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "opencv-python>=4.11.0",
#   "numpy>=1.26",
# ]
# ///
"""脚本1（拆分自 step_3_cut_cells.py）：把 raw 截图切割成 64x64 逐格小图并标注。

把最慢的「透视矫正 + 残局模板匹配」只在这里做一次，结果缓存到 cnn/cells_cut/，
后续去重/切分脚本直接读小图，避免重复矫正，大幅提速。

输出结构（一对一映射 raw/<状态>/<图名>.png -> cells_cut/<状态>/<图名>/<类>/<图名>_rXXcYY.png）：
    cnn/cells_cut/
      <状态号>/<源图名>/
        <class>/<源图名>_r00c00.png   # 该格属于哪个 class
        ...
  注意：单张源图只产出它实际出现的类子目录（空格通常最多 ~70 张）。

标注规则：
  - 状态 1-4：用固定标注映射（开局/绝杀/提子/被将 的棋子位置已知）。
  - 状态 5 残局：位置不固定，用 step_2_cut_templates.py 生成的棋子模板自动匹配逐格标注。

模式：
  --mode full        清空 cells_cut 后重切全部 raw（改分辨率/模板后务必用此）。
  --mode incremental 只切 .cut_done.txt 中未记录的源图（默认，未指定时交互询问）。

运行：  uv run step_3_cut_cells.py [--mode full|incremental] [--rebuild-templates]
依赖：  opencv-python, numpy
"""

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from yolo_common import (  # noqa: E402
    RAW_ROOT, CELLS_CUT_DIR, CLASSES, COLS, ROWS, CELL_OUT,
    correct_board, label_map_for_state, VALID_STATES,
    crop_cell, ensure_template_sets, match_board_with_best_set,
    load_lift_labels, label_map_for_lift, state_dir,
)

IMG_EXTS = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}
DONE_FILE = CELLS_CUT_DIR / ".cut_done.txt"


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


def find_sources() -> list[tuple[int, Path]]:
    """返回 [(状态号, 源图路径)]，状态号由父目录名推断。"""
    out: list[tuple[int, Path]] = []
    if not RAW_ROOT.exists():
        return out
    for p in RAW_ROOT.rglob("*"):
        if not p.is_file() or p.suffix.lower() not in IMG_EXTS:
            continue
        try:
            st = int(p.parent.name)
        except ValueError:
            print(f"[跳过] 无法从父目录名推断状态: {p}")
            continue
        if st not in VALID_STATES:
            print(f"[跳过] 父目录 {p.parent.name} 非 1-5 状态: {p}")
            continue
        out.append((st, p))
    out.sort(key=lambda x: (x[0], x[1].name))
    return out


def load_done() -> set[str]:
    if not DONE_FILE.exists():
        return set()
    return {line.strip() for line in DONE_FILE.read_text(encoding="utf-8").splitlines() if line.strip()}


def write_done(done: set[str]) -> None:
    DONE_FILE.parent.mkdir(parents=True, exist_ok=True)
    DONE_FILE.write_text("\n".join(sorted(done)) + "\n", encoding="utf-8")


def cell_name(src_stem: str, r: int, c: int) -> str:
    return f"{src_stem}_r{r:02d}c{c:02d}.png"


def main() -> int:
    ap = argparse.ArgumentParser(description="切割 raw 截图为逐格小图（脚本1）")
    ap.add_argument("--mode", choices=["full", "incremental"], default=None,
                    help="full=清空重切全部；incremental=只切未记录的（默认交互询问）")
    ap.add_argument("--rebuild-templates", action="store_true",
                    help="重新由 raw/1 生成残局匹配模板（仅影响状态5标注）")
    args = ap.parse_args()

    mode = resolve_mode(args.mode)
    sources = find_sources()
    if not sources:
        print(f"未在 {RAW_ROOT} 下找到截图。请先运行 step_1_collect_screenshots.py 采集。")
        return 1

    if mode == "full":
        if CELLS_CUT_DIR.exists():
            import shutil
            shutil.rmtree(CELLS_CUT_DIR)
        print("[full] 已清空 cells_cut，将重切全部源图。\n")
        done: set[str] = set()
    else:
        done = load_done()
        print(f"[incremental] 已记录 {len(done)} 张，将只切未记录的源图。\n")

    # 残局(状态5) 需要模板：仅在有待处理的状态5源图时加载（避免无谓构建）。
    pending = [(st, p) for st, p in sources if f"{st}/{p.stem}" not in done]
    need_templates = any(st == 5 for st, _ in pending)
    template_sets = None
    if need_templates:
        try:
            template_sets = ensure_template_sets(rebuild=args.rebuild_templates)
        except Exception as e:  # noqa: BLE001
            print(f"[错误] 残局模板准备失败: {e}")
            return 1
        nset = len(template_sets)
        print(f"[模板] 已加载 {nset} 套棋子模板（共 {nset * 14} 张）；残局将自动选匹配率最高的一套。\n")

    # 状态3 提子标签（labels.csv）：一次加载，供各源图动态构建标注映射；无记录默认红中兵(6,4)。
    lift_labels_3 = load_lift_labels(state_dir(3))

    print(f"发现 {len(sources)} 张截图，待处理 {len(pending)} 张...\n")
    cut_total = 0
    skipped = 0
    for st, p in pending:
        raw = np.fromfile(str(p), dtype=np.uint8)
        img = cv2.imdecode(raw, cv2.IMREAD_COLOR)
        if img is None:
            print(f"[跳过] 解码失败: {p.name}")
            skipped += 1
            continue
        try:
            corrected = correct_board(img)
        except Exception as e:  # noqa: BLE001
            print(f"[跳过] 矫正失败 {p.name}: {e}")
            skipped += 1
            continue

        cw, ch = corrected.shape[1], corrected.shape[0]
        if (cw, ch) != (900, 1000):
            print(f"[警告] {p.name} 矫正尺寸异常 {cw}x{ch}（应 900x1000），仍处理")

        # 该源图的输出根目录
        out_root = CELLS_CUT_DIR / f"{st}" / p.stem
        out_root.mkdir(parents=True, exist_ok=True)

        # 决定每格的 class
        if st == 5 and template_sets is not None:
            best_name, cells = match_board_with_best_set(corrected, template_sets)
            if best_name is None:
                print(f"[跳过] {p.name}: 任何皮肤模板都无 0.8+ 棋子匹配，疑似 raw/1 缺该皮肤；请补充对应开局图后重切")
                skipped += 1
                continue
            label_iter = iter(cells)  # (r, c, key, score)
            def next_label():
                r, c, key, _ = next(label_iter)
                return r, c, key
        else:
            if st == 3:
                lr, lc = lift_labels_3.get(p.stem, (6, 4))
                lmap = label_map_for_lift(lr, lc)
            else:
                lmap = label_map_for_state(st)
            items = [(r, c, lmap.get((r, c), "empty")) for r in range(ROWS) for c in range(COLS)]
            label_iter = iter(items)
            def next_label():
                return next(label_iter)

        n_this = 0
        try:
            while True:
                r, c, key = next_label()
                cell = crop_cell(corrected, r, c)
                if cell is None:
                    continue
                cls_dir = out_root / key
                cls_dir.mkdir(parents=True, exist_ok=True)
                out_path = cls_dir / cell_name(p.stem, r, c)
                cv2.imencode(".png", cell)[1].tofile(str(out_path))
                n_this += 1
        except StopIteration:
            pass

        cut_total += n_this
        done.add(f"{st}/{p.stem}")
        print(f"  [{st}] {p.name}: 切出 {n_this} 格 -> {out_root}")

    write_done(done)
    print(f"\n=== 切割完成 ===")
    print(f"本次新增 {cut_total} 格；跳过 {skipped} 张异常截图；累计已记录 {len(done)} 张。")
    print(f"缓存目录: {CELLS_CUT_DIR}")
    print("下一步：uv run step_4_dedup_cells.py  （去重 -> cells_dedup/）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
