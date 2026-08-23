# 架构重构方案：消灭 Mixin，数据 + 纯函数 + 薄控制层

> 评审对象：`src/xiangqi_bot/game/` 状态机（6 mixin + session，约 1300 行）
> 评审日期：2026-08-22
> 核心结论：**Mixin 不适合承载业务逻辑，本项目应改为「数据结构 + 纯函数模块 + 少量 IO 类 + 薄控制层」**。

---

## 一、为什么 Mixin 不适合本项目

当前 `GameSession` 继承 6 个 mixin，把一个大类拆成了 7 个文件，但 Mixin 解决的是"代码复用"问题，而本项目并不存在跨类复用——所有 mixin 只服务于 `GameSession` 一个类。Mixin 在这里只是"把大类拆文件"的手段，却带来了实打实的代价：

1. **隐式耦合**：每个 mixin 都通过 `self` 访问 20+ 个共享属性和其他 mixin 的方法。SelfMoveMixin 依赖 CaptureMixin 的 `_dismiss_draw`、BoardDiffMixin 的 `_infer_move`、GameOverMixin 的 `_checkmate_probe`，这些依赖只散落在 `_base.py` 的部分抽象声明里，漏一个 ty 也查不出来。

2. **命名被污染**：所有方法共享 `GameSession` 命名空间，不得不用 `_apply_self_move` / `_apply_enemy_move` / `_apply_self_then_enemy` 这种又长又像的名字来避免冲突。实际上"应用一步棋到棋盘"是同一个动作，却因为命名空间冲突被迫拆成三个方法。

3. **导航困难**：48 个方法分散在 7 个文件，"这个方法在哪定义"靠全局搜索。`_base.py` 的抽象契约声明了一部分又漏了一部分，标准不统一。

4. **无法独立测试**：要测 `_classify_n3` 必须构造完整的 `GameSession`（带 device/engine/回调），尽管它本质上是个纯函数——输入变动列表，输出分类结果。

5. **状态与逻辑混杂**：`halfmove_clock` 的更新规则（吃子归零/非吃+1）在三个 apply 方法里各写一遍；`"black" if x == "red" else "red"` 的轮次切换重复多次。纯逻辑埋在有 IO 副作用的方法里，抽不出来。

---

## 二、三种候选方案对比

### 方案 A：纯函数模块（全函数，无类）

所有逻辑改为模块级函数，棋盘状态通过参数传入传出。

- 优点：最易测试，无隐式耦合
- 缺点：`Capture` 有 homography 缓存、`AutoNext` 有 scan/setup 模式状态，用函数+返回状态元组处理很笨拙；引擎已经是类。强行全函数会让有状态部分变丑。

### 方案 B：独立协作类（一切皆类，组合替代继承）

每个职责一个类（`MoveVerifier`、`EnemyMoveDetector`、`BoardRecognizer`…），通过构造函数注入依赖，`GameSession` 持有它们。

- 优点：依赖显式、命名自由
- 缺点：纯决策逻辑（n==3 分类、开局推断）做成类没有意义，每个类要写 `__init__` 注入、`self.xxx` 访问，增加样板；类之间若互传 session 引用又回到隐式耦合。本项目规模下过度设计。

### 方案 C：数据 + 纯函数 + 薄控制层（推荐）

按"有没有副作用"切分：

| 层 | 形态 | 内容 | 依赖 |
|---|---|---|---|
| **数据** | dataclass / StrEnum / NamedTuple | `GameState`、`Move`、`Change`、`Side`、各结果枚举 | 无 |
| **纯逻辑** | 模块级函数 | 开局分析、走法推断/应用、帧分类、和棋决策 | 只依赖数据结构 + vision（识别） |
| **IO** | 少量类 | `Capture`（截图+矫正+弹窗，持 homography）、`AutoNext`（结算交互，持模式状态）、`Engine`（已有） | adb / vision / engine |
| **控制层** | `GameSession` 一个类 | 主循环编排、线程同步、回调推送；持有 state + IO 类 | 上面全部 |

- 优点：纯函数零依赖可独立测；有状态的 IO 自然封装成类；命名在各自模块内自由；状态集中在 `GameState` 一目了然
- 缺点：改动面中等（主要是搬运 + 改名），但有 31 个测试兜底

