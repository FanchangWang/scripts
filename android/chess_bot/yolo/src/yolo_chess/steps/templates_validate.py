"""templates_validate.py —— 模板匹配中间态验证（人工审核用）。

属于 OpenCV 模板（templates/raw）流水线，而非 YOLO 分类。
"""

from __future__ import annotations

import shutil

import cv2
import numpy as np

from yolo_chess.common import (
    CELL_OUT,
    COLS,
    CORRECT_CELL,
    CORRECT_H,
    CORRECT_W,
    EMPTY_MATCH_THRESHOLD,
    MATCH_SEARCH_HALF,
    ROWS,
    SHARED_ROOT,
    STATE_CN,
    STATE_ENDGAME,
    STATE_LIFT,
    STATE_MATE,
    STATE_OPENING,
    Param,
    correct_board,
    corrected_center,
    crop_cell,
    ensure_template_sets,
    imread,
    interactive_args,
    match_board_with_best_set,
    state_cn,
    state_dir,
)

FONT = cv2.FONT_HERSHEY_SIMPLEX
OUTPUT_DIR = SHARED_ROOT / "templates_validate"

PARAMS = [
    Param("thresh", "float", default=EMPTY_MATCH_THRESHOLD, cn="匹配阈值", desc="低于此值判为空格"),
    Param("suspicion", "float", default=0.5, cn="疑似阈值", desc="空格命中此分视为疑似误判"),
    Param(
        "states",
        "multiselect",
        default=[STATE_ENDGAME],
        choices=[STATE_OPENING, STATE_MATE, STATE_LIFT, STATE_ENDGAME],
        choice_cn=STATE_CN,
        cn="验证状态",
        desc="空格勾选要验证的棋局状态",
    ),
]


def match_cell_full(
    corrected: np.ndarray, r: int, c: int, templates: dict, threshold: float
) -> tuple[str, float, bool]:
    cx, cy = corrected_center(r, c)
    px, py = round(cx), round(cy)
    half = MATCH_SEARCH_HALF + CELL_OUT // 2
    x1 = int(max(0, px - half))
    y1 = int(max(0, py - half))
    window = corrected[y1 : py + half, x1 : px + half]
    if window.shape[0] < CELL_OUT or window.shape[1] < CELL_OUT:
        cell = crop_cell(corrected, r, c)
        if cell is None:
            return "empty", 0.0, True
        window = cell

    best_key, best_score = "empty", -1.0
    for key, tmpl in templates.items():
        res = cv2.matchTemplate(window, tmpl, cv2.TM_CCOEFF_NORMED)
        _, max_val, _, _ = cv2.minMaxLoc(res)
        if max_val > best_score:
            best_score, best_key = max_val, key
    is_empty = best_score < threshold
    return best_key, float(best_score), is_empty


def _draw_grid(vis: np.ndarray) -> None:
    for i in range(ROWS + 1):
        y = int(CORRECT_CELL * i)
        cv2.line(vis, (0, y), (CORRECT_W, y), (210, 210, 210), 1)
    for j in range(COLS + 1):
        x = int(CORRECT_CELL * j)
        cv2.line(vis, (x, 0), (x, CORRECT_H), (210, 210, 210), 1)


def _draw_block_text(
    vis: np.ndarray, cx: int, cy: int, line1: str, line2: str, color: tuple
) -> None:
    bw, bh = 60, 36
    x0, y0 = cx - bw // 2, cy - bh // 2
    cv2.rectangle(vis, (x0, y0), (x0 + bw, y0 + bh), (0, 0, 0), -1)
    cv2.putText(vis, line1, (x0 + 5, cy - 2), FONT, 0.5, color, 1, cv2.LINE_AA)
    if line2:
        cv2.putText(vis, line2, (x0 + 5, cy + 14), FONT, 0.33, (205, 205, 205), 1, cv2.LINE_AA)


def annotate(
    corrected: np.ndarray, results: list, threshold: float, suspicion: float
) -> np.ndarray:
    vis = corrected.copy()
    _draw_grid(vis)
    for idx, (best_piece, best_score, is_empty) in enumerate(results):
        r = idx // COLS
        c = idx % COLS
        cx, cy = corrected_center(r, c)
        cx, cy = int(cx), int(cy)
        if not is_empty:
            color = (90, 230, 90) if best_piece.startswith("r") else (120, 180, 255)
            _draw_block_text(vis, cx, cy, best_piece, f"{best_score:.2f}", color)
        else:
            if best_score >= suspicion:
                _draw_block_text(vis, cx, cy, best_piece, f"{best_score:.2f}", (60, 60, 255))
            else:
                cv2.putText(vis, ".", (cx - 3, cy + 5), FONT, 0.5, (110, 110, 110), 1, cv2.LINE_AA)
    return vis


