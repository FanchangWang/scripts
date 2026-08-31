# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "ultralytics",
#   "numpy",
#   "onnx>=1.12.0,<2.0.0",
#   "onnxruntime",
#   "onnxslim>=0.1.82",
# ]
# ///
"""脚本3：用 Ultralytics YOLO11n-cls 训练棋子分类模型并导出。

流程：
  1) 读取 cnn/dataset_yolo/data.yaml。
  2) 以 yolo11n-cls.pt 预训练权重做迁移学习（imgsz=64）。
  3) 导出为移动端可用的模型格式（默认 onnx；可选 ncnn / tflite / all）。
  4) 汇总到 cnn/export/，并写 model_info.json（含推理预处理参数与类名）。

运行：
  uv run step_6_train_yolo.py                         # 默认训练 + 导出 onnx
  uv run step_6_train_yolo.py --export all           # 同时导出 onnx + ncnn + tflite
  uv run step_6_train_yolo.py --epochs 120 --batch 128 --imgsz 64
依赖：  ultralytics（会自动拉取 torch，首次安装/运行较重）；
      导出 onnx 需 onnx / onnxruntime / onnxslim（已加进 dependencies，避免每次运行临时 AutoUpdate）。
"""

import argparse
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from yolo_common import (
    CELL_OUT,
    CLASS_CN,
    DATASET_ROOT,
    EXPORT_ROOT,
    RUNS_ROOT,
)

# 推理预处理唯一真相源（由 step_6 定义，不再读取 step_5 的 preprocess.json）。
# 本项目棋子分类模型按「无归一化」推理：BGR->RGB -> /255，不做 ImageNet 归一化。
# 经验证：若在此声明 ImageNet 归一化，step_8 / Android 端推理会把黑将等误判为空格
# （acc 从 1.0 跌到 0.98）。Ultralytics 分类训练的内部归一化不由此处控制，本常量仅描述
# 推理端实际应执行的预处理契约，必须与实际导出的模型保持一致。
_PREPROCESS_SCALE = 255.0
_PREPROCESS_MEAN = [0.0, 0.0, 0.0]
_PREPROCESS_STD = [1.0, 1.0, 1.0]


def _col(name: str, header: list) -> str | None:
    """在 results.csv 表头里模糊匹配列名（兼容 'metrics/accuracy_top1' 与 'accuracy_top1'）。"""
    for k in header:
        if k.strip() == name:
            return k
    for k in header:
        if name in k:
            return k
    return None


def collect_train_metrics(run_dir: Path) -> dict:
    """从 runs/<name>/results.csv 解析训练指标，方便后续排查。

    返回含 best_val_top1 / best_val_top1_epoch / final_* 等字段；解析失败则回退到
    trainer 末轮指标（model.trainer.metrics.results_dict）。
    """
    metrics: dict = {}
    csv_path = run_dir / "results.csv"
    if csv_path.exists():
        try:
            import csv
            with csv_path.open(encoding="utf-8", newline="") as f:
                rows = list(csv.DictReader(f))
            if rows:
                last = rows[-1]
                top1_k = _col("metrics/accuracy_top1", last) or _col("accuracy_top1", last)
                vloss_k = _col("val/loss", last) or _col("val_loss", last)
                tloss_k = _col("train/loss", last) or _col("train_loss", last)

                def to_f(x):
                    try:
                        return float(x)
                    except (TypeError, ValueError):
                        return None

                metrics["final_epoch"] = int(float(last.get("epoch", len(rows))))
                if top1_k is not None:
                    metrics["final_val_top1"] = round(to_f(last[top1_k]) or 0.0, 4)
                if vloss_k is not None:
                    metrics["final_val_loss"] = round(to_f(last[vloss_k]) or 0.0, 6)
                if tloss_k is not None:
                    metrics["final_train_loss"] = round(to_f(last[tloss_k]) or 0.0, 6)
                # 全程最佳 val top1 及其所在 epoch
                if top1_k is not None:
                    best_v, best_e = -1.0, -1
                    for r in rows:
                        v = to_f(r.get(top1_k))
                        e = to_f(r.get("epoch"))
                        if v is not None and v > best_v:
                            best_v, best_e = v, e
                    if best_v >= 0:
                        metrics["best_val_top1"] = round(best_v, 4)
                        metrics["best_val_top1_epoch"] = int(best_e) if best_e >= 0 else None
        except Exception as e:  # noqa: BLE001
            metrics["_csv_parse_error"] = str(e)
    return metrics


def copy_export(src_path: str, fmt: str) -> str:
    EXPORT_ROOT.mkdir(parents=True, exist_ok=True)
    stem = "chess_pieces"
    p = Path(src_path)
    if fmt == "onnx":
        dst = EXPORT_ROOT / f"{stem}.onnx"
        shutil.copy(p, dst)
        return dst.name
    if fmt == "tflite":
        dst = EXPORT_ROOT / f"{stem}.tflite"
        shutil.copy(p, dst)
        return dst.name
    if fmt == "ncnn":
        shutil.copy(p, EXPORT_ROOT / f"{stem}.param")
        binp = p.with_suffix(".bin")
        if binp.exists():
            shutil.copy(binp, EXPORT_ROOT / f"{stem}.bin")
        return f"{stem}.param / {stem}.bin"
    raise ValueError(fmt)


