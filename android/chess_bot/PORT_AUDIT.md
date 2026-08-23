# Android ↔ Python 全量逻辑对比审计（逐方法）

> 对照基准：
> - Python：`D:\Works\scripts\python\xiangqi-bot`（工作区最终版，含全部 11 轮整改）
> - Android：`D:\Works\scripts\android\chess_bot`（当前工作区）
>
> 审计方式：两侧源文件逐方法人工比对；常量逐值核对；守卫条件/时序/资源管理专项检查。
> 本文档只记录结论与问题定位，**未做任何代码修改**。
> 标记：✅ 语义一致　⚠️ 有意差异（已裁决/平台必需）　❌ 问题（待你裁决后修复）

---

## 一、结论摘要

| 级别 | 数量 | 编号 |
|---|---|---|
| ❌ 问题 | 3 | P1 / P2 / P3 |
| ⚠️ 有意差异 | 若干 | 见各节「⚠️」标记 |
| ✅ 一致 | 其余全部 | — |

**P1【高·内存泄漏】`TextMatcher.findText` 的 `gray` Mat 从不 release**
每次调用泄漏一整张灰度图（1080×≈2400 ≈ 2.4MB native 内存）。
调用频率：`Capture.dismissDraw` 每次 `grab()` 至少 1 次；`AutoNext.scanAndWait` 每 300ms 1 次。
长会话必然累积到原生内存 OOM。位置：`TextMatcher.kt:71-89`（`gray` 在 71 行创建、循环后无 release）。

**P2【中·健壮性】`PikafishEngine.drain` 使用严格 UTF-8 解码**
python 为 `errors="replace"` 容错解码。若引擎输出含非法字节，Kotlin `readLine()` 抛异常 → drain 线程静默死亡 → `lines` 停止更新 → 所有 waitFor 只能靠超时 → 触发 3 次自愈重启后抛 EngineError。
实际 pikafish 输出为 ASCII，触发概率低，但行为与 python 不同。位置：`PikafishEngine.kt:150`。

**P3【低·常量不一致】`Const.ENGINE_THREADS = 8`，python `config.py` 为 `12`**
违反「常量数值与 python config.py 严格一致」的项目约定。影响：搜索线程数少于 PC 基线。
需你裁决：改回 12，还是确认手机 SoC 核心数后定值并在注释中说明偏离原因。位置：`Const.kt:36` vs `config.py:43`。

---

## 二、逐模块明细

### 1. config.py ↔ game/Const.kt

| python | Kotlin | 值 | 状态 |
|---|---|---|---|
| BOARD_CORNERS 两档预设 | `BOARD_CORNERS` | 同 | ✅ |
| CORRECT_CELL/W/H、TEMPLATE_SIZE=100/900/1000/60 | 同名 | 同 | ✅ |
| TAP_HOLD/MOVE_SETTLE/VERIFY_COUNT/ATTEMPTS=400/500/5/2 | 同 | 同 | ✅ |
| ENEMY_RECHECK/NOISY_MAX=500/3 | 同 | 同 | ✅ |
| RESIGN_CONFIRM/SUSPECT_WAIT=3/1000 | 同 | 同 | ✅ |
| ENGINE_MOVETIME/HASH/MATE_PROBE/RULE60=1000/2048/200/60 | 同 | 同 | ✅ |
| **ENGINE_THREADS=12** | **=8** | **不一致** | ❌ P3 |
| DRAW_TEXT_THRESHOLD/DRAW_REJECT_CP=0.75/1000 | 同 | 同 | ✅ |
| AUTO_NEXT_GAME/TIMEOUT_S/SCAN_INTERVAL/STABLE_THRESHOLD/RETRY_MAX=true/180/300/3/3 | 同 | 同 | ✅ |
| GAMEOVER_TEXT_THRESHOLD/TEMPLATE_W=0.75/1080；BUTTON_WORDS×4；BACK_WORDS×3（顺序一致） | 同 | 同 | ✅ |
| DIFF_WINDOW/THRESHOLD、MATCH_SEARCH_HALF、EMPTY_MATCH=10/8/10/0.8 | 同 | 同 | ✅ |
| **ENDGAME_PIECE_COUNT=24、ENDGAME_MODE_PIECE_COUNT=31** | 已删除 | — | ⚠️ M7 裁决移除 |

### 2. board.py ↔ game/Board.kt

