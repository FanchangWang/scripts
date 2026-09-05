"""common —— 共享配置与工具（子包门面）。

原单文件 common.py 已拆分为 common/ 子包，本 __init__.py 仅作向后兼容 re-export，
所有符号实际定义在对应子模块中：
  paths / board / classes / io_utils / adb / templates / vision / metrics / ui / pose。
旧代码 `from yolo_chess.common import X` 保持不变。

路径约定：
- PROJECT_ROOT = yolo/ 目录（本包所在的上两级）
- SHARED_ROOT = yolo/shared/（原始资源）
- CLS_ROOT / DET_ROOT / POSE_ROOT = yolo/{cls,det,pose}/（各流水线产物）
"""

from yolo_chess.common.adb import (
    _parse_adb_devices,
    adb_screenshot,
    list_adb_devices,
    resolve_adb_serial,
    save_adb_serial,
    saved_adb_serial,
)
from yolo_chess.common.board import (
    CELL_OUT,
    COLS,
    CORRECT_CELL,
    CORRECT_H,
    CORRECT_W,
    DEFAULT_CORNERS,
    HALF,
    ROWS,
    _dst_points,
    correct_board,
    corrected_center,
    crop_cell,
    load_corners_json,
    resolve_homography,
    save_corners_json,
)
from yolo_chess.common.classes import (
    CLASS_CN,
    CLASS_IDX,
    CLASSES,
    LIFT_POINTS,
    STATE_CN,
    STATE_ENDGAME,
    STATE_LIFT,
    STATE_MATE,
    STATE_OPENING,
    STATES,
    VALID_STATES,
    _start_squares,
    append_lift_label,
    label_map_for_lift,
    label_map_for_lift_image,
    label_map_for_state,
    lift_label_csv,
    load_lift_labels,
    state_cn,
    state_dir,
)
from yolo_chess.common.io_utils import imread, iter_state_images, prepare_output_dir
from yolo_chess.common.metrics import collect_train_metrics
from yolo_chess.common.paths import (
    CLS_CELLS,
    CLS_CELLS_DEDUP,
    CLS_DATASET,
    CLS_EXPORT,
    CLS_ROOT,
    CLS_RUNS,
    CORNERS_JSON,
    DET_DATASET,
    DET_EXPORT,
    DET_ROOT,
    DET_RUNS,
    POSE_DATASET,
    POSE_EXPORT,
    POSE_ROOT,
    POSE_RUNS,
    PROJECT_ROOT,
    SHARED_RAW,
    SHARED_ROOT,
    SHARED_TEMPLATES,
    WEIGHTS_DIR,
    resolve_model,
)
from yolo_chess.common.pose import (
    CORNER_CELLS,
    KPT_ORDER,
    POSE_KPT_SHAPE,
    POSE_MARGIN,
    corner_visibility_for_state,
    corners_from_pose,
    pose_bbox_from_corners,
)
from yolo_chess.common.templates import (
    EMPTY_MATCH_THRESHOLD,
    MATCH_SEARCH_HALF,
    build_all_template_sets,
    cut_template_set_from_image,
    dedup_class,
    ensure_template_sets,
    load_template_sets,
    match_board_with_best_set,
    match_cell_in_corrected,
    match_cell_to_class,
)
from yolo_chess.common.ui import Param, interactive_args
from yolo_chess.common.vision import (
    CORNER_NAMES,
    QUAD_ORDER,
    _draw_boxes,
    _draw_corners,
    _letterbox,
    _stats,
)

__all__ = [
    "CELL_OUT",
    # classes
    "CLASSES",
    "CLASS_CN",
    "CLASS_IDX",
    "CLS_CELLS",
    "CLS_CELLS_DEDUP",
    "CLS_DATASET",
    "CLS_EXPORT",
    "CLS_ROOT",
    "CLS_RUNS",
    # board
    "COLS",
    "CORNERS_JSON",
    "CORNER_CELLS",
    # vision
    "CORNER_NAMES",
    "CORRECT_CELL",
    "CORRECT_H",
    "CORRECT_W",
    "DEFAULT_CORNERS",
    "DET_DATASET",
    "DET_EXPORT",
    "DET_ROOT",
    "DET_RUNS",
    # templates
    "EMPTY_MATCH_THRESHOLD",
    "HALF",
    "KPT_ORDER",
    "LIFT_POINTS",
    "MATCH_SEARCH_HALF",
    "POSE_DATASET",
    "POSE_EXPORT",
    "POSE_KPT_SHAPE",
    # pose
    "POSE_MARGIN",
    "POSE_ROOT",
    "POSE_RUNS",
    # paths
    "PROJECT_ROOT",
    "QUAD_ORDER",
    "ROWS",
    "SHARED_RAW",
    "SHARED_ROOT",
    "SHARED_TEMPLATES",
    "STATES",
    "STATE_CN",
    "STATE_ENDGAME",
    "STATE_LIFT",
    "STATE_MATE",
    "STATE_OPENING",
    "VALID_STATES",
    "WEIGHTS_DIR",
    # ui
    "Param",
    "_draw_boxes",
    "_draw_corners",
    "_dst_points",
    "_letterbox",
    "_parse_adb_devices",
    "_start_squares",
    "_stats",
    "adb_screenshot",
    "append_lift_label",
    "build_all_template_sets",
    # metrics
    "collect_train_metrics",
    "corner_visibility_for_state",
    "corners_from_pose",
    "correct_board",
    "corrected_center",
    "crop_cell",
    "cut_template_set_from_image",
    "dedup_class",
    "ensure_template_sets",
    # io_utils
    "imread",
    "interactive_args",
    "iter_state_images",
    "label_map_for_lift",
    "label_map_for_lift_image",
    "label_map_for_state",
    "lift_label_csv",
    "list_adb_devices",
    "load_corners_json",
    "load_lift_labels",
    "load_template_sets",
    "match_board_with_best_set",
    "match_cell_in_corrected",
    "match_cell_to_class",
    "pose_bbox_from_corners",
    "prepare_output_dir",
    "resolve_adb_serial",
    "resolve_homography",
    "resolve_model",
    "save_adb_serial",
    "save_corners_json",
    # adb
    "saved_adb_serial",
    "state_cn",
    "state_dir",
]
