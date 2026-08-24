# AGENTS.md — 中国象棋自动脚本

> 权威需求来源：`doc.md`。本文件是需求的整理与工程约定，供编码会话使用。
> 如与 `doc.md` 冲突，以 `doc.md` 为准。

## 项目概述

用 Python 编写中国象棋自动脚本：通过 ADB 控制 Android 手机（QQ/微信象棋等游戏）自动下棋。

- 用 ADB 截图识别棋盘：已知四角坐标做透视矫正，再对 14 张模板做模板匹配
- 用 pikafish 引擎（UCI 协议，长进程复用）计算下一步棋
- 用 ADB 模拟点击落子
- **网页端**（FastAPI + 原生 JS/Canvas）显示棋盘、日志并操作；手机/PC 浏览器均可访问

## 运行环境与工具链

- Windows 11，Python 3.12
- 包管理：`uv`（禁止手写 pyproject.toml 的依赖；必须用 `uv init` 初始化、`uv add` 添加依赖）
- 运行：一律 `uv run`，禁止 `python xxx.py`
- 依赖：`pure-python-adb`、`opencv-python`、`numpy`、`fastapi`、`uvicorn[standard]`、`pywebview`
- dev 依赖：`ruff`（格式/检查）、`ty`（类型检查）

## 常用命令

```powershell
uv init --package          # 初始化项目（自动生成 pyproject.toml 等）
uv add pure-python-adb opencv-python numpy fastapi "uvicorn[standard]"
uv add --dev ruff ty
uv run python -m xiangqi_bot   # 启动网页服务（自动打开浏览器，端口 8900）
.\check.ps1                # 一键 ruff format + ruff check + ty check
uv run pytest tests/ -v   # 全部测试
```

`check.ps1`（项目根目录，供随时调用）：

```powershell
uv run ruff format .
uv run ruff check .
uv run ty check .
```

## 关键常量与配置

```python
# 透视矫正：四角格中心坐标按分辨率查表（矫正棋盘固定 900x1000，格边长 100）
BOARD_CORNERS = {
    (1080, 2400): ((76.0, 680.0), (1004.0, 680.0), (67.0, 1700.0), (1013.0, 1700.0)),
    (1440, 3200): ((101.3, 906.7), (1338.7, 906.7), (89.3, 2266.7), (1350.7, 2266.7)),
}
CORRECT_CELL = 100
CORRECT_W = 900
CORRECT_H = 1000
CORRECT_TEMPLATE_SIZE = 60

# 延时（毫秒）
TAP_HOLD_INTERVAL_MS = 400  # 点起子 -> 点落子之间的间隔
MOVE_SETTLE_MS = 500  # 落子后每次校验截图前的等待
MOVE_VERIFY_COUNT = 5  # 走棋校验截图次数（全部失败才判定走棋失败）
SELF_MOVE_ATTEMPTS = 2  # 整步重试上限（_do_move 的外层循环）

# 引擎
ENGINE_MOVETIME_MS = 1000
ENGINE_THREADS = 12
ENGINE_HASH_MB = 2048
ENGINE_MATE_PROBE_MS = 200  # 绝杀判断用的短时限探测
ENGINE_RULE60_MAX_PLY = 60  # 自然限招（60 步不吃子判和，对应平台规则；引擎 Sixty Move Rule 默认开）

# 和棋弹窗
DRAW_TEXT_DIR = TEMPLATES_DIR / "draw"  # 和棋_同意.png / 和棋_拒绝.png 模板
DRAW_TEXT_THRESHOLD = 0.75  # 模板匹配阈值
DRAW_REJECT_CP = 1000  # 我方优势超过此值（厘兵，100≈1兵）则拒绝，否则同意
# 自动检测敌方走棋（毫秒）
ENEMY_RECHECK_WAIT_MS = 500  # 噪声帧延时复检
ENEMY_NOISY_MAX = 3  # 连续噪声帧上限，超过则暂停自动对弈

# 对局结束 / 认输检测
RESIGN_CONFIRM_COUNT = 3  # 双方将帅缺失需连续几帧才确认
RESIGN_SUSPECT_WAIT_MS = 1000  # 单帧疑似结束时延时再采样

# 图片识别（矫正棋盘空间，像素）
DIFF_WINDOW = 10
DIFF_THRESHOLD = 8
MATCH_SEARCH_HALF = 10
EMPTY_MATCH_THRESHOLD = 0.8

# 自动下一局
AUTO_NEXT_GAME = True
AUTO_NEXT_TIMEOUT_S = 180  # 结算交互+摆棋总超时（秒）
GAMEOVER_SCAN_INTERVAL_MS = 300  # 扫描间隔
BOARD_STABLE_THRESHOLD = 3  # 结算文字消失后连续相同棋盘帧数
GAMEOVER_RETRY_MAX = 3  # 同一按钮/遮罩操作上限
GAMEOVER_TEXT_THRESHOLD = 0.75
GAMEOVER_TEMPLATE_W = 1080
GAMEOVER_BUTTON_WORDS = ("下一关", "晋级赛", "重新挑战", "再来一局")  # 按钮类（点击），按优先级
GAMEOVER_BACK_WORDS = ("段位提升", "铜钱", "领取")  # 遮罩类（发返回键）
```