| python | Kotlin | 状态 |
|---|---|---|
| ROWS/COLS=10/9、Board 类型、make_empty_board | 同（typealias Array<Array<String?>>） | ✅ |
| PIECE_FEN / PIECE_CN 两映射 | 同（键值逐一比对） | ✅ |
| START_SQUARES 14 项坐标 | 逐一比对相同 | ✅ |
| corrected_center(r,c)=(50+100c,50+100r) | `correctedCenter` | ✅ |
| grid_to_square / square_to_grid（红黑两套公式） | `gridToSquare`/`squareToGrid` | ✅ |
| piece_color / piece_label | `pieceColor`(返回 Side) / `pieceLabel` | ✅ |
| fen_of_board（黑方行列反转、halfmove 写第六字段、fullmove=1） | `fenOfBoard` | ✅ |
| — | `fullStartBoard(side)` 测试辅助（python 无，测试用 conftest 等价物） | ⚠️ 新增辅助 |

### 3. game/state.py ↔ game/GameState.kt + 枚举

| python | Kotlin | 状态 |
|---|---|---|
| Side(StrEnum, .opponent/.cn) | `Side(cn){opponent}` enum | ✅ |
| Phase 三态 | **两态（OPENING/ENDGAME）** | ⚠️ M7 裁决 |
| Change/Move(dataclass) | NamedTuple/dataclass | ✅ |
| FrameResult 六值/FrameClass | 同 | ✅ |
| VerifyOutcome 五值 | 同 | ✅ |
| EnemyResult+EnemyFrame=`Move|EnemyResult` | `sealed interface EnemyFrame{Moved/Lifted/Noisy/Silent}` | ⚠️ 语言惯用等价 |
| ResignResult 三值 | 同 | ✅ |
| GameState 字段清单（board…liftLogged 共 14） | 逐一比对相同；占位 phase=OPENING（python 为 None→后改非可选） | ⚠️ 有意（initialized 门控） |
| reset() 不触碰 running/_auto_next | 同（且注释说明） | ✅ |
| snapshot_prev 深拷贝 | `snapshotPrev`=copyBoard | ✅ |
| apply_self_move/apply_enemy_move/apply_self_then_enemy（写盘+clock+轮次+高亮） | `applySelfMove`/`applyEnemyMove`/`applySelfThenEnemy` | ✅（clock 吃子归零/非吃+1 一致） |
| markInitialized(mySide,phase) 置 initialized=True | 同 | ✅ |

### 4. game/opening.py ↔ game/opening.kt

| python | Kotlin | 状态 |
|---|---|---|
| detect_side（行6..9 找将/帥） | `detectSide` | ✅ |
| detect_phase：<24→残局；32 子未走/恰一方走一步→开局；其余中局 | **两态化**：<24 或 非32合法开局 → 残局；仅 32 合法开局 → 开局 | ⚠️ M7 裁决 |
| infer_turn：仅开局（全默认红先/对方一步则轮我方） | `inferTurn` | ✅ |
| _color_deviates/_expected_start_squares/_single_piece_moved | 同名私有函数，遍历/计数逻辑一致 | ✅ |

### 5. game/moves.py ↔ game/moves.kt

| python | Kotlin | 状态 |
|---|---|---|
| infer（2格一起一落、子相同；replace 格计入 arrived） | `inferMove`（Quadr 内部类替代元组） | ✅ |
| apply（写盘+吃归零/非吃+1） | `applyMove` | ✅ |
| matches | `moveMatches` | ✅ |
| format_move/format_changes（红黑方记谱、吃子注记、标题行） | `formatMove`/`formatChanges` | ✅ |

### 6. game/classifier.py ↔ game/classifier.kt

| python | Kotlin | 状态 |
|---|---|---|
| is_resign_suspect（双将同时缺失） | `isResignSuspect` | ✅ |
| classify_self_frame 分派结构（n==0/1/2/3/4/>4） | 同 | ✅ |
| n==1 `_is_lifted_only`（src 提子+dst 非本子+末帧） | `isLiftedOnly` | ✅ |
| n==2 infer 命中→SELF_DONE | 同 | ✅ |
| n==2 兜底反吃（src 空+他格敌起空+newBoard[dst]==ep；captured=r2_old） | `enemyRecaptureN2` | ✅ |
| n==3 情况1/2/3 守卫与 captured=r2_old | `classifyN3` 三情况 | ✅ |
| n==4 rest==2 + infer + captured=r2_old | `classifyN4` | ✅ |
| classify_enemy_frame 四分派 | `classifyEnemyFrame` | ✅ |

### 7. game/recognition.py ↔ game/Recognition.kt

`recognize_board(corrected, templates, prevBoard)`：90 格 `analyzeCellWithPriority(old)` + 变动收集 —— 与 python 一次遍历识别+对比一致 ✅。

