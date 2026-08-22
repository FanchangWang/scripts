# xiangqi-bot — 中国象棋自动脚本

通过 ADB 控制 Android 手机（QQ/微信象棋等游戏）自动下中国象棋，提供**网页端**操作界面（FastAPI + 原生 JS/Canvas），手机/PC 浏览器均可访问。

## 功能特性

- **ADB 截图识别棋盘**：已知四角坐标做透视矫正，再对 14 张模板做模板匹配
- **pikafish 引擎计算走棋**：UCI 协议长进程复用，`go movetime` 固定时限思考；正确维护 FEN halfmove clock，引擎感知自然限招（60 步不吃子判和），优势残局主动避和
- **ADB 模拟点击落子**：点起子 → 间隔 → 点落子，`MOVE_VERIFY_COUNT=5` 帧逐帧分类校验
- **走棋校验分类**：按变动格数 `n` 分类（0/1/2/3/4/>4），命中即写入内存；提子未落补点重跑
- **走棋失败重试**：`SELF_MOVE_ATTEMPTS=2` 次整步重试上限；外层重新点击（完全不动）或补点重跑（提起未落）
- **自动走棋**：预计算引擎着法，检测到敌方走子后自动应棋
- **自动检测敌方走棋**（常开）：连续截图比对，识别敌方落子后自动分析并走棋；噪声帧延时复检
- **对局结束/认输检测**：双方将/帅同时缺失 + 连续 `RESIGN_CONFIRM_COUNT=3` 帧确认，自动收局
- **和棋智能决策**：同时识别到「和棋_同意」「和棋_拒绝」两按钮才认定为和棋弹窗；复用我方上一步走棋的引擎评估分（`info score cp`），我方优势超过 1000cp 拒绝，均势/劣势同意；不额外搜索，无延时
- **自动下一局**：对局结束后扫描结算文字（晋级赛/重新挑战/再来一局/下一关/段位提升/铜钱/领取），
  自动点击按钮或发返回键；scan/setup 状态机等待摆棋完毕再自动开始对弈；网页端**开关**可随时切换
  （对局结束判定时取最新值）
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
3. 点击「开始棋局」-> `/api/start` 截图识别棋盘并开始对弈
   - **开局**（或刚开局局面：全棋子、对方仅走一步、轮到已方）→ **自动开始对弈**
   - 双方均偏离默认位或残局（棋子 < `ENDGAME_PIECE_COUNT`）→ 只载入棋盘，弹窗确认「是否开始棋局」
4. 对弈中可「中断棋局」，中断后「开始棋局」用当前棋盘数据恢复对弈
5. 任一处绝杀/认输判定 → 对局结束；「自动下一局」开关开启（默认）时自动扫描结算文字
   （按钮类点击 / 段位提升/铜钱/领取发返回键）→ 等待摆棋完毕 → 自动开始下一局；开关可随时切换，
   对局结束判定时取最新值，中止或失败可「开始棋局」重开

### 流程按钮状态矩阵

| 状态 | 开始/中断棋局 |
|---|---|
| `idle` / `over`（刚连接/已结束） | 禁用 |
| `red` / `black` / `auto_next`（对弈中） | 可用（中断棋局） |
| `stopped`（残局已同步/中断后） | 可用（开始棋局，自动先同步） |

> `AUTO_NEXT_GAME` 开启时对局结束进入 `auto_next` 状态（按钮保持对弈中形态），中止后才切到 `over`。

## 目录结构