## 目录结构

```
xiangqi-bot/
├── pyproject.toml
├── check.ps1                      # ruff/ty 一键检查
├── src/xiangqi_bot/
│   ├── __init__.py / __main__.py  # 入口委派 main
│   ├── main.py                    # uvicorn 后台线程 + pywebview 独立窗口
│   ├── server.py                  # FastAPI：静态托管 + REST API + WebSocket + 后台 worker
│   ├── config.py                  # 常量、路径、阈值、四角坐标
│   ├── adb_client.py              # ppadb + adb.exe 封装（无终端交互）
│   ├── board.py                   # 网格坐标、记谱/FEN 转换、开局默认格
│   ├── vision.py                  # 透视矫正、模板匹配、两图对比
│   ├── engine.py                  # pikafish UCI 长进程客户端
│   ├── game/                      # 对局模块（数据结构 + 纯函数 + IO 类 + 薄控制层）
│   │   ├── __init__.py            # 导出 GameSession
│   │   ├── state.py               # Side/Move/Change/GameState/FrameResult/VerifyOutcome 等数据结构
│   │   ├── opening.py             # 开局分析纯函数（detect_side/detect_phase/infer_turn）
│   │   ├── moves.py               # 走法推断/应用/格式化纯函数（infer/apply/matches/format_move）
│   │   ├── classifier.py          # 帧分类纯函数（classify_self_frame/classify_enemy_frame/is_resign_suspect）
│   │   ├── recognition.py         # 棋盘识别纯函数（analyze：矫正图 → 布局+变动）
│   │   ├── draw.py                # 和棋决策纯函数（decide：分数 → accept/reject）
│   │   ├── capture.py             # Capture IO 类（截图/矫正/点击/和棋弹窗）
│   │   ├── auto_next.py           # AutoNext IO 类（结算交互 + 等待摆棋）
│   │   └── session.py             # GameSession 薄控制层（编排主循环，不继承任何类）
│   └── web/                       # 网页前端（静态文件）
│       ├── index.html / app.js / style.css
├── pikafish/
│   ├── pikafish-bmi2.exe          # 引擎（必须在其目录运行，依赖 pikafish.nnue）
│   └── pikafish.nnue
├── templates/*.png                # 14 张 60x60 棋子模板（从矫正棋盘切割，勿改）
├── templates/text/*.png           # 结算文字模板（脚本 generate_text_templates 生成）
├── raw_screenshots/               # 原始开局截图 + 结算截图（脚本数据源）
├── scripts/                       # regenerate_templates / compare_piece_templates /
│                                  # detect_board_corners / generate_text_templates
├── tests/                         # pytest 测试（10 个文件）
│   ├── conftest.py                # 共享 fixture + mock vision
│   ├── test_engine.py             # 引擎客户端
│   ├── test_fresh.py              # 开局轮次推断
│   ├── test_prompt.py             # 弹窗确认
│   ├── test_next.py               # 自动下一局
│   ├── test_eat_after_self_move.py # 吃子 + 敌方反吃
│   ├── test_capture.py            # 走棋校验 + 重试流程（12 场景）
│   ├── test_noisy.py              # 敌方走棋检测 + 噪声（6 场景）
│   ├── test_probe.py              # 绝杀探测（3 场景）
│   └── test_resign.py             # 认输检测（4 场景）
```

## 坐标体系（已用引擎实测验证）

### 四种坐标表示

