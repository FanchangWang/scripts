"""step_13_validate_corner.py
==========================
校验 step_12 训练出的「棋盘4角定位」检测模型的**像素级精度**。

为什么需要本脚本（重要）：
  step_12 训练日志里的 `mAP50-95(B)` **不能作为精度验收依据**。
  Ultralytics 对非 COCO 检测使用标准 mAP；本任务角框很小（~64px）、且真实棋盘位置相对
  DEFAULT_CORNERS 真值有 ~10–15px 散布，mAP 在数百 px 容差下早已饱和，对我们要的精度不敏感。
  → **只有本脚本的像素误差统计（MAE/RMSE/P95）才是真正的验收。**

做什么：
  - 从 raw/ 抽若干图（或按 val/train 划分），用训练好的检测模型推理，得到 4 个角框。
  - 每个角 = 「该类置信度最高的框」的框心；与 yolo_common.DEFAULT_CORNERS 真值比像素误差。
  - 输出逐图误差 + 全局汇总（MAE / RMSE / 中位数 / P95 / Max）+ 分角(TL/TR/BL/BR)统计。
  - **带符号偏移分解**：把误差拆成「系统偏移(bias)」与「随机散布(std)」。
  - **误差传导到切格**（真正的验收判据）：把预测四角代入 homography，算 90 个格心在矫正空间
    的位移。矫正空间一格 100px、切格只取中心 64px ⇒ 单边余量仅 18px，位移超过它就会切到邻格。
  - 按容差给出明确合格判定，并生成可视化图（绿=预测框心/框，红=真值点）到 corner/viz/。

⚠ imgsz 必须与训练时一致（本脚本默认自动从 runs/<name>/args.yaml 读取）。
  实测：用 imgsz=640 训练出的权重，若以 960 推理 MAE 从 11px 劣化到 24px，以 1280 推理直接崩坏
  （MAE 227px）。Ultralytics 的小目标检测对输入尺度高度敏感，切勿随手调大推理尺寸。

默认只评估 **val 划分**（step_11 切出的 50 张，模型训练时没见过），这才是有意义的精度；
用 `--split all/train` 可改。若只想「从 raw 抽几张看图」，用 `--n 8` 即可。

运行：
  uv run step_13_validate_corner.py                       # val 划分全量评估（推荐，先跑这个）
  uv run step_13_validate_corner.py --split all --n 8     # 从 raw 抽 8 张快速看图
  uv run step_13_validate_corner.py --weights corner/runs/corner/weights/best.pt
  uv run step_13_validate_corner.py --tol 8               # 自定收敛容差(px)

依赖：见 cnn/pyproject.toml（ultralytics / opencv-python / numpy 等），统一 uv run。
"""
from __future__ import annotations

import argparse
import json
import random
import re
import sys
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
RAW = HERE / "raw"                # 全部子目录 raw/1..6（勿写死单个子目录，否则评估范围被悄悄缩小）


def _imread(path) -> np.ndarray | None:
    """Unicode 安全读取（cv2.imread 在部分 Windows 构建上对中文路径返回 None）。"""
    b = np.fromfile(str(path), dtype=np.uint8)
    if b.size == 0:
        return None
    return cv2.imdecode(b, cv2.IMREAD_COLOR)
CORNER_ROOT = HERE / "corner"
DEFAULT_WEIGHTS = CORNER_ROOT / "runs" / "corner" / "weights" / "best.pt"
VAL_IMG_DIR = CORNER_ROOT / "dataset" / "images" / "val"
TRAIN_IMG_DIR = CORNER_ROOT / "dataset" / "images" / "train"
VIZ = CORNER_ROOT / "viz"

CORNER_NAMES = ["TL", "TR", "BL", "BR"]
QUAD_ORDER = [0, 1, 3, 2]   # 连线顺序 TL->TR->BR->BL（DEFAULT_CORNERS 原生序是 TL,TR,BL,BR）