### 8. vision.py ↔ vision/*（Homography / Recognizer / TextMatcher / VisionInit）

| python | Kotlin | 状态 |
|---|---|---|
| load_templates（BGR） | `VisionInit.loadPieceTemplates`（assets/templates，过滤子目录） | ✅ |
| homography(w,h) 查表+perspectiveTransform+缓存 | `Homography.get`（缓存 Map，未加锁——仅 worker 单线程访问） | ✅ |
| correct_board warp 到 900x1000 | `Recognizer.correctBoard` | ✅ |
| tap_xy 逆透视取整 | `Homography.tapXy`（SVD 求逆后手动投影） | ✅ |
| analyze_cell（中心±40 窗口、14 模板 CCOEFF_NORMED、<0.8 判空） | `Recognizer.analyzeCell`（窗口越界双向钳制——python 仅钳下界，结果等价） | ✅ |
| analyze_cell_with_priority | `analyzeCellWithPriority` | ✅ |
| analyze_board | `analyzeBoard` | ✅ |
| load_gameover/draw_text_templates（灰度缓存） | `TextMatcher.gameoverTemplates/drawTemplates` | ✅ |
| find_gameover_text/find_draw_dialog（1080 归一化、全点≥阈值降序） | `TextMatcher.findText`（每词最高分等价）+ `findGameoverScan`（词表优先级，R1） | ✅（R1 后） |
| diff_cells | 未移植（最终版 python 主流程已不使用） | ⚠️ 有意 |
| **—** | **❌ P1：`findText` 的 `gray` Mat 未 release（见摘要）** | ❌ |
| 灰度转换 BGR2GRAY | RGBA2GRAY（亮度权重对 R/B 对称，等效） | ⚠️ 平台差异 |
| 缩放插值 INTER_AREA/LINEAR 按 scale 选择 | `createScaledBitmap(filter=true)`（双线性） | ⚠️ 上行(>1080)场景质量略异 |

### 9. engine.py ↔ engine/PikafishEngine.kt

| python | Kotlin | 状态 |
|---|---|---|
| MAX_LINES=2000 环形缓冲 | ArrayDeque 上限 2000 | ✅ |
| _write OSError→EngineError | writeLine 同 | ✅ |
| _drain 后台线程 errors="replace" | UTF-8 严格读，异常线程静默退出 | ❌ P2 |
| _wait_for 行首严格匹配(reversed)+5ms 轮询+超时异常 | `waitFor` 同（nanoTime 时钟） | ✅ |
| _kill quit→wait5s→kill | kill 同（destroyForcibly） | ✅ |
| _restart 幂等清引用 | restart | ✅ |
| start 幂等+uci15s+Threads/Hash/Rule60MaxPly+isready15s | ensureStarted 同 + **额外 EvalFile 绝对路径** | ⚠️ 平台必需 |
| PIKAFISH_EXE 缺失报错 | exe.exists 检查 | ✅ |
| newgame 清行+ucinewgame+isready | 同 | ✅ |
| _go 3 次尝试/restart/0.5s/快照 | go 同（movetime+1000ms 缓冲） | ✅ |
| _parse_score cp/mate ±(100000-n)、取最后 info | parseScore 同 | ✅ |
| best_move "(none)"→null | 同 | ✅ |
| is_mate=bestMove null | isMate | ✅ |
| close quit 仅此处 | close | ✅ |
| cwd=pikafish 目录加载 NNUE | filesDir+显式 EvalFile；NNUE 首启从 assets 拷贝 | ⚠️ 平台方案 |
| — | NNUE 拷贝非原子：中途被杀留截断文件且 exists() 短路不再修复 | ❌ P2 附带风险（见问题汇总） |

### 10. adb_client.py ↔ 无障碍等价层

| python | Android | 状态 |
|---|---|---|
| screencap | MediaProjection latest() 副本（⑨） | ⚠️ 平台方案 |
| input_tap | dispatchGesture 单击（tapSync 阻塞≤1.5s，超时判 false） | ✅（超时语义为新增防御） |
| keyevent BACK | performGlobalAction(GLOBAL_ACTION_BACK) | ✅ |
| screen_size | currentWindowMetrics.bounds（启动时定格，旋转不支持） | ⚠️ 已知限制 |

### 11. game/capture.py ↔ service/Capture.kt

