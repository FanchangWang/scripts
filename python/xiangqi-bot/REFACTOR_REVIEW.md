# 重构审查报告（Mixin → 纯函数 + IO 类 + 薄控制层）

> 审查对象：未提交的工作区变更（相对 HEAD `b97dddd`）
> 审查方式：逐行对比旧实现（6 mixin + `_base.py`）与新实现（`state/opening/moves/classifier/recognition/draw` + `Capture/AutoNext` IO 类 + 薄 `GameSession`），并实跑检查与测试
> 审查日期：2026-08-22

## 一、总体结论

**重构方向合理，可以接受。** 新结构与设计文档 `ARCHITECTURE_REFACTOR.md` 的方案 C 一致：
纯决策逻辑（帧分类/走法推断/开局分析/和棋决策）全部变为可独立测试的纯函数，
IO 副作用收敛到 `Capture`/`AutoNext` 两个类，控制流集中在 `session.py`。
删除 `_base.py` 抽象契约是正确的——依赖现在通过函数签名显式表达。

**验证结果（均通过）：**

- `.\check.ps1`：ruff format 无改动、ruff check 通过、ty check 通过
- `uv run pytest tests/ -v`：31 个场景全过

**确认语义等价的核心逻辑（逐行对比无回归）：**

| 逻辑 | 结论 |
|---|---|
| `moves.infer` / `apply` / `matches` | 与旧 `_infer_move` / 三个 apply 方法等价；halfmove_clock 吃子归零/非吃+1 规则单点化 ✓ |
| n==2 干净走棋 / n==2 敌方反吃兜底 / n==3 三情况 / n==4 | 判定条件与新旧 board 写入顺序完全一致 |
| 敌方检测 n==0/1/2/>2 分类、提子提示一次、噪声计数上限暂停 | 等价 |
| 认输 streak 维护、`RESIGN_CONFIRM_COUNT=3` 连续帧确认 | 等价 |
| 绝杀探测仅限 n==2 infer 命中后调用；引擎异常降级 | 等价 |
| 自动下一局扫描/按钮重试上限/31 子特判/摆棋稳定/超时 | 等价 |
| 和棋决策阈值 `DRAW_REJECT_CP` | 等价 |
| `Side(StrEnum)` 与 `board.py` 的 `"red"/"black"` 字符串比较、JSON 序列化 | 兼容性核对无误 |
| 线程模型（worker 单线程 + `interrupt`/`answer_turn`/`set_auto_next` 跨线程） | 不变 |

**顺带修复的旧问题（正向）：**

1. `_compute_move()` 新增 `turn is None` 守卫。旧代码 turn 为 None 时会生成 `to_move` 错误的 FEN 交给引擎（潜在错误着法来源），现已堵住。
2. TRANSIENT 帧清零 `resign_streak`（见问题 4）：旧实现与旧文档宣称的行为不符，新实现对齐了文档，结算画面判定更严格。

---

## 二、发现的问题

### 问题 1【中低 · 偏离设计文档】`_initialize` 不再设置 `prev_board`

- 设计文档 §3.5 明确写了 `self.state.prev_board = [row[:] for row in board]`，实现里没有这行（session.py:228-248）。
- 目前安全的原因：`_flow()` 每轮次循环头调 `snapshot_prev()` 兜底；从初始化到 flow 首帧之间没有 `_grab_board()` 调用。
- **风险**：`prev_board=None` 时 `recognition.analyze` 恒返回空 changes，一旦未来有人在确认弹窗期间或 flow 之前插入识别调用，所有帧会被分类成 STATIONARY → 重走 → "走棋尝试失败"，非常难排查。属于脆弱不变量，建议在 `_initialize` 里补一行深拷贝（与设计文档对齐）。

### 问题 2【低 · 行为变化】弹窗点「不」后状态由 stopped 变为 idle

- 旧代码在 `_confirm_start()` **之前**就设 `_turn = my_side`，拒绝后状态为 "stopped"（前端显示「中断」）；新代码只在确认成功后才写 `state.turn`（session.py:147-158），拒绝后 turn=None → 状态 "idle"（显示「未开始」）。
- `tests/test_prompt.py` 已同步改为期望 idle，前端对 idle/stopped 的按钮行为一致，功能无损，仅状态文案变化。
- 注意：设计文档 §3.5 的示例代码是"先设 turn 再确认"的旧行为，**实现与其自身设计文档不一致**（同问题 7，文档需修订）。

### 问题 3【低 · Bug】`GameState.reset()` 把 `my_side` 重置为 `Side.RED`、`phase` 重置为 `Phase.MIDDLE`