**选定方案 C。** 理由：本项目的核心复杂度在"分类决策"（n==2/3/4 各种棋盘变动场景），这些天然是纯函数；IO 部分（截图/点击/引擎）已经独立，只需把 capture/auto_next 从 mixin 改成普通类；控制层保留一个类做编排，职责清晰。

---

## 三、详细设计

### 3.1 数据结构（`game/state.py`，新增）

集中定义所有跨模块传递的数据，取代散落在 `_base.py` / config 里的类型别名和魔法字符串。

```python
"""对局状态与数据结构。"""

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Literal, NamedTuple

from xiangqi_bot.board import Board, make_empty_board


class Side(StrEnum):
    """红黑方。StrEnum 直接兼容 JSON 序列化和现有 "red"/"black" 字符串。"""

    RED = "red"
    BLACK = "black"

    @property
    def opponent(self) -> Side:
        return Side.BLACK if self is Side.RED else Side.RED

    @property
    def cn(self) -> str:
        return "红" if self is Side.RED else "黑"


class Change(NamedTuple):
    """一格变动：(行, 列, 旧值, 新值)。"""

    r: int
    c: int
    old: str | None
    new: str | None


@dataclass(frozen=True)
class Move:
    """一步棋：起子格 -> 落子格，棋子 ID，被吃棋子（如有）。"""

    src: tuple[int, int]
    dst: tuple[int, int]
    piece: str
    captured: str | None = None


# ---------- 分类结果 ----------


class FrameResult(StrEnum):
    """单帧棋盘变动分类结果（纯函数 classifier 返回）。"""

    SELF_DONE = "self_done"  # 我方走棋成功
    SELF_THEN_ENEMY = "self_then_enemy"  # 我方走完 + 敌方也走完
    LIFTED_ONLY = "lifted_only"  # 提起未落（仅最后一帧有效）
    STATIONARY = "stationary"  # 无变动
    TRANSIENT = "transient"  # 有变动但无法分类
    RESIGN_SUSPECT = "resign_suspect"  # 双方将帅缺失（疑似结束）


@dataclass(frozen=True)
class FrameClass:
    """单帧分类结果 + 附带的走法数据。"""

    result: FrameResult
    self_move: Move | None = None
    enemy_move: Move | None = None


class VerifyOutcome(StrEnum):
    """_verify 多帧校验后的最终结论（控制层返回给 _do_move）。"""

    DONE_OK = "done_ok"
    DONE_END = "done_end"
    LIFTED_ONLY = "lifted_only"
    STATIONARY = "stationary"  # 全 n==0，建议外层重走 ADB
    TRANSIENT = "transient"  # 有变动没分类，不建议重走


EnemyFrame = Move | Literal["lifted", "noisy", "silent"]
"""敌方检测单帧结论：推断出走法 / 提子 / 噪声 / 无变动。"""


# ---------- 对局状态 ----------


@dataclass
class GameState:
    """一局棋的全部状态。控制层读写，纯函数只读。"""

    board: Board = field(default_factory=make_empty_board)
    prev_board: Board | None = None
    my_side: Side | None = None
    turn: Side | None = None
    phase: str | None = None  # 开局/中局/残局
    halfmove_clock: int = 0
    game_over: bool = False
    highlight: list[tuple[int, int]] = field(default_factory=list)
    last_move: str | None = None
    last_eval_score: int = 0
    # 检测瞬态（每次检测会话由控制层重置）
    resign_streak: int = 0
    noisy_count: int = 0
    lift_logged: bool = False

    def reset(self) -> None:
        """重置到新建状态（全量同步/下一局前调用）。"""
        self.board = make_empty_board()
        self.prev_board = None
        self.my_side = None
        self.turn = None
        self.phase = None
        self.halfmove_clock = 0
        self.game_over = False
        self.highlight = []
        self.last_move = None
        self.last_eval_score = 0
        self.resign_streak = 0
        self.noisy_count = 0
        self.lift_logged = False

    def snapshot_prev(self) -> None:
        """把当前 board 深拷贝为 prev_board（每轮次开头调用）。"""
        self.prev_board = [row[:] for row in self.board]
```

**要点**：
- `Side.opponent` / `Side.cn` 消除 `"black" if x == "red" else "red"` 和 `RED_CN/BLACK_CN` 重复
- `GameState.reset()` 取代 `_reset()` 手动枚举 15 个字段（消除 `__init__` 与 `_reset` 的重复）
- 控制标志（`_running` / `_interrupt` / `_auto_next` / `auto_next_game`）是线程/流程控制，不属棋局状态，留在 `GameSession`