| python | Kotlin | 状态 |
|---|---|---|
| screenshot | `screenshot()`=latest 副本 | ✅ |
| correct（warp+homography 缓存） | `correct` | ✅ |
| tap(r,c)=逆映射+input_tap | `tap`=逆映射+手势同步点击 | ✅ |
| keyevent BACK | `back()`=GLOBAL_ACTION_BACK | ✅ |
| _dismiss_draw：双按钮同现才认定；决策只算一次；点击失败 break；SETTLE 后重截；返回(img,count) | `dismissDraw` 同（count 不外传仅日志，等价） | ✅ |
| _draw_decision 读 _last_eval_score+阈值+日志 | `decideDraw()` 回调同 | ✅ |
| **启动阶段 running=false → 弹窗循环不进入** | 同（F3 确认为有意，已加注释） | ⚠️ 共有既有行为 |

### 12. game/auto_next.py ↔ game/AutoNext.kt

| python | Kotlin | 状态 |
|---|---|---|
| 顶部三检查（continue/enabled/timeout180s） | 同（顺序一致） | ✅ |
| sleep SCAN_INTERVAL 后扫文字 | 同 | ✅ |
| count>0 先清 last_word/retry_count | 同 | ✅ |
| 31 子跳过（prev=board,stable=0） | 同 | ✅ |
| 32 子立即 return（当做开局） | 同（handOff 所有权移交） | ✅（本轮补回） |
| prev 存在且相等→stable++≥3 返回；否则 prev=board/stable=0/重新计稳定 | 同（contentDeepEquals；本轮补回前置条件） | ✅（审查补丁） |
| 按钮点击/遮罩返回键+RETRY_MAX | 同（词表优先级经 R1 修正） | ✅ |
| back/tap 失败中止 | 同 | ✅ |

### 13. game/session.py ↔ game/BotSession.kt

| python | Kotlin | 状态 |
|---|---|---|
| interrupt（running=False+event） | interrupt（interrupted 标志+BotRuntime.running 同步；**内部 running 字段延后在 finally 归位**） | ⚠️ 时序微差（见 P4 备注） |
| answer_turn(answer) | answerTurn | ✅ |
| close=interrupt+engine.close | close | ✅ |
| start：clear event→grab(None→emit)→reset→initialize(失败 dump 布局)→inferTurn/confirm→emit→cond(startFlow)；catch Exception 兜底 | 同 + **startGuard 防抖（新增防御）** + corrected finally release | ✅/⚠️ 防抖为新增 |
| startFlow：running=True→newgame→flowLoop；except Exception 记录；finally running=False+emit；结束日志 | 同 | ✅ |
| flowLoop：snapshot→敌我分支→doMove 失败暂停→gameOver 自动下一局开关 | 同 | ✅ |
| initialize：analyzeBoard(无 priority)→detect_side(失败 dump 布局)→detect_phase→写 state→日志 | 同（dump 为新增诊断） | ✅ |
| doMove：compute/unpack/attempts 循环/LIFTED 补点不消耗/TRANSIENT break | 同 | ✅ |
| computeMove：initialized 守卫→fen(clock)→bestMove→(none)短时限重试→仍 null finishGame→score 缓存 | 同 | ✅ |
| unpackMove：起点无子告警；highlight/lastMove；MOVE 日志 | 同 | ✅ |
| attemptMove：src→TAP_HOLD→dst | 同（手势同步点击） | ✅ |
| verify：5 帧 SETTLE→grab None continue→flags→classify 六分派（SELF_DONE emit+probe；SELF_THEN_ENEMY emit；LIFTED 标记；RESIGN confirmed→DONE_END；TRANSIENT stationary=false+streak=0）→认输续帧 while（CONFIRMED/SUSPECT delay/NONE break）→LIFTED 优先返回 | 同 | ✅ |
| wait_for_enemy：三计数复位→循环（Moved apply+return；Lifted once+noisy 清零；Silent 复位；Noisy resign→count++/log changes/MAX 暂停/RECHECK delay） | 同 | ✅ |
| update_resign streak/log x/N | updateResign | ✅ |
| checkmate_probe：to_move=opp FEN→isMate(probe)→异常降级 false→mated finishGame | 同 | ✅ |
| _draw_decision：读 last_eval_score+阈值+日志 | decideDraw() | ✅ |
| _auto_next_game：interrupt 检查→flag+emit→scan→reset→initialize fail→newgame→ENDGAME 红 / 其余 infer 或排局默认红+布局打印（不再暂停）→finally flag/emit | 同 | ✅（M7 裁决后形态） |
| _grab_board：grab+recognize(prev)，corrected release | grabBoard | ✅ |
| _confirm_start：置标志+清答案+200ms 轮询+可中断 | confirmStart（BotRuntime.pendingTurnConfirm 驱动中央弹窗） | ✅（UI 形态差异已裁决） |
| _finish_game：[结束触发点] 日志+game_over+gameover 日志+emit | finishGame（协程堆栈打印未移植，已记录） | ⚠️ 有意简化 |
| _status/emit/statusLine | statusCn+emit（中文状态行） | ✅（文案差异已裁决） |
| — | startGuard 防抖 | ⚠️ 新增防御（python 无，队列天然串行） |