| 表示 | 说明 |
|---|---|
| 网格 `(r, c)` | 固定于屏幕：`r` 行 0..9（0=屏幕最上，9=最下），`c` 列 0..8（0=最左）；`(0,0)` 恒为左上角格子 |
| 屏幕坐标 `(x, y)` | 原始截图像素；点击用 `vision.tap_xy(H, r, c)`（逆透视映射） |
| 矫正坐标 | 透视矫正后的 900x1000 棋盘；格心 = `(50+100c, 50+100r)`，模板匹配在此空间进行 |
| 记谱 `a-i/0-9` | ICCS 绝对坐标系，与 pikafish UCI 方块一致；**不随红黑方变化**：`e9` 恒为黑将、`e0` 恒为红帥 |
| FEN | 同记谱：ICCS 绝对坐标系（黑方在上）；**不随红黑方变化** |

> 关键结论（用户确认）：网格/矫正/屏幕坐标永远固定（矫正只需按分辨率查表）；网格<->记谱的换算**受红黑方影响**；
> 记谱<->FEN 的换算**不受红黑方影响**。从网格生成 FEN 需按红黑方处理行列翻转，
> 但从记谱转 FEN 不需要。绘制棋盘时按红黑方决定下方棋子颜色，但 FEN/记谱不变。

### 转换公式（红方视角）

```python
c = ord(file) - ord("a")  # 记谱 -> 网格列
r = 9 - rank  # 记谱 -> 网格行
file = chr(ord("a") + c)  # 网格 -> 记谱
rank = 9 - r
```

### 转换公式（黑方视角，网格仍固定于屏幕）

```python
file = chr(ord("i") - c)  # 网格 -> 记谱：file 反向、rank 不翻转
rank = r
r = rank  # 记谱 -> 网格
c = ord("i") - ord(file)
```

### FEN 转换（红黑方相关，保证 FEN 恒为 ICCS 绝对坐标系）

```python
# 黑方（屏幕棋盘翻转，红方在网格 0-2 行）：网格行 r -> FEN 第 (10-r) 行，网格列 c -> FEN 第 (9-c) 列
# 红方：网格行 r -> FEN 第 (r+1) 行，网格列 c -> FEN 第 (c+1) 列
```

### 示例（已实测）

- 初始棋局红右炮：网格 `(7, 7)` = 记谱 `h2` = FEN 第 8 行 8 列
- 引擎着法 `h2e2`：红右炮从中路出到 `e2`，即网格 `(7,4)`
- 黑方（红方在屏幕上方）：红相走记谱 `g0 -> e2`，对应网格 `(0,2) -> (2,4)`
- 黑方引擎着法（不变 FEN）：`b7e7` = 黑砲架中炮，对应网格 `(7,7) -> (7,4)`
- 初始 FEN：`rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1`

## 棋子模板与字符映射

模板在 `templates/`，60x60，命名带 `b_`/`r_` 前缀（黑小写/红大写），**从矫正棋盘切割**。

| 模板 | 棋子 | 中文 | FEN 字符 |
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

FEN 规则：黑 = 小写，红 = 大写。内部棋盘状态用模板文件名去扩展名的 ID（如 `b_r`、`r_K`），空格用空串/None 表示。

## 架构设计

### adb_client.py — ADB 封装（API，无终端交互）

- `list_devices()` -> serial 列表（ppadb `Client.devices()`）
- `connect(ip, port)`：调 `adb connect`（ppadb 无配对/连接命令），成功返回 `ip:port`
- `disconnect(serial)`：调 `adb disconnect`（仅无线设备）
- `get_device(serial)`：按 serial 取 ppadb Device，不在线抛 `AdbError`
- `screencap(device)`：`device.screencap()` + `cv2.imdecode`，失败返回 None，异常抛 `AdbError`
- `tap(device, x, y)`：`device.input_tap(x, y)`，异常抛 `AdbError`
- `keyevent(device, keycode)`：`device.shell(f"input keyevent {keycode}")`，异常抛 `AdbError`；`KEYCODE_BACK = 4`
- `screen_size(device)`：解析 `wm size` 的 `Physical size: WxH`

### board.py — 棋盘状态与坐标转换

- `START_SQUARES`：14 类棋子的开局默认格（用于轮次推断/自愈）
- `PIECE_FEN` / `PIECE_CN`：棋子 ID -> FEN 字符 / 中文显示字（排局日志格式化用）
- 记谱 <-> 网格（受红黑方影响）、FEN <-> 布局（互逆，按红黑方翻转）
- `make_empty_board()` / `fen_of_board(board, side, to_move=..., halfmove_clock=0)`
  （`halfmove_clock` 为自上次吃子以来的半回合数，写入 FEN 第六字段供引擎自然限招判断）