```
xiangqi-bot/
├── pyproject.toml                  # uv 项目配置（依赖/ruff/ty）
├── check.ps1                       # 一键 ruff format + ruff check + ty check
├── src/xiangqi_bot/
│   ├── __init__.py / __main__.py   # 入口委派 main
│   ├── main.py                     # uvicorn 启动（0.0.0.0:8900，自动开浏览器）
│   ├── server.py                   # FastAPI：静态托管 + REST API + WebSocket + 后台 worker
│   ├── config.py                   # 常量、路径、阈值、四角坐标
│   ├── adb_client.py               # ppadb + adb.exe 封装（无终端交互）
│   ├── board.py                    # 网格坐标、记谱/FEN 转换、开局默认格
│   ├── vision.py                   # 透视矫正、模板匹配、两图对比
│   ├── engine.py                   # pikafish UCI 长进程客户端
│   ├── game/                       # 对局模块（数据结构 + 纯函数 + IO 类 + 薄控制层）
│   │   ├── __init__.py             # 导出 GameSession
│   │   ├── state.py                # Side/Move/Change/GameState/FrameResult/VerifyOutcome
│   │   ├── opening.py              # 开局分析纯函数（detect_side/detect_phase/infer_turn）
│   │   ├── moves.py                # 走法推断/应用/格式化纯函数
│   │   ├── classifier.py           # 帧分类纯函数（self/enemy 帧分类 + 认输疑似判断）
│   │   ├── recognition.py          # 棋盘识别纯函数（矫正图 → 布局+变动）
│   │   ├── draw.py                 # 和棋决策纯函数
│   │   ├── capture.py              # Capture IO 类（截图/矫正/点击/和棋弹窗）
│   │   ├── auto_next.py            # AutoNext IO 类（结算交互 + 等待摆棋）
│   │   └── session.py              # GameSession 薄控制层（编排主循环，不继承任何类）
│   └── web/                        # 网页前端（index.html / app.js / style.css）
├── pikafish/
│   ├── pikafish-bmi2.exe           # 引擎（必须在其目录运行，依赖 pikafish.nnue）
│   └── pikafish.nnue
├── templates/*.png                 # 14 张 60x60 棋子模板（从矫正棋盘切割，勿改）
├── templates/text/*.png            # 结算文字模板（下一关/晋级赛/重新挑战/再来一局/段位提升/铜钱/领取）
├── raw_screenshots/                # 原始开局截图 + 结算截图（脚本数据源，文件名含分辨率）
├── scripts/                        # regenerate_templates / compare_piece_templates /
│                                   # detect_board_corners / generate_text_templates
└── tests/                          # pytest 测试（10 个文件）
    ├── conftest.py                 # 共享 fixture + mock vision
    ├── test_engine.py              # 引擎客户端
    ├── test_fresh.py               # 开局轮次推断
    ├── test_prompt.py              # 弹窗确认
    ├── test_next.py                # 自动下一局
    ├── test_eat_after_self_move.py # 吃子 + 敌方反吃
    ├── test_capture.py             # 走棋校验 + 重试流程（12 场景）
    ├── test_noisy.py               # 敌方走棋检测 + 噪声（6 场景）
    ├── test_probe.py               # 绝杀探测（3 场景）
    └── test_resign.py              # 认输检测（4 场景）
```

## 工作原理

1. `device.screencap()` 截取屏幕 PNG，`cv2.imdecode` 解码
2. 按分辨率查 `BOARD_CORNERS` 做透视矫正到 900x1000 棋盘空间
3. 对 90 个格子逐一与 14 张模板做 `matchTemplate`（TM_CCOEFF_NORMED）识别棋子
4. 布局转 FEN（ICCS 绝对坐标系，黑方在上，不随红黑方变化；第六字段 halfmove clock 记录自上次吃子的半回合数）
5. 调 pikafish（UCI：`position fen` + `go movetime`）计算着法；引擎启动时设 `Rule60MaxPly=60`，配合 halfmove clock 感知自然限招；每局开始发 `ucinewgame` 清 hash
6. 矫正格心经逆单应映射回原图坐标，ADB 点击落子
7. `MOVE_VERIFY_COUNT=5` 帧逐帧分类校验（按变动格数 0/1/2/3/4/>4），命中即写入内存；失败整步重试或补点重跑

### 走棋校验分类（`_verify`）

`MOVE_VERIFY_COUNT=5` 帧逐帧校验，每帧由纯函数 `classifier.classify_self_frame` 按变动格数 `n` 分类：

| `n` | 处理 |
|---|---|
| 0 | STATIONARY，不做认输检测 |
| 1（最后一帧 + `_is_lifted_only`） | LIFTED_ONLY：提子未落，外层补点重跑（不消耗重试次数） |
| 2（`moves.infer` 命中） | `_apply_self_move` + `_checkmate_probe` → DONE_OK |
| 2（兜底） | 我方起点空 + 敌方起点空 + 终点是敌方棋 → `_apply_self_then_enemy` → DONE_OK |
| 3 | `_classify_n3`：敌方在终点反吃 / 我方走棋+敌方占我原位 |
| 4 | `_classify_n4`：我方走棋 + 敌方走棋 |
| >4 且双方将帅缺失 | RESIGN_SUSPECT（更新认输 streak） |