- state.py:124-126。旧 `_reset()` 重置为 None。
- 后果：初始化失败路径（无法判方，`_initialize` 返回 None）时，前端会收到 `my_side="red"`、`phase="中局"` 并显示「红方 / 中局」，而旧版显示「- / -」。对用户有误导性。
- 设计文档 §3.1 的伪代码同样是重置为 None，实现偏离。建议改回 None（需把字段类型改为 `Side | None` 等，ty 已能覆盖）。

### 问题 4【低 · 行为变化，偏正面】TRANSIENT 帧现在清零 `resign_streak`

- 旧实现中 n==1/2/3/4 未命中分类的帧**不动** streak（只有命中成功分支和 n>4 才动）；新实现任何 TRANSIENT 都执行 `resign_streak = 0`（session.py:393-395）。
- 效果：结算画面确认要求将帅缺失帧**严格连续**，中间插一个瞬态帧就重新计数。更保守、更抗误判，且与重构前 README/AGENTS 宣称的"n>=1 清除 _resign_streak"一致（旧实现其实没做到）。属合理修正，但确属行为变化，记录备查。

### 问题 5【低 · 健壮性】`Capture._dismiss_draw` 忽略点击失败

- 旧代码 ADB 点击抛 `AdbError` 时 log + break 立即退出循环；新代码 `self.tap_xy(x, y)` 的返回值被丢弃（capture.py:120），点击失败后仍继续 sleep + 截图循环。
- 有界性依赖后续截图失败（设备离线时 screencap 先挂）。实际风险小，但建议恢复"点击失败即退出"的防御。

### 问题 6【微 · 死代码】`_classify_n3` 情况 1 的 `cap` 分支不可达

- classifier.py:156 `cap = x_old if (xr, xc) == (r2, c2) else None`：情况 1 的守卫要求 `x_new is None` 且 `r2_new == piece`；若第三格就是 `(r2,c2)`，则 `x_new == r2_new == piece ≠ None` 自相矛盾，`cap` 恒为 None。
- 系从旧代码原样搬运的历史遗留，非本次引入。建议后续清理以免误导。

### 问题 7【微 · 冗余】若干无效/不可达代码

- session.py:580-582 `keep_running = self._running; self.state.reset(); self._running = keep_running`：新版 `GameState.reset()` 不再触碰 `_running`（旧 `_reset()` 会清零所以需要保存恢复），这三行现在是自赋值空操作，易误导读者以为 reset 会动 `_running`。
- session.py:646 `if not self._initialized: return "idle"` 在 `turn is None` 判断之后，当前流程下不可达；且 `_initialized` 置 True 后永不复位。
- state.py:92 `EnemyFrame` 类型别名定义后无人使用（`classifier.classify_enemy_frame` 注解写的是 `Move | str`）。建议要么用起来、要么删掉。

### 问题 8【微 · 类型标注退化】

- `capture.py`/`auto_next.py` 构造参数 `device: Any`——`_base.py` 删除后丢了精确类型（可用 `adb_client.Device`）。
- session.py:599 `_grab_board() -> tuple[Board, list] | None`：`list` 未参数化，应为 `list[Change]`。

### 问题 9【遗留物 / 待决断】

- 根目录新增两个**未跟踪**文件：
  - `ARCHITECTURE_REFACTOR.md`：设计文档，有存档价值，但其中两处设计与最终实现不一致（§3.1 reset 为 None、§3.5 先设 turn/设 prev_board），入库前应按实际实现修订，避免误导后续会话；
  - `fix_crlf.ps1`：换行符修复工具脚本，与本项目功能无关，AGENTS.md 目录结构也未收录——需决定入库或删除。
- `tests/conftest.py` 仍 mock 生产代码已不再直接调用的 `vision.analyze_cell` / `vision.diff_cells`（前者现仅被 `analyze_board` 内部使用，后者无任何生产调用方，重构前已是死代码）。无害，但属可清理项。

---

## 三、结论与建议

| 项 | 处理建议 |
|---|---|
| 问题 1 | 建议修复：`_initialize` 补 `prev_board` 深拷贝（一行） |
| 问题 3 | 建议修复：reset 回 None，失败时前端不再误显红方/中局 |
| 问题 5 | 建议修复：tap 失败即退出 dismiss 循环 |
| 问题 2/4 | 行为变化已知悉即可；如保留现状，请同步修订 ARCHITECTURE_REFACTOR.md |
| 问题 6/7/8 | 低优先级清理，可随下次改动顺带处理 |
| 问题 9 | 提交前决定两个未跟踪文件的取舍 |

整体质量良好：搬运忠实、测试同步更新、检查全绿。上述问题中没有会直接影响当前对弈正确性的缺陷（问题 3 仅影响异常路径的显示），主要风险点是问题 1 留下的脆弱不变量。