- `corrected_center(r, c)`：矫正空间格心（供脚本/调试）
- `piece_color(piece_id)`：返回 `"red"` / `"black"`

### vision.py — 图片识别（矫正空间）

- `homography(w, h)`：按分辨率查 `config.BOARD_CORNERS` 求矫正单应（带缓存），未知分辨率抛 RuntimeError
- `correct_board(img)`：warpPerspective 到 900x1000
- `analyze_cell(corrected, r, c, templates)`：中心点 ±10 窗口对 14 模板做 TM_CCOEFF_NORMED，低于阈值判空
- `analyze_cell_with_priority(corrected, r, c, templates, priority_id=None)`：同上但优先匹配 `priority_id`（基于 prev_board 的增量识别，回退时移除 priority_id 避免重复匹配）
- `analyze_board(corrected, templates)`：遍历 90 格
- `diff_cells(prev, cur)`：每格中心 10x10 区域平均绝对差 > 阈值视为变化
- `tap_xy(H, r, c)`：矫正格心经逆单应映射回原图，供点击
- `load_gameover_text_templates()`：加载 `templates/text/*.png` 结算文字模板（灰度，带缓存）
- `find_gameover_text(img, w, h)`：原始截图等比缩放到 `GAMEOVER_TEMPLATE_W` 宽后对全部结算文字模板做
  TM_CCOEFF_NORMED，返回所有高于阈值的 `[(文字, 屏幕x, 屏幕y, 分)]`（中心点，按分降序）
- `format_layout(board)` → `list[str]`：布局转可读文本行（r9..r0，每格中文棋子名或 ·），供日志打印排局

### engine.py — pikafish UCI 长进程客户端

- 每局只启动一个引擎子进程（cwd = `pikafish/`，引擎才能找到 `pikafish.nnue`），线程安全
- `start()` 幂等：`uci`->`uciok`、`setoption Threads/Hash/Rule60MaxPly/EvalFile`（权重绝对路径，
  不依赖 cwd 默认文件名）、`isready`->`readyok`
- `newgame()`：发 `ucinewgame` + `isready`->`readyok`，通知引擎新对局开始（清 hash）；引擎未启动时先 `start()`
- `best_move(fen, movetime_ms)` → `(move | None, score)`：`position fen` + `go movetime`，等 `bestmove`；
  无着法（终局）返回 `(None, 0)`；score 为 `info score cp/mate`（厘兵，正=行棋方占优；mate 映射 ±100000）
- 内部 `_go()` 封装 position+go+等 bestmove+重试，返回输出行快照；`_parse_score()` 静态方法解析分数
- **自愈**：引擎无响应（超时）或进程已退出（Windows 写管道可能抛 `OSError [Errno 22]`）时，
  自动结束进程重建并**重试一次**，仍失败抛 `EngineError`（绝不外泄裸 OSError 击穿调用方）
- `is_mate(fen, movetime_ms)`：对方无路可走即返回 True（绝杀/困毙）
- **`quit` 必须只在 close() 时发**，且所有 bestmove 均已返回；若与 `go` 批量写入会浅层搜索（棋力骤降）
- 后台线程持续读 stdout，一条条发指令并等待对应标记
- **自然限招**：引擎 `Sixty Move Rule` 默认开启，`start()` 时设 `Rule60MaxPly=ENGINE_RULE60_MAX_PLY(=60)`；
  引擎依赖 FEN 第六字段（halfmove clock）感知限着临近，故 `GameState.halfmove_clock` 必须正确维护
  （单方走一步+1，吃子归零；兵卒移动不重置），所有 `fen_of_board` 调用均需传入。残局/排局手动同步时
  clock 从 0 起算（截图无法还原历史吃子步数，属已知折中）

### game/ — 对局模块（数据结构 + 纯函数 + IO 类 + 薄控制层）

**不使用 Mixin**。棋局状态集中在 `GameState` 数据类；可测试的决策逻辑拆成无状态纯函数模块；
ADB/截图等副作用封装在 `Capture`/`AutoNext` 两个 IO 类；`GameSession` 只做编排（薄控制层）。

构造参数：`GameSession(device, log, on_state, ask_turn)`，内部持有 `state: GameState`、
`capture: Capture`、`auto_next_handler: AutoNext`、`engine: Engine`。

#### state.py — 数据结构

