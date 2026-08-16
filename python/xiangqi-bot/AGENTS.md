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
- 依赖：`pure-python-adb`、`opencv-python`、`numpy`、`fastapi`、`uvicorn[standard]`
- dev 依赖：`ruff`（格式/检查）、`ty`（类型检查）

## 常用命令

```powershell
uv init --package          # 初始化项目（自动生成 pyproject.toml 等）
uv add pure-python-adb opencv-python numpy fastapi "uvicorn[standard]"
uv add --dev ruff ty
uv run python -m xiangqi_bot   # 启动网页服务（自动打开浏览器，端口 8900）
.\check.ps1                # 一键 ruff format + ruff check + ty check
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
}  # 左上/右上/左下/右下 角格中心；3200 = 1080 等比 ×1.3333
CORRECT_CELL = 100
CORRECT_W = 900
CORRECT_H = 1000  # 矫正棋盘尺寸
CORRECT_TEMPLATE_SIZE = 60  # 矫正空间下的模板边长

# 延时（毫秒）
TAP_HOLD_INTERVAL_MS = 400  # 点起子 -> 点落子之间的间隔（勿设过小，否则第二次落子易失败）
MOVE_SETTLE_MS = 500  # 落子后每次校验截图前的等待（3 次均 500ms）
MOVE_VERIFY_COUNT = 3  # 走棋校验截图次数（全部失败才判定走棋失败）

# 引擎
ENGINE_MOVETIME_MS = 1000  # go movetime <ms>（思考时间）
ENGINE_THREADS = 12  # setoption name Threads
ENGINE_HASH_MB = 2048  # setoption name Hash（MB）
ENGINE_MATE_PROBE_MS = 200  # 绝杀判断用的短时限探测（无着法会立即返回 (none)）

# 自动检测敌方走棋（毫秒）
AUTO_DETECT_INTERVAL_MS = (
    0  # 截图间隔延时；ADB 截图本身耗时，不再额外延时（无次数限制，直到用户中断）
)

# 对局结束 / 敌方认输检测
RESIGN_PIECE_DROP_THRESHOLD = 3  # 可识别棋子数比内存布局至少少几枚，判为对局结束画面
RESIGN_CONFIRM_COUNT = 3  # 疑似对局结束画面需连续几帧稳定出现才确认（过滤瞬态误判）

# 图片识别（矫正棋盘空间，像素）
DIFF_WINDOW = 10  # 中心点 10x10 区域对比差异
DIFF_THRESHOLD = 8  # 10x10 区域平均绝对差超过此值视为"有变化"
MATCH_SEARCH_HALF = 10  # 模板匹配时在中心点 ±10px 窗口内滑动
EMPTY_MATCH_THRESHOLD = 0.8  # TM_CCOEFF_NORMED 低于此值判为空格

# 残局判断
ENDGAME_PIECE_COUNT = 20  # 可识别棋子总数少于该值视为残局（轮次无法静态推断）
```

## 目录结构

