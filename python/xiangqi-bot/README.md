# xiangqi-bot — 中国象棋自动脚本

用 Python 编写，通过 ADB 控制 Android 手机（QQ/微信象棋等游戏）自动下中国象棋。

## 功能特性

- **ADB 截图识别棋盘**：OpenCV 模板匹配，固定 1080x2400 分辨率
- **pikafish 引擎计算走棋**：UCI 协议，支持 `movetime` 固定时限思考
- **ADB 模拟点击落子**：点起子 → 间隔 → 点落子
- **走棋校验**：落子后多次截图确认，识别失败自动重试/绝杀判定
- **自动走棋**（默认开）：预计算引擎着法，检测到对方走子后自动应棋
- **自动检测敌方走棋**（默认开）：定时截图比对，检测对方落子后自动分析并走棋
- **彩色终端棋盘**：红黑异色、行列表记谱标注、高亮变动棋子

## 运行环境

- Windows 11
- Python 3.12
- ADB（手机开启 USB 调试并授权）
- 手机分辨率 **1080x2400**（不匹配则脚本结束）

## 安装

依赖使用 `uv` 管理，禁止手改 `pyproject.toml` 依赖。

```powershell
uv init --package
uv add pure-python-adb opencv-python numpy
uv add --dev ruff ty
```

## 运行

```powershell
uv run python -m xiangqi_bot
```

> 运行前确保手机已连接 ADB，且游戏画面停留在待对局的棋盘上。

### 主菜单操作

```
==== 中国象棋自动脚本 ====
[回车] 初始化对局
[1]    自动走棋：开          # 按 1 切换开/关
[2]    自动检测敌方走棋：开   # 按 2 切换开/关
[q]    退出脚本
```

> 自动走棋 / 自动检测敌方走棋为**会话级开关**（默认开），跨对局生效。

### 对局内操作

初始化对局后自动判断我方红/黑方，并按红黑方显示对应子菜单：

```
==== 我方为红方 ====                    ==== 我方为黑方 ====
[回车] 走棋                            [回车] 拉取新棋盘数据
[1]    走棋                            [1]    走棋
[2]    拉取新棋盘数据                    [2]    拉取新棋盘数据
[3]    初始化对局                        [3]    初始化对局
[q]    退出脚本                          [q]    退出脚本
```

## 目录结构

```
xiangqi-bot/
├── pyproject.toml              # uv 项目配置（依赖/ruff/ty）
├── check.ps1                   # 一键 ruff format + ruff check + ty check
├── src/xiangqi_bot/
│   ├── __init__.py
│   ├── config.py               # 常量、路径、阈值
│   ├── console.py              # 终端输入（EOF 容错）
│   ├── adb_client.py           # ppadb 封装：设备选择、截图、点击、wm size
│   ├── board.py                # 网格坐标、记谱/FEN 转换、棋盘状态
│   ├── vision.py               # 模板匹配、整盘分析、两图对比
│   ├── engine.py               # pikafish UCI 客户端
│   ├── printer.py              # 终端彩色棋盘打印
│   └── app.py                  # 主流程 / 菜单 / 子流程编排
├── pikafish/
│   ├── pikafish-bmi2.exe       # 引擎（必须在其目录运行）
│   └── pikafish.nnue           # 神经网络权重
├── templates/*.png             # 14 张 60x60 棋子模板
└── scripts/                    # visualize_grid.py / extract_piece_templates.py
```

## 工作原理

1. `device.screencap()` 截取屏幕 PNG，`cv2.imdecode` 解码
2. 对 90 个格子逐一与 14 张模板做 `matchTemplate`（TM_CCOEFF_NORMED）识别棋子
3. 布局转 FEN（ICCS 绝对坐标系，黑方在上，不随红黑方变化）
4. 调 pikafish（UCI：`position fen` + `go movetime`）计算着法
5. 用固定网格中心点坐标 `GRID_CENTERS_NP` 换算屏幕坐标，ADB 点击落子
6. 多次截图校验走棋结果，失败则按内存布局试走探测绝杀/困毙

### 坐标体系

| 表示 | 说明 |
|---|---|
| 网格 `(r, c)` | 固定于屏幕：`r` 行 0..9（0=最上），`c` 列 0..8（0=最左） |
| 屏幕坐标 `(x, y)` | `GRID_CENTERS_NP[r][c]`，浮点 |
| 记谱 `a-i/0-9` | ICCS 绝对坐标系，与 pikafish UCI 方块一致，不随红黑方变化 |
| FEN | 同记谱：ICCS 绝对坐标系，黑方在上，不随红黑方变化 |

关键结论：网格与屏幕坐标永远固定；网格 ↔ 记谱换算受红黑方影响；记谱 ↔ FEN 换算不受影响。

### 棋子模板

`templates/` 下 14 张 60x60 模板，命名带 `b_`（黑小写）/`r_`（红大写）前缀：

| 模板 | 棋子 | 中文 | FEN |
|---|---|---|---|
| b_r.png | 黑车 | 車 | r |
| b_n.png | 黑马 | 馬 | n |
| b_b.png | 黑象 | 象 | b |
| b_a.png | 黑士 | 士 | a |
| b_k.png | 黑将 | 將 | k |
| b_c.png | 黑砲 | 砲 | c |
| b_p.png | 黑卒 | 卒 | p |
| r_R.png | 红俥 | 俥 | R |
| r_N.png | 红傌 | 傌 | N |
| r_B.png | 红相 | 相 | B |
| r_A.png | 红仕 | 仕 | A |
| r_K.png | 红帥 | 帥 | K |
| r_C.png | 红炮 | 炮 | C |
| r_P.png | 红兵 | 兵 | P |

## 常用命令

```powershell
uv run python -m xiangqi_bot   # 运行脚本
.\check.ps1                    # 一键 ruff format + ruff check + ty check
```

## 关键配置（config.py）

| 常量 | 默认值 | 说明 |
|---|---|---|
| `TARGET_RESOLUTION` | 1080x2400 | 设备分辨率，不匹配则脚本结束 |
| `TAP_HOLD_INTERVAL_MS` | 300 | 点起子 → 点落子间隔 |
| `MOVE_SETTLE_MS` | 500 | 落子后校验截图前等待 |
| `MOVE_VERIFY_COUNT` | 3 | 走棋校验截图次数（全部失败才判定失败） |
| `ENGINE_MOVETIME_MS` | 1000 | 引擎思考时间（`go movetime`） |
| `ENGINE_THREADS` | 12 | 引擎线程数 |
| `ENGINE_HASH_MB` | 2048 | 引擎哈希（MB） |
| `ENGINE_MATE_PROBE_MS` | 200 | 绝杀探测短时限 |
| `AUTO_DETECT_INTERVAL_MS` | 500 | 自动检测敌方走棋截图间隔 |
| `AUTO_DETECT_MAX_COUNT` | 30 | 最大检测次数（30 x 0.5s = 15s） |