- `Side(StrEnum)`：`RED`/`BLACK`，`.opponent`（对手方）、`.cn`（"红"/"黑"）；StrEnum 兼容旧 `"red"`/`"black"` 字符串与 JSON 序列化
- `Change(NamedTuple)`：`(r, c, old, new)` 一格变动
- `Move(frozen dataclass)`：`src`/`dst`/`piece`/`captured` 一步棋
- `FrameResult(StrEnum)`：单帧分类结果（SELF_DONE/SELF_THEN_ENEMY/LIFTED_ONLY/STATIONARY/TRANSIENT/RESIGN_SUSPECT）
- `FrameClass(frozen dataclass)`：`result` + `self_move`/`enemy_move`
- `VerifyOutcome(StrEnum)`：`_verify` 多帧校验结论（DONE_OK/DONE_END/LIFTED_ONLY/STATIONARY/TRANSIENT）
- `EnemyResult(StrEnum)` + `EnemyFrame = Move | EnemyResult`：敌方检测单帧结论
  （LIFTED 提子未落 / NOISY 噪声 / SILENT 无变动）
- `ResignResult(StrEnum)`：认输检测单帧结论（CONFIRMED 确认结束 / SUSPECT 疑似 / NONE 无嫌疑）
- `GameState(dataclass)`：全部棋局状态（board/prev_board/my_side/turn/phase/initialized/
  halfmove_clock/game_over/highlight/last_move/last_eval_score/resign_streak/noisy_count/lift_logged），
  `reset()` 重置、`snapshot_prev()` 深拷贝当前 board 为 prev_board；
  my_side/turn/phase **非可选**（未初始化期间为占位值），`initialized: bool` 标记本轮
  `_initialize` 是否成功同步（驱动 `_status()` idle）；flow 各入口保证 turn 已定，
  `_compute_move` 以 initialized 兜底防占位值流入 FEN

#### opening.py — 开局分析（纯函数）

- `detect_side(board)` → `Side | None`：将/帥在屏幕下方（行 6..9）则该方为我方
- `detect_phase(board, my_side)` → `Phase`：两态——32 子且（全默认位 或 恰一方走一步）→ 开局；
  其余一律 → 残局
- `infer_turn(board, my_side, phase)` → `Side | None`：仅开局推断轮次
  （全默认位红先；恰一方走一步则轮到对方），其余阶段返回 None
- 内部 `_color_deviates`/`_expected_start_squares`/`_single_piece_moved`

#### moves.py — 走法推断与应用（纯函数）

- `infer(changes)` → `Move | None`：2 格变动（一起一落、棋子相同）推断走法
- `apply(board, move, clock)` → `new_clock`：写 board（起子格清空、落子格写棋子），吃子归零/非吃+1；我方/敌方共用
- `matches(move, expected)`：走法是否与引擎着法吻合
- `format_move(move, my_side)` / `format_changes(changes, my_side)`：日志格式化

#### classifier.py — 帧分类（纯函数）

- `classify_self_frame(changes, new_board, expected, my_side, is_last_frame)` → `FrameClass`：
  我方走棋后单帧分类，按 n==0/1/2/3/4/>4 分派；内部 `_is_lifted_only`/`_enemy_recapture_n2`/
  `_classify_n3`（三种情况）/`_classify_n4`
- `classify_enemy_frame(changes, my_side)` → `Move | EnemyResult`：敌方检测单帧分类
- `is_resign_suspect(board, my_side)` → `bool`：双方将/帥同时缺失（单帧疑似结束）；连续帧 streak 由控制层维护

#### recognition.py — 棋盘识别（纯函数）

- `analyze(corrected, templates, prev_board)` → `(Board, list[Change])`：
  逐格调 `vision.analyze_cell_with_priority`（优先匹配 prev_board），一次遍历完成识别+对比

#### draw.py — 和棋决策（纯函数）

- `decide(score, reject_cp)` → `"accept" | "reject"`：score 为我方评估分，超过 reject_cp 拒绝，否则同意

#### capture.py — Capture（IO 类）

持有 device/templates/homography 状态；通过 `decide_draw`/`should_continue` 回调与控制层解耦。

- `grab()` → `ndarray | None`：截图 → `_dismiss_draw` → 矫正（供棋盘识别）
- `screenshot()` → `ndarray | None`：原始截图（供文字识别）
- `tap(r, c)` / `tap_xy(x, y)` / `keyevent(keycode)` → bool
- `_dismiss_draw(img)`：`find_draw_dialog` 同时匹配到「和棋_同意」「和棋_拒绝」两按钮才认定和棋页面；
  首次命中调 `decide_draw` 回调决定同意/拒绝并点击，循环直到弹窗消失（点击失败即中止）；
  返回 `(截图, 点击次数)`

