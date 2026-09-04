"""cls_train.py —— 用 Ultralytics YOLO11n-cls 训练棋子分类模型并导出。

输出：cls/export/chess_pieces.onnx + model_info.json
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

from yolo_chess.common import (
    CELL_OUT,
    CLASS_CN,
    CLS_DATASET,
    CLS_EXPORT,
    CLS_RUNS,
    Param,
    collect_train_metrics,
    interactive_args,
    resolve_model,
)

_PREPROCESS_SCALE = 255.0
_PREPROCESS_MEAN = [0.0, 0.0, 0.0]
_PREPROCESS_STD = [1.0, 1.0, 1.0]

_METRIC_COLS: dict[str, tuple[list[str], int]] = {
    "val_top1": (["metrics/accuracy_top1", "accuracy_top1"], 4),
    "val_loss": (["val/loss", "val_loss"], 6),
    "train_loss": (["train/loss", "train_loss"], 6),
}

PARAMS = [
    Param("model", "str", default="yolo11n-cls.pt", cn="基础模型", desc="Ultralytics 预训练权重"),
    Param("epochs", "int", default=120, cn="训练轮数", desc="epochs"),
    Param("batch", "int", default=128, cn="批次大小", desc="batch size"),
    Param("imgsz", "int", default=CELL_OUT, cn="输入尺寸", desc="输入图边长，默认 64"),
    Param("name", "str", default="chess_pieces", cn="运行目录名", desc="runs/ 下的子目录名"),
    Param("device", "str", default="0", cn="设备", desc="GPU 编号或 CPU，0=第一块显卡"),
    Param(
        "export",
        "multiselect",
        default=["onnx"],
        choices=["onnx", "ncnn", "tflite"],
        choice_cn={"onnx": "ONNX", "ncnn": "NCNN", "tflite": "TFLite"},
        cn="导出格式",
        desc="空格勾选要导出的格式",
    ),
]


def copy_export(src_path: str, fmt: str) -> str:
    CLS_EXPORT.mkdir(parents=True, exist_ok=True)
    stem = "chess_pieces"
    p = Path(src_path)
    if fmt == "onnx":
        dst = CLS_EXPORT / f"{stem}.onnx"
        shutil.copy(p, dst)
        return dst.name
    if fmt == "tflite":
        dst = CLS_EXPORT / f"{stem}.tflite"
        shutil.copy(p, dst)
        return dst.name
    if fmt == "ncnn":
        shutil.copy(p, CLS_EXPORT / f"{stem}.param")
        binp = p.with_suffix(".bin")
        if binp.exists():
            shutil.copy(binp, CLS_EXPORT / f"{stem}.bin")
        return f"{stem}.param / {stem}.bin"
    raise ValueError(fmt)


def main() -> int:
    """训练分类模型主函数。"""
    args = interactive_args(PARAMS)
    if args is None:
        return 0

    data_dir = CLS_DATASET
    if not (data_dir / "train").is_dir() or not (data_dir / "val").is_dir():
        print(f"未找到数据集目录 {data_dir}/train 与 {data_dir}/val，请先运行「构建分类数据集」")
        return 1

    fmts = [f for f in (args.export or []) if f in ("onnx", "ncnn", "tflite")] or ["onnx"]

    from ultralytics import YOLO

    model_name = resolve_model(args.model)
    print(f"训练数据: {data_dir}")
    print(f"参数: epochs={args.epochs} batch={args.batch} imgsz={args.imgsz} model={model_name}\n")

    model = YOLO(model_name)
    model.train(
        data=str(data_dir),
        epochs=args.epochs,
        batch=args.batch,
        imgsz=args.imgsz,
        device=args.device,
        project=str(CLS_RUNS),
        name=args.name,
        exist_ok=True,
        verbose=True,
    )

    train_metrics = collect_train_metrics(CLS_RUNS / args.name, _METRIC_COLS, best="val_top1")
    if train_metrics:
        print("\n=== 训练指标 ===")
        for k, v in train_metrics.items():
            print(f"  {k}: {v}")

    best = CLS_RUNS / args.name / "weights" / "best.pt"
    if not best.exists():
        print(f"[警告] 未找到最佳权重 {best}，将用最终权重导出")
        best = CLS_RUNS / args.name / "weights" / "last.pt"

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
            print(f"  ✓ -> cls/export/{fname}")
        except Exception as e:
            print(f"  ✗ 导出 {fmt} 失败: {e}")

    if isinstance(m.names, dict):
        model_names = [m.names[i] for i in sorted(m.names)]
    else:
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
        "preprocess": {
            "scale": _PREPROCESS_SCALE,
            "mean": _PREPROCESS_MEAN,
            "std": _PREPROCESS_STD,
        },
        "class_keys": model_names,
        "class_cn": [CLASS_CN.get(k, k) for k in model_names],
        "exports": exports,
        "train_metrics": train_metrics,
    }
    CLS_EXPORT.mkdir(parents=True, exist_ok=True)
    (CLS_EXPORT / "model_info.json").write_text(
        json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    print("\n=== 完成 ===")
    print(f"训练过程: {CLS_RUNS / args.name}")
    print(f"导出模型: {CLS_EXPORT}")
    return 0
