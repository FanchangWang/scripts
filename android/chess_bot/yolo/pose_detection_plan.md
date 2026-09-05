# YOLO Pose 检测方案（设计稿 v2 · 决策已锁定）

> 状态：**已实现**（阶段 A + 阶段 B 全部落地）。common 包化（paths/board/classes/io_utils/adb/templates/vision/metrics/ui/pose 子模块 + 门面 re-export）、pose 三步流水线、det_validate 共用 vision helper 均已落地。`ruff` / `ty` / `pytest`(27 passed) 全绿。
> 目标：在现有 `cls` / `det` 检测基础上，**新增一套 YOLO Pose 流水线**，用「1 个棋盘框 + 4 个角点关键点」替代 `det` 的「4 个角点检测框」方案，实现棋盘四角定位。

> ⚠️ **实测结论（2026-09-05，已在真实数据验证，pose 不达标）**
> 同一批原始数据、同一 `yolo11n`、`imgsz=1280`、`epochs=100/batch=8`，pose 远逊 det：
>
> | 指标（同 235 张全状态验证集） | det | pose |
> |---|---|---|
> | 角点 MAE | **1.33px** | 32.65px |
> | 角点 P95 | 2.43px | 48.29px |
> | 格心位移 P95 | 1.75px | ~24px |
> | verdict | ✅ 合格 | ❌ 不合格 |
>
> 根因：**任务建模失败**。pose 用「整盘大框 + 4 关键点」，Ultralytics 关键点按框中心所在锚格回归，4 角需对距中心 ~440px 做长程 DFL 回归，74 张近似同构图让头收敛到「平均几何」，角点系统性被拉向棋盘中心（分角 |bias| 17~45px 而 std 仅 4px）；det 把每角编码为 130px 独立小框、框心即角点，DFL 只需短程 refinement，故 1px。
> **决策：生产保留 det；pose 代码/流水线保留作对比基线，不再推进。** mAP(=0.995) 因大框尺度归一而虚高，评估一律以像素级 `_validate` 为准。

---

## 1. 方案概述

### 与 det 的关系
- `det`：检测 4 个角点框（TL/TR/BL/BR，4 类），推理时对每类取最高分框的框心作为角点。
- `pose`：检测 **1 个棋盘框**（主目标框）+ **4 个关键点**（即 TL/TR/BL/BR 坐标）。推理直接由关键点给出 4 角，省去「每类 argmax」步骤。⚠ 实测该方案关键点回归受长程 DFL bias 影响，精度远逊 det（见顶部实测结论）。
- **定位函数不变**：无论 det 还是 pose，最终都拿到 4 个角点像素坐标 → `cv2.getPerspectiveTransform` → `correct_board`。`DEFAULT_CORNERS` 仍是数据集真值来源与推理失败回退。

### 已锁定决策（D1–D5）
| 决策点 | 结论 |
|---|---|
| D1 外扩语义 | 以 4 角包围盒为基准，四边各外扩 **100px** 后 clamp 到 `[0,w]/[0,h]` |
| D2 定位切换 | 保持「Python 用查表 `resolve_homography` / 部署用 pose ONNX」。Python 侧 `correct_board` 不改，新增 `corners_from_pose` 仅作部署等价入口 |
| D3 det 去留 | **保留** `det_*` 作为对比基线 |
| D4 共享 helper | 抽取到 `common/vision.py`；并给出 common.py 防膨胀拆分方案（见 §5） |
| D5 可见性 | 按状态查 `label_map_for_state` 自动判定：角格有棋子 → `v=1`（遮挡但位置已知），否则 → `v=2`（可见）。详见 §6 |

### 关键参数（常量）
| 项 | 值 |
|---|---|
| 主目标框 | `chessboard` 棋盘（1 类，class 0）|
| 关键点数量 | 4 |
| 关键点顺序 | TL(左上) → TR(右上) → BL(左下) → BR(右下)，与 `DEFAULT_CORNERS` 顺序一致 |
| 外扩像素 | 棋盘框由 4 角外扩 **100px**，越界 clamp 到 `[0, w]` / `[0, h]` |
| 关键点坐标 | 即 `DEFAULT_CORNERS` 的 4 个角点坐标（不扩）|
| kpt 维度 | `kpt_shape: [4, 3]`（x, y, visibility）|