#### auto_next.py — AutoNext（IO 类）

持有 device/capture/templates；通过 `should_continue`/`auto_next_enabled` 回调与控制层解耦。

- `scan_and_wait()` → `ndarray | None`：结算交互 + 等待摆棋稳定，返回矫正帧。
  单循环：先 `_scan_text`，按钮点击 / 遮罩发返回键（同一文字重试上限 `GAMEOVER_RETRY_MAX`）；
  无文字时分析棋盘，32 子直接返回，否则连续 `BOARD_STABLE_THRESHOLD=3` 帧棋盘相同返回；
  总超时 `AUTO_NEXT_TIMEOUT_S=180`
- `_scan_text(img=None)` → `(文字, x, y, is_button) | None`：优先遮罩类（GAMEOVER_BACK_WORDS），再按钮类

#### session.py — GameSession（薄控制层）

不继承任何类。持有 `state`/`capture`/`auto_next_handler`/`engine`，编排「截图→识别→分类→应用→引擎→点击」。

- 流程控制（非棋局状态）：`_running`、`_auto_next`、`_start_in_progress`（start 防抖）、
  `auto_next_game`、`_interrupt`(Event)、`_turn_answer`/`_turn_event`
- `start()`：进行中的 start 未结束前忽略重复触发（防抖）→ 清中断 → `capture.grab` →
  `state.reset` → `_initialize` → 推断轮次（未知时弹窗确认）→ 自动 `_start_flow`
- `_start_flow()`：`_running=True` → `engine.newgame()` → `_flow()`；顶层 try/except/finally 兜底
- `_flow()`：主循环，每轮次 `state.snapshot_prev()`；我方 `_do_move` ↔ 敌方 `_wait_for_enemy_move` 交替；
  game_over 后按 `auto_next_game` 开关调 `_auto_next_game`
- `_initialize(corrected)` → bool：`vision.analyze_board` → opening `detect_side`/`detect_phase`
  → 写 state（含 `initialized=True`）→ 日志；判方失败返回 False。**不含轮次**（start/自动下一局各自闭环处理 turn）
- `_do_move()` → bool：`_compute_move` → `_unpack_move` → 循环(`SELF_MOVE_ATTEMPTS`){`_attempt_move` → `_verify`}；
  LIFTED_ONLY 补点后重跑 `_verify`（不消耗 attempts）；TRANSIENT 中止
- `_compute_move()` → `(fen, move) | None`：`fen_of_board`（含 halfmove_clock）→ `engine.best_move`；
  None 时短时限重试一次；缓存 `state.last_eval_score`
- `_unpack_move(fen, move)` → `(r1,c1,r2,c2,piece|None)`：解析坐标 + 设置 highlight/last_move
- `_attempt_move(r1,c1,r2,c2)` → bool：只做 ADB 点击（起子 + 间隔 + 落子）
- `_verify(r1,c1,r2,c2,piece)` → `VerifyOutcome`：`MOVE_VERIFY_COUNT=5` 帧逐帧调
  `recognition.analyze` + `classifier.classify_self_frame`，按结果调 `_apply_self_move`/
  `_apply_self_then_enemy`/`_checkmate_probe`；5 帧未命中后认输续帧 while（仅 resign_streak>0）
- `_wait_for_enemy_move()`：持续 `_grab_board` + `classifier.classify_enemy_frame`；
  Move → `_apply_enemy_move`；lifted 提示一次；noisy → `_update_resign`/噪声计数，达 `ENEMY_NOISY_MAX` 暂停
- `_apply_self_move(move)` / `_apply_self_then_enemy(self_move, enemy_move)` / `_apply_enemy_move(move)`：
  调 `moves.apply` 写 board + 更新 clock + 切轮次 + 高亮 + 日志 + `_emit`
- `_update_resign(new_board)` → `ResignResult`：调 `classifier.is_resign_suspect` + 维护 streak
- `_checkmate_probe()` → bool：`engine.is_mate`（仅 n==2 干净走棋后调用）；引擎异常降级为未绝杀
- `_decide_draw()` → `"accept"/"reject"`：读 `state.last_eval_score` 调 `draw.decide`
- `_auto_next_game()` → bool：`auto_next_handler.scan_and_wait` → `state.reset` → `_initialize` →
  `engine.newgame` → 轮次处理（残局固定红先；开局先 `infer_turn`，无法推断视为闯关排局：
  默认玩家红方先行并打印排局布局）；流程期间 `_auto_next=True`；返回是否成功进入下一局