def main() -> int:
    ap = argparse.ArgumentParser(description="训练并导出棋子分类 YOLO 模型")
    ap.add_argument("--model", default="yolo11n-cls.pt", help="起始权重（默认 yolo11n-cls.pt）")
    ap.add_argument("--epochs", type=int, default=120, help="默认 120，若有问题可改 80")
    ap.add_argument("--batch", type=int, default=128, help="默认 128，若有问题可改 64")
    ap.add_argument("--imgsz", type=int, default=CELL_OUT, help="输入尺寸（默认 64，与切格一致）")
    ap.add_argument("--name", default="chess_pieces", help="runs 子目录名")
    ap.add_argument("--export", default="onnx",
                    help="导出格式：onnx / ncnn / tflite / all（逗号分隔）")
    args = ap.parse_args()

    # 分类任务：Ultralytics 要求 data 指向「数据集根目录」（内含 train/ 和 val/ 子目录），
    # 而非 data.yaml 文件（yaml 写法仅检测/分割任务使用）。类名由文件夹名自动推断。
    data_dir = DATASET_ROOT
    if not (data_dir / "train").is_dir() or not (data_dir / "val").is_dir():
        print(f"未找到数据集目录 {data_dir}/train 与 {data_dir}/val，请先运行 step_5_build_dataset.py")
        return 1

    fmts = []
    for f in args.export.lower().split(","):
        f = f.strip()
        if f == "all":
            fmts = ["onnx", "ncnn", "tflite"]
            break
        if f in ("onnx", "ncnn", "tflite"):
            fmts.append(f)
    if not fmts:
        fmts = ["onnx"]

    from ultralytics import YOLO  # 重量级依赖，延迟导入

    print(f"训练数据: {data_dir}")
    print(f"参数: epochs={args.epochs} batch={args.batch} imgsz={args.imgsz} model={args.model}\n")

    model = YOLO(args.model)
    model.train(
        data=str(data_dir),
        epochs=args.epochs,
        batch=args.batch,
        imgsz=args.imgsz,
        project=str(RUNS_ROOT),
        name=args.name,
        exist_ok=True,
        verbose=True,
    )

    # 训练指标（来自 runs/<name>/results.csv）— 落盘到 model_info.json 便于排查
    train_metrics = collect_train_metrics(RUNS_ROOT / args.name)
    if train_metrics:
        print("\n=== 训练指标 ===")
        for k, v in train_metrics.items():
            print(f"  {k}: {v}")

    best = RUNS_ROOT / args.name / "weights" / "best.pt"
    if not best.exists():
        print(f"[警告] 未找到最佳权重 {best}，将用最终权重导出")
        best = RUNS_ROOT / args.name / "weights" / "last.pt"

    print("\n=== 导出模型 ===")
    exports: dict[str, str] = {}
    m = YOLO(str(best))
    for fmt in fmts:
        dynamic = fmt != "tflite"
        print(f"导出 {fmt} ...")
        try:
            exported = m.export(format=fmt, imgsz=args.imgsz, dynamic=dynamic)
            fname = copy_export(exported, fmt)
            exports[fmt] = fname
            print(f"  ✓ -> cnn/export/{fname}")
        except Exception as e:  # noqa: BLE001
            print(f"  ✗ 导出 {fmt} 失败: {e}")

    # 关键：Ultralytics 分类输出索引 = 训练时文件夹「字母序」(b_a=0,...,r_R=15)，
    # 与 yolo_common.CLASSES 列表顺序(黑红交叉)不一致。必须读训练后模型自身的 names，
    # 否则 Android 端 argmax 查 class_keys 会张冠李戴（r_P 被标成 empty 等）。
    if isinstance(m.names, dict):
        model_names = [m.names[i] for i in sorted(m.names)]
    else:  # list
        model_names = list(m.names)
    info = {
        "task": "classification",
        "arch": args.model,
        "num_classes": len(model_names),
        "imgsz": args.imgsz,
        "input_shape": [1, 3, args.imgsz, args.imgsz],
        "input_dtype": "float32",
        "input_layout": "NCHW",
        "color_order": "RGB（cv2 裁出的格子是 BGR，喂模型前需 cvtColor(BGR2RGB)）",
        "preprocess": {"scale": _PREPROCESS_SCALE, "mean": _PREPROCESS_MEAN, "std": _PREPROCESS_STD},
        "class_keys": model_names,
        "class_cn": [CLASS_CN.get(k, k) for k in model_names],
        "android_recommend": "onnx (ONNX Runtime Mobile)",
        "exports": exports,
        "train_metrics": train_metrics,
    }
    EXPORT_ROOT.mkdir(parents=True, exist_ok=True)
    (EXPORT_ROOT / "model_info.json").write_text(
        json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    print("\n=== 完成 ===")
    print(f"训练过程: {RUNS_ROOT / args.name}")
    print(f"导出模型: {EXPORT_ROOT}")
    print(f"推理参数: {EXPORT_ROOT / 'model_info.json'}")
    print("\nAndroid 端推荐：将 chess_pieces.onnx 放入 assets，用 ONNX Runtime Mobile 加载；")
    print("逐格裁 64x64 -> BGR2RGB -> /255 -> 不做归一化(mean=0,std=1) -> NCHW -> 推理 -> argmax 取 class_keys。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
