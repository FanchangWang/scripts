# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "opencv-python>=4.11.0",
#   "numpy>=1.26",
# ]
# ///
"""step_7_validate_cv.py —— 残局(状态5)模板匹配中间态验证脚本（人工审核用）。

背景：step_3_cut_cells.py 在状态5 用模板匹配逐格标注，判定阈值为 EMPTY_MATCH_THRESHOLD(0.8)。
任何真实棋子只要与最佳模板分数 < 0.8 就会被静默判成 empty，造成误标空格。
本脚本把同一套匹配逻辑在矫正图上逐格可视化，便于人工找出「本应是棋子、却被判成空格」的图。

逻辑（与 step_3_cut_cells.py 完全一致，便于复现真实失败）：
  1. 对每张 raw/5/<图>.png：解码 -> correct_board(900x1000)。
  2. match_board_with_best_set 选匹配率最高的那套模板（与管线一致）。
  3. 在该套模板下逐格做滑动窗口匹配，取最佳棋子候选及其分数 best_score。
  4. 标注：
       - 棋子格(best_score >= thresh)：写 class 简称(如 b_k / r_c) + 分数；
         红方棋子绿色、黑方棋子橙色。
       - 空格格(best_score < thresh)：
           * 若 best_score >= --suspicion(默认0.5)：用红色写「最佳候选:分数」，
             即「疑似被误判为空格的棋子」，人工重点审核。
           * 否则：灰色写「.」表示确定空格。

输出：
  raw_cv/5/<图名>.png        逐格标注后的矫正图（900x1000）
  raw_cv/5/_summary.txt      每张图统计：选用模板套、棋子数、疑似误判空格数及坐标

运行：  uv run step_7_validate_cv.py [--rebuild-templates] [--thresh 0.8] [--suspicion 0.5]
依赖：  opencv-python, numpy
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from yolo_common import (  # noqa: E402
    RAW_ROOT, COLS, ROWS, CORRECT_CELL, CORRECT_W, CORRECT_H,
    CELL_OUT, MATCH_SEARCH_HALF, EMPTY_MATCH_THRESHOLD,
    corrected_center, crop_cell, correct_board, ensure_template_sets,
    match_board_with_best_set, state_dir,
)

STATE = 5
FONT = cv2.FONT_HERSHEY_SIMPLEX


def match_cell_full(corrected, r: int, c: int, templates: dict, threshold: float):
    """逐格匹配：返回 (best_piece_key, best_score)。

    等价 match_cell_in_corrected 的滑动窗口匹配，但保留「最佳棋子候选」与其分数
    （即便低于 threshold 也返回该候选，供上层判断是否误判空格）。
    与 xiangqi-bot/vision.analyze_cell 一致：以格心为中心取
    (MATCH_SEARCH_HALF + CELL_OUT/2) 半径搜索窗口容忍亚像素偏移。
    """
    cx, cy = corrected_center(r, c)
    px, py = int(round(cx)), int(round(cy))
    half = MATCH_SEARCH_HALF + CELL_OUT // 2
    x1 = int(max(0, px - half))
    y1 = int(max(0, py - half))
    window = corrected[y1:py + half, x1:px + half]
    if window.shape[0] < CELL_OUT or window.shape[1] < CELL_OUT:
        cell = crop_cell(corrected, r, c)
        if cell is None:
            return "empty", 0.0
        window = cell

    best_key, best_score = "empty", -1.0
    for key, tmpl in templates.items():
        res = cv2.matchTemplate(window, tmpl, cv2.TM_CCOEFF_NORMED)
        _, max_val, _, _ = cv2.minMaxLoc(res)
        if max_val > best_score:
            best_score, best_key = max_val, key
    is_empty = best_score < threshold
    return best_key, float(best_score), is_empty


def draw_grid(vis) -> None:
    for i in range(ROWS + 1):
        y = int(CORRECT_CELL * i)
        cv2.line(vis, (0, y), (CORRECT_W, y), (210, 210, 210), 1)
    for j in range(COLS + 1):
        x = int(CORRECT_CELL * j)
        cv2.line(vis, (x, 0), (x, CORRECT_H), (210, 210, 210), 1)


def _draw_block_text(vis, cx: int, cy: int, line1: str, line2: str, color) -> None:
    """在 (cx,cy) 居中画：先填黑底块，再在黑块上写字，保证任意棋子底色上都可辨。

    line1=棋子标签(如 b_k)，line2=分数(可空)；color 用于 line1 着色。
    """
    bw, bh = 60, 36
    x0, y0 = cx - bw // 2, cy - bh // 2
    cv2.rectangle(vis, (x0, y0), (x0 + bw, y0 + bh), (0, 0, 0), -1)  # 黑底
    cv2.putText(vis, line1, (x0 + 5, cy - 2), FONT, 0.5, color, 1, cv2.LINE_AA)
    if line2:
        cv2.putText(vis, line2, (x0 + 5, cy + 14), FONT, 0.33, (205, 205, 205), 1, cv2.LINE_AA)


def annotate_no_skin(corrected):
    """无匹配皮肤（0.8+ 自信棋子=0）时输出带红条警告的整图，提示需补 raw/1 皮肤。"""
    vis = corrected.copy()
    draw_grid(vis)
    cv2.rectangle(vis, (0, 0), (CORRECT_W, 44), (0, 0, 180), -1)
    cv2.putText(vis, "无匹配皮肤: 0.8+ 棋子=0 (raw/1 缺该皮肤)", (12, 30),
                FONT, 0.72, (255, 255, 255), 2, cv2.LINE_AA)
    return vis


def annotate(corrected, results: list, threshold: float, suspicion: float):
    """results: 长度 ROWS*COLS 的列表，元素 (best_piece, best_score, is_empty)。

    返回标注图 (900x1000 BGR)。标注统一先黑底块再写字，确保棋子自身颜色不干扰辨认。
    """
    vis = corrected.copy()
    draw_grid(vis)
    for idx, (best_piece, best_score, is_empty) in enumerate(results):
        r = idx // COLS
        c = idx % COLS
        cx, cy = corrected_center(r, c)
        cx, cy = int(cx), int(cy)
        if not is_empty:
            # 亮色字区分阵营：红方=亮绿，黑方=亮蓝；黑底上清晰可辨
            color = (90, 230, 90) if best_piece.startswith("r") else (120, 180, 255)
            _draw_block_text(vis, cx, cy, best_piece, f"{best_score:.2f}", color)
        else:
            if best_score >= suspicion:
                # 疑似误判空格：红字黑底，重点提示
                _draw_block_text(vis, cx, cy, best_piece, f"{best_score:.2f}", (60, 60, 255))
            else:
                # 确定空格：仅极淡灰点，避免遮挡与杂乱
                cv2.putText(vis, ".", (cx - 3, cy + 5), FONT, 0.5, (110, 110, 110), 1, cv2.LINE_AA)
    return vis


def main() -> int:
    ap = argparse.ArgumentParser(description="残局(状态5)模板匹配可视化验证")
    ap.add_argument("--rebuild-templates", action="store_true",
                    help="重新由 raw/1 生成残局匹配模板")
    ap.add_argument("--thresh", type=float, default=EMPTY_MATCH_THRESHOLD,
                    help="判空格阈值（默认 0.8，与管线一致）")
    ap.add_argument("--suspicion", type=float, default=0.5,
                    help="疑似误判空格的最低分数（默认 0.5，红色标注）")
    ap.add_argument("--state", type=int, default=STATE,
                    help="要验证的状态目录（默认 5=残局）")
    args = ap.parse_args()

    state_dir_path = state_dir(args.state)
    if not state_dir_path.exists():
        print(f"[错误] 未找到 {state_dir_path}，请先采集该状态截图。")
        return 1

    imgs = sorted(state_dir_path.glob("*.png"))
    if not imgs:
        print(f"[错误] {state_dir_path} 下无 .png 截图。")
        return 1

    try:
        template_sets = ensure_template_sets(rebuild=args.rebuild_templates)
    except Exception as e:  # noqa: BLE001
        print(f"[错误] 模板准备失败: {e}")
        return 1
    print(f"[模板] 已加载 {len(template_sets)} 套棋子模板；将自动选匹配率最高的一套。\n")

    out_root = RAW_ROOT.parent / "raw_cv"
    if out_root.exists():
        shutil.rmtree(out_root)
        print(f"[清空] 已删除旧输出目录: {out_root}")
    out_dir = out_root / f"{args.state}"
    out_dir.mkdir(parents=True, exist_ok=True)

    summary_lines = []
    print(f"发现 {len(imgs)} 张截图，开始验证...\n")
    for p in imgs:
        raw = np.fromfile(str(p), dtype=np.uint8)
        img = cv2.imdecode(raw, cv2.IMREAD_COLOR)
        if img is None:
            print(f"[跳过] 解码失败: {p.name}")
            continue
        try:
            corrected = correct_board(img)
        except Exception as e:  # noqa: BLE001
            print(f"[跳过] 矫正失败 {p.name}: {e}")
            continue

        best_name, _cells = match_board_with_best_set(corrected, template_sets, args.thresh)
        if best_name is None:
            print(f"  [{p.name}] ⚠️ 无皮肤模板能自信匹配(0.8+棋子=0) -> 疑似 raw/1 缺该皮肤")
            vis = annotate_no_skin(corrected)
            out_path = out_dir / f"{p.stem}.png"
            cv2.imencode(".png", vis)[1].tofile(str(out_path))
            summary_lines.append(
                f"{p.name}\tset=NONE\tpieces=0\tsuspicious_empty=0  [NO-SKIN]"
            )
            continue

        tset = template_sets[best_name]

        results = []
        for r in range(ROWS):
            for c in range(COLS):
                results.append(match_cell_full(corrected, r, c, tset, args.thresh))

        vis = annotate(corrected, results, args.thresh, args.suspicion)
        out_path = out_dir / f"{p.stem}.png"
        cv2.imencode(".png", vis)[1].tofile(str(out_path))

        # 统计
        piece_count = sum(1 for _, _, e in results if not e)
        suspicious = [(idx // COLS, idx % COLS, k, s) for idx, (k, s, e) in enumerate(results)
                      if e and s >= args.suspicion]
        n_susp = len(suspicious)
        flag = " ⚠️疑似误判空格" if n_susp else ""
        if piece_count == 0:
            flag += " 🔴0棋子(疑似缺皮肤模板)"
        print(f"  [{p.name}] 套={best_name} 棋子={piece_count} 疑似空格={n_susp}{flag} -> {out_path.name}")
        summary_lines.append(
            f"{p.name}\tset={best_name}\tpieces={piece_count}\tsuspicious_empty={n_susp}"
            f"{'  [0-PIECE]' if piece_count == 0 else ''}"
        )
        if suspicious:
            cells = ", ".join(f"({r},{c})={k}:{s:.2f}" for (r, c, k, s) in suspicious)
            summary_lines.append(f"    可疑: {cells}")

    summary_path = out_dir / "_summary.txt"
    summary_path.write_text("\n".join(summary_lines) + "\n", encoding="utf-8")
    print(f"\n=== 验证完成 ===")
    print(f"标注图目录: {out_dir}")
    print(f"统计摘要:   {summary_path}")
    print("请人工审核 raw_cv/5/ 中红色标注的格子（疑似被误判为空格的棋子），")
    print("并告知哪些图识别失败，再决定降阈值或补皮肤模板。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