---

## 2. 文件目录结构（新增 / 变更）

```
yolo/
├── src/yolo_chess/
│   ├── common.py                 # 【改→逐步瘦身】先作为兼容门面，长期拆为 common/ 子包（见 §5）
│   ├── common/                   # 【新·防膨胀】共享子包（§5 详述）
│   │   ├── __init__.py           # 向后兼容 re-export
│   │   ├── vision.py             # 【新】_letterbox / _stats / _draw_corners / _draw_boxes（det/pose 共用）
│   │   └── pose.py               # 【新】POSE_* 常量 + pose_bbox_from_corners + corner_visibility_for_state + corners_from_pose
│   ├── cli.py                    # 【改】注册 3 个 pose 步骤到主菜单
│   └── steps/
│       ├── det_dataset.py        # 【不动】
│       ├── det_train.py          # 【不动】
│       ├── det_validate.py       # 【改】_letterbox/_stats/_draw_* 改为从 common.vision 导入（逻辑不变）
│       ├── pose_dataset.py       # 【新】构建 pose 数据集（bbox+4kpt 标签，可见性按状态判定）
│       ├── pose_train.py         # 【新】训练 yolo11n-pose 并导出 ONNX
│       └── pose_validate.py      # 【新】校验 pose ONNX 的角点像素精度
├── pose/                         # 【新】pose 流水线产物（gitignore，同 det/）
│   ├── dataset/                  # images/{train,val} + labels/{train,val} + data.yaml
│   ├── runs/                     # Ultralytics 训练输出
│   ├── export/                   # board_pose.onnx + model_info.json（入库）
│   └── validate_output/          # 可视化 + _summary.txt
├── pyproject.toml                # 【不改依赖】ultralytics>=8.4.138 已支持 pose
├── README.md                     # 【改】补充 pose 流水线说明与目录树
└── weights/
    └── yolo11n-pose.pt           # 【需下载】pose 专用预训练权重（gitignore）
```

> 依赖结论：**无需 `uv add`**。Pose 是 Ultralytics 内置 task，现有 `ultralytics>=8.4.138` 已覆盖。仅需要把 `yolo11n-pose.pt` 放进 `weights/`。

---

## 3. uv project 源码变动结构

| 文件 | 动作 | 主要内容 |
|---|---|---|
| `src/yolo_chess/common/vision.py` | 新 | 共享推断/绘图 helper：`_letterbox`、`_stats`、`_draw_corners`、`_draw_boxes`（从 det_validate 迁出）|
| `src/yolo_chess/common/pose.py` | 新 | `POSE_ROOT/POSE_DATASET/POSE_RUNS/POSE_EXPORT`、`POSE_MARGIN=100`、`KPT_ORDER`、`POSE_KPT_SHAPE=[4,3]`、`CORNER_CELLS`、`pose_bbox_from_corners()`、`corner_visibility_for_state()`、`corners_from_pose()` |
| `src/yolo_chess/steps/det_validate.py` | 改 | 删除本地 `_letterbox/_stats/_draw_*` 定义，改为 `from yolo_chess.common.vision import ...`（行为不变）|
| `src/yolo_chess/steps/pose_dataset.py` | 新 | 镜像 `det_dataset`：由 `DEFAULT_CORNERS` 生成外扩 bbox + 4 关键点（可见性按 `corner_visibility_for_state`）；写 `data.yaml`(`task:pose, nc:1, kpt_shape:[4,3]`) |
| `src/yolo_chess/steps/pose_train.py` | 新 | 镜像 `det_train`：基模型 `yolo11n-pose.pt`，同增强方案，导出 `board_pose.onnx`，`model_info.json` 注明 pose 输出布局 |
| `src/yolo_chess/steps/pose_validate.py` | 新 | 镜像 `det_validate`：加载 pose ONNX，letterbox 推理，解码 bbox+4 关键点，与 `DEFAULT_CORNERS` 比像素误差/格心位移 |
| `src/yolo_chess/cli.py` | 改 | `STEPS` 追加 `pose_dataset` / `pose_train` / `pose_validate`（置于 det 三步之后）|
| `README.md` | 改 | 目录树增加 `pose/` 与 `common/`，流程表增加 pose 三步；实测后 pose 不达标，说明「det 为生产四角定位方案，pose 作对比保留」|