# 精度判据：棋盘一格在原始截图中宽约 116px。角点误差经透视矫正会传导为格心偏移，
# 要保证 64×64 切格稳定命中棋子中心，角点误差应显著小于半格。
TOL_GOOD = 10.0     # ≤ 优秀（约 1/11 格）
TOL_WARN = 25.0     # ≤ 可用但需留意；> 则视为不合格

# 矫正空间切格余量：一格 CORRECT_CELL=100px，切格 CELL_OUT=64px ⇒ 单边余量 (100-64)/2 = 18px。
# 格心位移一旦超过它，64×64 窗口就会切进邻格（棋子被裁边），这是最硬的物理约束。
SHIFT_GOOD = 6.0    # ≤ 约占切格 10%，CNN 基本无感
SHIFT_WARN = 18.0   # = 切格余量；> 必然切错


def _draw_corners(img, pts, color) -> None:
    import cv2
    for i, (x, y) in enumerate(pts):
        xi, yi = int(round(x)), int(round(y))
        cv2.circle(img, (xi, yi), 12, color, -1)
        cv2.putText(img, f"{i}:{CORNER_NAMES[i]}", (xi + 14, yi),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 2)
    poly = [(int(round(pts[i][0])), int(round(pts[i][1]))) for i in QUAD_ORDER]
    for a in range(len(poly)):
        cv2.line(img, poly[a], poly[(a + 1) % len(poly)], color, 2)


def _draw_boxes(img, boxes, color) -> None:
    import cv2
    for (x1, y1, x2, y2) in boxes:
        cv2.rectangle(img, (int(round(x1)), int(round(y1))),
                      (int(round(x2)), int(round(y2))), color, 1)


def _ns_key(p: Path) -> str:
    """raw/<子目录>/<stem>.png -> '<子目录>_<stem>'，与 step_11 的命名空间一致。"""
    return f"{p.parent.name}_{p.stem}"


def _train_imgsz(weights: Path) -> int | None:
    """从 runs/<name>/args.yaml 读取训练时的 imgsz（weights 在 runs/<name>/weights/ 下）。"""
    ay = weights.parent.parent / "args.yaml"
    if not ay.exists():
        return None
    for line in ay.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith("imgsz:"):
            try:
                return int(float(line.split(":", 1)[1].strip()))
            except ValueError:
                return None
    return None


def _split_keys(split: str) -> set[str] | None:
    """取 step_11 划分出的 train/val 文件名集合（命名空间键）。all 返回 None 表示不过滤。

    step_11 出于 Unicode 安全，dataset 图像用 ASCII 茎存储，并写入 _nsmap.json
    把「ASCII茎 -> raw命名空间键(可能含中文)」映射回来，这里据此反查。
    """
    if split == "all":
        return None
    d = VAL_IMG_DIR if split == "val" else TRAIN_IMG_DIR
    if not d.exists():
        print(f"⚠ 未找到划分目录 {d}，请先运行 step_11_build_corner_dataset.py；本次回退为 all")
        return None
    nsmap_path = CORNER_ROOT / "dataset" / "_nsmap.json"
    raw_keys: set[str] = set()
    if nsmap_path.exists():
        m = json.loads(nsmap_path.read_text(encoding="utf-8"))
        for stem in (p.stem for p in d.glob("*.png")):
            if stem in m:
                raw_keys.add(m[stem])
    else:
        # 回退：dataset 茎即命名空间键（ASCII 场景）
        raw_keys = {p.stem for p in d.glob("*.png")}
    return raw_keys


