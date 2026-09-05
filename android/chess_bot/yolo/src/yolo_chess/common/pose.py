"""common/pose.py —— YOLO Pose 棋盘四角定位。

设计要点（详见 pose_detection_plan.md）：
- 主目标框：1 个 `chessboard` 框（class 0），由 4 角包围盒四边各外扩 POSE_MARGIN 后 clamp。
- 关键点：4 个，顺序 TL/TR/BL/BR（与 DEFAULT_CORNERS 一致），坐标 = DEFAULT_CORNERS 角点（不扩）。
- 可见性：corner_visibility_for_state 按 status 查 label_map 判定 v=1(遮挡但位置已知)/v=2(可见)。
- 推断：corners_from_pose 加载 pose ONNX，letterbox 推理，解码 17 通道得 4 角（部署等价入口；
  D2 决策：Python 侧 resolve_homography 不切换，仅部署用）。
"""

from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np

from yolo_chess.common.board import COLS, ROWS
from yolo_chess.common.classes import label_map_for_state
from yolo_chess.common.paths import POSE_EXPORT
from yolo_chess.common.vision import _letterbox

POSE_MARGIN = 100
KPT_ORDER = ["tl", "tr", "bl", "br"]
POSE_KPT_SHAPE = [4, 3]
CORNER_CELLS = [(0, 0), (0, COLS - 1), (ROWS - 1, 0), (ROWS - 1, COLS - 1)]

POSE_MODEL = POSE_EXPORT / "board_pose.onnx"
POSE_INFO = POSE_EXPORT / "model_info.json"


def pose_bbox_from_corners(
    corners: np.ndarray, w: float, h: float, margin: int = POSE_MARGIN
) -> tuple[float, float, float, float]:
    """由 4 角像素坐标算棋盘主目标框，四边各外扩 margin 后 clamp 到 [0,w]/[0,h]。"""
    xs = corners[:, 0].astype(float)
    ys = corners[:, 1].astype(float)
    x1 = max(0.0, float(xs.min()) - margin)
    y1 = max(0.0, float(ys.min()) - margin)
    x2 = min(float(w), float(xs.max()) + margin)
    y2 = min(float(h), float(ys.max()) + margin)
    return x1, y1, x2, y2


def corner_visibility_for_state(state: str) -> list[int]:
    """角格有棋子(非 empty/lift)→1(遮挡但位置已知)，否则→2(可见)。"""
    lmap = label_map_for_state(state)
    vis: list[int] = []
    for r, c in CORNER_CELLS:
        occ = lmap.get((r, c))
        vis.append(1 if occ not in (None, "empty", "lift") else 2)
    return vis


def _decode_pose_row(out: np.ndarray, nk: int, nc: int) -> np.ndarray | None:
    """把 Ultralytics pose ONNX 输出 reshape 为 (N, 4+nc+3*nk) 并取 cls 最高候选。"""
    ch = 4 + nc + 3 * nk
    flat = np.asarray(out, dtype=np.float32).reshape(ch, -1).T  # (N, channels)
    if flat.shape[0] == 0:
        return None
    conf = flat[:, 4 : 4 + nc].max(axis=1)
    k = int(np.argmax(conf))
    return flat[k]


def corners_from_pose(onnx_path: str | Path, img: np.ndarray) -> np.ndarray:
    """pose ONNX 推断 4 角（TL,TR,BL,BR 顺序），返回 (4,2) 原图像素坐标。

    失败抛异常，由调用方回退查表 resolve_homography。
    """
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    out_name = sess.get_outputs()[0].name
    shape = sess.get_inputs()[0].shape
    imgsz = int(shape[-1]) if isinstance(shape[-1], int) else 640

    lb, r, left, top = _letterbox(img, imgsz)
    rgb = cv2.cvtColor(lb, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    inp = rgb.transpose(2, 0, 1)[None]
    out = np.asarray(sess.run([out_name], {in_name: inp})[0], dtype=np.float32)

    # ⚠ T5 校验点：首次部署务必先跑一次实推理、打印 out.shape，确认与下方布局一致。
    # 标准 Ultralytics pose 导出布局：每候选 4+nc+3*nk 通道；
    # 本配置 nc=1 nk=4 => 17 通道 = [cx,cy,w,h, cls, kp0x,kp0y,kp0c, ..., kp3x,kp3y,kp3c]
    row = _decode_pose_row(out, nk=4, nc=1)
    if row is None:
        raise RuntimeError("pose ONNX 未解码出任何候选框")
    kp = row[5 : 5 + 3 * 4].reshape(4, 3)  # (x, y, conf) * 4，顺序 TL,TR,BL,BR
    pts = np.full((4, 2), np.nan, dtype=np.float64)
    for i in range(4):
        pts[i, 0] = (kp[i, 0] - left) / r
        pts[i, 1] = (kp[i, 1] - top) / r
    return pts


def _onnx_imgsz(model_path: Path) -> int | None:
    """从 model_info.json 读取导出时的 imgsz。"""
    info_path = model_path.with_name("model_info.json")
    if not info_path.exists():
        return None
    try:
        info = json.loads(info_path.read_text(encoding="utf-8"))
        return int(info.get("imgsz", 0)) or None
    except (ValueError, TypeError, OSError):
        return None
