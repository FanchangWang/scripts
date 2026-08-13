# AGENTS.md — 中国象棋自动脚本

> 权威需求来源：`doc.md`。本文件是需求的整理与工程约定，供编码会话使用。
> 如与 `doc.md` 冲突，以 `doc.md` 为准。

## 项目概述

用 Python 编写中国象棋自动脚本：通过 ADB 控制 Android 手机（QQ/微信象棋等游戏）自动下棋。

- 用 ADB 截图识别棋盘（OpenCV 模板匹配，固定 1080x2400 分辨率）
- 用 pikafish 引擎（UCI 协议）计算下一步棋
- 用 ADB 模拟点击落子
- 终端用彩色文字打印当前棋局

## 运行环境与工具链

- Windows 11，Python 3.12
- 包管理：`uv`（禁止手写 pyproject.toml 的依赖；必须用 `uv init` 初始化、`uv add` 添加依赖）
- 运行：一律 `uv run`，禁止 `python xxx.py`
- 依赖：`pure-python-adb`、`opencv-python`、`numpy`
- dev 依赖：`ruff`（格式/检查）、`ty`（类型检查）

## 常用命令

```powershell
uv init --package          # 初始化项目（自动生成 pyproject.toml 等）
uv add pure-python-adb opencv-python numpy
uv add --dev ruff ty
uv run python -m xiangqi_bot   # 运行脚本
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
# 设备
TARGET_RESOLUTION = (1080, 2400)  # 不匹配则脚本结束

# 延时（毫秒）
TAP_HOLD_INTERVAL_MS = 300  # 点起子 -> 点落子之间的间隔
MOVE_SETTLE_MS = 500  # 落子后每次校验截图前的等待（3 次均 500ms）
MOVE_VERIFY_COUNT = 3  # 走棋校验截图次数（全部失败才判定走棋失败）

# 引擎
ENGINE_MOVETIME_MS = 1000  # go movetime <ms>（思考时间）
ENGINE_THREADS = 12  # setoption name Threads
ENGINE_HASH_MB = 2048  # setoption name Hash（MB）
ENGINE_MATE_PROBE_MS = 200  # 绝杀判断用的短时限探测（无着法会立即返回 (none)）

# 自动检测敌方走棋（毫秒）
AUTO_DETECT_INTERVAL_MS = 500  # 每 500ms 截图一次
AUTO_DETECT_MAX_COUNT = 30  # 最大检测次数（30 次 x 0.5 秒 = 15 秒）

# 图片识别
DIFF_WINDOW = 10  # 中心点 10x10 区域对比差异
DIFF_THRESHOLD = 8  # 10x10 区域平均绝对差超过此值视为"有变化"
MATCH_SEARCH_HALF = 10  # 模板匹配时在中心点 ±10px 窗口内滑动
EMPTY_MATCH_THRESHOLD = 0.8  # TM_CCOEFF_NORMED 低于此值判为空格
```

## 目录结构（目标）

```
xiangqi-bot/
├── pyproject.toml
├── check.ps1                      # ruff/ty 一键检查
├── src/xiangqi_bot/
│   ├── __init__.py
│   ├── config.py                  # 常量、路径、阈值
│   ├── console.py                 # 终端输入（EOF 容错）
│   ├── adb_client.py              # ppadb 封装：设备选择、截图、点击、wm size
│   ├── board.py                   # 网格坐标、记谱/FEN 转换、棋盘状态
│   ├── vision.py                  # 模板匹配、整盘分析、两图对比
│   ├── engine.py                  # pikafish UCI 客户端
│   ├── printer.py                 # 终端彩色棋盘打印
│   └── app.py                     # 主流程 / 菜单 / 子流程编排
├── pikafish/
│   ├── pikafish-bmi2.exe          # 引擎（必须在其目录运行，依赖 pikafish.nnue）
│   └── pikafish.nnue
├── templates/*.png                # 14 张 60x60 棋子模板（已提供，勿改）
└── scripts/                       # visualize_grid.py / extract_piece_templates.py（已有，勿改）
```

## 坐标体系（已用引擎实测验证）

### 四种坐标表示