def annotate_no_skin(corrected: np.ndarray) -> np.ndarray:
    """无匹配皮肤（0.8+ 自信棋子=0）时输出带红条警告的整图，提示需补 raw/opening 皮肤。"""
    vis = corrected.copy()
    _draw_grid(vis)
    cv2.rectangle(vis, (0, 0), (CORRECT_W, 44), (0, 0, 180), -1)
    cv2.putText(
        vis,
        "无匹配皮肤: 0.8+ 棋子=0 (raw/opening 缺该皮肤)",
        (12, 30),
        FONT,
        0.72,
        (255, 255, 255),
        2,
        cv2.LINE_AA,
    )
    return vis


def main() -> int:
    """模板匹配验证主函数。"""
    args = interactive_args(PARAMS)
    if args is None:
        return 0

    try:
        template_sets = ensure_template_sets()
    except Exception as e:
        print(f"[错误] 模板准备失败: {e}")
        return 1
    print(f"[模板] 已加载 {len(template_sets)} 套棋子模板\n")

    if OUTPUT_DIR.exists():
        shutil.rmtree(OUTPUT_DIR)

    for state in args.states:
        state_dir_path = state_dir(state)
        if not state_dir_path.exists():
            print(f"[跳过] 未找到 {state_dir_path}（无 {state_cn(state)} 截图）")
            continue
        imgs = sorted(state_dir_path.glob("*.png"))
        if not imgs:
            print(f"[跳过] {state_dir_path} 下无 .png 截图。")
            continue

        out_dir = OUTPUT_DIR / state
        out_dir.mkdir(parents=True, exist_ok=True)

        summary_lines = []
        print(f"--- 状态 {state_cn(state)} ({state})  共 {len(imgs)} 张 ---")
        for p in imgs:
            img = imread(p)
            if img is None:
                print(f"[跳过] 解码失败: {p.name}")
                continue
            try:
                corrected = correct_board(img)
            except Exception as e:
                print(f"[跳过] 矫正失败 {p.name}: {e}")
                continue

            best_name, _cells = match_board_with_best_set(corrected, template_sets, args.thresh)
            if best_name is None:
                vis = annotate_no_skin(corrected)
                out_path = out_dir / f"{p.stem}.png"
                cv2.imencode(".png", vis)[1].tofile(str(out_path))
                print(f"  [{p.name}] 无皮肤模板能自信匹配 -> {out_path.name}")
                summary_lines.append(f"{p.name}\tset=NONE\tpieces=0\tsuspicious_empty=0  [NO-SKIN]")
                continue

            tset = template_sets[best_name]
            results = []
            for r in range(ROWS):
                for c in range(COLS):
                    results.append(match_cell_full(corrected, r, c, tset, args.thresh))

            vis = annotate(corrected, results, args.thresh, args.suspicion)
            out_path = out_dir / f"{p.stem}.png"
            cv2.imencode(".png", vis)[1].tofile(str(out_path))

            piece_count = sum(1 for _, _, e in results if not e)
            suspicious = [
                (idx // COLS, idx % COLS, k, s)
                for idx, (k, s, e) in enumerate(results)
                if e and s >= args.suspicion
            ]
            n_susp = len(suspicious)
            flag = " 疑似误判空格" if n_susp else ""
            zero_tag = ""
            if piece_count == 0:
                zero_tag = "  [0-PIECE]"
                flag += " 0棋子(疑似缺皮肤模板)"
            print(
                f"  [{p.name}] 套={best_name} 棋子={piece_count} 疑似空格={n_susp}{flag} -> {out_path.name}"
            )
            summary_lines.append(
                f"{p.name}\tset={best_name}\tpieces={piece_count}\tsuspicious_empty={n_susp}{zero_tag}"
            )
            if suspicious:
                cells = ", ".join(f"({r},{c})={k}:{s:.2f}" for r, c, k, s in suspicious)
                summary_lines.append(f"    可疑: {cells}")

        summary_path = out_dir / "_summary.txt"
        summary_path.write_text("\n".join(summary_lines) + "\n", encoding="utf-8")
        print(f"[{state_cn(state)}] 标注图: {out_dir}  摘要: {summary_path}")

    print("\n=== 验证完成 ===")
    return 0
