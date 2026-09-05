"""pose_validate.py —— 校验「棋盘4角定位」pose 模型（导出的 ONNX）的像素级精度。

与 det_validate 的区别：推断直接由 4 个关键点给出角点（省去「每类 argmax」步骤），
其余误差/格心位移统计、可视化、判定逻辑完全对齐 det_validate。
"""

from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np

from yolo_chess.common import (
    COLS,
    CORRECT_CELL,
    DEFAULT_CORNERS,
    POSE_ROOT,
    PROJECT_ROOT,
    ROWS,
    STATE_CN,
    STATE_ENDGAME,
    STATE_LIFT,
    STATE_MATE,
    STATE_OPENING,
    Param,
    _dst_points,
    imread,
    interactive_args,
    iter_state_images,
    prepare_output_dir,
)
from yolo_chess.common.pose import POSE_MODEL, _decode_pose_row, _onnx_imgsz
from yolo_chess.common.vision import (
    CORNER_NAMES,
    _draw_boxes,
    _draw_corners,
    _letterbox,
    _stats,
)

OUTPUT_DIR = POSE_ROOT / "validate_output"

TOL_GOOD = 10.0
TOL_WARN = 25.0
SHIFT_GOOD = 6.0
SHIFT_WARN = 18.0

PARAMS = [
    Param(
        "weights",
        "str",
        default=str(POSE_MODEL),
        cn="模型路径",
        desc="导出的 ONNX 模型（由「训练 pose 四角模型」导出）",
    ),
    Param(
        "states",
        "multiselect",
        default=[STATE_ENDGAME],
        choices=[STATE_OPENING, STATE_MATE, STATE_LIFT, STATE_ENDGAME],
        choice_cn=STATE_CN,
        cn="验证状态",
        desc="空格勾选要验证的棋局状态",
    ),
    Param("imgsz", "int", default=0, cn="推理尺寸", desc="0 自动读导出信息"),
    Param("tol", "float", default=TOL_GOOD, cn="合格阈值", desc="角点误差判定阈值"),
    Param(
        "conf", "float", default=0.001, cn="参考阈值", desc="仅用于置信度余量诊断（对比部署阈值）"
    ),
]