def _stats(a) -> dict:
    import numpy as np
    return {
        "n": int(a.size),
        "mae": float(np.mean(a)),
        "rmse": float(np.sqrt(np.mean(a ** 2))),
        "median": float(np.median(a)),
        "p95": float(np.percentile(a, 95)),
        "max": float(np.max(a)),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="校验棋盘4角定位检测模型的像素级精度")
    ap.add_argument("--weights", default=str(DEFAULT_WEIGHTS), help="权重路径（默认 best.pt）")
    ap.add_argument("--split", default="val", choices=["val", "train", "all"],
                    help="评估哪个划分：val(默认，模型未训过，最有意义) / train / all")
    ap.add_argument("--n", type=int, default=0, help="抽样张数；0=该划分全量（默认）")
    ap.add_argument("--seed", type=int, default=7, help="抽样随机种子")
    ap.add_argument("--imgsz", type=int, default=0,
                    help="推理尺寸；0=自动读取训练时的 imgsz（args.yaml）。必须与训练一致！")
    # 本任务每张图每类**恒有且仅有 1 个**真实目标，且我们「每类取最高分框」，
    # 所以置信度阈值唯一的作用就是制造假漏检 —— 必须设到极低。
    # 实测(251 张 raw)：conf=0.1 漏 2 张(BR)，conf=0.001 漏 0 张，
    # 且两者精度完全相同(MAE 0.53px / P95 1.19px)。BR 类天生置信度偏低(最低 0.0048)，
    # 但定位精度与置信度**无关**（该 0.0048 的框误差仅 1.06px）。
    ap.add_argument("--conf", type=float, default=0.001,
                    help="置信度阈值（默认 0.001，故意极低：每类取最高分框，高阈值只会假漏检）")
    ap.add_argument("--tol", type=float, default=TOL_GOOD, help=f"合格容差 px（默认 {TOL_GOOD}）")
    ap.add_argument("--max-viz", type=int, default=12, help="最多写出多少张可视化图（避免全量刷盘）")
    args = ap.parse_args()

    weights = Path(args.weights)
    if not weights.is_absolute():
        weights = HERE / weights
    if not weights.exists():
        print(f"未找到权重: {weights}\n请先运行: uv run step_12_train_corner.py")
        return 1

    try:
        import cv2
        import numpy as np
        from ultralytics import YOLO
    except ImportError as e:
        print("依赖缺失:", e, "\n请确认已在 cnn/ 下执行 uv sync")
        return 1

    sys.path.insert(0, str(HERE))
    from yolo_common import COLS, CORRECT_CELL, DEFAULT_CORNERS, ROWS, _dst_points

    trained = _train_imgsz(weights)
    if args.imgsz <= 0:
        args.imgsz = trained or 640
        auto_note = "（自动读取训练值）" if trained else "（未找到 args.yaml，回退默认）"
    elif trained and args.imgsz != trained:
        auto_note = (f"⚠ 与训练值 {trained} 不一致！小目标检测对尺度极敏感，"
                     f"结果不可信（实测 640→1280 推理 MAE 会从 11px 崩到 227px）")
    else:
        auto_note = "（与训练一致）"

    # files = sorted(p for d in RAW.iterdir() if d.is_dir() for p in d.glob("*.png"))
    files = sorted(RAW.rglob("*.png"))
    if not files:
        print("raw/ 下无图片")
        return 1

    keys = _split_keys(args.split)
    if keys is not None:
        files = [p for p in files if _ns_key(p) in keys]
        if not files:
            print(f"划分 {args.split} 与 raw/ 无交集（raw 可能已变动，请重跑 step_11）")
            return 1
    if args.n and args.n < len(files):
        random.seed(args.seed)
        files = sorted(random.sample(files, args.n))

    print(f"权重     : {weights}")
    print(f"划分     : {args.split}（{len(files)} 张）")
    print(f"推理尺寸 : {args.imgsz} {auto_note}")
    print(f"合格容差 : {args.tol:.1f}px  |  一格宽约 116px")
    print("提示：mAP50-95(B) 对本任务不敏感（小框 + 真值自身散布），以下像素统计才是验收依据。\n")

    model = YOLO(str(weights))
    VIZ.mkdir(parents=True, exist_ok=True)

    C_PRED = (0, 200, 0)   # 绿：预测
    C_GT = (0, 0, 255)     # 红：真值

    all_err: list = []          # 每个角的误差（展平）
    per_corner: list[list] = [[], [], [], []]
    per_image: list[tuple[str, float, float]] = []
    deltas: list = []           # 每图 (4,2) 带符号偏移 pred-gt
    shifts: list = []           # 每图 90 个格心在矫正空间的位移(px)
    confs: list = []            # 每图 (4,) 各角采用框的置信度
    skipped_no_gt = 0
    skipped_no_det = 0
    n_viz = 0

    for p in files:
        img = _imread(p)
        if img is None:
            continue
        h, w = img.shape[:2]
        key = (int(w), int(h))
        if key not in DEFAULT_CORNERS:
            skipped_no_gt += 1
            continue

        res = model.predict(str(p), imgsz=args.imgsz, conf=args.conf, verbose=False)[0]
        boxes = res.boxes
        pred_boxes: list[tuple[float, float, float, float]] = []
        pts = np.full((4, 2), np.nan, dtype=np.float64)
        cf = np.full(4, np.nan, dtype=np.float64)
        if boxes is not None and len(boxes) > 0:
            xyxy = boxes.xyxy.cpu().numpy()
            conf = boxes.conf.cpu().numpy()
            cls = boxes.cls.cpu().numpy().astype(int)
            for c in range(4):
                mask = cls == c
                if not mask.any():
                    continue
                local = int(np.argmax(conf[mask]))
                idx = int(np.where(mask)[0][local])
                x1, y1, x2, y2 = xyxy[idx]
                pts[c] = [(x1 + x2) / 2.0, (y1 + y2) / 2.0]
                cf[c] = float(conf[idx])
                pred_boxes.append((x1, y1, x2, y2))

        if np.isnan(pts).any():
            got = sorted(set(cls.tolist())) if boxes is not None and len(boxes) else []
            miss = [CORNER_NAMES[i] for i in range(4) if np.isnan(pts[i, 0])]
            print(f"⚠ 角点缺失: {p.parent.name}/{p.name} 检测到类={got} 缺={miss}"
                  f"  → 该角置信度低于 --conf {args.conf}，试更低阈值（如 0.0005）")
            skipped_no_det += 1
            continue
        confs.append(cf)

        gt = np.array(DEFAULT_CORNERS[key], dtype=np.float64)   # (4,2) TL,TR,BL,BR

        err = np.linalg.norm(pts - gt, axis=1)                  # 每角欧氏距离(px)
        all_err.extend(err.tolist())
        for i in range(4):
            per_corner[i].append(float(err[i]))
        per_image.append((f"{p.parent.name}/{p.name}", float(err.mean()), float(err.max())))
        deltas.append(pts - gt)

        # --- 误差传导：同一物理格心，用「真值H」与「预测H」矫正后落点之差 ---
        dst = _dst_points().astype(np.float32)
        h_gt = cv2.getPerspectiveTransform(gt.astype(np.float32), dst)
        h_pr = cv2.getPerspectiveTransform(pts.astype(np.float32), dst)
        centers = np.array([[CORRECT_CELL * (c + 0.5), CORRECT_CELL * (r + 0.5)]
                            for r in range(ROWS) for c in range(COLS)], dtype=np.float32)
        src = cv2.perspectiveTransform(centers.reshape(-1, 1, 2), np.linalg.inv(h_gt))
        got = cv2.perspectiveTransform(src, h_pr).reshape(-1, 2)
        shifts.append(np.linalg.norm(got - centers, axis=1))

        if n_viz < args.max_viz:
            _draw_corners(img, pts, C_PRED)
            _draw_boxes(img, pred_boxes, C_PRED)
            _draw_corners(img, gt, C_GT)
            # 命名空间化，避免 raw/1..5 同名文件互相覆盖；文件名也需 ASCII（cv2.imwrite 中文路径失败）
            safe = re.sub(r"[^A-Za-z0-9_.-]", "_", _ns_key(p))
            cv2.imwrite(str(VIZ / f"{safe}.png"), img)
            n_viz += 1

    if not all_err:
        print("没有可评估样本（无 GT 分辨率或全部无检测）")
        return 1

    a = np.array(all_err, dtype=np.float64)
    s = _stats(a)

    print("=== 逐图误差（按平均误差降序，展示最差 10 张） ===")
    for name, m, mx in sorted(per_image, key=lambda t: -t[1])[:10]:
        flag = "✅" if m <= args.tol else ("⚠" if m <= TOL_WARN else "❌")
        print(f"  {flag} {name:<20} 平均 {m:6.2f}px   最大 {mx:6.2f}px")

    print(f"\n=== 全局角点误差统计（{s['n']} 个角 = {len(per_image)} 图 × 4） ===")
    print(f"  MAE(平均)  : {s['mae']:.2f} px")
    print(f"  RMSE       : {s['rmse']:.2f} px")
    print(f"  中位数     : {s['median']:.2f} px")
    print(f"  P95        : {s['p95']:.2f} px")
    print(f"  最大        : {s['max']:.2f} px")

    d = np.array(deltas, dtype=np.float64)      # (n,4,2) 带符号
    print("\n=== 分角统计 + 误差分解（bias=系统偏移 / std=随机散布） ===")
    print(f"  {'角':<4}{'平均':>8}{'最大':>8}{'dx均值':>9}{'dy均值':>9}{'|bias|':>9}{'std':>8}  主因")
    for i, nm in enumerate(CORNER_NAMES):
        c = np.array(per_corner[i], dtype=np.float64)
        dx, dy = d[:, i, 0], d[:, i, 1]
        bias = float(np.hypot(dx.mean(), dy.mean()))
        rnd = float(np.hypot(dx.std(), dy.std()))
        cause = "系统偏移(可校正)" if bias > rnd * 1.5 else ("随机(精度天花板)" if rnd > bias * 1.5 else "混合")
        print(f"  {nm:<4}{c.mean():7.2f}px{c.max():7.2f}px{dx.mean():+9.2f}{dy.mean():+9.2f}"
              f"{bias:9.2f}{rnd:8.2f}  {cause}")
    g_bias = float(np.hypot(d[:, :, 0].mean(), d[:, :, 1].mean()))
    g_std = float(np.hypot(d[:, :, 0].std(), d[:, :, 1].std()))
    print(f"  全局: |bias|={g_bias:.2f}px  std={g_std:.2f}px  → "
          + ("随机散布主导：提高 imgsz / 减弱增强重训才有效，加常量校正无用"
             if g_std > g_bias * 1.5 else "系统偏移主导：可考虑常量校正"))

    cfa = np.array(confs, dtype=np.float64) if confs else np.zeros((1, 4))
    print("\n=== 检测置信度余量（诊断漏检风险；与定位精度无关） ===")
    print(f"  {'角':<4}{'最低':>9}{'中位':>9}{'最高':>9}  风险")
    for i, nm in enumerate(CORNER_NAMES):
        col = cfa[:, i]
        risk = ("❌ 极低，常规阈值必漏检" if col.min() < 0.01
                else "⚠ 偏低，勿用 conf≥0.1" if col.min() < 0.1 else "✅ 充裕")
        print(f"  {nm:<4}{col.min():9.4f}{np.median(col):9.4f}{col.max():9.4f}  {risk}")
    print(f"  全局最低置信度 = {cfa.min():.4f}（当前 --conf {args.conf}）"
          + ("  ← 阈值已足够低" if args.conf < cfa.min() else "  ← ⚠ 阈值过高，可能已假漏检"))

    sh = np.concatenate(shifts) if shifts else np.zeros(1)
    sh_p95, sh_max = float(np.percentile(sh, 95)), float(sh.max())
    over = float((sh > SHIFT_WARN).mean() * 100)
    print(f"\n=== 误差传导到切格（矫正空间；一格{CORRECT_CELL}px，切格64px ⇒ 单边余量{SHIFT_WARN:.0f}px） ===")
    print(f"  格心位移  平均 {sh.mean():.2f}px   P95 {sh_p95:.2f}px   最大 {sh_max:.2f}px")
    print(f"  占 64px 切格比例（平均）: {sh.mean() / 64 * 100:.0f}%")
    print(f"  超出 {SHIFT_WARN:.0f}px 余量的格子占比: {over:.2f}%   ← 这些格子会切进邻格")

    within = float((a <= args.tol).mean() * 100)
    print("\n=== 判定（以格心位移为准，角点误差仅供参考） ===")
    print(f"  ≤{args.tol:.0f}px 的角点占比: {within:.1f}%")
    if sh_p95 <= SHIFT_GOOD:
        verdict = (f"✅ 合格：格心位移 P95 {sh_p95:.2f}px ≤ {SHIFT_GOOD:.0f}px（切格的 "
                   f"{sh_p95/64*100:.0f}%），可替代 DEFAULT_CORNERS。")
    elif sh_p95 <= SHIFT_WARN and over < 1.0:
        verdict = (f"⚠ 基本可用：格心位移 P95 {sh_p95:.2f}px 未超 {SHIFT_WARN:.0f}px 余量、"
                   f"越界格仅 {over:.2f}%，但 CNN 是用「精确居中」的格子训练的，"
                   f"须端到端验证分类准确率是否下降。")
    else:
        why = (f"P95 {sh_p95:.2f}px 已超 {SHIFT_WARN:.0f}px 余量"
               if sh_p95 > SHIFT_WARN else
               f"P95 {sh_p95:.2f}px 尚在 {SHIFT_WARN:.0f}px 余量内，但尾部有 {over:.2f}% 的格子越界"
               f"（≈每图 {over / 100 * COLS * ROWS:.1f} 格被切进邻格）")
        verdict = (f"❌ 不合格：{why}。棋盘识别要求 90 格全对，每图漏 1 格即可能读错局面。"
                   f"→ 提高训练 imgsz 到 1280 并重训（增强建议用 --aug robust）。")
    print("  " + verdict)

    if skipped_no_gt:
        print(f"\n跳过 {skipped_no_gt} 张（分辨率未收录于 DEFAULT_CORNERS）")
    if skipped_no_det:
        print(f"⚠ {skipped_no_det} 张角点缺失。本任务每类恒有 1 个目标且只取最高分框，"
              f"先把 --conf 降到 0.0005 复测；若仍缺才是真漏检。")

    summary = VIZ / "_summary.txt"
    with summary.open("w", encoding="utf-8") as f:
        f.write(f"weights={weights}\nsplit={args.split} n_images={len(per_image)} "
                f"imgsz={args.imgsz} tol={args.tol}\n")
        f.write(f"MAE={s['mae']:.3f} RMSE={s['rmse']:.3f} median={s['median']:.3f} "
                f"P95={s['p95']:.3f} max={s['max']:.3f}\n")
        for i, nm in enumerate(CORNER_NAMES):
            c = np.array(per_corner[i], dtype=np.float64)
            dx, dy = d[:, i, 0], d[:, i, 1]
            f.write(f"{nm}: mean={c.mean():.3f} max={c.max():.3f} "
                    f"dx={dx.mean():+.3f} dy={dy.mean():+.3f} "
                    f"bias={np.hypot(dx.mean(), dy.mean()):.3f} "
                    f"std={np.hypot(dx.std(), dy.std()):.3f}\n")
        f.write(f"global_bias={g_bias:.3f} global_std={g_std:.3f}\n")
        f.write("conf_min=" + " ".join(
            f"{CORNER_NAMES[i]}:{cfa[:, i].min():.4f}" for i in range(4))
            + f" (used --conf {args.conf})\n")
        f.write(f"cell_shift mean={sh.mean():.3f} p95={sh_p95:.3f} max={sh_max:.3f} "
                f"over_margin={over:.2f}%\n")
        f.write(f"within_tol={within:.2f}%\n{verdict}\n\n")
        for name, m, mx in sorted(per_image, key=lambda t: -t[1]):
            f.write(f"{name}\tmean={m:.3f}\tmax={mx:.3f}\n")

    print(f"\n可视化 {n_viz} 张 -> {VIZ}")
    print(f"汇总 -> {summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
