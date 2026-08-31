# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "paddleocr",
#     "onnxruntime",
#     "opencv-python",
#     "numpy",
# ]
# ///

"""
PP-OCRv6 文本检测 + 识别（高精度版，基于 PaddleOCR onnxruntime 后端）。

适用场景：识别棋盘之外的 UI 文字（按钮、对话框等字体渲染的文字）。
棋子上的字是图片而非字体，识别率低属于正常，本脚本不针对棋子优化。

相比旧版（PP-OCRv2 ncnn + 手写 DB 后处理 + 手写 CTC 解码）：
  * 直接采用官方 PP-OCRv6 模型（small/medium 等档位），检测/识别精度大幅跃升；
  * 检测、多边形后处理、CTC/字典解码全部由 PaddleOCR 内部完成，无需手写；
  * 仍保留原有对外输出：每个文字「矩形两点坐标 (x1,y1)-(x2,y2) + 文字 + 置信度」，
    并写 test_result.json、画 test_result.png 供核对；
  * 阅读顺序按行 y 聚类再按 x 排序，输出稳定易读。

运行：uv run test.py [图片路径] [--model small] [--min-conf 0.0] [--no-viz]
首次运行会自动下载对应档位的 ONNX 模型（medium 约 70+MB，仅此一次，之后走缓存）。
"""

import argparse
import json
import os

import cv2
import numpy as np
from paddleocr import PaddleOCR

# 可选档位：tiny(1.5M) / small(7.7M) / medium(34.5M，精度最高)
# 用户要求「尽量精准、不计速度」，但 medium 检测头会把相邻按钮合并，故默认 small。
MODEL_TIERS = ("tiny", "small", "medium")


def imread_unicode(path: str) -> np.ndarray:
    """OpenCV 的 cv2.imread 在 Windows 上对中文（非 ASCII）路径会静默失败，
    改用 np.fromfile + cv2.imdecode 可正确处理任意 UTF-8 路径。"""
    buf = np.fromfile(path, dtype=np.uint8)
    img = cv2.imdecode(buf, cv2.IMREAD_COLOR)
    return img


def build_ocr(tier: str, engine: str) -> PaddleOCR:
    return PaddleOCR(
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        text_detection_model_name=f"PP-OCRv6_{tier}_det",
        text_recognition_model_name=f"PP-OCRv6_{tier}_rec",
        engine=engine,
    )


def sort_boxes(boxes):
    """按阅读顺序排序：先按行（y 聚类）再按列（x）。"""
    if not boxes:
        return boxes
    heights = [b[3] - b[1] for b in boxes]
    med_h = float(np.median(heights)) if heights else 30.0
    order = sorted(range(len(boxes)), key=lambda i: (boxes[i][1], boxes[i][0]))
    rows, cur = [], [order[0]]
    for i in order[1:]:
        if boxes[i][1] - boxes[cur[-1]][1] <= med_h * 0.6:
            cur.append(i)
        else:
            rows.append(cur)
            cur = [i]
    rows.append(cur)
    out = []
    for r in rows:
        r.sort(key=lambda i: boxes[i][0])
        out.extend(r)
    return [boxes[i] for i in out]


def poly_to_box(poly) -> list[int]:
    """由 4 点（或 N 点）多边形得到轴对齐两点矩形 [x1, y1, x2, y2]。"""
    pts = np.asarray(poly, dtype=np.float32).reshape(-1, 2)
    x1, y1 = int(round(pts[:, 0].min())), int(round(pts[:, 1].min()))
    x2, y2 = int(round(pts[:, 0].max())), int(round(pts[:, 1].max()))
    return [x1, y1, x2, y2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image", nargs="?", default="b.png", help="待识别图片路径")
    ap.add_argument("--model", choices=MODEL_TIERS, default="small",
                    help="模型档位（默认 small，能正确分开相邻按钮；疑难字可改 medium）")
    ap.add_argument("--engine", default="onnxruntime",
                    help="推理后端（默认 onnxruntime；可改 paddle）")
    ap.add_argument("--min-conf", type=float, default=0.0,
                    help="仅输出置信度不低于该值的识别（默认 0.0=全部）")
    ap.add_argument("--no-viz", action="store_true", help="不生成可视化图")
    args = ap.parse_args()

    img_path = args.image
    if not os.path.exists(img_path):
        print(f"找不到图像文件: {img_path}")
        return

    print(f"加载 PP-OCRv6_{args.model}（engine={args.engine}）...")
    ocr = build_ocr(args.model, args.engine)

    img = imread_unicode(img_path)
    if img is None:
        print(f"图像读取失败: {img_path}")
        return
    img_h, img_w = img.shape[:2]

    result = ocr.predict(img)
    res = result[0]

    polys = res["rec_polys"]      # list[np.ndarray(4,2)]
    texts = res["rec_texts"]      # list[str]
    scores = res["rec_scores"]    # list[float]

    boxes = [poly_to_box(p) for p in polys]
    boxes = sort_boxes(boxes)

    print(f"==== 共检测到 {len(boxes)} 个区域 ====")
    results = []
    viz = img.copy()
    idx = 0
    for (x1, y1, x2, y2), text, conf in zip(boxes, texts, scores):
        if not text.strip() or conf < args.min_conf:
            continue
        results.append({
            "index": idx, "text": text, "confidence": round(float(conf), 4),
            "box": [x1, y1, x2, y2],
            "points": {"tl": [x1, y1], "br": [x2, y2]},
        })
        print(f"[{idx:02d}] 矩形({x1:4d},{y1:4d})-({x2:4d},{y2:4d})  文字: {text}  置信度: {conf:.3f}")
        cv2.rectangle(viz, (x1, y1), (x2, y2), (0, 0, 255), 2)
        cv2.putText(viz, f"{idx}:{text}", (x1, max(0, y1 - 4)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2)
        idx += 1

    with open("test_result.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\n已写入 test_result.json（共 {len(results)} 条有效识别）")

    if not args.no_viz:
        cv2.imwrite("test_result.png", viz)
        print("已生成可视化图 test_result.png")


if __name__ == "__main__":
    main()