5 次全没命中后：认输续帧 while 循环（仅 `resign_streak > 0` 时进入），confirmed → DONE_END。
最终返回 `VerifyOutcome` 枚举：DONE_OK / DONE_END / LIFTED_ONLY / STATIONARY / TRANSIENT。

### 走棋主流程（`_do_move`）

1. `_compute_move()` → `(fen, move)`
2. `_unpack_move(fen, move)` → `(r1, c1, r2, c2, piece)` + 设置 highlight
3. `_attempt_move(r1, c1, r2, c2)`：**只做 ADB 点击**（起子 + `TAP_HOLD_INTERVAL_MS` 间隔 + 落子）
4. `_verify(r1, c1, r2, c2, piece)` → `VerifyOutcome`
   - `DONE_OK` → return True
   - `DONE_END` → return False
   - `LIFTED_ONLY` → `capture.tap(r2,c2)` 补点 + 重跑 `_verify`（不消耗 `SELF_MOVE_ATTEMPTS`）
   - `STATIONARY` → 外层重走（整步重新点击）
   - `TRANSIENT` → break（中止）
5. `SELF_MOVE_ATTEMPTS=2` 次用完 → "走棋尝试失败" → return False

### 自动检测敌方走棋（`_wait_for_enemy_move`）

- 连续截图（无额外延时），无限循环（直到用户中断或对局结束）
- 每轮次开头 `state.snapshot_prev()`，作为变动对比基准
- 每帧由纯函数 `classifier.classify_enemy_frame` 分类：
  - `Move`（n==2 infer 命中）→ `_apply_enemy_move` + return
  - `"lifted"`（n==1 敌方提子）→ 提示一次「检测到敌方提起棋子」，continue
  - `"silent"`（n==0）→ 重置提子/噪声计数，continue
  - `"noisy"` → `_update_resign`：
    - confirmed → `_finish_game` + return
    - suspect → 延时 `RESIGN_SUSPECT_WAIT_MS` 复检
    - none → `noisy_count += 1`；达 `ENEMY_NOISY_MAX` → 暂停自动对弈（不提交变动）

### 认输检测（`_update_resign`）

纯函数 `classifier.is_resign_suspect` 判断双方将/帅是否同时缺失，控制层维护 streak，
返回 `ResignResult` 枚举：

- 双方将/帅均缺失 → `resign_streak += 1`，达 `RESIGN_CONFIRM_COUNT=3` → `CONFIRMED`
- 未达阈值 → `SUSPECT`（延时复检）
- 单方或双方都在 → `resign_streak = 0` → `NONE`

### 自动下一局（`_auto_next_game`）

对局结束后若 `auto_next_game` 开启且未中断，自动开始下一局：

1. `auto_next_handler.scan_and_wait()`：单循环扫描结算文字 + 等待摆棋
   - 扫到按钮类（下一关/晋级赛/重新挑战/再来一局）→ 点击；遮罩类（段位提升/铜钱/领取）→ 发返回键
   - 同一文字连续操作上限 `GAMEOVER_RETRY_MAX=3`，不同文字出现时重新计数
   - 无文字时分析棋盘：32 子直接返回；否则连续 `BOARD_STABLE_THRESHOLD=3` 帧棋盘相同返回
   - 总超时 `AUTO_NEXT_TIMEOUT_S=180`
2. `state.reset()` + `_initialize(corrected)` 初始化下一局
3. 残局模式（棋子 < `ENDGAME_MODE_PIECE_COUNT=31`）固定红方先走

流程期间 `_auto_next=True`（`_status()` 返回 `auto_next`），网页端按钮状态保持不变。

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

- REST：`/api/devices`、`/api/connect`、`/api/disconnect`、`/api/start`、`/api/interrupt`、
  `/api/answer_turn`、`/api/auto_next`（`{enable}` 实时开关自动下一局）