- `_grab_board()` → `(Board, list[Change]) | None`：`capture.grab` + `recognition.analyze`
- `_confirm_start()` → bool：弹窗等待网页确认（`_turn_event`，可被中断）
- `_finish_game(reason)`：打调用路径日志 + `game_over=True` + 日志 + `_emit`
- `_status()` → `idle/red/black/over/stopped/auto_next`；`_emit()` 推送 state dict
- `interrupt()` / `answer_turn(answer)` / `set_auto_next(enable)` / `close()`：线程安全公共接口

### server.py — FastAPI + WebSocket

- `Hub`：持有当前设备与会话；`post()` 把命令投递到**后台 worker 线程**串行执行，`broadcast()` 跨线程推送
- REST：`/api/devices`、`/api/connect`（serial 或 ip+port）、`/api/disconnect`、`/api/start`、`/api/interrupt`、
  `/api/answer_turn`、`/api/auto_next`（`{enable}` 实时开关自动下一局）
- `/ws` WebSocket：广播 `log` / `state` / `prompt_turn` / `connected` / `disconnected`；新客户端连上先补发最新 state
- 静态：`/pieces/<id>.png`（模板图）、`/`（`web/` 目录，`Cache-Control: no-cache`）

### web/ — 网页前端（原生 JS + Canvas）

- 连接页：ADB 设备列表（一键连接）+ ip:port 手动连接
- 主界面：Canvas 棋盘（900x1000 矫正比例 + 四边标注）、设备信息 + 断开设备、棋盘阶段/阵营信息、
  状态行（含**自动下一局开关**，对弈过程中可实时切换）、flow 按钮、日志面板
- **流程按钮**（`btn-flow` 开始或中断棋局）：

  | 状态 | 开始/中断棋局 |
  |---|---|
  | `idle`/`over`（刚连接/已结束） | 禁用 |
  | `red`/`black`/`auto_next`（对弈中） | 可用（中断棋局） |
  | `stopped`（残局已同步/中断后） | 可用（开始棋局，自动先同步） |

- 棋盘标注随 `my_side` 翻转（红：列 a..i；黑：列 i..a）
- 棋子绘制为**纯文字 + 圆圈**，走棋高亮：原位 = 格中心白点，落点 = 四角白色 90 度角标
- 开始棋局确认弹窗：收到 `prompt_turn` 显示「是否开始棋局」；「不」在左 / 「开始」在右

### main.py — 入口

`uvicorn.run("xiangqi_bot.server:app", host="0.0.0.0", port=8900)` 在后台线程运行，pywebview 打开独立窗口；pywebview 不可用时回退 `webbrowser.open()`。

## 主流程（网页版）

1. 启动服务，浏览器打开连接页
2. `/api/devices` 列设备：USB 设备直接「使用」；无线设备输入已配对 `ip:port` 连接
3. 连接成功进入主界面（自动走棋 / 自动检测敌方走棋**常开**，无开关）
4. 点击「开始棋局」-> 截图识别棋盘并开始对弈（`/api/start` 调用 `start()`），无历史/已结束走全量初始化，否则增量拉取：
   - 全默认位 -> 红先；仅红偏离 -> 黑走；仅黑偏离 -> 红走
   - 双方均偏离或残局 -> 默认我方走棋，网页弹窗确认是否开始
   - **开局**自动开始对弈；**刚开局局面**（全棋子、对方仅走一步、轮到已方）也自动开始对弈；其余**残局/排局**只载入棋盘（stopped），等用户点「开始棋局」
5. 对弈中可「中断棋局」（暂停 flow），中断后「开始棋局」用当前棋盘数据恢复对弈
6. 任一处绝杀/认输判定 -> `game_over`，主界面提示；`AUTO_NEXT_GAME` 开启且未中断时自动扫描结算文字
   （按钮类点击 / 段位提升发返回键）-> 等待摆棋完毕 -> 自动开始下一局；中止或失败后保持结束状态，可手动「开始棋局」重开

### 子流程 走棋校验分类（`_verify`）

`MOVE_VERIFY_COUNT=5` 帧逐帧校验，每帧调 `classifier.classify_self_frame` 按变动格数 `n` 分类：

