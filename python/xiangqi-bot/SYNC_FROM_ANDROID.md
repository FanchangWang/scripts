# Android 侧优化变更同步记录（python/xiangqi-bot）

> 方向：`android/chess_bot`（已真机验证）→ `python/xiangqi-bot`
> 状态：**已实施（2026-08-24）**。A1/A2/B1/B2/B3 经审核批准后完成；C1 被否决
> （日志刷屏便于排查，保持现状）。验证：ruff/ty 通过、pytest 32 场景全绿
> （含新增「24 子双将俱全 → 残局」回归用例）。
> Android 参考实现：`BotSession.autoNextGame` / `GameState` / `opening.detectPhase` /
> `start()` 防抖 / `PikafishEngine.ensureStarted` / `Capture.grab`（均已真机跑通连续多局）。

---

## 一、A 类：行为变更（核心，建议同步）

### A1. 移除「中局」阶段——两态化

**Android 现状**：`Phase` 仅 OPENING/ENDGAME。
判定规则：32 子且（全默认位 或 恰一方走一步）→ 开局；**其余一切 → 残局**。

**Python 待改**：
- `game/state.py`：`Phase` 移除 `MIDDLE`
- `game/opening.py::detect_phase`：

```python
def detect_phase(board, my_side):
    count = sum(cell is not None for row in board for cell in row)
    if count != 32:
        return "残局"
    red_dev = _color_deviates(board, my_side, "red")
    black_dev = _color_deviates(board, my_side, "black")
    if not red_dev and not black_dev:
        return "开局"
    if red_dev != black_dev:
        moved = "red" if red_dev else "black"
        if _single_piece_moved(board, my_side, moved):
            return "开局"
    return "残局"
```

- `config.py`：删除 `ENDGAME_PIECE_COUNT`、`ENDGAME_MODE_PIECE_COUNT`（代码中仅前者被
  detect_phase 引用，后者本就无人使用）
- 测试影响：现有用例全部兼容（原「中局」断言点即新「残局」）；建议补一条
  「24 子双将俱全 → 残局」回归用例（本次事故场景）

**解决的实测问题**：24 子残局关卡被误判中局 → 无法推断轮次 → 自动下一局暂停卡死。

### A2. 闯关排局默认红方先行 + 排局布局打印（替代「无法推断轮次」暂停）

**Android 现状**（`BotSession.autoNextGame`）：自动下一局语境下，非开局面一律视为
新的闯关排局——默认玩家执红先行，并打印完整排局布局日志供核对；不再出现暂停分支。

**Python 待改**（`game/session.py::_auto_next_game` else 分支）：

```python
else:
    inferred = opening.infer_turn(self.state.board, self.state.my_side, self.state.phase)
    if inferred is not None:
        self.state.turn = inferred
        self._log("ok", f"下一局开始：轮到{inferred.cn}方走棋")
    else:
        # 排局（非开局形态）：无法静态推断轮次，
        # 按 JJ 平台规则默认玩家（红方）先行；对齐残局固定红先规则
        self.state.turn = Side.RED
        self._log("ok", f"排局模式：{self.state.phase}、默认轮到红方走棋")
        for line in recognition_format_layout(self.state.board):   # 复用识别布局格式化
            self._log("info", f"排局 {line}")
```

（`format_layout` 若不便复用 web 版格式化，可直接打印 10 行 r9..r0 文本）

**解决的实测问题**：摆棋中间态/24 子排局不再触发「turn 轮次无法推断，自动对弈已暂停」。

**附带假设（请确认）**：JJ 象棋闯关恒为玩家执红、红方先行。若存在黑先关卡，此默认会走错
先后手——遇到时以排局日志即可发现。

---

## 二、B 类：防御与注释（低成本，建议同步）

### B1. start() 启动防抖

进行中的 start 未结束前忽略重复触发（web 端 busy 标志已防大部分，此为最后防线）。

Python 落点：`session.py::start()` 顶部加模块级/实例级 `threading.Lock` 或布尔标志，
`try/finally` 复位；重复调用打 WARN 后直接返回。

### B2. engine.start 显式设置 EvalFile

消除对「cwd 下默认权重文件名」的隐式依赖：

```python
self._write(
    proc.stdin,
    f"setoption name EvalFile value {config.PIKAFISH_DIR / 'pikafish.nnue'}",
)
```

（插在 Rule60MaxPly 之后、isready 之前；路径用 `str(...)` 保证 Windows 反斜杠兼容）

### B3. capture.grab 注释固化「启动阶段有意跳过和棋弹窗」

`_dismiss_draw` 循环条件含 `_running`，而开始棋局同步阶段恒为 False —— 该跳过是**有意
行为**（对齐最终版语义：未开始对弈不代替用户做和棋决策）。目前无注释，后人易误判为缺陷。
Python 落点：`capture.py::_grab_board` 或 `_dismiss_draw` docstring 补一句说明。

---

## 三、C 类：可选新优化（非移植产物，两侧现状相同）

### C1. 自动下一局摆棋期日志节流 【已否决——日志刷屏便于排查，保持现状】

现两侧均为每帧打印「等待摆棋：X 个棋子，重新计稳定」/「稳定 N/3」（300ms 一条），
长摆棋期间刷屏。可选方案：同一子数的中间帧只打一次，子数变化或达到阈值再打。
（Android 本次未做，如需要我可在两侧同时实现。）

---

## 四、明确不同步项（平台差异或两侧已一致）

| Android 改动 | 不同步原因 |
|---|---|
| MediaProjection 截屏 / 无障碍手势 / latest() 副本 | 平台输入输出层，python 走 ADB |
| NNUE tmp+rename 原子拷贝 | python 权重随仓库分发，无拷贝环节 |
| ENGINE_THREADS=8 | 手机裁决值；python 维持 12 |
| 日志镜像 logcat / 操作条收起停靠 / 中央弹窗 / 权限引导页 | 平台 UI 层 |
| startGuard 用 AtomicBoolean、handOffToCaller 所有权移交、latest() copy | 语言实现差异，语义上 python 已等价 |
| finishGame 协程堆栈简化 | python 保留 stderr 堆栈（网页调试用途） |

---

## 五、验收清单（A/B 类同步后）

1. `uv run pytest tests/ -v` 全绿（现有 31 场景；建议按 A1 补 24 子残局用例）
2. `.\check.ps1` 通过
3. 真机/模拟器场景：
   - 普通对局下一关 → 32 子快速进入初始化，行为不变
   - 残局关卡（含 24 子）自动下一局 → 默认红先直接进入走棋，日志含「排局模式」与布局行
   - 手动开始棋局同步一盘下到一半的棋 → 仍弹「是否由我方先走」确认框