| 表示 | 说明 |
|---|---|
| 网格 `(r, c)` | 固定于屏幕：`r` 行 0..9（0=屏幕最上，9=最下），`c` 列 0..8（0=最左）；`(0,0)` 恒为左上角格子 |
| 屏幕坐标 `(x, y)` | `GRID_CENTERS_NP[r][c]`，浮点；与网格一一对应，永远不变 |
| 记谱 `a-i/0-9` | ICCS 绝对坐标系，与 pikafish UCI 方块一致；**不随红黑方变化**：`e9` 恒为黑将、`e0` 恒为红帥 |
| FEN | 同记谱：ICCS 绝对坐标系（黑方在上）；**不随红黑方变化** |

> 关键结论（用户确认）：网格与屏幕坐标永远固定；网格<->记谱的换算**受红黑方影响**；
> 记谱<->FEN 的换算**不受红黑方影响**。从网格生成 FEN 需按红黑方处理行列翻转，
> 但从记谱转 FEN 不需要。绘制棋盘时按红黑方决定下方棋子颜色，但 FEN/记谱不变。

### 转换公式（红方视角）

```python
# 记谱 <-> 网格（红方，记谱 file/rank 与 pikafish UCI 方块完全一致，已实测 h2e2 走红右炮）
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

# 屏幕坐标
x, y = GRID_CENTERS_NP[r, c]  # np.float32 -> int(round(...)) 后用于点击/截图
```

### 示例（已实测）

- 初始棋局红右炮：网格 `(7, 7)` = 记谱 `h2` = FEN 第 8 行 8 列，屏幕 `GRID_CENTERS_NP[7,7]`
- 引擎着法 `h2e2`：红右炮从中路出到 `e2`，即网格 `(7,4)`
- 引擎着法 `b2e2`：红左炮出中路，即网格 `(7,1) -> (7,4)`
- 黑方（红方在屏幕上方）：红相走记谱 `g0 -> e2`，对应网格 `(0,2) -> (2,4)`（`grid_to_square(0,2,"black")="g0"`）
- 黑方引擎着法（不变 FEN）：`b7e7` = 黑砲架中炮，对应网格 `(7,7) -> (7,4)`（`square_to_grid("b7","black")=(7,7)`，起点为黑砲）
- 初始 FEN：`rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1`

## 棋子模板与字符映射

模板在 `templates/`，60x60，命名带 `b_`/`r_` 前缀（黑小写/红大写）。

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

### adb_client.py — ADB 封装

基于 `pure-python-adb`（已实测）：

```python
from ppadb.client import Client as AdbClient

client = AdbClient(host="127.0.0.1", port=5037)  # 默认即可
devices = client.devices()  # -> [Device]，含 .serial
device.screencap()  # -> PNG 字节（用 cv2.imdecode 解码）
device.shell("wm size")  # -> "Physical size: 1080x2400"
device.input_tap(x, y)  # -> 模拟点击
```

要点：
- 截图统一用 `device.screencap()` + `cv2.imdecode`，不落盘
- `wm size` 输出含 `Physical size: 1080x2400` 文本，需解析数字与 `TARGET_RESOLUTION` 比较
- 多设备：列编号，用户输入数字选择（**仅数字，不用方向键**）
- 无设备：提示后结束

### board.py — 棋盘状态与坐标转换

- `GRID_CENTERS_NP`：直接复制 `scripts/visualize_grid.py` 中的 10x9 矩阵（固定值）
- 记谱 <-> 网格（受红黑方影响）、FEN <-> 布局（互逆，按红黑方翻转）的转换函数
- 布局对象：10x9 数组（网格固定于屏幕，每格一个棋子 ID 或空），支持序列化为 FEN
  - FEN 每行：空格计数用数字，如 8 个空 = `8`；棋子直接写字符
  - side to move 始终写我方颜色（红 `w` / 黑 `b`），剩余字段 `- - 0 1`
  - FEN 恒为 ICCS 绝对坐标系（黑方在上）：我方为黑方时行列反转后写入
- 我方颜色判定：整盘分析后，若 `r_K` 所在行 > 5 → 我方是红方；若 `b_k` 所在行 > 5 → 黑方；都找不到 → 报错并让用户初始化/退出