---

### 3.2 纯逻辑模块

#### `game/opening.py` — 开局/阵营/轮次分析（从 session.py 搬出）

全部纯函数，输入 board 输出结构体，不碰 IO、不碰 self。

```python
"""开局局面分析：判阵营、判阶段、推断轮次（纯函数）。"""


@dataclass(frozen=True)
class OpeningInfo:
    my_side: Side
    phase: str  # 开局/中局/残局
    turn: Side | None  # None = 无法推断，需用户确认


def analyze(board: Board) -> OpeningInfo | None:
    """分析棋盘：返回阵营/阶段/轮次；无法判方（无将帥）返回 None。"""


def detect_side(board: Board) -> Side | None:
    """将/帥在屏幕下方（行 6..9）则该方为我方。"""


# 以下为内部函数
def _phase_and_turn(board: Board, my_side: Side) -> tuple[str, Side | None]: ...
def _color_deviates(board: Board, my_side: Side, color: Side) -> bool: ...
def _expected_start_squares(my_side: Side, color: Side) -> set[tuple[int, int]]: ...
def _single_piece_moved(board: Board, my_side: Side, color: Side) -> bool: ...
```

对应现有方法：`_detect_side` / `_analyze_opening` / `_color_deviates` / `_expected_start_squares` / `_single_piece_moved`，全部原样搬运，去掉 `self`，board/my_side 改参数传入。

#### `game/moves.py` — 走法推断、应用、格式化（从 board_diff.py + self_move.py + session.py 搬出）

```python
"""走法推断与应用（纯函数）。"""


def infer(changes: list[Change]) -> Move | None:
    """2 格变动推断一步棋：一起一落、棋子相同。"""


def apply(board: Board, move: Move, clock: int) -> int:
    """把走法写入 board（起子格清空、落子格写棋子），返回新的 halfmove_clock。
    吃子归零，非吃 +1。我方/敌方走棋同一个函数，不再分三个方法。"""
    board[move.src[0]][move.src[1]] = None
    board[move.dst[0]][move.dst[1]] = move.piece
    return 0 if move.captured is not None else clock + 1


def matches(move: Move, expected: Move) -> bool:
    """走法是否与引擎着法完全吻合（起点/终点/棋子）。"""


def format_move(move: Move, my_side: Side) -> str:
    """格式化走棋日志（红/黑方 + 棋子 + 记谱 + 吃子）。取代 _log_move。"""


def format_changes(changes: list[Change], my_side: Side) -> list[str]:
    """格式化逐格变动日志。取代 _log_updates。"""
```

**关键变化**：`_apply_self_move` / `_apply_enemy_move` / `_apply_self_then_enemy` 三个方法合并为 `moves.apply` 一个函数。连续走两步（我方+敌方）就是调两次：

```python
state.halfmove_clock = moves.apply(state.board, self_move, state.halfmove_clock)
state.halfmove_clock = moves.apply(state.board, enemy_move, state.halfmove_clock)
state.turn = state.my_side  # 两步后轮到我方
```

#### `game/classifier.py` — 帧分类（从 self_move.py + game_over.py 搬出）

这是最有价值的提取：约 250 行纯决策逻辑，目前埋在 `_verify_and_classify` 的截图循环里，必须构造完整 session 才能测。

```python
"""棋盘变动帧分类（纯函数）。"""


def classify_self_frame(
    changes: list[Change],
    new_board: Board,
    expected: Move,
    my_side: Side,
    is_last_frame: bool,
) -> FrameClass:
    """我方走棋后单帧分类：n==0/1/2/3/4/>4。"""


def classify_enemy_frame(changes: list[Change], my_side: Side) -> EnemyFrame:
    """敌方走棋检测单帧分类。"""


def is_resign_suspect(board: Board, my_side: Side) -> bool:
    """双方将/帥同时缺失（单帧疑似结束）。streak 计数由控制层维护。"""


# 内部
def _is_lifted_only(change, expected, new_board) -> bool: ...
def _find_third_cell(changes, expected) -> Change | None: ...
def _classify_n3(changes, new_board, expected, my_side) -> FrameClass | None: ...
def _classify_n4(changes, new_board, expected, my_side) -> FrameClass | None: ...
def _enemy_recapture_n2(changes, new_board, expected, my_side) -> Move | None: ...
```

