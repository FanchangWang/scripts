"""step_12_train_corner.py
========================
用 Ultralytics YOLO11n 训练「棋盘4角定位」检测模型并导出。

流程（与 step_6_train_yolo.py 对称）：
  1) 读取 step_11 生成的 corner/dataset/data.yaml（nc=4，4 类角 tl/tr/bl/br）。
  2) 以 yolo11n.pt 预训练权重做迁移学习（imgsz=640）。
  3) 导出为移动端可用格式（默认 onnx；可选 ncnn / tflite / all）。
  4) 汇总到 cnn/corner/export/，并写 model_info.json（含推理预处理/后处理契约与角类顺序）。

输出约定（Android 端必须一致）：
  - 4 个类 0=TL 1=TR 2=BL 3=BR，每个类对应棋盘的一个角。
  - 推理时取「每类置信度最高的框」的**框心**作为该角坐标，按顺序组装后喂 homography 矫正。

运行：
  uv run step_12_train_corner.py                       # 默认：imgsz=1280 / device=0 / aug=precise（近乎不增强）
  uv run step_12_train_corner.py --export all          # 同时导出 onnx + ncnn + tflite
  uv run step_12_train_corner.py --imgsz 960           # 降显存可改小（推理须同值）
  uv run step_12_train_corner.py --aug robust          # 切换重度增强（默认 precise；若漏检多/装饰遮挡建议 robust）
  uv run step_12_train_corner.py --export-only         # 跳过训练，仅对已有 best.pt 重新导出

精度要点（实测教训，勿踩）：
  · **推理 imgsz 必须等于训练 imgsz**（默认 1280）。step_13 已改为自动从 args.yaml 读取，勿手工覆盖。
  · **增强预设默认 precise（近乎不增强）**：当前固定分辨率 1080×2400、棋盘角位置一致，precise 即可拟合到亚像素
    （det 1280+robust 实测 MAE 0.82px）。但若截图出现**装饰遮挡角点**（如皮肤/龙/兔等 UI 元素盖住棋盘角），
    precise 会让模型去「背固定角部图块」，遇遮挡图直接漏检；此时应改 `--aug robust`
    （mosaic+scale+translate 逼模型学「位置/尺度不变的角特征」，对遮挡/不同 UI 状态更鲁棒）。
  · ⚠️ **漏检是真实风险**：val 48 图约 4% 漏掉某角（raw/1 子组更高，达 27%，因其含装饰遮挡图）。
    生产环境必须混合兜底：det 预测优先；某类缺失/低置信则回退 DEFAULT_CORNERS（已知分辨率精确 0px）。
  · 历史参考（pose 版）：1280+precise MAE 15.62px 比 640+robust 11.03px 更差——单靠加大 imgsz 不保证更准，
    mosaic 还会把棋盘缩回同尺寸、抵消分辨率收益；改 det 头后此问题已不存在。

依赖：见 cnn/pyproject.toml（ultralytics / numpy / onnx / onnxruntime / onnxslim /
     torch[CUDA cu128] 等），已统一由 uv 在项目级 pyproject.toml 管理。

目录隔离：所有产物都在 cnn/corner/ 下，与 step_2..8 的 dataset_yolo/ runs/ export/ 互不干扰。
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
CORNER_ROOT = HERE / "corner"
DATA = CORNER_ROOT / "dataset" / "data.yaml"   # step_11 产出
RUNS_ROOT = CORNER_ROOT / "runs"               # 训练过程
EXPORT_ROOT = CORNER_ROOT / "export"           # 导出模型
EXPORT_STEM = "board_corners"

# 角类顺序唯一真相源：与 yolo_common.DEFAULT_CORNERS / _dst_points() 完全一致
CORNER_CLASSES = ["TL", "TR", "BL", "BR"]

# 推理预处理唯一真相源（由本脚本定义并写入 model_info.json，Android 端必须一致）。
_PREPROCESS_SCALE = 255.0
_PREPROCESS_MEAN = [0.0, 0.0, 0.0]
_PREPROCESS_STD = [1.0, 1.0, 1.0]
_LETTERBOX_PAD = [114, 114, 114]


def _col(name: str, header) -> str | None:
    """在 results.csv 表头里模糊匹配列名（兼容带/不带 'metrics/' 前缀）。"""
    for k in header:
        if k.strip() == name:
            return k
    for k in header:
        if name in k:
            return k
    return None


def collect_train_metrics(run_dir: Path) -> dict:
    """从 runs/<name>/results.csv 解析检测训练指标，落盘便于排查。

    检测任务关注 (B)=Box 指标；本任务每角一个实例，真正决定四角精度的是
    step_13 的像素误差统计（mAP50-95 对本任务不敏感，见 step_13 说明）。
    """
    metrics: dict = {}
    csv_path = run_dir / "results.csv"
    if not csv_path.exists():
        return metrics
    try:
        import csv
        with csv_path.open(encoding="utf-8", newline="") as f:
            rows = list(csv.DictReader(f))
        if not rows:
            return metrics
        last = rows[-1]

        def to_f(x):
            try:
                return float(x)
            except (TypeError, ValueError):
                return None

        cols = {
            "map50_box": _col("metrics/mAP50(B)", last),
            "map5095_box": _col("metrics/mAP50-95(B)", last),
            "box_loss": _col("train/box_loss", last),
            "cls_loss": _col("train/cls_loss", last),
            "dfl_loss": _col("train/dfl_loss", last),
        }
        metrics["final_epoch"] = int(float(last.get("epoch", len(rows))))
        for tag, key in cols.items():
            if key is not None:
                v = to_f(last[key])
                if v is not None:
                    metrics[f"final_{tag}"] = round(v, 4)

        # 全程最佳 mAP50-95(B) 及其所在 epoch（Ultralytics 选 best.pt 的主要依据）
        k = cols["map5095_box"]
        if k is not None:
            best_v, best_e = -1.0, -1
            for r in rows:
                v, e = to_f(r.get(k)), to_f(r.get("epoch"))
                if v is not None and v > best_v:
                    best_v, best_e = v, e
            if best_v >= 0:
                metrics["best_map5095_box"] = round(best_v, 4)
                metrics["best_map5095_box_epoch"] = int(best_e) if best_e >= 0 else None
    except Exception as e:  # noqa: BLE001
        metrics["_csv_parse_error"] = str(e)
    return metrics


def copy_export(src_path: str, fmt: str) -> str:
    """把 ultralytics 导出的文件汇总到 corner/export/ 并统一命名。"""
    EXPORT_ROOT.mkdir(parents=True, exist_ok=True)
    p = Path(src_path)
    if fmt == "onnx":
        dst = EXPORT_ROOT / f"{EXPORT_STEM}.onnx"
        shutil.copy(p, dst)
        return dst.name
    if fmt == "tflite":
        dst = EXPORT_ROOT / f"{EXPORT_STEM}.tflite"
        shutil.copy(p, dst)
        return dst.name
    if fmt == "ncnn":
        # ncnn 导出得到一个目录（*_ncnn_model/），内含 model.ncnn.param / model.ncnn.bin
        if p.is_dir():
            param = next(iter(p.glob("*.param")), None)
            binf = next(iter(p.glob("*.bin")), None)
        else:
            param, binf = p, p.with_suffix(".bin")
        if param is None or not Path(param).exists():
            raise FileNotFoundError(f"ncnn param 未找到: {p}")
        shutil.copy(param, EXPORT_ROOT / f"{EXPORT_STEM}.param")
        if binf is not None and Path(binf).exists():
            shutil.copy(binf, EXPORT_ROOT / f"{EXPORT_STEM}.bin")
        return f"{EXPORT_STEM}.param / {EXPORT_STEM}.bin"
    raise ValueError(fmt)


def parse_formats(spec: str) -> list[str]:
    fmts: list[str] = []
    for f in spec.lower().split(","):
        f = f.strip()
        if f == "all":
            return ["onnx", "ncnn", "tflite"]
        if f in ("onnx", "ncnn", "tflite") and f not in fmts:
            fmts.append(f)
    return fmts or ["onnx"]


def main() -> int:
    ap = argparse.ArgumentParser(description="训练并导出棋盘4角定位 YOLO-Detection 模型")
    ap.add_argument("--model", default="yolo11n.pt", help="起始权重（默认 yolo11n.pt，首次自动下载）")
    ap.add_argument("--epochs", type=int, default=100, help="默认 100")
    ap.add_argument("--batch", type=int, default=16, help="默认 16（1080x2400 长图 + 1280 输入，显存/内存敏感）")
    ap.add_argument("--imgsz", type=int, default=1280, help="输入尺寸（默认 1280；降显存可改 960/640，推理须同值）")
    ap.add_argument("--name", default="corner", help="runs 子目录名")
    ap.add_argument("--device", default="0",
                    help="训练设备（默认 0 = 第1块 CUDA GPU；auto / cuda / cpu）")
    ap.add_argument("--export", default="onnx",
                    help="导出格式：onnx / ncnn / tflite / all（逗号分隔）")
    ap.add_argument("--export-only", action="store_true",
                    help="跳过训练，仅对已有 best.pt 重新导出（训练耗时长，改导出格式时用）")
    # --- 增强控制 ---
    # 默认 precise（近乎不增强）：当前固定分辨率 1080×2400、棋盘角位置一致，precise 即可拟合到亚像素
    # （det 1280+robust 实测 MAE 0.82px）。
    # 但若截图含装饰遮挡角点（皮肤/龙/兔等 UI 元素盖住棋盘角），precise 易漏检 → 改 --aug robust 更鲁棒。
    # erasing（随机擦除补丁）会抹掉角点区域，precise 强制 0；robust 保留默认 0.4 以维鲁棒性。
    ap.add_argument("--aug", default="precise", choices=["precise", "robust"],
                    help="增强预设：precise(默认，近乎不增强) / robust(Ultralytics 重度增强，抗遮挡更鲁棒)")
    ap.add_argument("--mosaic", type=float, default=None, help="覆盖 mosaic（0~1）")
    ap.add_argument("--scale", type=float, default=None, help="覆盖 scale 抖动幅度")
    ap.add_argument("--translate", type=float, default=None, help="覆盖 translate 抖动幅度")
    ap.add_argument("--fliplr", type=float, default=None,
                    help="覆盖水平翻转概率（本任务应为 0：镜像棋盘不存在）")
    ap.add_argument("--erasing", type=float, default=None,
                    help="覆盖随机擦除概率（precise 预设强制 0；robust 保留默认 0.4，会抹掉角点区域，慎用）")
    args = ap.parse_args()

    # 预设 + 逐项覆盖
    aug = ({"mosaic": 0.0, "scale": 0.1, "translate": 0.02, "fliplr": 0.0, "erasing": 0.0}
           if args.aug == "precise" else
           {"mosaic": 1.0, "scale": 0.5, "translate": 0.1, "fliplr": 0.5, "erasing": 0.4})
    for k in ("mosaic", "scale", "translate", "fliplr", "erasing"):
        v = getattr(args, k)
        if v is not None:
            aug[k] = v

    if not DATA.exists():
        print(f"未找到数据集 {DATA}，请先运行 step_11_build_corner_dataset.py")
        return 1

    fmts = parse_formats(args.export)
    from ultralytics import YOLO  # 重量级依赖，延迟导入

    run_dir = RUNS_ROOT / args.name
    if args.export_only:
        print("[export-only] 跳过训练，直接导出已有权重")
    else:
        print(f"训练数据: {DATA}")
        print(f"参数: epochs={args.epochs} batch={args.batch} imgsz={args.imgsz} model={args.model}")
        print(f"类别: {len(CORNER_CLASSES)} 个 -> {CORNER_CLASSES}（0=TL 1=TR 2=BL 3=BR）")
        print(f"增强[{args.aug}]: " + "  ".join(f"{k}={v}" for k, v in aug.items())
              + ("   ← 重度增强：学位置/尺度不变特征（抗遮挡/不同 UI 状态更鲁棒）" if args.aug == "robust"
                 else "   ← 近乎不增强（固定分辨率拟合亚像素；若漏检多/装饰遮挡请改 --aug robust）"))
        print()
        model = YOLO(args.model)
        model.train(
            data=str(DATA),
            epochs=args.epochs,
            batch=args.batch,
            imgsz=args.imgsz,
            device=args.device,
            project=str(RUNS_ROOT),
            name=args.name,
            exist_ok=True,
            verbose=True,
            **aug,
        )

    train_metrics = collect_train_metrics(run_dir)
    if train_metrics:
        print("\n=== 训练指标 ===")
        for k, v in train_metrics.items():
            print(f"  {k}: {v}")

    best = run_dir / "weights" / "best.pt"
    if not best.exists():
        alt = run_dir / "weights" / "last.pt"
        if not alt.exists():
            print(f"[中止] 未找到权重 {best} / {alt}")
            return 1
        print(f"[警告] 未找到最佳权重 {best}，将用最终权重导出")
        best = alt

    print("\n=== 导出模型 ===")
    exports: dict[str, str] = {}
    m = YOLO(str(best))
    for fmt in fmts:
        dynamic = fmt != "tflite"   # tflite 不支持动态轴
        print(f"导出 {fmt} ...")
        try:
            exported = m.export(format=fmt, imgsz=args.imgsz, dynamic=dynamic)
            fname = copy_export(exported, fmt)
            exports[fmt] = fname
            print(f"  ✓ -> cnn/corner/export/{fname}")
        except Exception as e:  # noqa: BLE001
            print(f"  ✗ 导出 {fmt} 失败: {e}")

    if isinstance(m.names, dict):
        class_keys = [m.names[i] for i in sorted(m.names)]
    else:
        class_keys = list(m.names)

    info = {
        "task": "detect",
        "purpose": "棋盘4角定位（4 类检测：tl/tr/bl/br），用于替代硬编码的 yolo_common.DEFAULT_CORNERS",
        "arch": args.model,
        "num_classes": len(class_keys),
        "class_keys": class_keys,
        "corner_classes": CORNER_CLASSES,
        "corner_order": "0=TL 1=TR 2=BL 3=BR（与 yolo_common.DEFAULT_CORNERS / _dst_points() 一致）",
        "corner_box": "每角一个框，框心=角点坐标；推理取每类置信度最高框的框心",
        "imgsz": args.imgsz,
        "input_shape": [1, 3, args.imgsz, args.imgsz],
        "input_dtype": "float32",
        "input_layout": "NCHW",
        "color_order": "RGB（cv2 读入是 BGR，喂模型前需 cvtColor(BGR2RGB)）",
        "preprocess": {
            "resize": f"letterbox 等比缩放到 {args.imgsz}x{args.imgsz}，短边居中填充",
            "pad_value": _LETTERBOX_PAD,
            "scale": _PREPROCESS_SCALE,
            "mean": _PREPROCESS_MEAN,
            "std": _PREPROCESS_STD,
        },
        "postprocess": {
            "raw_output_shape": [1, 4 + len(class_keys), "num_anchors(8400 @imgsz=640)"],
            "channel_layout": (
                f"{4 + len(class_keys)} = 4(box cx,cy,w,h) + {len(class_keys)}(cls conf)"
            ),
            # ⚠️ 关键契约：本任务每张图每类恒有且仅有 1 个真实目标，且后处理是「每类取最高分框」，
            # 因此置信度阈值**唯一的作用就是制造假漏检**，必须设到极低（0.001）。
            # 实测 251 张 raw：conf=0.1 漏 2 张(BR)，conf=0.001 漏 0 张，精度完全相同。
            # BR 类置信度天生偏低（实测最低 0.0048），但该框定位误差仍仅 1.06px
            # —— 定位精度与置信度无关，切勿用置信度做质量筛选。
            "conf_threshold": 0.001,
            "nms": "不需要按常规 NMS 去重；直接对每类做 argmax(conf) 即可",
            "steps": [
                "以极低阈值 conf=0.001 解码（切勿用 0.25/0.1 等常规值，会漏掉 BR）",
                "对每类 c∈{0,1,2,3} 取其 cls==c 中置信度最高的框",
                "取该框框心 ((x1+x2)/2,(y1+y2)/2) 作为第 c 个角的坐标（原图像素）",
                "按 0=TL 1=TR 2=BL 3=BR 组装后传入 correct_board() 做透视矫正",
                "若某类仍无任何框（极罕见），该角回退 DEFAULT_CORNERS[(w,h)]；"
                "分辨率未收录则整帧判失败",
            ],
        },
        "android_recommend": "onnx (ONNX Runtime Mobile)",
        "exports": exports,
        "train_metrics": train_metrics,
    }
    EXPORT_ROOT.mkdir(parents=True, exist_ok=True)
    (EXPORT_ROOT / "model_info.json").write_text(
        json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    print("\n=== 完成 ===")
    print(f"训练过程: {run_dir}")
    print(f"最佳权重: {best}")
    print(f"导出模型: {EXPORT_ROOT}")
    print(f"推理参数: {EXPORT_ROOT / 'model_info.json'}")
    print("\n下一步：uv run step_13_validate_corner.py  # 随机取 raw 图标记预测四角并打印误差")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