### 14. server.py / web/ ↔ OverlayManager / MainActivity / 悬浮窗

平台范式不同，按行为映射核对：

| python 行为 | Android 对应 | 状态 |
|---|---|---|
| worker 单线程串行命令 | botScope 单线程执行器 | ✅ |
| interrupt 即时生效（不走队列） | interrupt 直调 | ✅ |
| WS 推送 log/state/prompt_turn | SharedFlow/StateFlow + 中央弹窗 | ✅ |
| 网页 flow 按钮 idle/over 可用、red/black/auto_next=中断、stopped=开始 | 操作条 onStartStop 以 BotRuntime.running 区分 | ✅ |
| 自动下一局开关实时切换+持久化 | DataStore+Switch | ✅ |
| 日志面板最近 N 条 | logs 上限 100 + 主界面 50 | ✅ |
| — | 权限引导页/省电白名单行/收起展开/右缘停靠 | ⚠️ 平台新增 |

---

## 三、发现问题汇总（❌，均未修改，等你裁决）

| # | 级别 | 位置 | 问题 | 影响与建议方向 |
|---|---|---|---|---|
| P1 | 高 | `vision/TextMatcher.kt:71-89` | `findText` 中 `gray` Mat 创建后从未 release | 每次调用泄漏 ~2.5MB native；dismissDraw 每帧、AutoNext 每 300ms 各调一次，长会话必然 OOM。建议：循环结束后 `gray.release()` |
| P2 | 中 | `engine/PikafishEngine.kt:150` | drain 读取用严格 UTF-8，异常即静默终止读取线程（python 为 replace 容错） | 引擎输出异常字节时退化为连续超时自愈循环。建议：readLine 换容错解码或捕获后继续 |
| P2b | 中 | `engine/PikafishEngine.kt:36-39` | NNUE 首启拷贝非原子：中途进程被杀会残留截断文件，之后 `exists()==true` 永远使用坏权重 | 建议：先拷 `*.tmp` 再 rename，或校验长度与 assets 一致 |
| P3 | 低 | `Const.kt:36` | ENGINE_THREADS=8 ≠ python 12 | 违反“严格一致”约定；请裁决目标值（手机核心数可能少于 PC，若维持 8 请在注释注明偏离依据） |

## 四、有意差异 / 平台新增（已裁决或必需，非问题）

1. Phase 两态化（移除 MIDDLE）+ 闯关排局默认红先（M7）
2. captured=r2_old 修正全套带入（与 python 最终版一致）
3. EnemyFrame sealed interface；ResignResult/VerifyOutcome 枚举化
4. turn 推断移出 initialize（start/autoNext 各自闭环）
5. EvalFile 显式路径 + filesDir 加载 NNUE；二进制随 jniLibs 入库
6. latest() 返回副本（防串帧）；startGuard 防抖；CoroutineExceptionHandler 兜底
7. 日志镜像 adb logcat（tag=ChessBot）；finishGame 协程堆栈打印未移植（有意简化）
8. 省电白名单行（系统 API 判定+弹窗设置）；操作条右缘停靠/收起展开/纵向拖动锁定
9. 轮次确认中央模态弹窗替代网页 prompt_turn
10. AutoNext 31 子特判以字面量表达（原常量已删）

## 五、死代码清理候选（不影响运行）

- `BotSession.statusCn()`：已被 emit 内联 when 取代，无调用方
- `BotAccessibilityService.tap(x,y,onResult)`（回调版）：状态机走 tapSync，悬浮窗测试钮已删，当前无调用方
- python 侧 `vision.diff_cells`/`analyze_cell`（后者仍被 analyze_board 内部调用）在 Android 侧未移植 diff_cells —— 与最终 python 主流程一致

---

## 六、审核指引

建议按此顺序核对：第三节常量表 → 第 6 节分类器六分支 → 第 13 节 session 逐方法 → 第 9 节引擎协议 → 第 8 节视觉算法 → 其余。
每个 ❌/⚠️ 条目都给出了 文件:行号 定位。核对过程中发现新的疑点，直接在本文档对应行追加批注即可。