class OnnxPoseDetector:
    """ultralytics 导出的 YOLO-Pose ONNX 推理：letterbox + 取 cls 最高候选解码 bbox+4 关键点。"""

    def __init__(self, model_path: Path, imgsz: int):
        import onnxruntime as ort

        so = ort.SessionOptions()
        so.intra_op_num_threads = 4
        so.inter_op_num_threads = 4
        self.sess = ort.InferenceSession(str(model_path), so, providers=["CPUExecutionProvider"])
        self.in_name = self.sess.get_inputs()[0].name
        self.out_name = self.sess.get_outputs()[0].name
        self.imgsz = imgsz

    def predict(self, img_bgr: np.ndarray) -> tuple[np.ndarray, np.ndarray, tuple]:
        """推理一副图，返回 (角点 4x2 原图坐标, 每角关键点置信 4, 主目标框 (x1,y1,x2,y2))。"""
        lb, r, left, top = _letterbox(img_bgr, self.imgsz)
        rgb = cv2.cvtColor(lb, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        inp = rgb.transpose(2, 0, 1)[None]
        out = np.asarray(self.sess.run([self.out_name], {self.in_name: inp})[0], dtype=np.float32)

        # ⚠ T5 校验点：首次部署务必先跑一次实推理、打印 out.shape，确认通道顺序。
        # 标准 Ultralytics pose 导出布局：每候选 4+nc+3*nk 通道；
        # 本配置 nc=1 nk=4 => 17 通道 = [cx,cy,w,h, cls, kp0x,kp0y,kp0c, ..., kp3x,kp3y,kp3c]
        row = _decode_pose_row(out, nk=4, nc=1)
        pts = np.full((4, 2), np.nan, dtype=np.float64)
        kc = np.full(4, np.nan, dtype=np.float64)
        box: tuple[float, float, float, float] = (np.nan, np.nan, np.nan, np.nan)
        if row is not None:
            cx, cy, w, h = row[0:4]
            cx0, cy0 = (cx - left) / r, (cy - top) / r
            w0, h0 = w / r, h / r
            box = (cx0 - w0 / 2, cy0 - h0 / 2, cx0 + w0 / 2, cy0 + h0 / 2)
            kp = row[5 : 5 + 3 * 4].reshape(4, 3)  # (x, y, conf) * 4
            for i in range(4):
                pts[i, 0] = (kp[i, 0] - left) / r
                pts[i, 1] = (kp[i, 1] - top) / r
                kc[i] = float(kp[i, 2])
        return pts, kc, box


def main() -> int:
    """pose 四角精度验证主函数。"""
    args = interactive_args(PARAMS)
    if args is None:
        return 0

    states = list(args.states or [])

    weights = Path(args.weights)
    if not weights.is_absolute():
        weights = PROJECT_ROOT / weights
    if not weights.exists():
        print(f"未找到模型: {weights}\n请先运行「训练 pose 四角模型」导出 ONNX")
        return 1

    try:
        import onnxruntime as ort  # noqa: F401
    except ImportError as e:
        print("依赖缺失:", e)
        return 1

    trained = _onnx_imgsz(weights)
    if args.imgsz <= 0:
        args.imgsz = trained or 1280
        auto_note = "（自动读取导出信息）" if trained else "（回退默认）"
    elif trained and args.imgsz != trained:
        auto_note = f"⚠ 与导出信息 {trained} 不一致！"
    else:
        auto_note = "（与导出一致）"

    model = OnnxPoseDetector(weights, args.imgsz)

    print(f"模型     : {weights}")
    print(f"状态     : {states}")
    print(f"推理尺寸 : {args.imgsz} {auto_note}\n")

    prepare_output_dir(OUTPUT_DIR)

    color_pred = (0, 200, 0)
    color_gt = (0, 0, 255)

    all_err: list[float] = []
    per_corner: list[list[float]] = [[], [], [], []]
    per_image: list[tuple[str, float, float]] = []
    deltas: list = []
    shifts: list = []
    confs: list = []
    skipped_no_gt = 0
    skipped_no_det = 0
    n_viz = 0

    for st, files, out_dir in iter_state_images(states, OUTPUT_DIR):
        st_err: list[float] = []
        st_deltas: list = []
        st_shifts: list = []
        st_conf: list = []
        st_skipped = 0
        per_image_lines: list[str] = []
        print(f"=== 状态 {st}  共 {len(files)} 张 ===")

        for p in files:
            img = imread(p)
            if img is None:
                st_skipped += 1
                continue
            h, w = img.shape[:2]
            key = (int(w), int(h))
            if key not in DEFAULT_CORNERS:
                skipped_no_gt += 1
                st_skipped += 1
                continue

            pts, kc, pred_box = model.predict(img)

            if np.isnan(pts).any():
                missing = [CORNER_NAMES[i] for i in range(4) if np.isnan(pts[i, 0])]
                print(f"⚠ 角点缺失: {st}/{p.name} 缺={missing}")
                skipped_no_det += 1
                continue
            confs.append(kc)
            st_conf.append(kc)

            gt = np.array(DEFAULT_CORNERS[key], dtype=np.float64)
            err = np.linalg.norm(pts - gt, axis=1)
            all_err.extend(err.tolist())
            st_err.extend(err.tolist())
            for i in range(4):
                per_corner[i].append(float(err[i]))
            per_image.append((f"{st}/{p.name}", float(err.mean()), float(err.max())))
            deltas.append(pts - gt)
            st_deltas.append(pts - gt)

            dst = _dst_points().astype(np.float32)
            h_gt = cv2.getPerspectiveTransform(gt.astype(np.float32), dst)
            h_pr = cv2.getPerspectiveTransform(pts.astype(np.float32), dst)
            centers = np.array(
                [
                    [CORRECT_CELL * (c + 0.5), CORRECT_CELL * (r + 0.5)]
                    for r in range(ROWS)
                    for c in range(COLS)
                ],
                dtype=np.float32,
            )
            src = cv2.perspectiveTransform(centers.reshape(-1, 1, 2), np.linalg.inv(h_gt))
            got = cv2.perspectiveTransform(src, h_pr).reshape(-1, 2)
            shifts.append(np.linalg.norm(got - centers, axis=1))
            st_shifts.append(np.linalg.norm(got - centers, axis=1))

            shift_mean = float(np.linalg.norm(got - centers, axis=1).mean())
            mean_err = float(err.mean())
            max_err = float(err.max())
            line = f"{p.name}\tavg={mean_err:.3f}px\tmax={max_err:.3f}px\tshift={shift_mean:.2f}px"
            per_image_lines.append(line)
            print(
                f"  [{p.name}] 平均={mean_err:.2f}px  最大={max_err:.2f}px  "
                f"格心位移={shift_mean:.2f}px"
            )

            _draw_corners(img, pts, color_pred)
            _draw_boxes(img, [pred_box], color_pred)
            _draw_corners(img, gt, color_gt)
            cv2.imwrite(str(out_dir / f"{p.stem}.png"), img)
            n_viz += 1

        st_sum = _stats(np.array(st_err, dtype=np.float64)) if st_err else None
        if st_sum is not None:
            sd = np.array(st_deltas, dtype=np.float64)
            ssh = np.concatenate(st_shifts) if st_shifts else np.zeros(1)
            scfa = np.array(st_conf, dtype=np.float64) if st_conf else np.zeros((1, 4))
            # 状态级 bias/std：按角先算再聚合（与全局 summary 口径一致），
            # 避免把 4 角 dx/dy 混平均后方向抵消、低估系统偏移。
            _per_b = [float(np.hypot(sd[:, i, 0].mean(), sd[:, i, 1].mean())) for i in range(4)]
            _per_s = [float(np.hypot(sd[:, i, 0].std(), sd[:, i, 1].std())) for i in range(4)]
            content = [f"images={len(files)} skipped={st_skipped}"]
            content += per_image_lines
            content += [
                "",
                f"MAE={st_sum['mae']:.3f} RMSE={st_sum['rmse']:.3f} P95={st_sum['p95']:.3f}",
                f"cell_shift p95={np.percentile(ssh, 95):.3f} max={ssh.max():.3f} "
                f"over={(ssh > SHIFT_WARN).mean() * 100:.2f}%",
                f"bias={np.mean(_per_b):.3f}(max{np.max(_per_b):.3f}) std={np.mean(_per_s):.3f}",
                f"conf_min={scfa.min():.4f}",
            ]
            (out_dir / "_summary.txt").write_text("\n".join(content) + "\n", encoding="utf-8")

    if not all_err:
        print("没有可评估样本")
        return 1

    a = np.array(all_err, dtype=np.float64)
    s = _stats(a)

    print("\n=== 逐图误差（最差 10 张） ===")
    for name, m, mx in sorted(per_image, key=lambda t: -t[1])[:10]:
        flag = "✅" if m <= args.tol else ("⚠" if m <= TOL_WARN else "❌")
        print(f"  {flag} {name:<30} 平均 {m:6.2f}px   最大 {mx:6.2f}px")

    print(f"\n=== 全局角点误差统计（{s['n']} 个角） ===")
    print(f"  MAE   : {s['mae']:.2f} px")
    print(f"  RMSE  : {s['rmse']:.2f} px")
    print(f"  中位数: {s['median']:.2f} px")
    print(f"  P95   : {s['p95']:.2f} px")
    print(f"  最大   : {s['max']:.2f} px")

    d = np.array(deltas, dtype=np.float64)
    print("\n=== 分角统计 + 误差分解（bias=系统偏移 / std=随机散布） ===")
    print(
        f"  {'角':<4}{'平均':>8}{'最大':>8}{'dx均值':>9}{'dy均值':>9}{'|bias|':>9}{'std':>8}  主因"
    )
    # 注：全局判定不能把 4 角的 dx/dy 混在一起求均值——各角偏差方向不同会互相抵消，
    # 会误判成「随机散布主导」。应聚合各角各自的 |bias|/std。
    per_bias: list[float] = []
    per_rnd: list[float] = []
    for i, nm in enumerate(CORNER_NAMES):
        c = np.array(per_corner[i], dtype=np.float64)
        dx, dy = d[:, i, 0], d[:, i, 1]
        bias = float(np.hypot(dx.mean(), dy.mean()))
        rnd = float(np.hypot(dx.std(), dy.std()))
        per_bias.append(bias)
        per_rnd.append(rnd)
        cause = (
            "系统偏移(可校正)"
            if bias > rnd * 1.5
            else ("随机(精度天花板)" if rnd > bias * 1.5 else "混合")
        )
        print(
            f"  {nm:<4}{c.mean():7.2f}px{c.max():7.2f}px{dx.mean():+9.2f}{dy.mean():+9.2f}"
            f"{bias:9.2f}{rnd:8.2f}  {cause}"
        )
    # 全局 = 4 角各自 |bias|/std 的均值（不抵消）；另报最大 |bias| 兜底单角强偏移
    g_bias = float(np.mean(per_bias))
    g_std = float(np.mean(per_rnd))
    g_bias_max = float(np.max(per_bias))
    print(
        f"  全局: 平均|bias|={g_bias:.2f}px(最大{g_bias_max:.2f}px) 平均std={g_std:.2f}px  → "
        + (
            "随机散布主导：提高 imgsz / 减弱增强重训才有效，加常量校正无用"
            if g_std > g_bias * 1.5
            else "系统偏移主导：可考虑常量校正"
        )
    )

    cfa = np.array(confs, dtype=np.float64) if confs else np.zeros((1, 4))
    print("\n=== 关键点置信度余量（诊断漏检风险；与定位精度无关） ===")
    print(f"  {'角':<4}{'最低':>9}{'中位':>9}{'最高':>9}  风险")
    for i, nm in enumerate(CORNER_NAMES):
        col = cfa[:, i]
        risk = (
            "❌ 极低，常规阈值必漏检"
            if col.min() < 0.01
            else "⚠ 偏低，勿用 conf≥0.1"
            if col.min() < 0.1
            else "✅ 充裕"
        )
        print(f"  {nm:<4}{col.min():9.4f}{np.median(col):9.4f}{col.max():9.4f}  {risk}")
    print(
        f"  全局最低置信度 = {cfa.min():.4f}（对比参考阈值 --conf {args.conf}）"
        + ("  ← 阈值已足够低" if args.conf < cfa.min() else "  ← ⚠ 阈值过高，可能已假漏检")
    )

    sh = np.concatenate(shifts) if shifts else np.zeros(1)
    sh_p95 = float(np.percentile(sh, 95))
    sh_max = float(sh.max())
    over = float((sh > SHIFT_WARN).mean() * 100)
    print("\n=== 误差传导到切格（矫正空间；一格100px ⇒ 切格64px ⇒ 单边余量18px） ===")
    print(f"  格心位移  平均 {sh.mean():.2f}px   P95 {sh_p95:.2f}px   最大 {sh_max:.2f}px")
    print(f"  超出 {SHIFT_WARN:.0f}px 余量的格子占比: {over:.2f}%   ← 这些格子会切进邻格")

    within = float((a <= args.tol).mean() * 100)
    verdict = ""
    print("\n=== 判定（以格心位移为准，角点误差仅供参考） ===")
    print(f"  ≤{args.tol:.0f}px 的角点占比: {within:.1f}%")
    if sh_p95 <= SHIFT_GOOD:
        verdict = (
            f"✅ 合格：格心位移 P95 {sh_p95:.2f}px ≤ {SHIFT_GOOD:.0f}px，可替代 DEFAULT_CORNERS。"
        )
        print(f"  {verdict}")
    elif sh_p95 <= SHIFT_WARN and over < 1.0:
        verdict = (
            f"⚠ 基本可用：格心位移 P95 {sh_p95:.2f}px 未超 {SHIFT_WARN:.0f}px 余量、"
            f"越界格仅 {over:.2f}%，但 CNN 是用「精确居中」的格子训练的，"
            f"须端到端验证分类准确率是否下降。"
        )
        print(f"  {verdict}")
    else:
        why = (
            f"P95 {sh_p95:.2f}px 已超 {SHIFT_WARN:.0f}px 余量"
            if sh_p95 > SHIFT_WARN
            else f"P95 {sh_p95:.2f}px 尚在 {SHIFT_WARN:.0f}px 余量内，但尾部有 {over:.2f}% 的格子越界"
        )
        hint = (
            f"误差以系统偏移为主(avg|bias|={g_bias:.1f}px>std={g_std:.1f}px)："
            f"先排查任务建模/数据同质（如整盘大框+长程关键点易拉向中心），勿只靠加 imgsz"
            if g_bias > g_std * 1.5
            else f"误差以随机散布为主(std={g_std:.1f}px)：可提高训练 imgsz 并重训"
        )
        verdict = f"❌ 不合格：{why}。{hint}。"
        print(f"  {verdict}")

    if skipped_no_gt:
        print(f"\n跳过 {skipped_no_gt} 张（分辨率未收录于 DEFAULT_CORNERS）")
    if skipped_no_det:
        print(
            f"⚠ {skipped_no_det} 张角点缺失。pose 应恒有 1 个棋盘框 + 4 关键点；"
            f"先把 --conf 降到 0.0005 复测；若仍缺才是真漏检。"
        )

    print(f"\n可视化 {n_viz} 张 -> {OUTPUT_DIR}")

    summary = OUTPUT_DIR / "_summary.txt"
    with summary.open("w", encoding="utf-8") as f:
        f.write(f"images={n_viz} skipped_no_gt={skipped_no_gt} skipped_no_det={skipped_no_det}\n")
        f.write(f"MAE={s['mae']:.3f} RMSE={s['rmse']:.3f} P95={s['p95']:.3f}\n")
        f.write(f"cell_shift p95={sh_p95:.3f} max={sh_max:.3f} over={over:.2f}%\n")
        f.write(f"within_tol={within:.2f}%\n")
        f.write(f"global_bias={g_bias:.2f} global_std={g_std:.2f}\n")
        f.write(f"conf_min={cfa.min():.4f}\n")
        f.write(f"verdict={verdict}\n")
        for i, nm in enumerate(CORNER_NAMES):
            c = np.array(per_corner[i], dtype=np.float64)
            dx, dy = d[:, i, 0], d[:, i, 1]
            f.write(
                f"corner {nm} mae={c.mean():.3f} bias={np.hypot(dx.mean(), dy.mean()):.3f} "
                f"std={np.hypot(dx.std(), dy.std()):.3f}\n"
            )

    return 0
