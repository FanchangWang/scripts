# xiangqi-bot — 中国象棋自动脚本

通过 ADB 控制 Android 手机（QQ/微信象棋等游戏）自动下中国象棋，提供**网页端**操作界面（FastAPI + 原生 JS/Canvas），手机/PC 浏览器均可访问。

## 功能特性

- **ADB 截图识别棋盘**：已知四角坐标做透视矫正，再对 14 张模板做模板匹配
- **pikafish 引擎计算走棋**：UCI 协议长进程复用，`go movetime` 固定时限思考
- **ADB 模拟点击落子**：点起子 → 间隔 → 点落子，多次截图校验
- **走棋失败自动恢复**：校验失败自动检测对局是否结束 / 整步重试 / 只落子重试（一次）
- **自动走棋**：预计算引擎着法，检测到对方走子后自动应棋
- **自动检测敌方走棋**（常开）：连续截图比对，识别敌方落子后自动分析并走棋
- **对局结束/认输检测**：棋子数骤降 + 连续帧确认，自动收局
- **网页棋盘**：Canvas 绘制棋盘、走棋高亮、日志面板、同步/开始/中断棋局

## 运行环境

- Windows 11，Python 3.12
- ADB（手机开启 USB 调试并授权；无线连接需先在本机 `adb connect ip:port` 配对）
- 支持分辨率：**1080x2400**、**1440x3200**（在 `config.py` 的 `BOARD_CORNERS` 查表，可扩展）

## 安装

依赖使用 `uv` 管理，禁止手改 `pyproject.toml` 依赖。

```powershell
uv init --package
uv add pure-python-adb opencv-python numpy fastapi "uvicorn[standard]"
uv add --dev ruff ty
```

## 运行

```powershell
uv run python -m xiangqi_bot
```

启动 FastAPI 服务（`0.0.0.0:8900`）并自动打开浏览器。局域网内手机浏览器访问 `http://<电脑IP>:8900` 亦可操作。

> 运行前确保手机已连接 ADB，且游戏画面停留在待对局的棋盘上。

## 网页操作流程

1. 打开连接页，`/api/devices` 列出设备：USB 设备直接「使用」；无线设备输入已配对 `ip:port` 连接
2. 连接成功进入主界面（自动走棋 / 自动检测敌方走棋**常开**，无开关）
3. 「同步棋局」：服务端截图识别棋盘并进入对局
   - **开局**（或刚开局局面：全棋子、对方仅走一步、轮到已方）→ **自动开始对弈**
   - 双方均偏离默认位或残局（棋子 < 20）→ 只载入棋盘，弹窗确认「是否开始棋局」
4. 对弈中可「中断棋局」，中断后「开始棋局」用当前棋盘数据恢复对弈
5. 任一处绝杀/认输判定 → 对局结束，可再「同步棋局」重开

## 目录结构

```
xiangqi-bot/
├── pyproject.toml              # uv 项目配置（依赖/ruff/ty）
├── check.ps1                   # 一键 ruff format + ruff check + ty check
├── src/xiangqi_bot/
│   ├── __init__.py / __main__.py   # 入口委派 main
│   ├── main.py                 # uvicorn 启动（0.0.0.0:8900，自动开浏览器）
│   ├── server.py               # FastAPI：静态托管 + REST API + WebSocket + 后台 worker
│   ├── game.py                 # 对局状态机（同步/开始/中断/自动对弈）
│   ├── config.py               # 常量、路径、阈值、四角坐标
│   ├── adb_client.py           # ppadb + adb.exe 封装（无终端交互）
│   ├── board.py                # 网格坐标、记谱/FEN 转换、开局默认格
│   ├── vision.py               # 透视矫正、模板匹配、两图对比
│   ├── engine.py               # pikafish UCI 长进程客户端
│   └── web/                    # 网页前端（index.html / app.js / style.css）
├── pikafish/
│   ├── pikafish-bmi2.exe       # 引擎（必须在其目录运行，依赖 pikafish.nnue）
│   └── pikafish.nnue
├── templates/*.png             # 14 张 60x60 棋子模板（从矫正棋盘切割，勿改）
├── raw_screenshots/            # 原始开局截图（脚本数据源，文件名含分辨率）
└── scripts/                    # regenerate_templates / compare_piece_templates /
                                # detect_board_corners
```

