"""det_train.py —— 用 Ultralytics YOLO11n 训练「棋盘4角定位」检测模型并导出。"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

from yolo_chess.common import (
    DET_DATASET,
    DET_EXPORT,
    DET_RUNS,
    Param,
    collect_train_metrics,
    interactive_args,
    resolve_model,
)

EXPORT_STEM = "board_corners"
CORNER_CLASSES = ["TL", "TR", "BL", "BR"]

_METRIC_COLS: dict[str, tuple[list[str], int]] = {
    "map50_box": (["metrics/mAP50(B)"], 4),
    "map5095_box": (["metrics/mAP50-95(B)"], 4),
    "box_loss": (["train/box_loss"], 4),
    "cls_loss": (["train/cls_loss"], 4),
    "dfl_loss": (["train/dfl_loss"], 4),
}

_PREPROCESS_SCALE = 255.0
_PREPROCESS_MEAN = [0.0, 0.0, 0.0]
_PREPROCESS_STD = [1.0, 1.0, 1.0]
_LETTERBOX_PAD = [114, 114, 114]

PARAMS = [
    Param("model", "str", default="yolo11n.pt", cn="基础模型", desc="Ultralytics 预训练权重"),
    Param("epochs", "int", default=100, cn="训练轮数", desc="epochs"),
    Param("batch", "int", default=8, cn="批次大小", desc="batch size；12GB 显存 @1280 建议 8"),
    Param("imgsz", "int", default=1280, cn="输入尺寸", desc="输入图边长"),
    Param("name", "str", default="corner", cn="运行目录名", desc="runs/ 下的子目录名"),
    Param("device", "str", default="0", cn="设备", desc="GPU 编号或 CPU"),
    Param(
        "export",
        "multiselect",
        default=["onnx"],
        choices=["onnx", "ncnn", "tflite"],
        choice_cn={"onnx": "ONNX", "ncnn": "NCNN", "tflite": "TFLite"},
        cn="导出格式",
        desc="空格勾选要导出的格式",
    ),
    Param("export_only", "bool", default=False, cn="仅导出", desc="跳过训练，直接导出已有权重"),
    Param(
        "aug",
        "choice",
        default="precise",
        choices=["precise", "robust"],
        cn="增强方案",
        desc="precise 低增强，robust 高增强",
    ),
    Param("mosaic", "float", default=None, cn="mosaic", desc="增强覆盖；留空用方案默认"),
    Param("scale", "float", default=None, cn="scale", desc="缩放增强；留空用方案默认"),
    Param("translate", "float", default=None, cn="translate", desc="平移增强；留空用方案默认"),
    Param("fliplr", "float", default=None, cn="fliplr", desc="水平翻转；留空用方案默认"),
    Param("erasing", "float", default=None, cn="erasing", desc="随机擦除；留空用方案默认"),
]


def copy_export(src_path: str, fmt: str) -> str:
    DET_EXPORT.mkdir(parents=True, exist_ok=True)
    p = Path(src_path)
    if fmt == "onnx":
        dst = DET_EXPORT / f"{EXPORT_STEM}.onnx"
        shutil.copy(p, dst)
        return dst.name
    if fmt == "tflite":
        dst = DET_EXPORT / f"{EXPORT_STEM}.tflite"
        shutil.copy(p, dst)
        return dst.name
    if fmt == "ncnn":
        if p.is_dir():
            param = next(iter(p.glob("*.param")), None)
            binf = next(iter(p.glob("*.bin")), None)
        else:
            param, binf = p, p.with_suffix(".bin")
        if param is None or not Path(param).exists():
            raise FileNotFoundError(f"ncnn param 未找到: {p}")
        shutil.copy(param, DET_EXPORT / f"{EXPORT_STEM}.param")
        if binf is not None and Path(binf).exists():
            shutil.copy(binf, DET_EXPORT / f"{EXPORT_STEM}.bin")
        return f"{EXPORT_STEM}.param / {EXPORT_STEM}.bin"
    raise ValueError(fmt)


def main() -> int:
    """训练检测模型主函数。"""
    args = interactive_args(PARAMS)
    if args is None:
        return 0

    data_yaml = DET_DATASET / "data.yaml"
    if not data_yaml.exists():
        print(f"未找到数据集 {data_yaml}，请先运行「构建四角数据集」")
        return 1

    aug = (
        {"mosaic": 0.0, "scale": 0.1, "translate": 0.02, "fliplr": 0.0, "erasing": 0.0}
        if args.aug == "precise"
        else {"mosaic": 1.0, "scale": 0.5, "translate": 0.1, "fliplr": 0.5, "erasing": 0.4}
    )
    for k in ("mosaic", "scale", "translate", "fliplr", "erasing"):
        v = getattr(args, k)
        if v is not None:
            aug[k] = v

    fmts = [f for f in (args.export or []) if f in ("onnx", "ncnn", "tflite")] or ["onnx"]
    from ultralytics import YOLO

    run_dir = DET_RUNS / args.name
    if args.export_only:
        print("[export-only] 跳过训练，直接导出已有权重")
    else:
        model_name = resolve_model(args.model)
        print(f"训练数据: {data_yaml}")
        print(
            f"参数: epochs={args.epochs} batch={args.batch} imgsz={args.imgsz} model={model_name}\n"
        )
        model = YOLO(model_name)
        model.train(
            data=str(data_yaml),
            epochs=args.epochs,
            batch=args.batch,
            imgsz=args.imgsz,
            device=args.device,
            project=str(DET_RUNS),
            name=args.name,
            exist_ok=True,
            verbose=True,
            **aug,
        )

    train_metrics = collect_train_metrics(run_dir, _METRIC_COLS, best="map5095_box")
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
        dynamic = fmt != "tflite"
        print(f"导出 {fmt} ...")
        try:
            exported = m.export(format=fmt, imgsz=args.imgsz, dynamic=dynamic)
            fname = copy_export(exported, fmt)
            exports[fmt] = fname
            print(f"  ✓ -> det/export/{fname}")
        except Exception as e:
            print(f"  ✗ 导出 {fmt} 失败: {e}")

    if isinstance(m.names, dict):
        class_keys = [m.names[i] for i in sorted(m.names)]
    else:
        class_keys = list(m.names)

    info = {
        "task": "detect",
        "purpose": "棋盘4角定位（4 类检测：tl/tr/bl/br），用于替代硬编码的 yolo_chess.DEFAULT_CORNERS",
        "arch": args.model,
        "num_classes": len(class_keys),
        "class_keys": class_keys,
        "corner_classes": CORNER_CLASSES,
        "corner_order": "0=TL 1=TR 2=BL 3=BR（与 yolo_chess.DEFAULT_CORNERS / _dst_points() 一致）",
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
    DET_EXPORT.mkdir(parents=True, exist_ok=True)
    (DET_EXPORT / "model_info.json").write_text(
        json.dumps(info, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    print("\n=== 完成 ===")
    print(f"训练过程: {run_dir}")
    print(f"最佳权重: {best}")
    print(f"导出模型: {DET_EXPORT}")
    print(f"推理参数: {DET_EXPORT / 'model_info.json'}")
    return 0
