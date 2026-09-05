# YOLO 中国象棋棋子分类 + 棋盘四角定位训练工具

用 YOLO（Ultralytics）训练两个模型，替代硬编码的模板匹配 / 四角坐标：

1. **棋子分类（cls）**：逐格识别 16 类（14 棋子 + empty + lift），喂 64x64 格子图。
2. **棋盘四角定位（det，生产）**：检测 4 个角点框（TL/TR/BL/BR），取框心作角点。实测 MAE≈1.3px（235 张全状态验证），为**生产方案**。
3. **棋盘四角定位（pose，对比）**：检测 1 个棋盘框 + 4 个角点关键点（TL/TR/BL/BR）。实测关键点受长程回归 bias 影响，MAE≈33px，**仅保留作对比基线，不用于生产**。

产物导出为 ONNX，供 Android（ONNX Runtime Mobile）端部署。

## 环境

- Windows 11，Python 3.12
- 包管理：`uv`

```powershell
uv sync
uv run yolo-chess          # 交互式主菜单（或 uv run python -m yolo_chess）
```

程序入口是 questionary 交互菜单，依次选择「采集 -> 切模板 -> 切割格子 -> 去重 -> 建数据集 -> 训练 -> 导出 -> 验证」。

## 流程步骤（`src/yolo_chess/steps/`）

| 步骤 | 模块 | 说明 |
|---|---|---|
| 采集截图 | shared_collect | 用 adb 按棋局状态采集原始截图到 `shared/raw/<状态>/` |
| 切割棋子模板 | shared_templates | 从开局图切 14 子模板（残局自动匹配定位用） |
| 模板匹配验证 | templates_validate | 中间态人工审核模板匹配效果 |
| cls 切割逐格小图 | cls_cells | raw 截图 -> 64x64 逐格图 -> `cls/cells/` |
| cls 去重 | cls_dedup | 同类近重复合并 -> `cls/cells_dedup/` |
| cls 构建数据集 | cls_dataset | 划分 train/val -> `cls/dataset/` |
| cls 训练分类模型 | cls_train | Ultralytics 训练 + 导出 ONNX |
| cls 分类模型验证 | cls_validate | 用导出的 ONNX 整盘识别，逐图/逐状态汇总 |
| det 构建数据集 | det_dataset | 由四角真值生成 YOLO 检测标签（4 角点框） |
| det 训练四角模型 | det_train | Ultralytics 训练 + 导出 ONNX |
| det 四角精度验证 | det_validate | 逐图/分角/切格误差 + 置信余量诊断 |
| pose 构建数据集 | pose_dataset | 生成 YOLO-Pose 标签（1 棋盘框 + 4 关键点，可见性按状态判定） |
| pose 训练四角模型 | pose_train | yolo11n-pose 训练 + 导出 `board_pose.onnx` |
| pose 四角精度验证 | pose_validate | 与 det 对齐的逐图/分角/切格误差 + 置信余量诊断 |

## 目录结构

```
yolo/
├── src/yolo_chess/        # 包：common/（共享子包）+ steps/（各步骤）+ cli.py（主菜单）
│   └── common/             # 子包：paths/board/classes/io_utils/adb/templates/vision/metrics/ui/pose
├── shared/raw/<状态>/     # 原始截图（opening/mate/lift/endgame）
├── shared/templates/      # 切出的 14 子模板套
├── cls/                     # cls 产物（cells/cells_dedup/dataset/runs/export/validate_output）
├── det/                     # det 产物（dataset/runs/export/validate_output）
├── pose/                    # pose 产物（dataset/runs/export/validate_output）
├── weights/               # Ultralytics 预训练权重（不入库）
├── tests/                 # common 纯函数测试
└── check.ps1              # ruff format + ruff check + ty check 一键检查
```

> **四角定位方案选择**：实测（同数据、同 yolo11n、同 imgsz=1280、同 100 epochs）`det` 角点 MAE≈1.3px 显著优于 `pose`≈33px——pose 用整盘大框 + 4 关键点，长程回归被系统性拉向棋盘中心。**生产用 `det`；`pose` 保留作对比基线**。两者最终都汇入 `cv2.getPerspectiveTransform` 透视矫正，`DEFAULT_CORNERS` 仍是真值来源与推理失败回退。

## 棋局状态

- `opening` 开局（32 子满盘）
- `mate` 绝杀（仅将帥初始位）
- `lift` 提子（将帥 + 提子点）
- `endgame` 残局（模板自动匹配标注）

## 常用命令

```powershell
.\check.ps1                     # ruff format + check + ty check
uv run pytest tests/ -q         # 单元测试
uv run yolo-chess               # 交互式运行各步骤
```

## 约定

- 依赖一律 `uv add`，勿手改 `pyproject.toml` 依赖后 `uv sync`
- 模型/数据集/截图产物均在 `.gitignore`，不入库；`weights/` 预训练权重也不入库
- 导出模型（`cls/export/`、`det/export/`、`pose/export/`）与 `model_info.json` 入库提交