注意 `is_resign_suspect` 只做单帧判断（双方将帅缺失），**连续 3 帧的 streak 计数留在控制层**——因为 streak 是跨帧的控制状态，不是纯函数该管的事。这也让 `_detect_resignation_board` 里混在一起的"判断+计数+日志"拆干净。

#### `game/recognition.py` — 棋盘识别（从 board_diff.py 搬出）

```python
"""棋盘识别：矫正图 -> (布局, 变动列表)。薄封装 vision。"""


def analyze(
    corrected: ndarray,
    templates: dict,
    prev_board: Board | None,
) -> tuple[Board, list[Change]]:
    """逐格识别（优先匹配 prev_board），一次遍历同时返回新布局和变动列表。"""
```

就是现在的 `_analyze_board_with_prev_board`，去掉 self。

#### `game/draw.py` — 和棋决策（从 capture.py 搬出）

```python
"""和棋弹窗决策（纯函数）。"""


def decide(score: int, reject_cp: int) -> Literal["accept", "reject"]:
    """score 为我方评估分（正=我方占优）；超过 reject_cp 拒绝，否则同意。"""
    return "reject" if score > reject_cp else "accept"
```

弹窗的**检测**（模板匹配）和**点击**（IO）在 `Capture` 类里，**决策**（分数判断）是纯函数。

---

### 3.3 IO 类

#### `game/capture.py` — 截图/矫正/弹窗/点击（从 CaptureMixin 改为普通类）

```python
"""ADB 截图 + 透视矫正 + 和棋弹窗处理（IO 类，持 homography 状态）。"""


class Capture:
    def __init__(
        self,
        device: Device,
        templates: dict,
        log: LogFn,
        decide_draw: Callable[[], Literal["accept", "reject"]],
    ) -> None: ...

    def grab(self) -> ndarray | None:
        """截图 → 处理和棋弹窗 → 矫正，返回 corrected 图。取代 _capture。"""

    def tap(self, r: int, c: int) -> None:
        """点击网格格心（内部逆透视映射）。"""

    def tap_xy(self, x: int, y: int) -> None: ...
    def keyevent(self, keycode: int) -> None: ...

    # 内部
    def _screenshot(self) -> ndarray | None: ...
    def _correct(self, raw: ndarray) -> ndarray | None: ...
    def _dismiss_draw(self, raw: ndarray) -> ndarray:
        """检测和棋弹窗（两按钮同时匹配），调 decide_draw() 决定同意/拒绝并点击，
        循环直到弹窗消失，返回弹窗消失后的截图。"""
```

`decide_draw` 是回调，因为和棋决策需要 session 的 `last_eval_score`：

```python
# session 构造时
self.capture = Capture(
    device,
    templates,
    log,
    decide_draw=lambda: draw.decide(self.state.last_eval_score, DRAW_REJECT_CP),
)
```

`_homography` 成为 Capture 的私有状态，不再污染 session。

#### `game/auto_next.py` — 结算交互（从 AutoNextMixin 改为普通类）

```python
"""结算画面交互 + 等待摆棋（IO 类，持 scan/setup 模式状态）。"""


class AutoNext:
    def __init__(self, device: Device, capture: Capture, templates: dict, log: LogFn) -> None: ...

    def scan_and_wait(self) -> ndarray | None:
        """扫描结算文字 → 点击按钮/发返回键 → 等待摆棋稳定，返回 corrected 帧。
        取代 _scan_gameover_interact。"""
```

内部 scan/setup 模式状态成为类的私有属性，不需要跨方法传参。

#### `engine.py` — 不动

已经是干净的长进程客户端类，保持原样。

---

### 3.4 薄控制层（`game/session.py`）

`GameSession` 不再继承任何 mixin，只做编排：持有 `GameState` + `Capture` + `AutoNext` + `Engine`，主循环里串联"截图 → 识别 → 分类 → 应用 → 引擎 → 点击"。