---

## 4. 关键实现要点

### 4.1 数据集标签（pose 格式）
每行：`class cx cy w h x1 y1 v1 x2 y2 v2 x3 y3 v3 x4 y4 v4`（均为归一化值）。
- `class = 0`（chessboard）
- bbox：由 4 角算出的**外扩 100px** 框，`cx,cy,w,h`
- 4 关键点：严格按 `DEFAULT_CORNERS` 的 `(TL, TR, BL, BR)` 顺序，可见性由 `corner_visibility_for_state(state)` 给出（`v=2` 可见 / `v=1` 遮挡）

外扩框 + 可见性逻辑（新增到 `common/pose.py`）：
```python
CORNER_CELLS = [
    (0, 0),
    (0, COLS - 1),
    (ROWS - 1, 0),
    (ROWS - 1, COLS - 1),
]  # 4 角对应网格(車/俥初始位)，与 DEFAULT_CORNERS 一致


def pose_bbox_from_corners(corners, w, h, margin=100) -> tuple[float, float, float, float]:
    xs, ys = corners[:, 0].astype(float), corners[:, 1].astype(float)
    x1 = max(0.0, float(xs.min()) - margin)
    y1 = max(0.0, float(ys.min()) - margin)
    x2 = min(float(w), float(xs.max()) + margin)
    y2 = min(float(h), float(ys.max()) + margin)
    return x1, y1, x2, y2


def corner_visibility_for_state(state: str) -> list[int]:
    """角格有棋子(非 empty/lift)→1(遮挡但位置已知)，否则→2(可见)。"""
    lmap = label_map_for_state(state)
    vis = []
    for r, c in CORNER_CELLS:
        occ = lmap.get((r, c))
        vis.append(1 if occ not in (None, "empty", "lift") else 2)
    return vis
```
> 可见性推导依据：本域 4 角恒对应网格 (0,0)/(0,8)/(9,0)/(9,8)，即开局 車/俥 初始位；`label_map_for_state` 已声明各状态每格内容，零成本复用即可判定遮挡。

### 4.2 训练
- 基模型：`resolve_model("yolo11n-pose.pt")`（与 `yolo11n.pt` 不同文件）
- `data.yaml` 关键字段：`task: pose`、`nc: 1`、`names: ['chessboard']`、`kpt_shape: [4, 3]`
- 增强方案沿用 `det_train` 的 `precise/robust`
- metrics 收集（pose 专用列）：`metrics/mAP50(P)`、`metrics/mAP50-95(P)`、`metrics/mAP50(box)`、`train/box_loss`、`train/pose_loss`、`train/dfl_loss`

### 4.3 ONNX 推理解码（**最关键，需实测校验**）
Ultralytics pose ONNX 导出输出按 `(4 + nc + 3*nk, N)` 布局（N=候选数，如 8400 @640）。本配置 `nc=1, nk=4` → 每候选 **17 通道**：
```
[cx, cy, w, h,  cls_conf,  kp1x,kp1y,kp1c, kp2x,kp2y,kp2c, kp3x,kp3y,kp3c, kp4x,kp4y,kp4c]
```
解码步骤：
1. `out.reshape(17, -1).T` → 每行 1 个候选
2. 取 `cls_conf` 最高（或 `>conf_thr`）的候选作为棋盘
3. bbox = `row[0:4]`；keypoints = `row[5:].reshape(4,3)` → `(x,y,conf)`
4. 关键点坐标由 letterbox 空间反变换回原图：`kx0 = (kx-left)/r, ky0 = (ky-top)/r`
5. 4 关键点（顺序 TL,TR,BL,BR）即角点 → 代入 `getPerspectiveTransform`

> ⚠ **校验点**：实际部署前先用一次真实导出跑单张推理，打印 `out.shape` 确认通道顺序（不同 ultralytics 版本/导出参数可能微调 cls/conf 顺序）。该步骤列入 T5 子项，未验证前不写死解码。