### vision.py — 图片识别

- `analyze_cell(img, r, c)`：取中心点 ±`MATCH_SEARCH_HALF` 窗口，对 14 张模板各做一次 `cv2.matchTemplate(..., TM_CCOEFF_NORMED)`，取最大分；低于 `EMPTY_MATCH_THRESHOLD` 判空；返回最佳棋子 ID 或 None
- `analyze_board(img)`：遍历 90 格，返回 10x9 布局
- `diff_cells(prev_img, cur_img)`：对每格取中心点 `10x10` 区域（先 `int(round())`），算平均绝对差 > `DIFF_THRESHOLD` 视为变化，返回变化格子集合
- 性能：90 格 × 14 模板 = 1260 次小窗口 matchTemplate，可接受

### engine.py — pikafish UCI 客户端

- 每次走棋启动一个子进程（cwd = `pikafish/` 目录，引擎才能找到 `pikafish.nnue`）
- 采用**交互式对话**（后台线程持续读 stdout）：一条条发指令并等待对应标记，**`quit` 必须等收到 `bestmove` 后再发**——若与 `go` 一起批量写入，引擎会立刻读到 `quit` 中断搜索，固定时限失效、只做浅层搜索，棋力骤降（实测只走兵）
- 协议（已实测）：
  - `uci` -> 等 `uciok`
  - `setoption name Threads value 12`、`setoption name Hash value 2048`（增强固定时限下的棋力）
  - `isready` -> 等 `readyok`
  - `position fen <fen>`
  - `go movetime 1000` -> 等 `bestmove`（红方开局出 `h2e2`、黑方开局出 `b7e7`）
  - `quit`
- 着法解析：`bestmove` 形如 `h2e2`（6 字符：4 字符起止格）。`(none)` 表示无着法（终局），打印提示
- 注意 stdout 解码容错（`text=True`，UCI 输出为 ASCII）

### printer.py — 终端打印棋盘

- 每格内容为**单个全角汉字**，空格用全角空格 `　`，保证所有格子等宽、棋盘矩形规整
- 红子用红色、黑子用蓝/青色（或黑子默认色），空格用默认色
- 上、下边缘标注列 `a b c d e f g h i`；左、右边缘标注行号 `0..9`（上为 9，下为 0）。该标注即 ICCS 绝对坐标系（红方视角：红方在下、行 0 在下）。**屏幕下方即我方一侧，显示始终按网格第 0 行在上、第 9 行在下（我方在下方）；我方为黑方时仅翻转标注**：列从左到右变为 `i..a`，行号上为 0、下为 9
- 列标注与棋子按显示宽度对齐：每格 = 全角子占 2 列 + 左右空格 = 4 列，格距 5（4 内容 + 1 竖线），列标注每格 5 列、字母居格中心；横线每段 4 个 `─`（`┌┬┐`/`├┼┤`/`└┴┘`），与竖线 `│` 逐列对齐
- 可选高亮：传入起止格 `(r1,c1),(r2,c2)`，用反色/背景色标记棋子原位置与当前位置（着法如 `h2e2`）
- Windows 终端需启用 VT 转义：`ctypes.windll.kernel32.SetConsoleMode` 开启 `ENABLE_VIRTUAL_TERMINAL_PROCESSING`（或用 `subprocess.run("", shell=True)` 兜底）

## 主流程（依 doc.md + 已确认的澄清）

1. 连接 ADB，取设备列表
   - 0 台：提示并结束
   - 1 台：直接用
   - 多台：列出编号，输入数字选择（仅数字）
2. `wm size` 校验分辨率 == `1080x2400`，否则结束
3. 主菜单：**初始化对局（默认回车）** / `1` 切换自动走棋（默认开） / `2` 切换自动检测敌方走棋（默认开） / `q` 退出
4. 初始化对局（确定我方红/黑）
5. 进入颜色对应的子菜单循环：

**红方菜单（默认 走棋）**
```
走棋（默认，回车） / 拉取新棋盘数据 / 初始化对局 / q 退出
```