```python
class GameSession:
    def __init__(self, device, log, on_state=None, ask_turn=None):
        # 回调与依赖
        self.device = device
        self._log = log
        self._on_state = on_state
        self._ask_turn_cb = ask_turn
        self.templates = vision.load_templates()
        # 状态与 IO
        self.state = GameState()
        self.engine = engine.Engine()
        self.capture = Capture(device, self.templates, log, self._decide_draw)
        self.auto_next_handler = AutoNext(device, self.capture, self.templates, log)
        # 流程控制
        self._running = False
        self._auto_next = False
        self.auto_next_game = AUTO_NEXT_GAME
        self._interrupt = threading.Event()
        self._turn_answer: str | None = None
        self._turn_event = threading.Event()
```

**保留在 session 的方法**（控制流，非纯逻辑）：

| 方法 | 职责 |
|---|---|
| `start` / `interrupt` / `answer_turn` / `set_auto_next` / `close` | 公共接口 |
| `_start_flow` / `_flow` | 主循环 |
| `_do_move` / `_compute_move` / `_verify` | 我方走棋编排（计算→点击→多帧校验） |
| `_wait_for_enemy_move` | 敌方走棋检测循环 |
| `_checkmate_probe` | 绝杀探测编排（调 engine.is_mate） |
| `_auto_next_game` | 下一局编排（调 AutoNext + 初始化 + engine.newgame） |
| `_initialize` | 初始化编排（见 3.5） |
| `_confirm_start` / `_finish_game` / `_emit` / `_status` | 交互/推送 |
| `_grab_board` | 私有小工具：grab + recognize 一步到位 |

**搬出的方法**：全部纯逻辑（见 3.2）和 IO（见 3.3）。

预估 session.py 从 345 行降到约 250 行，且不再有任何棋盘分类/坐标计算逻辑。

---

### 3.5 初始化逻辑拆分（回应 `_initialize_board` 的诉求）

核心思路：**初始化分两层**——

1. `opening.analyze(board)` 是纯函数，输入棋盘数据，返回 `OpeningInfo` 结构体
2. `session._initialize(corrected)` 是薄编排：截图识别 → 调纯函数 → 写 state → 打日志，返回 `OpeningInfo`
3. `start()` 和 `_auto_next_game()` 拿到同一个 `OpeningInfo`，各自做自己的决策

```python
# session.py
def _initialize(self, corrected: ndarray) -> OpeningInfo | None:
    """从矫正图初始化棋盘，返回开局信息；判方失败返回 None。"""
    board = vision.analyze_board(corrected, self.templates)
    info = opening.analyze(board)
    if info is None:
        self._log("error", "无法判断我方红黑方（未识别到将/帥），请检查棋盘画面后重新同步")
        return None
    self.state.board = board
    self.state.prev_board = [row[:] for row in board]
    self.state.my_side = info.my_side
    self.state.phase = info.phase
    self.state.turn = info.turn
    turn_cn = info.turn.cn if info.turn else "未知"
    self._log("ok", f"我方为{info.my_side.cn}方，当前棋盘为{info.phase}，轮到{turn_cn}方")
    return info
```

`start()` 用返回值决定是否弹窗、是否启动 flow：

```python
def start(self) -> None:
    self._interrupt.clear()
    try:
        corrected = self.capture.grab()
        if corrected is None:
            self._emit()
            return
        self.state.reset()
        info = self._initialize(corrected)
        if info is None:
            self._emit()
            return
        start_now = False
        if info.turn is None:
            self.state.turn = info.my_side  # 默认我方先走
            start_now = self._confirm_start()
            if not start_now:
                self._log("ok", f"我方为{info.my_side.cn}方，当前棋盘为{info.phase}，未开始对弈")
                self._emit()
                return
        self._emit()
        if start_now or (info.phase == "开局" and info.turn is not None):
            self._start_flow()
    except Exception as exc:
        ...  # 兜底不变
```

`_auto_next_game()` 用同一个返回值，但做不同决策（残局固定红先）：

```python
def _auto_next_game(self) -> ndarray | None:
    corrected = self.auto_next_handler.scan_and_wait()
    if corrected is None:
        return None
    keep_running = self._running
    self.state.reset()
    self._running = keep_running
    info = self._initialize(corrected)
    if info is None:
        return None
    if info.phase == "残局":
        self.state.turn = Side.RED
        self._log("ok", f"残局模式：我方为{info.my_side.cn}方，轮到我方先走")
    self.engine.newgame()
    return corrected
```

