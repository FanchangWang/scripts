"""cls_validate.py —— 用训练导出的 CNN(ONNX) 模型对 raw 截图做整盘识别可视化验证。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from yolo_chess.common import (
    COLS,
    CORRECT_CELL,
    CORRECT_H,
    CORRECT_W,
    ROWS,
    STATE_CN,
    STATE_ENDGAME,
    STATE_LIFT,
    STATE_MATE,
    STATE_OPENING,
    Param,
    correct_board,
    corrected_center,
    crop_cell,
    imread,
    interactive_args,
    iter_state_images,
    label_map_for_lift,
    label_map_for_state,
    load_lift_labels,
    prepare_output_dir,
    state_dir,
)

FONT = cv2.FONT_HERSHEY_SIMPLEX
OUTPUT_DIR = Path(__file__).resolve().parent.parent.parent.parent / "cls" / "validate_output"

PARAMS = [
    Param(
        "states",
        "multiselect",
        default=[STATE_ENDGAME],
        choices=[STATE_OPENING, STATE_MATE, STATE_LIFT, STATE_ENDGAME],
        choice_cn=STATE_CN,
        cn="验证状态",
        desc="空格勾选要验证的棋局状态",
    ),
    Param("model", "str", default=None, cn="模型路径", desc="ONNX 模型，留空用默认导出"),
    Param("info", "str", default=None, cn="模型信息", desc="model_info.json，留空用默认"),
    Param("suspicion", "float", default=0.6, cn="疑似阈值", desc="低于此置信度视为低置信告警"),
]


def _softmax(x: np.ndarray) -> np.ndarray:
    x = x.astype(np.float32)
    x = x - x.max()
    e = np.exp(x)
    return e / e.sum()


def _load_model(model_path: Path, info_path: Path) -> tuple:
    import onnxruntime as ort

    info = json.loads(info_path.read_text(encoding="utf-8"))
    class_keys = info["class_keys"]
    class_cn = info.get("class_cn", list(class_keys))
    pp = info.get("preprocess", {})
    scale = float(pp.get("scale", 255.0))
    mean = np.array(pp.get("mean", [0.485, 0.456, 0.406]), dtype=np.float32)
    std = np.array(pp.get("std", [0.229, 0.224, 0.225]), dtype=np.float32)
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    so.inter_op_num_threads = 4
    sess = ort.InferenceSession(str(model_path), so, providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    out_name = sess.get_outputs()[0].name
    return sess, class_keys, class_cn, mean, std, scale, in_name, out_name


def _infer_cell(
    sess: Any,
    in_name: str,
    out_name: str,
    cell_bgr: np.ndarray,
    mean: np.ndarray,
    std: np.ndarray,
    scale: float,
) -> tuple[int, float, np.ndarray]:
    rgb = cv2.cvtColor(cell_bgr, cv2.COLOR_BGR2RGB).astype(np.float32) / scale
    rgb = (rgb - mean.reshape(1, 1, 3)) / std.reshape(1, 1, 3)
    inp = rgb.transpose(2, 0, 1)[None]
    out = sess.run([out_name], {in_name: inp})[0][0]  # type: ignore[union-attr]
    if out.max() > 1.0 or abs(float(out.sum()) - 1.0) > 1e-3:
        out = _softmax(out)
    idx = int(np.argmax(out))
    return idx, float(out[idx]), out


def _draw_grid(vis: np.ndarray) -> None:
    for i in range(ROWS + 1):
        y = int(CORRECT_CELL * i)
        cv2.line(vis, (0, y), (CORRECT_W, y), (210, 210, 210), 1)
    for j in range(COLS + 1):
        x = int(CORRECT_CELL * j)
        cv2.line(vis, (x, 0), (x, CORRECT_H), (210, 210, 210), 1)


def _side_color(key: str) -> tuple[int, int, int]:
    if key.startswith("r"):
        return (90, 230, 90)
    if key.startswith("b"):
        return (120, 180, 255)
    return (200, 200, 200)


def _draw_block_text(
    vis: np.ndarray, cx: int, cy: int, line1: str, line2: str, color: tuple
) -> None:
    bw, bh = 60, 36
    x0, y0 = cx - bw // 2, cy - bh // 2
    cv2.rectangle(vis, (x0, y0), (x0 + bw, y0 + bh), (0, 0, 0), -1)
    cv2.putText(vis, line1, (x0 + 5, cy - 2), FONT, 0.5, color, 1, cv2.LINE_AA)
    if line2:
        cv2.putText(vis, line2, (x0 + 5, cy + 14), FONT, 0.33, (205, 205, 205), 1, cv2.LINE_AA)


def main() -> int:
    """CNN 推理验证主函数。"""
    args = interactive_args(PARAMS)
    if args is None:
        return 0

    states = list(args.states or [])

    base = Path(__file__).resolve().parent.parent.parent.parent
    model_path = Path(args.model) if args.model else (base / "cls" / "export" / "chess_pieces.onnx")
    info_path = Path(args.info) if args.info else (base / "cls" / "export" / "model_info.json")
    if not model_path.exists():
        print(f"[错误] 未找到模型: {model_path}")
        return 1
    if not info_path.exists():
        print(f"[错误] 未找到 meta: {info_path}")
        return 1

    sess, class_keys, _class_cn, mean, std, scale, in_name, out_name = _load_model(
        model_path, info_path
    )
    print(f"[模型] {model_path.name}  类数={len(class_keys)}  类序前4={class_keys[:4]}\n")

    gt_states = {STATE_OPENING, STATE_MATE, STATE_LIFT}
    prepare_output_dir(OUTPUT_DIR)

    for st, imgs, out_dir in iter_state_images(states, OUTPUT_DIR):
        use_gt = st in gt_states
        lift_labels_3 = load_lift_labels(state_dir(STATE_LIFT)) if st == STATE_LIFT else {}

        summary = []
        total_imgs = 0
        total_cells = 0
        total_errors = 0
        min_conf_total = 1.0
        print(
            f"=== 状态 {st}（{'有真值自动比对' if use_gt else '残局·仅肉眼+低置信告警'}）"
            f"  共 {len(imgs)} 张 ==="
        )
        for p in imgs:
            img = imread(p)
            if img is None:
                continue
            try:
                corrected = correct_board(img)
            except Exception:
                continue

            if use_gt and st == STATE_LIFT:
                lr, lc = lift_labels_3.get(p.stem, (6, 4))
                gt_map = label_map_for_lift(lr, lc)
                lift_tag = f" 提子点=({lr},{lc})"
            elif use_gt:
                gt_map = label_map_for_state(st)
                lift_tag = ""
            else:
                gt_map = {}
                lift_tag = ""

            cells = []
            img_min = 1.0
            for r in range(ROWS):
                for c in range(COLS):
                    cell_img = crop_cell(corrected, r, c)
                    if cell_img is None:
                        cells.append({"r": r, "c": c, "key": "empty", "conf": 0.0})
                        continue
                    idx, conf, _probs = _infer_cell(
                        sess, in_name, out_name, cell_img, mean, std, scale
                    )
                    if conf < img_min:
                        img_min = conf
                    if conf < min_conf_total:
                        min_conf_total = conf
                    cells.append({"r": r, "c": c, "key": class_keys[idx], "conf": conf})

            vis = corrected.copy()
            _draw_grid(vis)
            mismatch = 0
            for cell in cells:
                r, c = int(cell["r"]), int(cell["c"])
                cx, cy = corrected_center(r, c)
                cx, cy = int(cx), int(cy)
                key, conf = str(cell["key"]), cell["conf"]
                if use_gt:
                    gt = gt_map.get((r, c), "empty")
                    if key == gt:
                        _draw_block_text(vis, cx, cy, key, f"{conf:.2f}", _side_color(key))
                    else:
                        mismatch += 1
                        _draw_block_text(vis, cx, cy, key, f"GT={gt}", (210, 0, 210))
                else:
                    if key == "empty" and conf >= args.suspicion:
                        rad = int(CORRECT_CELL * 0.34)
                        cv2.circle(vis, (cx, cy), rad, (150, 150, 150), -1)
                        cv2.circle(vis, (cx, cy), rad, (70, 70, 70), 2)
                    elif conf >= args.suspicion:
                        _draw_block_text(vis, cx, cy, key, f"{conf:.2f}", _side_color(key))
                    else:
                        _draw_block_text(vis, cx, cy, key, f"{conf:.2f}", (60, 60, 255))

            out_path = out_dir / f"{p.stem}.png"
            cv2.imencode(".png", vis)[1].tofile(str(out_path))

            total_imgs += 1
            if use_gt:
                total_cells += len(cells)
                total_errors += mismatch
                acc = 1.0 - mismatch / len(cells)
                line = f"{p.name}\tacc={acc:.3f}\tmismatch={mismatch}/{len(cells)}"
                print(f"  [{p.name}] 准确率={acc:.3f}  错格={mismatch}{lift_tag}")
            else:
                susp_cells = [
                    cl for cl in cells if cl["key"] != "empty" and cl["conf"] < args.suspicion
                ]
                total_errors += len(susp_cells)
                line = f"{p.name}\tstate={st}\tsuspicious={len(susp_cells)}\tminConf={img_min:.2f}"
                detail = " ".join(
                    f"({cl['r']},{cl['c']})/{cl['key']}/{cl['conf']:.2f}" for cl in susp_cells
                )
                if detail:
                    line += f"\t告警: {detail}"
                print(
                    f"  [{p.name}] 低置信告警={len(susp_cells)}  最低置信={img_min:.2f}{lift_tag}"
                )
            summary.append(line)

        if use_gt and total_cells:
            overall = 1.0 - total_errors / total_cells
            summary.append(f"\n[状态 {st}] 准确率={overall:.4f}（{total_errors}/{total_cells} 错）")
            print(f"  >> 准确率 = {overall:.4f}")
        elif not use_gt:
            summary.append(
                f"\n[状态 {st} 汇总] 图={total_imgs} 低置信告警格={total_errors}"
                f" 最低置信={min_conf_total:.2f}（残局无真值，需人工肉眼复核标注图）"
            )
            print(
                f"  >> 低置信告警格累计 = {total_errors}  最低置信 = {min_conf_total:.2f}"
                f"（残局无真值，需人工肉眼复核标注图）"
            )
        summary_path = out_dir / "_summary.txt"
        summary_path.write_text("\n".join(summary) + "\n", encoding="utf-8")

    print("\n=== 完成 ===")
    return 0