**黑方菜单（默认 拉取新棋盘数据）**
```
拉取新棋盘数据（默认，回车） / 走棋 / 初始化对局 / q 退出
```

> 说明（用户确认）：
> - 自动走棋 / 自动检测敌方走棋为**会话级开关**（默认开），在主菜单按 `1`/`2` 切换，跨对局生效，初始化对局时不再询问
> - 菜单默认项按 `has_moved` 状态切换：红方仅初始化后第一次默认“走棋”（红先手）；我方任一色**走动成功后**（`has_moved=True`）默认改为“拉取新棋盘数据”（等对方走棋）；拉取/走棋校验检测到对方走子后回到“走棋”默认
> - **对局结束**（`game_over`）：`_side_loop` 顶部检测，不再显示子菜单，直接回到主菜单（只显示 初始化对局 / q 退出）；任一处（初始化自动走棋、走棋、拉取自动走棋）触发绝杀/困毙判定后都走此流程
> - **自动检测敌方走棋**（`_wait_for_enemy_move`）：每 500ms 截图一次、最多 20 次（10 秒）；比较时**忽略己方棋子变动**（己方起止格已反映在内存布局，`old == new` 自动跳过），只统计敌方棋子；敌方变动构成完整着法即分析走棋方案（`_on_enemy_move`）

### 子流程 初始化对局

1. 清除内存历史：`prev_screenshot = None`，`my_side = None`，`has_moved = False`，`pending_move = None`（截图与状态**只存内存**，不落盘）；`auto_move`/`auto_detect` 为会话级开关，由主菜单切换，此处不询问
2. `screencap()` 截图
3. `analyze_board()` 分析全部 90 格
4. 判断我方红/黑（`r_K`/`b_k` 哪个在下方，行 > 5）
5. `prev_screenshot = 当前截图`
6. 打印当前棋局
7. **预计算**：我方为红方时立即调 pikafish 算出 `bestmove` 缓存到 `pending_move`（我方为黑方时红先手，暂无着法）
8. **自动走棋**：预计算成功（存在着法）且 `auto_move` 开启 → 直接执行走棋；未开启 → 只回菜单

### 子流程 打印当前棋局

- 彩色终端棋盘（见 printer.py），上下标 `a-i`、左右标 `0-9`，红黑异色；行列均有边框线（`┌┬┐`/`├┼┤`/`└┴┘`）
- 若知道变化路径（如 `h2e2`）或用 `_infer_move()` 推断出走子，高亮起止两格；推断不出则高亮全部变化格子

### 子流程 拉取新棋盘数据

1. `screencap()` 截图
2. 无历史截图（`prev_screenshot is None`）→ 走“初始化对局”逻辑（全量分析 + 判方 + 存历史）
3. 有历史截图 → `diff_cells()` 找出变化格子
4. 对每个变化格子用 `analyze_cell()` 重新分析
5. 更新布局、`prev_screenshot = 当前截图`
6. 有变化（对方已走子，轮到我们）：
   - 高亮起止两格（推断出走子）或全部变化格子（无法推断），再打印棋局
   - `_infer_move()` 推断走子并打印日志：
     - 2 格且满足"一格变空 + 另一格同棋子填入" → `黑方走炮：h7->e7` 这类日志（吃子则附 `（吃XX）`）
     - 变化较多难以推断 → 只列逐格变化：`h7 黑炮->空`、`e7 空->黑炮`（格子用 `a2 b3` 记谱）
   - **预计算**：立即调 pikafish 算出我方着法缓存到 `pending_move`
   - **自动走棋**：`auto_move` 开启 → 直接执行走棋；未开启 → 只回菜单
7. 无变化：打印"棋盘无变化"，不动 `pending_move`

### 子流程 走棋