---

## 四、裁决与整改记录（2026-08-22 第二轮）

> 逐项经项目负责人裁决后整改；整改后 `.\check.ps1`（ruff format/check + ty）与
> `uv run pytest tests/ -v`（31 场景）全部通过。

| # | 裁决 | 整改动作 |
|---|---|---|
| 问题 1 | **否决**：`prev_board` 本就是"上一轮 board"快照，由 `_flow` 循环头 `snapshot_prev()` 设置符合流程，`_initialize` 不应代劳 | 不修改；原建议撤回 |
| 问题 2 | **采纳重构**：`_phase_and_turn` 拆分为独立纯函数；删除 `analyze` 与 `OpeningInfo` | opening.py 改为 `detect_side` / `detect_phase(board, my_side)` / `infer_turn(board, my_side, phase)` 三个公开纯函数；session `_initialize` 直接编排三函数 |
| 问题 3 | **保持非可选类型**：方法传参维持干净的 `Side`/`Phase`，不引入 `Side \| None` 污染。`turn` 是唯一真正三态的字段（None = 未初始化/轮次未知），由它驱动 `_status()` idle；判方失败时前端误显「红方/中局」改为在**显示层**解决 | `GameState.my_side: Side = Side.RED`、`phase: Phase = Phase.MIDDLE` 不变；web/app.js `renderStatus()` 增加守卫：棋盘无棋子（未成功同步）时阵营/阶段显示 `-` |
| 问题 4 | **接受新行为**：TRANSIENT 帧清零 `resign_streak`（对齐旧文档宣称的语义，结算判定要求严格连续帧） | 无需改动 |
| 问题 5 | **同意修复** | `Capture._dismiss_draw` 点击失败即 `break`（capture.py） |
| 问题 6 | **解释确认**：情况 1 = 我方走到空格成功 + 敌方他子提起未落（无吃子）。死代码根因：`_find_third_cell` 明确排除起/终点，`(xr,xc)==(r2,c2)` 恒假。同时发现实质缺陷——守卫丢弃了 `r2_old`，罕见帧「吃子落点 + 无关敌子闪动」会丢吃子记录导致 halfmove_clock 不归零 | 删除恒 None 死分支；captured 改取 `r2_old`（敌方棋才记录），常规场景行为不变 |
| 问题 7 | **全部采纳** | ① session.py keep_running 自赋值三行删除；② `_initialized` 变量删除（`_status()` 相应分支移除）；③ `EnemyFrame` 用起来：新增 `EnemyResult(StrEnum)`（LIFTED/NOISY/SILENT）取代硬编码 `Literal["lifted","noisy","silent"]`，`classify_enemy_frame` 返回 `EnemyFrame`，session 按 `EnemyResult.*` 比较 |
| 问题 8 | **同意** | capture/auto_next 构造参数 `device: Any` → `Device`；`_grab_board()` 返回类型 → `tuple[Board, list[Change]] \| None` |
| 问题 9 | **部分处理** | `ARCHITECTURE_REFACTOR.md` / `fix_crlf.ps1` / `REFACTOR_REVIEW.md` 保持未跟踪，后续定夺入库或删除；tests/conftest.py 已清理无生产调用方的 `analyze_cell`/`diff_cells` mock |

**顺带统一**：session.py 中 `"开局"`/`"残局"` 字符串比较改为 `Phase.OPENING`/`Phase.ENDGAME`；
`moves.format_changes(changes, self.state.my_side or Side.RED)` 的兜底去除（类型已保证非 None）。

**文档同步**：AGENTS.md / README.md 已更新 opening.py API（detect_phase/infer_turn）、
`_initialize` → bool、`EnemyResult`、`_dismiss_draw` 点击失败中止等描述。

**验证**：`.\check.ps1` 全过；31 个测试场景全过。

---

## 六、第四轮裁决（2026-08-22）：turn 去除 Optional

**裁决**：`_initialize` 已不处理 turn，`turn: Side | None` 的 None 可以去除——前端确认从不读取
turn 字段（UI 全由 `status` 驱动）；引擎路径安全的前提是 flow 各入口保证 turn 已定。

**实施**：