### 4.4 集成入口 `corners_from_pose`（部署等价）
`common/pose.py` 新增：
```python
def corners_from_pose(onnx_path: str, img: np.ndarray) -> np.ndarray:
    """pose ONNX 推断 4 角（TL,TR,BL,BR 顺序），返回 (4,2) 像素坐标；失败抛异常由调用方回退查表。"""
    # letterbox -> ort 推理 -> 解码 17 通道 -> 反变换 -> 4 角
```
Android 端部署即调用等价 pose ONNX：取 4 关键点 → 透视矫正。Python 侧 `resolve_homography` 按 D2 **不切换**。

---

## 5. common.py 防膨胀拆分方案（D4 优化）

当前 `common.py` 已 ~933 行，混杂 14 类职责（路径/权重、adb、棋盘几何、类定义、状态、IO、标注映射、矫正、模板、模板匹配、去重、指标解析、交互 UI）。继续往里加 pose 常量只会加剧。

### 5.1 目标结构：拆为 `common/` 子包
```
src/yolo_chess/
├── common/                         # 子包，替代单文件
│   ├── __init__.py                 # 向后兼容 re-export（旧 `from yolo_chess.common import X` 全部不动）
│   ├── paths.py                    # 目录常量 + resolve_model
│   ├── board.py                    # 棋盘几何 + corrected_center/crop_cell + DEFAULT_CORNERS/homography/correct_board
│   ├── classes.py                  # CLASSES/CLASS_CN/CLASS_IDX + 状态 STATES + label_map_*
│   ├── io_utils.py                 # imread / prepare_output_dir / iter_state_images
│   ├── adb.py                      # adb 设备/截图
│   ├── templates.py                # 模板套 切/载/ensure + 模板匹配 + dedup_class
│   ├── vision.py                   # 【本期落地】共享推断/绘图 helper（det/pose 共用）
│   ├── metrics.py                  # collect_train_metrics
│   ├── ui.py                       # Param / interactive_args
│   └── pose.py                     # 【本期落地】POSE_* 常量 + corners_from_pose + 可见性逻辑
```

### 5.2 落地节奏（分两阶段，互不阻塞）
- **阶段 A（本期随 pose 一起做，低风险）**：
  1. 新建 `common/vision.py`，把 `det_validate` 里的 `_letterbox`/`_stats`/`_draw_corners`/`_draw_boxes` 迁入；`det_validate` 改为 import；`pose_validate` 直接 import。
  2. 新建 `common/pose.py`，承载所有 pose 相关新增（§3）。
  3. `common/__init__.py` 立即 re-export 这两个新模块的符号，保证旧 import 不破。
- **阶段 B（独立重构任务，可后续单独排期）**：
  将 `paths/board/classes/io_utils/adb/templates/metrics/ui` 逐个从 `common.py` 迁出，每迁一个模块就跑一次 `ty check` + `ruff`，确认无破坏再继续。`common.py` 最终退化为只含 `__init__` 门面（或整体重命名为 `common/__init__.py`）。

### 5.3 防膨胀护栏（写入约定）
- 新增共享常量/工具 **优先进对应子模块**，禁止无脑追加到 `common.py` 根部。
- 凡 `steps/*` 间可复用的推断/绘图/IO helper，一律进 `common/vision.py` 或 `common/io_utils.py`，不在各 step 内复制。
- 每个模块迁出都是独立可验证步骤（ty + ruff 绿），不在一次提交里大改。

---

## 6. D5 可见性推荐（详细）

### 现象
- 空棋盘：4 角格为空 → 角点清晰可见。
- 有棋子棋盘：4 角格（車/俥 初始位）可能被棋子**遮挡**。

### 推荐方案（已写入 §4.1）
**按状态自动判定可见性**：角格有棋子（非 `empty`/`lift`）→ `v=1`（遮挡但位置已知，仍参与 pose 监督）；角格为空 → `v=2`（可见）。

