# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "opencv-python>=4.11.0",
#   "numpy>=1.26",
#   "onnxruntime>=1.18",
# ]
# ///
"""step_8_validate_cnn.py —— 用训练导出的 CNN(ONNX) 模型对 raw 截图做整盘识别可视化验证。

目的：
  验证训练结果（导出模型 chess_pieces.onnx + model_info.json）在真实整盘截图上
  的识别是否正确。复用与训练管线完全一致的裁剪/预处理（correct_board -> crop_cell ->
  BGR2RGB /255，无 ImageNet 归一化，与训练一致），仅把「模板匹配」换成「ONNX 推理」。

为什么验证 raw/3 与 raw/5：
  - raw/3 是状态3(提子)：四角将帥 + 红中兵被提(lift)位置是 *固定真值*，本脚本会
    自动比对真值、在错格上标品红、并输出每张图的准确率——这是强自动校验。
  - raw/5 是残局：无固定真值，只能肉眼审 + 对低置信(疑似不确定)格标红告警。

重要前提（务必知悉）：
  raw/3 与 raw/5 都曾参与过训练（step_3_cut_cells 把 raw/1..5 全部切进数据集），
  故本脚本是「训练域自洽性检查」——能证明 导出模型+预处理+类序 全链路无 bug，
  但 *不* 证明泛化能力。真泛化需用 App 全新截图（状态3 真值布局仍适用）。

输出：
  raw_cnn/<状态>/<图名>.png  逐格标注（黑底块+文字，与 step_7_validate_cv 风格一致）
  raw_cnn/<状态>/_summary.txt 每张图统计 + 错格明细 + 整体准确率

运行：
  uv run step_8_validate_cnn.py                 # 默认校验状态 3 与 5
  uv run step_8_validate_cnn.py --states 1 2 3 4 5   # 全部状态
  uv run step_8_validate_cnn.py --states 3 --model cnn/export/chess_pieces.onnx
依赖：opencv-python, numpy, onnxruntime
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from yolo_common import (  # noqa: E402
    RAW_ROOT, COLS, ROWS, CORRECT_CELL, CORRECT_W, CORRECT_H,
    corrected_center, crop_cell, correct_board, state_dir,
    label_map_for_state, load_lift_labels, label_map_for_lift,
)

FONT = cv2.FONT_HERSHEY_SIMPLEX


def softmax(x: np.ndarray) -> np.ndarray:
    x = x.astype(np.float32)
    x = x - x.max()
    e = np.exp(x)
    return e / e.sum()


def load_model(model_path: Path, info_path: Path):
    """加载 ONNX 模型与 meta，返回 (session, class_keys, class_cn, mean, std, scale, in/out name)。"""
    import onnxruntime as ort

    info = json.loads(info_path.read_text(encoding="utf-8"))
    class_keys = info["class_keys"]
    class_cn = info.get("class_cn", [k for k in class_keys])
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


def infer_cell(sess, in_name, out_name, cell_bgr, mean, std, scale):
    """单格(64x64 BGR) -> (pred_idx, conf, probs)。

    预处理严格遵循 model_info.json 的 preprocess 字段：
      BGR->RGB -> /scale(默认255) -> 减均值除标准差（当前 model_info 为 mean=0/std=1，
      即不做归一化、仅 /scale），NCHW。模型输出节点已含 Softmax，直接当概率用
    （对极端情况做 softmax 兜底）。
    """
    rgb = cv2.cvtColor(cell_bgr, cv2.COLOR_BGR2RGB).astype(np.float32) / scale
    rgb = (rgb - mean.reshape(1, 1, 3)) / std.reshape(1, 1, 3)
    inp = rgb.transpose(2, 0, 1)[None]  # (1,3,64,64) NCHW
    out = sess.run([out_name], {in_name: inp})[0][0]  # (16,) 已为 softmax 概率
    if out.max() > 1.0 or abs(float(out.sum()) - 1.0) > 1e-3:
        out = softmax(out)
    idx = int(np.argmax(out))
    return idx, float(out[idx]), out


def draw_grid(vis) -> None:
    for i in range(ROWS + 1):
        y = int(CORRECT_CELL * i)
        cv2.line(vis, (0, y), (CORRECT_W, y), (210, 210, 210), 1)
    for j in range(COLS + 1):
        x = int(CORRECT_CELL * j)
        cv2.line(vis, (x, 0), (x, CORRECT_H), (210, 210, 210), 1)


def _side_color(key: str):
    """红方亮绿、黑方亮蓝（黑底上清晰）；empty/lift 用中性灰。"""
    if key.startswith("r"):
        return (90, 230, 90)
    if key.startswith("b"):
        return (120, 180, 255)
    return (200, 200, 200)


def _draw_block_text(vis, cx, cy, line1, line2, color) -> None:
    """黑底块 + 文字（与 step_7_validate_cv 一致：先填黑底再写字，任意棋子底色可辨）。"""
    bw, bh = 60, 36
    x0, y0 = cx - bw // 2, cy - bh // 2
    cv2.rectangle(vis, (x0, y0), (x0 + bw, y0 + bh), (0, 0, 0), -1)
    cv2.putText(vis, line1, (x0 + 5, cy - 2), FONT, 0.5, color, 1, cv2.LINE_AA)
    if line2:
        cv2.putText(vis, line2, (x0 + 5, cy + 14), FONT, 0.33, (205, 205, 205), 1, cv2.LINE_AA)


def annotate(corrected, cells, use_gt, gt_map, suspicion):
    """cells: 长度 ROWS*COLS 的列表，每元素 dict(r,c,key,conf,probs)。

    标注规则：
      - use_gt=True（状态2/3/4）：与真值比对。
          * 正确：正常彩色块（红绿/黑蓝）。
          * 错误：品红块，第二行写「GT=真值」。
      - use_gt=False（状态5 残局）：无真值。
          * 置信 >= suspicion：正常彩色块。
          * 置信 < suspicion：红块，视为「模型不确定」，人工重点看。
          * 高置信 empty：大实心灰圆标记（非小点），便于肉眼定位空格。
    返回 (vis, mismatch_count, suspicious_count, min_conf)。
    """
    vis = corrected.copy()
    draw_grid(vis)
    mismatch = 0
    suspicious = 0
    min_conf = 1.0
    for cell in cells:
        r, c = cell["r"], cell["c"]
        cx, cy = corrected_center(r, c)
        cx, cy = int(cx), int(cy)
        key, conf = cell["key"], cell["conf"]
        min_conf = min(min_conf, conf)
        if use_gt:
            gt = gt_map.get((r, c), "empty")
            if key == gt:
                _draw_block_text(vis, cx, cy, key, f"{conf:.2f}", _side_color(key))
            else:
                mismatch += 1
                _draw_block_text(vis, cx, cy, key, f"GT={gt}", (210, 0, 210))
        else:
            if key == "empty" and conf >= suspicion:
                # 空格：大实心灰圆（带描边），比小灰点醒目，便于肉眼定位空格
                rad = int(CORRECT_CELL * 0.34)
                cv2.circle(vis, (cx, cy), rad, (150, 150, 150), -1)
                cv2.circle(vis, (cx, cy), rad, (70, 70, 70), 2)
            elif conf >= suspicion:
                _draw_block_text(vis, cx, cy, key, f"{conf:.2f}", _side_color(key))
            else:
                suspicious += 1
                _draw_block_text(vis, cx, cy, key, f"{conf:.2f}", (60, 60, 255))
    return vis, mismatch, suspicious, (min_conf if min_conf < 1.0 else 0.0)


def main() -> int:
    ap = argparse.ArgumentParser(description="用导出的 CNN(ONNX) 整盘识别可视化验证")
    ap.add_argument("--states", nargs="+", type=int, default=[3, 5],
                    help="要验证的状态目录（默认 3 5）")
    ap.add_argument("--model", type=str, default=None,
                    help="ONNX 模型路径（默认 cnn/export/chess_pieces.onnx）")
    ap.add_argument("--info", type=str, default=None,
                    help="model_info.json 路径（默认 cnn/export/model_info.json）")
    ap.add_argument("--suspicion", type=float, default=0.6,
                    help="低置信告警阈值（默认 0.6，低于此值标红）")
    args = ap.parse_args()

    base = Path(__file__).resolve().parent
    model_path = Path(args.model) if args.model else (base / "export" / "chess_pieces.onnx")
    info_path = Path(args.info) if args.info else (base / "export" / "model_info.json")
    if not model_path.exists():
        print(f"[错误] 未找到模型: {model_path}")
        return 1
    if not info_path.exists():
        print(f"[错误] 未找到 meta: {info_path}")
        return 1

    sess, class_keys, class_cn, mean, std, scale, in_name, out_name = load_model(model_path, info_path)
    key_to_cn = dict(zip(class_keys, class_cn))
    print(f"[模型] {model_path.name}  类数={len(class_keys)}  "
          f"类序前4={class_keys[:4]} ...  预处理 scale={scale} mean={mean.tolist()} std={std.tolist()}\n")

    # 真值可用状态：1-4 有固定布局；5 残局无
    gt_states = {s for s in (1, 2, 3, 4)}

    out_root = base / "raw_cnn"
    if out_root.exists():
        shutil.rmtree(out_root)
        print(f"[清空] 已删除旧输出目录: {out_root}")

    for st in args.states:
        sdir = state_dir(st)
        if not sdir.exists():
            print(f"[跳过] 未找到 {sdir}")
            continue
        imgs = sorted(sdir.glob("*.png"))
        if not imgs:
            print(f"[跳过] {sdir} 下无 .png")
            continue

        use_gt = st in gt_states
        lift_labels_3 = load_lift_labels(state_dir(3)) if st == 3 else {}
        out_dir = out_root / f"{st}"
        out_dir.mkdir(parents=True, exist_ok=True)

        summary = []
        total_imgs = 0
        total_cells = 0
        total_errors = 0  # mismatch(有GT) 或 suspicious(无GT)
        print(f"=== 状态 {st}（{'有真值自动比对' if use_gt else '残局·仅肉眼+低置信告警'}）"
              f"  共 {len(imgs)} 张 ===")
        for p in imgs:
            raw = np.fromfile(str(p), dtype=np.uint8)
            img = cv2.imdecode(raw, cv2.IMREAD_COLOR)
            if img is None:
                print(f"  [跳过] 解码失败: {p.name}")
                continue
            try:
                corrected = correct_board(img)
            except Exception as e:  # noqa: BLE001
                print(f"  [跳过] 矫正失败 {p.name}: {e}")
                continue

            # 真值映射：状态3 据 labels.csv 动态构建（无记录默认红中兵 6,4）；其余状态用固定布局
            if use_gt and st == 3:
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
            for r in range(ROWS):
                for c in range(COLS):
                    cell_img = crop_cell(corrected, r, c)
                    if cell_img is None:
                        # 边界异常：当 empty 处理，置信 0
                        cells.append({"r": r, "c": c, "key": "empty", "conf": 0.0, "probs": None})
                        continue
                    idx, conf, _probs = infer_cell(sess, in_name, out_name, cell_img, mean, std, scale)
                    cells.append({"r": r, "c": c, "key": class_keys[idx], "conf": conf, "probs": _probs})

            vis, err, susp, min_conf = annotate(corrected, cells, use_gt, gt_map, args.suspicion)
            out_path = out_dir / f"{p.stem}.png"
            cv2.imencode(".png", vis)[1].tofile(str(out_path))

            total_imgs += 1
            if use_gt:
                total_cells += len(cells)
                total_errors += err
                acc = 1.0 - err / len(cells)
                line = (f"{p.name}\tstate={st}\tacc={acc:.3f}\t"
                        f"mismatch={err}/{len(cells)}\tminConf={min_conf:.2f}")
                print(f"  [{p.name}] 准确率={acc:.3f}  错格={err}  最低置信={min_conf:.2f}{lift_tag}")
                if err:
                    detail = []
                    for cell in cells:
                        gt = gt_map.get((cell["r"], cell["c"]), "empty")
                        if cell["key"] != gt:
                            detail.append(f"({cell['r']},{cell['c']})pred={cell['key']}/GT={gt}/{cell['conf']:.2f}")
                    line += "\n    错格: " + ", ".join(detail)
            else:
                susp_cells = [cl for cl in cells if cl["key"] != "empty" and cl["conf"] < args.suspicion]
                total_errors += len(susp_cells)
                line = (f"{p.name}\tstate={st}\tsuspicious={len(susp_cells)}\t"
                        f"minConf={min_conf:.2f}")
                print(f"  [{p.name}] 低置信告警={len(susp_cells)}  最低置信={min_conf:.2f}{lift_tag}")
                if susp_cells:
                    detail = [f"({cl['r']},{cl['c']}){cl['key']}/{cl['conf']:.2f}" for cl in susp_cells]
                    line += "\n    告警: " + ", ".join(detail)
            summary.append(line)

        # 每状态小结
        if use_gt and total_cells:
            overall = 1.0 - total_errors / total_cells
            summary.append(f"\n[状态 {st} 汇总] 图={total_imgs} 格={total_cells} "
                           f"总错格={total_errors} 整体准确率={overall:.4f}")
            print(f"  >> 状态 {st} 整体准确率 = {overall:.4f}（{total_errors}/{total_cells} 错）")
        else:
            summary.append(f"\n[状态 {st} 汇总] 图={total_imgs} 低置信告警格={total_errors}"
                           f"（残局无真值，需人工肉眼复核标注图）")
        summary_path = out_dir / "_summary.txt"
        summary_path.write_text("\n".join(summary) + "\n", encoding="utf-8")
        print(f"  标注图: {out_dir}  摘要: {summary_path}\n")

    print("=== 完成 ===")
    print("提示：raw/3 与 raw/5 均参与过训练，本校验为「训练域自洽性」检查，")
    print("验证 导出模型+预处理+类序 全链路；泛化能力请用 App 全新截图另测。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