| 项 | 动作 |
|---|---|
| `GameState.turn` | 改为非可选 `Side = Side.RED`（未初始化期间为占位值）；新增 `initialized: bool = False` 存储 `_initialize` 处理结果（用户提议的字段），`reset()` 归 False |
| `_status()` | idle 判定由 `turn is None` 改为 `not initialized` |
| `start()` | 推断/确认完整闭环不变；拒绝后状态由 idle 变回 **stopped**（棋盘已同步未运行，语义更准） |
| `_compute_move()` | 防御守卫改用 `initialized`（防占位 turn 流入 FEN 的兜底） |
| `_flow()` | 删除 turn-None break（入口已保证） |
| `_auto_next_game()` | 无法推断轮次（摆棋中间态等）：告警 + 置 `_running=False` 暂停 flow，仍返回 corrected（保持旧行为"静默暂停"的对外结果并补显式日志；曾尝试直接中止返回 None，被 test_next 场景21 否决——30 子中间板面本就允许返回） |

**测试适配**：test_prompt 场景1 期望 stopped；test_capture/test_eat 手工会话补 `initialized=True`。

**验证**：`.\check.ps1` 全过；31 个测试场景全过。

---

## 七、第五轮（2026-08-22）：`_update_resign` 返回值枚举化

`_update_resign` 返回值由魔法字符串 `"confirmed"/"suspect"/"none"` 改为新增
`ResignResult(StrEnum)`（CONFIRMED/SUSPECT/NONE，定义于 state.py，与 FrameResult/
VerifyOutcome/EnemyResult 同风格）；session 三处调用点比较同步改为枚举。
测试断言保留字符串形式（StrEnum 与 str 相等性兼容）。

**验证**：`.\check.ps1` 全过；31 个测试场景全过。

---

## 八、第六轮（2026-08-22）：classifier 各"我方走棋成功"分支 captured 语义修正

用户复核发现 `_classify_n3` 情况2 的 `self_moved.captured = e_piece` 有误——应为
`r2_old`（我方落子前该格内容）。`e_piece = r2_new` 是随后反吃我方的敌方棋子，
不是被我方吃的子。该错误源自旧 mixin 原样搬运。

**为何至今无功能影响**：敌方紧接同格吃子必然把 halfmove_clock 归零（掩盖差异）、
board 写入不使用 captured、self_move 不单独输出日志——纯数据语义失真。

**同类排查与一并修正**（同一根因共四处）：

| 分支 | 原 captured | clock 是否被掩盖 |
|---|---|---|
| n3 情况2 | `e_piece` | 是（敌方同格反吃归零） |
| n==2 兜底 self_move | 硬编码 `None` | 是 |
| n3 情况3（敌占我原位） | 硬编码 `None` | **否**——漏记吃子会多计 halfmove_clock，影响自然限招判断 |
| n==4 `_classify_n4` | 硬编码 `None` | **否**（敌方各走各的） |

统一改为 `captured = r2_old if (r2_old 为敌子) else None`（颜色守卫防误识别帧，
与情况1写法一致）；`_classify_n4` 补 `my_side` 参数。

**顺带清理**：`_classify_n3` 签名中的 `new_board` 参数为旧 mixin 遗留的无用参数
（函数体从未引用），已移除；`_is_lifted_only`/`_enemy_recapture_n2` 确实使用
new_board 查落点，保留不变。

**验证**：`.\check.ps1` 全过；31 个测试场景全过。

---

## 九、第七轮（2026-08-22）：`_auto_next_game` 返回值改 bool

**裁决**：返回 `corrected` 是历史遗留——设计文档当初设想"摆棋帧回传调用方"，但自
mixin 时代起 `_initialize` 就在方法内部消费该帧，唯一调用方 `_flow` 只做 `is None`
判断，ndarray 载荷从未被下游使用。语义即"是否成功进入下一局"，改为 `bool`。

- 签名 `-> ndarray | None` → `-> bool`；成功（含轮次无法推断的暂停路径）返回 True，
  中断/扫描失败/初始化失败返回 False
- `_flow` 调用点简化为 `if not self._auto_next_game(): break`
- test_next.py 断言同步：`is not None/is None` → `is True/is False`

**验证**：`.\check.ps1` 全过；31 个测试场景全过。

---

## 五、第三轮裁决（2026-08-22）

| # | 裁决 | 整改动作 |
|---|---|---|
| 问题 2 补充 | **采纳**：`_initialize` 内部去除 turn 处理——turn 的后续处理 start 与自动下一局各有具体逻辑（弹窗确认 / 残局固定红先），不应把 turn 截断成两半（`_initialize` 推断一半、调用方处理一半） | ① `_initialize` 只做「识别→判方→阶段→写 state→日志」，不再调 `infer_turn`/写 `state.turn`；② `start()` 自己完成完整闭环：`infer_turn` → None 时弹窗确认 → 设 turn；③ `_auto_next_game()` 自己闭环：残局固定红先、其余 `infer_turn`。自动开始条件相应简化（确认路径 start_now 或 phase==OPENING） |

**验证**：`.\check.ps1` 全过；31 个测试场景全过。