推荐理由：
1. **零成本**：4 角恒对应网格 (0,0)/(0,8)/(9,0)/(9,8)，`label_map_for_state` 已声明各状态每格内容，直接查表即得，无需额外检测。
2. **监督一致**：被遮挡角点仍用真值 `DEFAULT_CORNERS` 坐标监督（`v=1` 在 Ultralytics 中仍计入 pose loss），模型学到「角点恒在此处」的几何先验，对偶发遮挡稳健；同时诚实标记遮挡，避免模型把棋子误当角点特征。
3. **不推荐 `v=0`**：`v=0` 表示「未标注、不参与 loss」，会让同一角点在不同图里时而监督、时而不监督，破坏固定棋盘域的一致性。

### 各状态可见性预期
| 状态 | 4 角格内容 | 可见性 |
|---|---|---|
| opening（满盘）| 4 角均为車/俥 | `[1,1,1,1]` |
| mate（仅将帥）| 4 角空 | `[2,2,2,2]` |
| lift（提子）| 4 角空 | `[2,2,2,2]` |
| endgame（残局）| 不定，按标注 | 角格有子→1，否则→2 |

### 简化回退（可选）
若你更看重简单、不想要状态分支：可统一填 `v=2`（始终监督）。固定棋盘域下模型主要靠空间先验，uniform `v=2` 也能工作，只是少了「遮挡诚实标注」这一层。默认采用上面的**按状态判定**方案。

---

## 7. Todo 任务列表（更新）

**阶段 A — pose 实现 + 共享 helper（已完成）**
- [x] **T0 · 共享 helper 抽取** — 新建 `common/vision.py`（迁入 `_letterbox/_stats/_draw_corners/_draw_boxes`），`det_validate` 改为 import；新建 `common/pose.py` 承载 pose 常量与 `corners_from_pose`；`common/__init__.py` 作 re-export 门面。
- [x] **T1 · 脚手架常量** — `common/pose.py` 落地 `POSE_*` 目录、`POSE_MARGIN=100`、`KPT_ORDER`、`POSE_KPT_SHAPE=[4,3]`、`CORNER_CELLS`、`pose_bbox_from_corners()`、`corner_visibility_for_state()`；`weights/yolo11n-pose.pt` 已就位。
- [x] **T2 · pose_dataset** — 新建 `steps/pose_dataset.py`：由 `DEFAULT_CORNERS` 生成外扩 bbox + 4 关键点（可见性走 `corner_visibility_for_state`），写 `data.yaml`（`task:pose, kpt_shape:[4,3]`），复用 `_ascii_stem`/nsmap/触边检查。
- [x] **T3 · pose_train** — 新建 `steps/pose_train.py`：`yolo11n-pose.pt` 训练 + 导出 `board_pose.onnx` + `model_info.json`（含 pose 输出布局）。
- [x] **T4 · pose_validate 框架** — 新建 `steps/pose_validate.py`：letterbox、`_stats`、误差/格心位移统计、可视化，结构镜像 `det_validate`。
- [x] **T5 · pose ONNX 解码** ⚠ — 按文档化 17 通道布局实现解码（`_decode_pose_row` + `corners_from_pose`）；⚠ **运行时校验待办**：首次实跑导出推理、打印 `out.shape` 确认通道顺序（已在代码注释标注 T5 校验点）。
- [x] **T6 · cli 注册** — `cli.py` 追加 `pose_dataset/pose_train/pose_validate` 三步。
- [x] **T7 · 集成入口** — `common/pose.py` 实现 `corners_from_pose()`（按 §4.4，D2 不接 `resolve_homography`）。
- [x] **T8 · 文档** — 更新 `README.md` 目录树与流程表；实测后「det 为生产四角定位方案、pose 作对比保留」。
- [x] **T9 · 质量门** — `ruff` / `ty` / `pytest`(27 passed) 全绿。

**阶段 B — common.py 全量拆分（已完成，本期纳入）**
- [x] **TB · 子包拆分** — `common.py` 转为 `common/__init__.py` 门面，按 `paths/board/classes/io_utils/adb/templates/vision/metrics/ui/pose` 迁出并 re-export；下游 `from yolo_chess.common import X` 全部不破（已用 AST 枚举 62 个符号校验）。

---

## 8. 仍待你最终确认
- D5 可见性采用「**按状态判定 v=1/v=2**」（默认），还是简化为「统一 v=2」？
- 阶段 B（common.py 全量拆分）是否纳入本期，还是作为独立后续任务？

确认后我开始实现 T0–T9（阶段 A）。