```
xiangqi-bot/
├── pyproject.toml
├── check.ps1                      # ruff/ty 一键检查
├── src/xiangqi_bot/
│   ├── __init__.py / __main__.py  # 入口委派 main
│   ├── main.py                    # uvicorn 启动（0.0.0.0:8900，自动开浏览器）
│   ├── server.py                  # FastAPI：静态托管 + REST API + WebSocket + 后台 worker
│   ├── game.py                    # 对局状态机（同步/开始/中断/自动对弈）
│   ├── config.py                  # 常量、路径、阈值、四角坐标
│   ├── adb_client.py              # ppadb + adb.exe 封装（无终端交互）
│   ├── board.py                   # 网格坐标、记谱/FEN 转换、开局默认格
│   ├── vision.py                  # 透视矫正、模板匹配、两图对比
│   ├── engine.py                  # pikafish UCI 长进程客户端
│   └── web/                       # 网页前端（静态文件）
│       ├── index.html / app.js / style.css
├── pikafish/
│   ├── pikafish-bmi2.exe          # 引擎（必须在其目录运行，依赖 pikafish.nnue）
│   └── pikafish.nnue
├── templates/*.png                # 14 张 60x60 棋子模板（从矫正棋盘切割，勿改）
├── raw_screenshots/               # 原始开局截图（脚本数据源，文件名含分辨率）
│                                  # 木/石 棋盘 × 红/黑 方（1080x2400）+ 高清（1440x3200）
├── scripts/                       # regenerate_templates / compare_piece_templates /
│                                  # detect_board_corners（已有，勿改）
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
# 网格 -> 记谱：file 反向、rank 不翻转
file = chr(ord("i") - c)
rank = r
# 记谱 -> 网格
r = rank
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
- `screen_size(device)`：解析 `wm size` 的 `Physical size: WxH`

### board.py — 棋盘状态与坐标转换

- `START_SQUARES`：14 类棋子的开局默认格（用于轮次推断/自愈）
- 记谱 <-> 网格（受红黑方影响）、FEN <-> 布局（互逆，按红黑方翻转）
- `make_empty_board()` / `fen_of_board(board, side, to_move=...)`
- `corrected_center(r, c)`：矫正空间格心（供脚本/调试）

### vision.py — 图片识别（矫正空间）

- `homography(w, h)`：按分辨率查 `config.BOARD_CORNERS` 求矫正单应（带缓存），未知分辨率抛 RuntimeError
- `correct_board(img)`：warpPerspective 到 900x1000
- `analyze_cell(corrected, r, c, templates)`：中心点 ±10 窗口对 14 模板做 TM_CCOEFF_NORMED，低于阈值判空
- `analyze_board(corrected, templates)`：遍历 90 格
- `diff_cells(prev, cur)`：每格中心 10x10 区域平均绝对差 > 阈值视为变化
- `tap_xy(H, r, c)`：矫正格心经逆单应映射回原图，供点击

### engine.py — pikafish UCI 长进程客户端

- 每局只启动一个引擎子进程（cwd = `pikafish/`，引擎才能找到 `pikafish.nnue`），线程安全
- `start()` 幂等：`uci`->`uciok`、`setoption Threads/Hash`、`isready`->`readyok`
- `best_move(fen, movetime_ms)`：`position fen` + `go movetime`，等 `bestmove`；`(none)`/超时返回 None
- `is_mate(fen, movetime_ms)`：对方无路可走即返回 True（绝杀/困毙）
- **`quit` 必须只在 close() 时发**，且所有 bestmove 均已返回；若与 `go` 批量写入会浅层搜索（棋力骤降）
- 后台线程持续读 stdout（`text=True`），一条条发指令并等待对应标记

### game.py — 对局状态机

构造参数：`GameSession(device, log, on_state, ask_turn)`，由 server 的**单个 worker 线程**调用。

- 状态：`board`(10x9)、`prev`(上帧矫正图)、`my_side`、`_turn`、`phase`、`pending_move`、`game_over`、`_running`（自动对弈循环进行中）
- `sync()`：无历史或已结束 -> `_reset` + `_init_from_corrected`（截图矫正 -> 全量分析 -> 判方（r_K/b_k 谁在 6..9 行）-> 判阶段/轮次）；否则 `_pull`（认输检测 -> diff_cells -> 重分析变化格 -> 敌方走棋处理）。**自动开始对弈**：`phase=="开局"`，或 `_is_fresh_one_move()` 成立（全 32 子在盘、仅对方相对默认格偏离且恰为一步移动、轮到已方）—— 后者即「刚开局红方只走了一步」的场景；其余中局/残局只载入棋盘，等用户点「开始棋局」
- `_is_fresh_one_move()` / `_single_piece_moved()`：行棋严格交替，故「对方走一步、我方未走」等价于「我方全在默认格 + 对方偏离」；再要求总子数=32、对方恰 1 个默认格空出 + 1 个非默认格落子，杜绝已走多步/残局误判
- `start()`：用当前棋盘数据直接开始对弈（不重新拉取棋盘）
- `move()`：内存布局生成 FEN，命中 `pending_move` 缓存则直接用，否则引擎现算；`tap_xy` 点击，`_verify_move_loop` 校验（失败帧收集），成功分三类处理；失败进 `_recover_move_failure` 恢复流程（对局结束判定 + 一次重试），仍失败返回 False
- `interrupt()` / `answer_turn(answer)`：线程安全，从任意线程可调（打断自动对弈 / 响应开始确认弹窗，answer 为 "start"/"no"）
- `_flow()`：自动对弈主循环（我方走棋 <-> `_wait_for_enemy_move` 检测敌方走棋，无次数上限），直到中断或对局结束；**我方走棋后若敌方已在走棋校验期间走完（turn 回到我方），立即继续我方走棋，不进入敌方检测**
- `_wait_for_enemy_move()`：每 `AUTO_DETECT_INTERVAL_MS` 截图一次，无限循环；每次检测会话只提示一次「检测敌方走棋」，敌方提起棋子仅提示一次「检测到敌方提起棋子」（复位当变化消失时）
- `_status()`：`idle`(未开始) / `red` / `black`（对弈中轮到哪方）/ `over`(绝杀/对局结束) / `stopped`(中断或待开始)
- `close()`：关引擎进程
- 回调：`log(kind, msg)`、`on_state(state)`（含 board/highlight/my_side/turn/phase/status/game_over）、`ask_turn()`（无法静态推断轮次时触发网页弹窗确认是否开始）

### server.py — FastAPI + WebSocket

- `Hub`：持有当前设备与会话；`post()` 把命令投递到**后台 worker 线程**串行执行（ADB/引擎阻塞调用），`broadcast()` 经 `run_coroutine_threadsafe` 跨线程推送
- REST：`/api/devices`、`/api/connect`（serial 或 ip+port）、`/api/disconnect`、`/api/sync`、`/api/start`、`/api/interrupt`、`/api/answer_turn`
- `/ws` WebSocket：广播 `log` / `state` / `prompt_turn` / `connected` / `disconnected`；新客户端连上先补发最新 state
- 静态：`/pieces/<id>.png`（模板图）、`/`（`web/` 目录，`Cache-Control: no-cache`）

### web/ — 网页前端（原生 JS + Canvas）

- 连接页：ADB 设备列表（一键连接）+ ip:port 手动连接（连接过程显示 loading 遮罩，失败在连接卡片内提示）
- 主界面：Canvas 棋盘（900x1000 矫正比例 + 四边标注）、设备信息 + 断开设备、棋盘阶段/阵营信息、[同步棋局]、状态行、flow 按钮、日志面板
- **流程按钮互斥矩阵**（`btn-sync` 同步棋局 / `btn-flow` 开始或中断棋局）：

  | 状态 | 同步棋局 | 开始/中断棋局 |
  |---|---|---|
  | `idle`/`over`（刚连接/已结束） | 可用 | 禁用（开始棋局） |
  | `red`/`black`（对弈中） | 禁用 | 可用（中断棋局） |
  | `stopped`（残局已同步/中断后） | 可用 | 可用（开始棋局） |

  点击后进入 busy 状态：对应按钮置灰并禁止重复点击，客户端日志加一条命令日志；Python 执行完毕推送 `state` 事件后恢复（按矩阵逻辑，该禁用仍禁用）。
- 棋盘标注随 `my_side` 翻转（红：列 a..i；黑：列 i..a），数字行为固定不翻转：上方 1-9、下方 九八七六五四三二一（左->右）
- **左右两侧行号**：随红黑翻转，红方上到下 9-0、黑方 0-9；上/下坐标文字加大加粗（22px bold）
- **楚河汉界**：不绘制，棋盘为直接画满的 10x9 格子（11 横 × 9 竖线），棋子落子不受河界影响
- 棋子绘制为**纯文字 + 圆圈**（楷体类字体），`PIECE_CHARS` 映射与 `board.PIECE_CN` 一致；走棋高亮：原位 = 格中心白点，落点 = 四角白色 90 度角标
- 页面加载即绘制空棋盘（`drawBoard()`，未同步时 `board` 为 null 只画网格不画棋子）
- 开始棋局确认弹窗：收到 `prompt_turn` 显示「是否开始棋局（我方开始走棋）？」；按钮**上下排列**，「不」(`prompt-no`，灰 #444d5c) / 「开始」(`prompt-start`，绿 #27ae60)，点击 -> `/api/answer_turn`（`turn: "no"/"start"`）

### main.py — 入口

`uvicorn.run("xiangqi_bot.server:app", host="0.0.0.0", port=8900)`，打印本机/局域网地址并自动打开浏览器。

## 主流程（网页版）

1. 启动服务，浏览器打开连接页
2. `/api/devices` 列设备：USB 设备直接「使用」；无线设备输入已配对 `ip:port` 连接
3. 连接成功进入主界面（自动走棋 / 自动检测敌方走棋**常开**，无开关）
4. 「同步棋局」-> 服务端截图识别棋盘，无历史/已结束走全量初始化，否则增量拉取：
   - 全默认位 -> 红先；仅红偏离 -> 黑走；仅黑偏离 -> 红走
   - 双方均偏离或残局（棋子 < 20）-> 默认我方走棋，网页弹窗确认是否开始
   - **开局**自动开始对弈；**刚开局局面**（全棋子、对方仅走一步、轮到已方）也自动开始对弈；其余**中局/残局**只载入棋盘（stopped），等用户点「开始棋局」
5. 对弈中可「中断棋局」（暂停 flow），中断后「开始棋局」用当前棋盘数据恢复对弈
6. 任一处绝杀/认输判定 -> `game_over`，主界面提示，可再「同步棋局」重开

### 子流程 走棋结果分类（校验截图）

- 只我方走棋 -> 保存截图为历史，进入自动检测敌方走棋
- 我方走棋 + 敌方完整走棋 -> 保存截图，按对方走棋处理（立即预计算并继续）
- 我方走棋 + 敌方提子未落子 -> 不保存截图，进入自动检测
- 终点被我方走后又被吃掉 -> 打印提示并按敌方完整走棋处理
- 我方走棋后（含「敌方提子未落子」场景）`_checkmate_probe()` 探测绝杀 -> 确认则 `game_over`（提子截图不保存，FEN 用内存棋盘生成）
- 3 次校验全失败 -> `_is_mate_by_move()` 确认绝杀则按成功+结束处理；否则进入 `_recover_move_failure()` 恢复流程：
  1. 复用校验失败的截图帧判定对局是否结束（`_detect_resignation` 连续帧计数），结束则 `_finish_game` 收局
  2. 未结束则对比走棋前棋局分情况重试**一次**（`RECOVERY_WAIT_MS` 为提起后二次确认延时）：
     - 棋局未变化 -> 整步重新点击（起子+落子）重走
     - 仅我方原棋子被提起（起点变空、终点未落子、无其它变化，`_only_piece_lifted`）-> 延迟确认一次后**只点落子**
     - 其它变化 -> 无法恢复，中止
  3. 重试后照常走 `_verify_move_loop` 校验；仍失败则中止自动对弈（可「开始棋局」重试）

### 自动检测敌方走棋

- 连续截图（无额外延时，仅受 ADB 截图耗时限制），无限循环（直到用户中断或对局结束）；每次检测会话开始前重置 `_resign_streak`，且只提示一次「检测敌方走棋」
- 比较时忽略己方棋子变动；先分离"自愈"变化（我方棋子重新识别出，只修正布局）
- 其余敌方变动可推断完整着法、或出现敌方新棋子 -> 视为已走棋并处理；全是"有棋子->空" -> 提起未放下（仅提示一次，变化消失时复位）
- 将/帥缺失（且有棋子减少）或可识别棋子数比内存布局少 ≥ `RESIGN_PIECE_DROP_THRESHOLD`，
  且连续 `RESIGN_CONFIRM_COUNT` 帧稳定出现 -> 判敌方认输/对局结束

## 编程规范

- 所有依赖必须 `uv add` 添加，禁止手改 pyproject.toml 依赖后 `uv sync`
- `pyproject.toml` 需加入 doc.md 给出的 `[tool.ty.environment]` 与 `[tool.ruff]`/`[tool.ruff.lint]`/`[tool.ruff.format]` 配置（这是配置，允许直接编辑；依赖必须 uv add）
- ruff：`target-version="py312"`，`line-length=100`，双引号、空格缩进；忽略 `E501/TRY003/RUF012`
- 命名遵循 ruff `N` 规则（模块名全小写等）
- 注释/日志用中文（与现有 scripts 一致），代码本身不加多余注释
- 写完后必须跑 `.\check.ps1`（ruff format + ruff check + ty check）且通过

## 已实测的技术事实（勿重复验证）

- pikafish-bmi2.exe 用 UCI；`position startpos moves h2e2` 走红右炮；`go depth 6` 输出 `bestmove b2e2 ponder b9c7`
- **长进程下 `go movetime 3000` 用满时限（depth ~25），红方开局出 `h2e2`、黑方开局出 `b7e7`；若与 `quit` 批量写入，约 0.3s 就出 `bestmove`（只走兵，棋力骤降）**
- FEN 恒为 ICCS 绝对坐标系（黑方在上），不随我方红黑变化：我方为黑方时 `fen_of_board` 行列反转，side to move 相应为 `b`，引擎照常计算黑方着法（已实测出 `b7e7`）
- 记谱与 pikafish 方块一致：文件 = 网格列，行号 = `9 - 行`
- 矫正 + 模板匹配：raw_screenshots/ 下 6 张图（木/石 × 红/黑，含 1440x3200 高清）全部 90/90 格、32/32 棋子识别正确；矫正模板跨分辨率/跨红黑通用
- 黑方截图（木_黑/石_黑，1080x2400）棋盘上下翻转，但棋子保持正立（不需旋转模板）
- 3200 高清图 = 1080 图纯 1.3333x 缩放，BOARD_CORNERS 等比放大即可
- 引擎在 `pikafish/` 目录启动才能加载 `pikafish.nnue`
- ppadb 0.3.0-dev 无配对/连接命令（无线连接须调用本机 adb.exe）