- WebSocket：`/ws`（广播 `log` / `state` / `prompt_turn` / `connected` / `disconnected`）
- 静态：`/pieces/<id>.png`（模板图）、`/`（网页前端）

## 常用命令

```powershell
uv run python -m xiangqi_bot                              # 启动网页服务（端口 8900，自动开浏览器）
.\check.ps1                                               # 一键 ruff format + ruff check + ty check
uv run pytest tests/ -v                                  # 全部测试（31 场景）
uv run python scripts/regenerate_templates.py            # 从矫正棋盘重新切割棋子模板
uv run python scripts/detect_board_corners.py <截图> [--save-board]  # 探测四角坐标
uv run python scripts/generate_text_templates.py         # 从结算截图重新生成结算文字模板
uv run python scripts/compare_piece_templates.py        # 对比模板相似度
```

## 关键配置（config.py）

| 常量 | 默认值 | 说明 |
|---|---|---|
| `BOARD_CORNERS` | 查表 | 按分辨率查四角格中心坐标，透视矫正输入 |
| `CORRECT_CELL/W/H` | 100 / 900 / 1000 | 矫正棋盘尺寸 |
| `TAP_HOLD_INTERVAL_MS` | 400 | 点起子 → 点落子间隔 |
| `MOVE_SETTLE_MS` | 500 | 落子后校验截图前等待 |
| `MOVE_VERIFY_COUNT` | 5 | 走棋校验截图次数（全部失败才判定走棋失败） |
| `SELF_MOVE_ATTEMPTS` | 2 | 整步重试上限（`_do_move` 外层循环） |
| `ENGINE_MOVETIME_MS` | 1000 | 引擎思考时间（`go movetime`） |
| `ENGINE_THREADS` | 12 | 引擎线程数 |
| `ENGINE_HASH_MB` | 2048 | 引擎哈希（MB） |
| `ENGINE_MATE_PROBE_MS` | 200 | 绝杀探测短时限 |
| `ENGINE_RULE60_MAX_PLY` | 60 | 自然限招步数（60 步不吃子判和，引擎 `Rule60MaxPly`） |
| `ENEMY_RECHECK_WAIT_MS` | 500 | 多格变动/无法构成完整一步（疑似瞬态噪声）时延时复检 |
| `ENEMY_NOISY_MAX` | 3 | 连续噪声帧上限，超过则暂停自动对弈 |
| `RESIGN_CONFIRM_COUNT` | 3 | 双方将/帅均缺失需连续几帧才确认认输 |
| `RESIGN_SUSPECT_WAIT_MS` | 1000 | 单帧疑似结束时延时再采样（过滤瞬态误判） |
| `ENDGAME_PIECE_COUNT` | 24 | 可识别棋子数低于该值视为残局（轮次无法静态推断） |
| `ENDGAME_MODE_PIECE_COUNT` | 31 | 残局模式（如「下一关」）棋子数上限，固定红先 |
| `AUTO_NEXT_GAME` | True | 对局结束后自动开始下一局（网页端开关默认值，运行时可实时修改） |
| `AUTO_NEXT_TIMEOUT_S` | 180 | 结算交互 + 摆棋等待总超时（秒） |
| `GAMEOVER_SCAN_INTERVAL_MS` | 300 | 扫描间隔 |
| `GAMEOVER_TEXT_THRESHOLD` | 0.75 | 结算文字模板匹配阈值 |
| `GAMEOVER_TEMPLATE_W` | 1080 | 结算文字模板基准宽度（匹配前等比缩放） |
| `BOARD_STABLE_THRESHOLD` | 3 | 结算文字消失后连续相同棋盘帧数 |
| `GAMEOVER_RETRY_MAX` | 3 | 同一按钮/遮罩连续操作上限（不同文字出现时重新计数） |
| `GAMEOVER_BUTTON_WORDS` | 下一关/晋级赛/重新挑战/再来一局 | 按钮类（点击）优先级 |
| `GAMEOVER_BACK_WORDS` | 段位提升/铜钱/领取 | 文字/遮罩类（发送返回键） |
| `DIFF_THRESHOLD` / `MATCH_SEARCH_HALF` / `EMPTY_MATCH_THRESHOLD` | 8 / 10 / 0.8 | 图片识别阈值 |