1. 用**内存布局**直接生成 FEN（side = 我方颜色），**不刷新截图**（用户确认：只按内存棋盘走棋）
2. 若 `pending_move` 的 FEN 与当前一致 → 直接用缓存的 `bestmove`（节省引擎计算时间）；否则现场调 pikafish 计算
3. 解析起止格，用 `GRID_CENTERS_NP` 取屏幕坐标
4. 打印日志：棋子、原格子+屏幕坐标、目标格子+屏幕坐标
5. `input_tap(起点)`，间隔 500ms，`input_tap(终点)`
6. 校验（`_attempt_move`）：**3 次**截图、每次等待 500ms（`MOVE_SETTLE_MS`）；`_verify_our_move()` 判定本次截图走棋是否成功
   - 起点已无我方棋子（可能被敌人移到别处或空）+（终点为我方棋子 或 终点已被敌人占据/改变）→ 成功
   - 起点仍有我方棋子 → 本帧未成功
   - 3 次全部未识别 → 先 `_is_mate_by_move()`：按内存布局假设该着法已生效，用引擎探测对方是否无着法（`(none)` = 绝杀/困毙）；确认绝杀则按走棋成功 + `game_over` 处理（结束动画会挡住棋子导致识别失败），否则走棋失败
7. 成功：`_apply_move_result()` 先更新布局起止格，再按校验截图内容分三类处理
   - 只我方走棋（无其它变化）→ 提示走棋成功、**保存截图**为历史，进入下一流程（自动检测敌方走棋或显示菜单）
   - 我方走棋 + 敌方完整走棋（`_enemy_changes()` 变化可被 `_infer_move` 推断）→ 提示走棋成功、**保存截图**、按“对方走棋”处理（打印日志、**立即预计算**下一手；`auto_move` 开启则继续自动走棋）
   - 我方走棋 + 敌方提子未落子（变化不可推断）→ 提示走棋成功、**不保存截图**，进入下一流程（自动检测敌方走棋或显示菜单）
   - 若终点被我方棋子走后又遭对方吃掉 → 额外打印“对方吃掉了走到 XX 的我方XX”（并按“敌方完整走棋”处理）
   - 下一流程：`auto_detect` 开启 → `_wait_for_enemy_move()`；否则回菜单
   - 我方走棋后 `_checkmate_probe()` 探测绝杀 → 确认则 `game_over`
8. 失败：提示菜单
   ```
   重新走棋（默认，回车） / 拉取新棋盘数据 / 初始化对局 / q 退出
   ```
   - 重新走棋：重试同一着法的点击（布局未变）
   - 拉取新棋盘数据：用户可能手动走棋了
   - 初始化对局 / q：同前

## 编程规范

- 所有依赖必须 `uv add` 添加，禁止手改 pyproject.toml 依赖后 `uv sync`
- `pyproject.toml` 需加入 doc.md 给出的 `[tool.ty.environment]` 与 `[tool.ruff]`/`[tool.ruff.lint]`/`[tool.ruff.format]` 配置（这是配置，允许直接编辑；依赖必须 uv add）
- ruff：`target-version="py312"`，`line-length=100`，双引号、空格缩进；忽略 `E501/TRY003/RUF012`
- 命名遵循 ruff `N` 规则（模块名全小写等）
- 注释/日志用中文（与现有 scripts 一致），代码本身不加多余注释
- 写完后必须跑 `.\check.ps1`（ruff format + ruff check + ty check）且通过

## 已实测的技术事实（勿重复验证）

- pikafish-bmi2.exe 用 UCI；`position startpos moves h2e2` 走红右炮；`go depth 6` 输出 `bestmove b2e2 ponder b9c7`
- **`go movetime 3000` 单独发时用满 3000ms（depth ~25），红方开局出 `h2e2`、黑方开局出 `b7e7`；若与 `quit` 批量写入，约 0.3s 就出 `bestmove`（只走兵，棋力骤降）**
- FEN 恒为 ICCS 绝对坐标系（黑方在上），不随我方红黑变化：我方为黑方时 `fen_of_board` 行列反转，得到 `rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR b - - 0 1`，side to move = `b`，引擎照常计算黑方着法（已实测出 `b7e7`）
- 记谱与 pikafish 方块一致：文件 = 网格列，行号 = `9 - 行`
- ppadb：`device.input_tap(x, y)`、`device.screencap()` 返回 PNG bytes、`device.shell("wm size")`
- 引擎在 `pikafish/` 目录启动才能加载 `pikafish.nnue`