对比现在：两条路径共享 `_initialize`（识别+写状态+统一日志），差异只在"轮次为空时怎么办"——start 弹窗，auto_next 残局固定红先。不再有两段几乎相同的初始化代码。

---

### 3.6 命名解放（回应命名污染问题）

拆成独立模块后，方法名不需要再加 `_self_` / `_enemy_` 前缀来避免冲突：

| 旧名（mixin 命名空间） | 新名 | 所在 |
|---|---|---|
| `_analyze_board_with_prev_board` | `analyze` | recognition.py |
| `_infer_move` | `infer` | moves.py |
| `_apply_self_move` | `apply` | moves.py |
| `_apply_enemy_move` | `apply` | moves.py（同一个函数） |
| `_apply_self_then_enemy` | 连调两次 `apply` | session 编排 |
| `_moved_matches` | `matches` | moves.py |
| `_verify_and_classify` | `classify_self_frame`（纯分类）+ `_verify`（循环） | classifier.py + session.py |
| `_is_lifted_only` | `_is_lifted_only` | classifier.py（内部） |
| `_classify_n3` / `_classify_n4` | `_classify_n3` / `_classify_n4` | classifier.py（内部） |
| `_detect_resignation_board` | `is_resign_suspect`（单帧）+ streak 在 session | classifier.py + session.py |
| `_detect_side` | `detect_side` | opening.py |
| `_analyze_opening` | `analyze` | opening.py |
| `_color_deviates` | `_color_deviates` | opening.py（内部） |
| `_expected_start_squares` | `_expected_start_squares` | opening.py（内部） |
| `_single_piece_moved` | `_single_piece_moved` | opening.py（内部） |
| `_log_move` | `format_move` | moves.py |
| `_log_updates` | `format_changes` | moves.py |
| `_draw_decision` | `decide` | draw.py |
| `_dismiss_draw` | `_dismiss_draw` | Capture 类 |
| `_take_screenshot` | `_screenshot` | Capture 类 |
| `_correct_from_raw` | `_correct` | Capture 类 |
| `_capture` | `grab` | Capture 类 |
| `_scan_gameover_interact` | `scan_and_wait` | AutoNext 类 |
| `_auto_next_game` | `_auto_next_game` | session.py（编排，保留） |
| `_checkmate_probe` | `_checkmate_probe` | session.py（编排，保留） |

模块内部的私有函数仍用 `_` 前缀，但公开函数名简短干净（`analyze` / `apply` / `infer` / `decide`），因为它们在各自模块命名空间内不冲突。

---

## 四、重构后目录结构

```
src/xiangqi_bot/
├── __init__.py / __main__.py
├── main.py                  # 入口（不动）
├── server.py                # FastAPI + WebSocket（不动）
├── config.py                # 常量（SELF_MOVE_ATTEMPTS 移入）
├── adb_client.py            # ADB 封装（不动）
├── vision.py                # 透视矫正/模板匹配（不动）
├── board.py                 # 坐标转换/FEN（不动，Side 相关中文可移走）
├── engine.py                # pikafish UCI 客户端（不动）
└── game/
    ├── __init__.py          # 导出 GameSession
    ├── state.py             # Side / Move / Change / GameState / 结果枚举（新增）
    ├── opening.py           # 开局/阵营/轮次分析（纯函数，从 session.py 搬出）
    ├── moves.py             # 走法推断/应用/格式化（纯函数，从 board_diff+self_move+session 搬出）
    ├── classifier.py        # 帧分类/认输判断（纯函数，从 self_move+game_over 搬出）
    ├── recognition.py       # 棋盘识别（纯函数，从 board_diff 搬出）
    ├── draw.py              # 和棋决策（纯函数，从 capture 搬出）
    ├── capture.py           # Capture 类：截图/矫正/弹窗/点击（IO，从 mixin 改类）
    ├── auto_next.py         # AutoNext 类：结算交互（IO，从 mixin 改类）
    └── session.py           # GameSession 薄控制层（去掉所有 mixin 继承）
```

删除：`game/_base.py`（抽象契约不再需要——依赖通过函数签名/类构造函数显式表达）。

`game/board_diff.py` / `game/enemy_move.py` / `game/game_over.py` / `game/self_move.py` 四个 mixin 文件删除，内容按上述归属搬运。

---

## 五、关键收益