## 工作原理

1. `device.screencap()` 截取屏幕 PNG，`cv2.imdecode` 解码
2. 按分辨率查 `BOARD_CORNERS` 做透视矫正到 900x1000 棋盘空间
3. 对 90 个格子逐一与 14 张模板做 `matchTemplate`（TM_CCOEFF_NORMED）识别棋子
4. 布局转 FEN（ICCS 绝对坐标系，黑方在上，不随红黑方变化）
5. 调 pikafish（UCI：`position fen` + `go movetime`）计算着法
6. 矫正格心经逆单应映射回原图坐标，ADB 点击落子
7. 多次截图校验走棋结果，失败自动进入恢复流程（对局结束判定 / 整步重试 / 只落子重试）

### 坐标体系

| 表示 | 说明 |
|---|---|
| 网格 `(r, c)` | 固定于屏幕：`r` 行 0..9（0=最上），`c` 列 0..8（0=最左） |
| 屏幕坐标 `(x, y)` | 原始截图像素；点击用 `vision.tap_xy(H, r, c)` 逆透视映射 |
| 矫正坐标 | 透视矫正后的 900x1000 棋盘，格心 = `(50+100c, 50+100r)` |
| 记谱 `a-i/0-9` | ICCS 绝对坐标系，与 pikafish UCI 方块一致，不随红黑方变化 |
| FEN | 同记谱：ICCS 绝对坐标系，黑方在上，不随红黑方变化 |

关键结论：网格/矫正/屏幕坐标永远固定；网格 ↔ 记谱换算受红黑方影响；记谱 ↔ FEN 换算不受影响。

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

## API

- REST：`/api/devices`、`/api/connect`、`/api/disconnect`、`/api/sync`、`/api/start`、`/api/interrupt`、`/api/answer_turn`
- WebSocket：`/ws`（广播 `log` / `state` / `prompt_turn` / `connected` / `disconnected`）
- 静态：`/pieces/<id>.png`（模板图）、`/`（网页前端）

## 常用命令

```powershell
uv run python -m xiangqi_bot   # 启动网页服务（端口 8900，自动开浏览器）
.\check.ps1                    # 一键 ruff format + ruff check + ty check
uv run python scripts/regenerate_templates.py   # 从矫正棋盘重新切割模板
uv run python scripts/detect_board_corners.py <截图> [--save-board]  # 探测四角坐标
```

## 关键配置（config.py）

| 常量 | 默认值 | 说明 |
|---|---|---|
| `BOARD_CORNERS` | 查表 | 按分辨率查四角格中心坐标，透视矫正输入 |
| `CORRECT_CELL/W/H` | 100 / 900 / 1000 | 矫正棋盘尺寸 |
| `TAP_HOLD_INTERVAL_MS` | 400 | 点起子 → 点落子间隔 |
| `MOVE_SETTLE_MS` | 500 | 落子后校验截图前等待 |
| `MOVE_VERIFY_COUNT` | 3 | 走棋校验截图次数 |
| `RECOVERY_WAIT_MS` | 500 | 走棋失败恢复：棋子被提起后的二次确认延迟 |
| `ENGINE_MOVETIME_MS` | 1000 | 引擎思考时间（`go movetime`） |
| `ENGINE_THREADS` | 12 | 引擎线程数 |
| `ENGINE_HASH_MB` | 2048 | 引擎哈希（MB） |
| `ENGINE_MATE_PROBE_MS` | 200 | 绝杀探测短时限 |
| `AUTO_DETECT_INTERVAL_MS` | 0 | 自动检测敌方走棋截图间隔（无额外延时，无次数限制） |
| `RESIGN_PIECE_DROP_THRESHOLD` | 3 | 可识别棋子数比内存布局少几枚判对局结束 |
| `RESIGN_CONFIRM_COUNT` | 3 | 疑似结束画面需连续帧数 |
| `RESIGN_SUSPECT_WAIT_MS` | 1000 | 单帧疑似结束时延时再采样（过滤瞬态误判） |
| `ENDGAME_PIECE_COUNT` | 20 | 可识别棋子数低于该值视为残局 |
| `DIFF_THRESHOLD` / `MATCH_SEARCH_HALF` / `EMPTY_MATCH_THRESHOLD` | 8 / 10 / 0.8 | 图片识别阈值 |