- `n==0` → STATIONARY（不做认输检测）
- `n==1` + 最后一帧命中 `_is_lifted_only`（起点空 + 终点空 + 无其他变动）→ LIFTED_ONLY（提起未落，外层补点重试）
- `n==2` + `moves.infer` 命中 → `_apply_self_move` + `_checkmate_probe` → DONE_OK
- `n==2` 兜底：我方起点空 + 另一格敌方起点空 + 终点是敌方棋 → `_apply_self_then_enemy` → DONE_OK
- `n==3` → `_classify_n3`：敌方在终点反吃 / 我方走棋+敌方占我原位 → `_apply_self_then_enemy`
- `n==4` → `_classify_n4`：我方走棋 + 敌方走棋 → `_apply_self_then_enemy`
- `n>4` 且双方将帅缺失 → RESIGN_SUSPECT（更新 streak）
- 5 次全没命中后：认输续帧 while 循环（仅 `resign_streak > 0` 时进入），confirmed → DONE_END
- 返回 `VerifyOutcome`：DONE_OK / DONE_END / LIFTED_ONLY / STATIONARY / TRANSIENT

### 子流程 我方走棋主流程（`_do_move`）

1. `_compute_move()` → `(fen, move)`
2. `_unpack_move(fen, move)` → `(r1, c1, r2, c2, piece)` + 设置 highlight
3. `_attempt_move(r1, c1, r2, c2)` → bool（只做 ADB 点击：起子 + `TAP_HOLD_INTERVAL_MS` 间隔 + 落子）
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
- 每帧调 `classifier.classify_enemy_frame`：
  - `Move`（n==2 infer 命中）→ `_apply_enemy_move` + return
  - `"lifted"`（n==1 敌方提子）→ 提示一次「检测到敌方提起棋子」，continue
  - `"silent"`（n==0）→ 重置提子/噪声计数，continue
  - `"noisy"` → `_update_resign`：
    - confirmed → `_finish_game` + return
    - suspect → 延时 `RESIGN_SUSPECT_WAIT_MS` 复检
    - none → `noisy_count += 1`；达 `ENEMY_NOISY_MAX` → 暂停自动对弈（不提交变动）

## 编程规范

- 所有依赖必须 `uv add` 添加，禁止手改 pyproject.toml 依赖后 `uv sync`
- `pyproject.toml` 需加入 doc.md 给出的 `[tool.ty.environment]` 与 `[tool.ruff]`/`[tool.ruff.lint]`/`[tool.ruff.format]` 配置
- ruff：`target-version="py312"`，`line-length=100`，双引号、空格缩进；忽略 `E501/TRY003/RUF012`
- 命名遵循 ruff `N` 规则（模块名全小写等）
- 注释/日志用中文，代码本身不加多余注释
- 写完后必须跑 `.\check.ps1`（ruff format + ruff check + ty check）且通过
- 测试：`uv run pytest tests/ -v`，31 个场景全通过
- 文本文件换行符统一使用 LF（`.py`/`.md`/`.ps1`/`.toml` 等），仓库根目录 `.gitattributes` 已强制 `eol=lf`；
  Windows 下不要提交 CRLF

## 已实测的技术事实（勿重复验证）

- pikafish-bmi2.exe 用 UCI；`position startpos moves h2e2` 走红右炮；`go depth 6` 输出 `bestmove b2e2 ponder b9c7`
- **长进程下 `go movetime 3000` 用满时限（depth ~25），红方开局出 `h2e2`、黑方开局出 `b7e7`；若与 `quit` 批量写入，约 0.3s 就出 `bestmove`（只走兵，棋力骤降）**
- FEN 恒为 ICCS 绝对坐标系（黑方在上），不随我方红黑变化
- 记谱与 pikafish 方块一致：文件 = 网格列，行号 = `9 - 行`
- 矫正 + 模板匹配：raw_screenshots/ 下 6 张图（木/石 × 红/黑，含 1440x3200 高清）全部 90/90 格、32/32 棋子识别正确
- 黑方截图棋盘上下翻转，但棋子保持正立（不需旋转模板）
- 3200 高清图 = 1080 图纯 1.3333x 缩放，BOARD_CORNERS 等比放大即可
- 引擎在 `pikafish/` 目录启动才能加载 `pikafish.nnue`
- ppadb 0.3.0-dev 无配对/连接命令（无线连接须调用本机 adb.exe）