1. **纯逻辑可独立单测**：`classifier.classify_self_frame` 传入 changes 列表就能测，不需要 mock ADB/引擎/回调。现有 31 个测试里大量 conftest mock 可以简化。
2. **命名干净**：`moves.apply` 一个函数取代三个 apply 方法；`opening.analyze` / `moves.infer` / `draw.decide` 简短达意。
3. **状态集中**：`GameState` dataclass 一眼看清一局棋有哪些状态；`reset()` 取代 `__init__`/`_reset` 双重维护。
4. **类型安全**：`Side` StrEnum 消除 `"red"`/`"black"` 字面量拼写错误；`FrameResult`/`VerifyOutcome` 枚举取代 `"_done_ok_"` 魔法字符串。
5. **IO 状态内聚**：`_homography` 归 Capture、scan/setup 模式归 AutoNext，不再挂在 session 上。
6. **初始化复用**：`_initialize` 一处，start/auto_next 靠 `OpeningInfo` 返回值差异化处理。
7. **halfmove_clock 规则单点**：`moves.apply` 唯一维护吃子归零/非吃+1，改规则不会漏。

---

## 六、迁移路径（分 6 步，每步跑全量测试）

每一步都是独立可提交的增量，**不改变外部行为**，跑 `.\check.ps1` + `uv run pytest tests/ -v`（31 测试全过）才进入下一步。

### 步骤 1：建数据结构（不改行为）
- 新建 `game/state.py`：`Side`、`Change`、`Move`、`GameState`、`FrameResult`/`FrameClass`/`VerifyOutcome`
- `GameSession.__init__` 里把棋局字段收拢为 `self.state = GameState()`，控制标志仍留 session
- 现有代码通过 `self.state.board` / `self.state.turn` 等访问（这一步改动面广但纯机械替换）
- `_reset()` 改为 `self.state.reset()`
- **先不删 `_base.py`**，`Change`/`MoveResult` 暂时从 state re-export 保持兼容

### 步骤 2：搬纯函数
逐个新建模块、搬运函数、session 方法改为委托调用：
1. `moves.py`：搬 `infer`/`apply`（合并三个 apply）/`matches`/`format_move`/`format_changes`
2. `opening.py`：搬 `detect_side`/`analyze`/内部辅助
3. `classifier.py`：搬帧分类 + `is_resign_suspect`
4. `recognition.py`：搬 `analyze`
5. `draw.py`：搬 `decide`

每搬一个模块，session 里对应方法改为调用纯函数，跑测试。

### 步骤 3：抽 IO 类
1. `Capture` 类：把 `_take_screenshot`/`_correct`/`grab`/`_dismiss_draw`/`tap` 搬过去，`_homography` 归它
2. `AutoNext` 类：把 `scan_and_wait` 搬过去，scan/setup 状态归它
3. session 持有 `self.capture` / `self.auto_next_handler`，调用点改委托

### 步骤 4：消灭 mixin
- `GameSession` 去掉所有 mixin 继承，只保留 `object`
- 删除 `_base.py`、`board_diff.py`、`enemy_move.py`、`game_over.py`、`self_move.py`
- 清理 import，跑全量测试

### 步骤 5：清理命名与类型
- `SELF_MOVE_ATTEMPTS` 移到 config.py
- 所有 `"red"`/`"black"` 比较改为 `Side.RED`/`Side.BLACK`
- `"_done_ok_"` 等字符串改为 `VerifyOutcome` 枚举
- 测试里的字符串断言同步改
- 跑 check.ps1 + 测试

### 步骤 6：更新文档
- 同步更新 AGENTS.md 目录结构、方法清单、架构描述
- README 如有涉及也同步

**预估总工作量**：4-6 小时（含测试调试）。步骤 1 机械替换最耗时但最简单；步骤 2 是核心价值；步骤 3-4 水到渠成。

---

## 七、不在本次范围

- **engine.py**：已是干净类，不动
- **vision.py / board.py / adb_client.py**：已实测验证的底层，不动
- **server.py worker 队列模型**：简单可靠，不动
- **前端 web/**：不受影响（`_emit` 推送的 state dict 结构不变，`Side` 是 StrEnum 序列化仍为 "red"/"black"）
- **显式状态机（FlowState Enum）**：当前 `_turn`/`game_over`/`_running` 标志组合工作良好，等分支进一步复杂化再考虑
