# chess_bot — Android 中国象棋自动对弈 Bot（AGENTS.md）

> 移植自 `python/xiangqi-bot`：同一套「截屏识别 → 引擎计算 → 注入点击 → 多帧校验」逻辑，
> 运行形态从 **PC + ADB 控制手机** 变为 **手机本机 App 悬浮窗自动化**。
> 本文件是本项目的开发约定与技术方案；改架构先改这里。
> 本文只描述**当前最终态**，历史演进与已回滚的决策见末节「开发演进与决策回滚」。

---

## 一、项目目标

悬浮窗覆盖在手机上的象棋 App（JJ 象棋）上方运行，功能与 `python/xiangqi-bot` 对齐：

| 功能 | 说明 |
|---|---|
| 开始棋局 | 截图全量同步棋盘 → 判我方红黑 → 判阶段(开局/残局) → 推断轮次（未知时确认弹窗）→ 自动对弈 |
| 我方走棋 | pikafish 算着法 → 点击起子/落子 → verifyForSelfMove v3 diff 数量分流（n==2 恰两格直接成功 / n≤4 classifySelfFrame 归类 / 5..30 灰区 / >30 OCR 检查 / 稳定兜底），提起未落补点、整步重试 |
| 敌方走棋检测 | 持续帧差分类（n==2 走法 / 提子 / 噪声 / 无变动），噪声达上限暂停 |
| 认输检测 | 双方将帅同时缺失连续 3 帧 → 结束 |
| 绝杀探测 | Y 方案：引擎 info 质量达标且 matePly 非空非 1 → 跳过探测；matePly==1 直接判定绝杀；无 mate / 盲区 → 仅终局附近（≤ENDGAME_PROBE_PIECE_MAX 子）n==2 干净走棋后调 engine.is_mate（保困毙+盲区兜底） |
| 和棋弹窗 | 同意+拒绝双按钮同现才认定；按最近评估分 > DRAW_REJECT_CP 拒绝否则同意 |
| 自动下一局 | 统一启动循环 StartLoop：结算文字交互（按钮点击/遮罩返回键，重试上限）→ 摆棋稳定等待 → 重新初始化；开关实时可切 |
| halfmove_clock | 吃子归零/非吃 +1，写入 FEN 供引擎自然限招 |

**UI 强制要求**：
1. 主界面三段式：① 权限与授权（4 项）② 棋盘四角校准 ③ 对弈；每段 Card 条目为「序号圆徽标 + 标题，右侧值/状态徽章」行样式
2. 对弈段启动按钮：点击后走 MediaProjection 授权 → 创建**悬浮操作条**；运行中按钮变「停止并退出悬浮窗」
3. 操作条两个控件：**开始/中断棋局** 按钮、**自动下一局** 开关（默认开）
4. 校准流程 2 步：① 进入人机模式（App 退后台、悬浮截图条）② 截屏识别（回 App 显示识别中→结果〔四角 300×300 裁剪图 2×2 展示〕→可选手动微调）→ 保存回主界面；保存前强制 cls 32 子校验，未通过禁止保存
5. **配置项一律单行左右结构**（左文字、右控件）；离散选项统一用 ExposedDropdownMenu 下拉，不用分段胶囊

---

## 二、技术路线总览

| 关注点 | python 实现 | Android 实现 |
|---|---|---|
| 截屏 | `adb screencap` | MediaProjection + VirtualDisplay(ImageReader)，常驻缓存最新帧按需取用 |
| 点击注入 | ADB shell tap | AccessibilityService.dispatchGesture（免 root 标准方案） |
| 视觉识别 | cv2 模板匹配(TM_CCOEFF_NORMED) + warpPerspective | OpenCV for Android 同算法移植（矫正/帧差）；**棋子识别 2026-09-05 起改 YOLO cls ONNX（16 类）**，模板仅存各皮肤套 b_r/r_R 供四角校准 |
| 象棋引擎 | pikafish 可执行文件子进程 stdio UCI | NDK 交叉编译 pikafish arm64 打进 APK（jniLibs），ProcessBuilder 子进程 stdio UCI |
| 控制台 | FastAPI + WebSocket 网页 | Compose 主界面 + WindowManager 悬浮窗；WebSocket 推送改为 StateFlow/SharedFlow |
| 状态机 | GameSession(6 mixin) 单 worker 线程 + interrupt Event | BotSession 单线程协程直译；AtomicBoolean 中断对齐 threading.Event 语义 |
| 配置 | config.py 常量 | Const.kt 全量搬移（阈值/间隔/重试上限一字不差） |

**技术栈**：Kotlin 2.x + Coroutines/Flow · Jetpack Compose + Material 3（全程无 XML 布局，悬浮窗内也用 ComposeView）· Foreground Service（`foregroundServiceType="mediaProjection"`）· DataStore Preferences · OpenCV for Android 4.x · Gradle Kotlin DSL + version catalog · JUnit 单测（python 纯函数场景逐一翻译）· 未引入 Hilt（依赖 4~5 个手工构造）。

---

## 三、关键机制设计

### 1. 权限与启动时序
```
主界面引导页依次检查/请求：
① POST_NOTIFICATIONS(33+)      FGS 通知
② SYSTEM_ALERT_WINDOW          悬浮窗特殊权限（跳系统设置）
③ 无障碍服务                    用户在系统设置中开启（BotAccessibilityService）
④ 后台运行不受限               ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（MIUI 深链方案已移除）
⑤ MediaProjection              createScreenCaptureIntent 授权 → 启动前台服务
   （Android 14+ 必须先起 mediaProjection 型前台服务再取 projection，
     且授权令牌一次性：服务被杀后需重新授权——统一从「启动按钮」走完整流程）
就绪后：创建悬浮操作条 + 信息框
```

### 2. 截屏管线（对应 Capture / ScreenCaptureSource）
- ImageReader 持续收帧，仅保留最新 Bitmap（丢弃旧帧），`grab()` 语义对齐 python：截图 → 和棋弹窗检测循环 → 矫正
- `_correct` 缓存 homography 与 python 相同；分辨率预设表从 config/vision 移植；OpenCV init 前置到 grab/correct（避免新进程首点即 UnsatisfiedLinkError）
- 取帧节拍由消费方控制（MOVE_SETTLE_MS 等），管线本身只维护最新帧

### 3. 点击管线（对应 _attempt_move / _tap_cell）
- grid(r,c) --逆单应映射--> 屏幕(x,y)（复用 python 数学）；dispatchGesture 单击 stroke
- TAP_HOLD_INTERVAL_MS 起落间隔语义不变；失败源为手势回调 result=false

### 4. 引擎进程（对应 engine.py）
- 二进制放置：APK `jniLibs/<abi>/libpikafish.so` → 安装后在 nativeLibraryDir 下可直接 exec（规避 targetSdk 29+ W^X 限制）
- NNUE：`assets/pikafish.nnue` 首启拷贝至 filesDir（临时名 + rename 原子写），启动参数显式 setoption EvalFile
- 方法一一对应：best_move / is_mate / newgame / close；ucinewgame→go movetime→bestmove 解析、(none) 重试短时限、EngineError 异常类型全部对齐
- **position 滚动重锚演进制（2026-09-12 D1=B 窗口 6 / D2=A DEBUG 日志）**：引擎 position 一律 `position fen <基线> moves <锚点后着法>`；基线由 `GameState.ensureEngineBaseline()`（无基线时首拍，幂等）与 `maybeRollEngineBaseline()`（滚动重锚）共同维护——movesList 达 `ENGINE_MOVE_WINDOW`(6) 手即重拍基线 FEN（`fenOfBoard(board, mySide, turn, halfmoveClock)`，侧向/半回合钟由当前 state 保证，50 回合规则无损）、moves 清零，**携带上限 = 窗口−1 = 5 手**。滚动仅挂在三个 apply* 提交函数末尾（board/turn/halfmoveClock 一致点）；`applySelfThenEnemy` 两手提交完才判（中途滚动会拍到错误行棋方）。取舍：窗口越大重复局面/长打感知越深（引擎靠 position 回放历史识别重复），越小 position 行越短——性能收益≈0，本机制价值在列表有界 + FEN 直接由视觉 board 生成。消费端（computeMove / maybeStartPonder / PikafishEngine）零感知

### 5. 状态机（对应 session.py GameSession）
方法级对照（2026-09-09 方案 6 拆分后，internal 扩展函数路线——类成员 private 放宽 internal，调用点零改动）：
- **BotSession.kt**（核心粘合）：start / interrupt / close / visionWarmup / initialize / finishGame / setStatus / emit / grabBoard + 全部字段
- **BotSessionFlow.kt**（主循环+我方走子）：decideStartTurn / startFlow / flowLoop / doMove / maybeStartPonder / computeMove / unpackMove / attemptMove / evalDetail / evalOverlayText / recordMateInfo / autoNextGame（嵌套类 PendingMove/Unpacked 顶层化于此）
- **BotSessionVerify.kt**（verify v3）：verifyForSelfMove / commitSelfSettled / commitSelfThenEnemy / tryCommitSelfThenEnemy / refreshBaselineCells / verifyEndgameCheck / tryRecoverSwallowedTap
- **BotSessionEnemy.kt**（敌方链）：recoverOwnLift / waitForEnemyMove / reconfirmEnemyMoved / maybeHarvestPonderCap / commitEnemyMove / applyEnemyMove
- **BotSessionEndgame.kt**（终局判定）：confirmEndByOcr / updateResign / checkmateProbe / logProbeSkipChange / decideDraw
python 对照：start / verify / waitForEnemyMove / applySelfMove / applySelfThenEnemy / applyEnemyMove / updateResign / checkmateProbe / decideDraw / autoNextGame / initialize / confirmStart / finishGame / emit

- GameState 字段照搬（board/prevBoard/mySide/turn/phase/initialized/halfmoveClock/gameOver/
  highlight/lastMove/lastEvalScore/resignStreak/noisyCount/liftLogged/lastMoveDepth）
- mySide/turn/phase 非可选 + initialized 标志；flow 入口保证 turn 已定；computeMove 以 initialized 兜底防占位值流入 FEN
- confirmStart / 轮次判定：不再弹中央模态对话框，改为 `decideStartTurn` 三路径（32 子默认位红先 / inferTurn 推断 / 残局·排局无法推断时默认**我方**先走，非红方），不自动选择
- **绝杀提前终局**：引擎 `matePly==1` 置 `selfMatePending`，verify 落子后 `SELF_DONE` 或 `RESIGN_SUSPECT` 结束画面即 `finishGame("我方绝杀…")`，省二次引擎调用并阻断 doMove 重复点击死盘；**Y 方案（2026-09-08）**：`recordMateInfo` 同时记 `mateInfoSolid`（质量达标 + matePly 非空非 1，主搜与 ponderHit 两路共用）→ `checkmateProbe` 入口直接跳过 200ms 二次探测
- **棋盘几何守卫 + 提子卡死诊断门（2026-09-08 G1=A+B；log2.txt 复审修正）**：① 摆棋 Ready 分支 det 重定位四角 vs 校准四角（`BoardGeometryGuard`，容差 `BOARD_GEOMETRY_TOL_PX=10`）拦截结束动画缩小棋盘误开局；② 缩小棋盘可能被误读为我方提子、确认计数永远到不了 Ready → SettleWaiter 在确认超 `LIFT_STALL_FRAMES(2)` 帧仍未恢复时发 `OwnLiftStalled`，两处调用方（AutoNext / waitForBoardSettled）主动 det 诊断（`LIFT_STALL_CHECK_INTERVAL_MS=10s` 限频）：**MISMATCH → `blockLift(liftPos)` 封锁该提子格**（静止缩小棋盘重置确认计数无效——复审实证重置后 2 帧即复原，封锁后同位置不再确认/恢复，换位/消失自动解除），**PASS/NO_DETECT → `ackStall()` 放行真提子恢复**；事件不内置位、节流期内每帧重发不 ack（原实现节流吞事件 + 提前 stallFired 导致门失效），诊断期间不跑恢复流程；③ 提子确认/敌方提子/recoverOwnLift「将帅不在上半区」三类重复日志全部封顶或节流；④ waitForEnemyMove 的 SILENT 需连续 2 帧才重置 `liftLogged`（防「对方提起棋子」双打）
- **ponder 质量门控改造（2026-09-08 F1-A/F2-A/F3-A）**：`go ponder` 改**裸发**（Stockfish 系 ponder 阶段不做时间检查，旧 `movetime` 实为「ponderhit 后立即到期」），时长控制上移 App 侧——收割两触发点：① 敌着命中预测 → `ponderHit()`；② 敌方思考超 `ENGINE_PONDER_CAP_MS`(3s) → waitForEnemyMove 轮询里 `maybeHarvestPonderCap()` 提前收割（结果缓存 `prematurePonderHarvested`+`pendingPonderResult`，敌走 Y 直接消费、走 Z 作废）。两路共用主搜同款 `monitorSearch` 质量门控（近杀提前停/质量达标停/盲区止损/停更止损/硬顶）后才 stop；F3-A 最短总思考时长自 go ponder 起算（elapsed 含 ponder 段，不足 TARGET 等满），硬顶时钟自 ponderhit 起算（收割等待有界）。`buildResult` 抽出共用，ponder 路径 qualityReached/seldepth/nodes 全字段透传（修复 Y 方案 mateInfoSolid 在 ponder 路径质量判定缺口的隐患）
- **D6 采样结论 + 盲区提前止损（2026-09-08）**：`ENGINE_INFO_SAMPLE` 采样 14911 行 info（128 段）离线分析（报告 `.workbuddy/d6_dynamic_threshold_plan.md`）证明：① 现行 A1/A2 **零误杀**（KEEP 行 nodes 最小 40935 ≫ 10000 门槛，合法巨 gap 行 gap=239 全幸存）→ **伪影阈值不动**（H1/H2/H3 否决：伪影 nodes 高达 19 万、nps 反而最高，均无区分力；A2 为防御性规则保留）；② 盲区真机理 = TT 饱和时引擎 <100ms 冲到伪深度后**整个搜索期沉默**（55 盲区段 200ms 后 0 新 info），不是伪影误杀；③ 盲区指纹 = 伪深度行（`d≥100 且 sd≤12`）：盲区段 50/55 出现、晚 KEEP 段 0/14 → **盲区提前止损**落地：`elapsed≥min(ENGINE_BLIND_EARLY_MS(200), TARGET) && currentInfo==null && pseudoFloodSeen` → stop（省 ~300ms/次，主搜盲区 38% → 每局省 3~6s；无指纹盲区段退回 TARGET 原时点止损）；采样代码保留、开关默认 false
- **日志架构优化（2026-09-09 D1-D6 全 A，方案 `.workbuddy/log_optimization_plan.md`）**：LogBus/LogLevel/LogTag/FileLogger 骨架不动，只做降噪/级别/文本三收敛。① **耗时拆解门控**（`grabBoard`，原占单局日志 44.7%）：零变化帧不打、`detailKey`（变化+途经+低置信+cls 拼接）与上次相同只打首条（提子悬停期逐帧重复 diff 曾刷屏）、`grabMs > Const.GRAB_LOG_SLOW_MS(80)` 或含诊断信息必打；② **级别收敛**（ERROR=真实失败/需人工介入）：中断棋局 WARN→INFO、「识别到终止弹窗自动中断」ERROR→INFO（设计内流程）、失败帧诊断 ERROR→WARN/PLAY、守卫触发布局 ERROR→WARN；③ **每步 boilerplate 降噪**：「非终局跳过绝杀探测」改 `logProbeSkipChange`（原因变化才打，`lastProbeSkip` 记账、探测真正执行时清零）、「开局库未命中」删除（未命中是常态）、「等待对方走棋」INFO→DEBUG、「生成 FEN」并入「计算着法中：$fen」；④ **开局库 7 处 tag 统一 ENGINE**（原 PLAY/ENGINE 混用）；⑤ **文本规范**：评估分统一 `%+d` 带符号、`mate=null`→`mate=-`、「对方提起棋子/棋子提起未落」统一「对方提子/我方提子未落」、轮次行「（我方）」标注统一（inferred==mySide 时附加）；⑥ 删 `LogLevel.rank` 冗余属性。实测 2401 行 → 约 1780 行（-26%），诊断能力零损失
- **grabBoard 日志两行拆分 + 棋子中文化（2026-09-09 复审 R1=A/R2=A/R3=B + D1-D6 全 A）**：原「耗时拆解」单行混装性能/变化/cls 三种语义且一格打两遍（变化段 + cls 段重复），拆为——**行1「异常行」** `grabBoard grab=Xms recog=Yms（慢帧 / diff N / 暂缓 N / 剔除 N / 漂移 N）`：仅 grab>80ms 或暂缓/剔除/漂移>0 事件必打，安静期全静默（grep grabBoard=性能与识别异常流）；**行2「变化行」** `棋盘变化：g7 空->黑砲[1.00], c8 ?->黑马(0.97)未确认`：变化+未确认并一行、内容与上一条相同才静默，cls 置信度内联进变化项（lift 概率仅 >`GRAB_LOG_LIFT_NOTE_MIN`(0.10) 附注）——grep 棋盘变化=走子回放。实现：`Change` 增 `top1Prob/liftProb` 字段（Float）、`BoardScan.clsDetail` 改 `unconfirmedDetail`（仅未确认格明细）、棋子名走 `pieceLabel`（黑X/红X/提起）、漂移自修复格首次入日志；**R4=A 收紧漂移触发**：漂移仅在「无变化帧」（`changes.isEmpty()`，静止棋盘白点/高亮自愈）时报——过渡期内已变格持续 diff 且识别值==提交值必然计为漂移（log3.txt 实测 611 次/2 局），属物理必然不进异常行；D6：ponder 行「预测敌着 b9c7」→「预测敌着 黑马 b9 -> c7」（`squareToGrid` 查我方走子后局面起点格）。模拟实测 671 行 → ~741 行（+10%，漂移事件未计），单行平均 165 → ~60-90 字符。**R4 实测遗留**：静止漂移本身仍高频（log3 中 611 条漂移行里 468 条发生在无变化帧，疑似选中高亮/最近一步标记逐帧像素漂移），R4 收紧后异常行仅 641→498，如需进一步收敛可对异常行非慢帧部分按指纹去重（变化行打印时重置）→ **R5=A 指纹去重实测仅 641→467**（diff 数逐帧波动，指纹几乎不重复）→ **R7 限频落地**：漂移仅在距上次漂移打点 ≥`GRAB_LOG_DRIFT_INTERVAL_MS`(3s) 时报（`lastDriftLogMs` 单调 ms 记账），指纹仅兜底连续相同的暂缓/剔除行；log3 模拟 641 → **109 行**（慢帧 44 逐条必打不受影响）。**【下述口径已于 2026-09-12 Q1/Q2 收敛，见下条】**
- **日志口径收敛（2026-09-12 Q1+Q2，用户批示）**：① **grabBoard 性能行** = `grabBoard grab=Xms recog=Yms`，仅 `grabMs > GRAB_LOG_SLOW_MS(80)` 时打印且**只带时间**——原「（慢帧 / diff N / 暂缓 N / 剔除 N / 漂移 N）」事件明细整块删除（剔除=空格被 cls 误读成 lift 的飞行途经伪影，本帧即弃；漂移=光效致基线自愈，与棋子变更无关；两者均无诊断价值），`lastAnomalyKey` / `lastDriftLogMs` / `GRAB_LOG_DRIFT_INTERVAL_MS` 与漂移限频逻辑一并删除；② **棋盘变化行**只由 `scan.changes`（置信度达标）拼装，置信度不足的未确认格**不再并入本行**，内容与上一条相同才静默；③ **新增「识别明细」日志（开关 `BotConfigData.debugVisionDetail`，设置页「调试日志」分组，默认关）**：落在 `recognizeBoardChanged` 内，三行结构——`识别明细 光影=6 确认=2 未确认=1` + `识别明细·确认 e7 黑將->空[1.00], …` + `识别明细·未确认 e7 黑將->空[0.35], …`；**触发门（2026-09-12 Q2 二次裁定 A）：仅当本帧有确认格或未确认格时才打整块（含汇总行）——纯漂移帧（`光影=N 确认=0 未确认=0`，UI 光效致 diff 触发但棋子未变）整块静默**（真机实测该类占汇总行 60%：834/1390、占全日志 24%；收紧后识别明细行 2027 → 1193），开关关闭时零开销；④ 新增统一表述函数 `changeText(r,c,old,new,prob,mySide)`（`Recognition.kt`，棋盘变化行与识别明细行共用，格式 `格 旧->新[置信]`）；⑤ **`BoardScan` 瘦身**：删 `transitLifts` / `unconfirmedDetail` / `unconfirmedCells` 三个纯日志字段（降为 `recognizeBoardChanged` 内局部变量），余 board/changes/diffCells/driftCells 四字段均有功能消费者（diffCells 供 verify/enemy 的 `>VERIFY_OCR_DIFF_CELLS(30)` 大面积遮挡分流）
- **统一启动循环 StartLoop（2026-09-09 U1-U4 全 A，用户提议）**：原 `AutoNext.scanAndWait`（OCR 先行但 0 命中即落摆棋分支——log.txt 事故入口）与 `BotSession.waitForBoardSettled`（仅摆棋无 OCR）合并为 `StartLoop.kt` 单循环，每轮「头部检查（中断/开关/超时）→ OCR 结算交互（终止词/遮罩返回键/按钮点击，重试上限）→ det 四角 + SettleWaiter 稳定判定」。**摆棋准入证据门**（非 32 子开局形态的 Ready 在自动路径需证据；**V2 三游戏模式建模 2026-09-09 D1=A**：①32子连续=动画后自动摆32子/②32子单局=按钮点击后摆32子/③残局单局=按钮点击后摆残局——非32子形态唯一合法来源=模式③按钮点击，故证据**仅认 `allowSettle`**（结算交互成功）；原 `resetSeen` 清盘帧证据**已废弃删除**——模式③结算页中部缩小棋盘会被 cls 读 0 子，连续 0 子≠真清盘（17:00 循环实证：resetSeen 被污染放行缩小棋盘重演的上局残盘，log4 17:00:48））；`OCR_INTERACTION_SETTLE_MS(800)`：OCR 交互成功后延时等页面切换动画播完再恢复检测；`SettleWaiter.Feed.Ready` 增 `openingForm` 标志（32 子完整开局形态直通）。路径差异经 `StartExpectation` 注入：手动（MANUAL）=无超时/下半区提子→recoverOwnLift 恢复/残局免证据/不查开关；自动=180s 超时/残局需证据/查开关/**下半区提子（敌方红方提子帧）按过渡等待不恢复**。**子状态回切**：OCR 命中切 NEXT_BUTTON/NEXT_MASK，无命中回棋盘检测时切回 `waitingStatus`（手动 WAIT_PLACEMENT/自动 AUTO_NEXT，修复原交互后状态卡死不回切）。**日志只留关键点**（2026-09-09 用户裁定）：OCR 命中类、稳定 1/2/3 计数、就绪/拒绝/证据门、提子确认与诊断流保留；「识别到 N 个棋子但将帅未同现」「31 子提子过渡态」「周期性当前识别 N 子」等常态帧日志删除（等待态由悬浮窗 waitDetail 实时呈现），`WAIT_BOARD_LOG_INTERVAL_S` 随之删除。AutoNext.kt 已清空待用户手动删除
- **敌着准入与两帧一致确认（2026-09-06 T-D）**：所有敌着提交点（waitForEnemyMove MOVED / 噪声复判 / verify N2 反吃+N3+N4 的 SELF_THEN_ENEMY / 吞点击恢复 tryRecoverSwallowedTap）统一走「伪合法校验（rules.kt isPseudoLegal）→ `reconfirmEnemyMoved` 两帧一致确认 → `commitEnemyMove` 公共提交（ponder 处理 + cellImgs 更新 + applyEnemyMove）」。复检**不加显式延时**（2026-09-06 02:30 用户实测去除原 ENEMY_MOVE_SETTLE_MS=30ms，常量已删）：单次 grabBoard 耗时 ~70-100ms，复抓本身已越过半格飞行窗口——动画中途帧（如車 C0→C9 途经 C5，几何合法、伪合法拦截不了）的 cls 读数必已变化，两帧同着法即排除中途帧；未复现则丢弃该帧回循环（MOVED 路径不计噪声），SELF_THEN_ENEMY 复判为 SELF_DONE 时仅提交我方走子（敌着视为瞬时伪影，交回 waitForEnemyMove 继续检测）
- **T-C 稳判提交后跳过 verify（2026-09-06 02:27 事故；v3 后语义调整）**：verifyForSelfMove **入口保留防御检查**（`state.board[r1][c1]==null && state.board[r2][c2]==piece` → 直接 DONE_OK）。v3 后 attemptMove 已是纯点击、不再有「重试稳判提交」入口，该检查为防御性保留；「已落定」场景由 verify 的 n==2 快速成功路径覆盖
- **v3 verifyForSelfMove 重构（2026-09-06 23:33，Q1 方案 .workbuddy/self_move_verify_redesign.md）**：diff 数量分流、无全局时间窗（maxWaitMs/maxWaitHardMs 废除）。每帧 grabBoard 一次，`n = changes.size`：
  - **(a) n==2 且恰为本步两格 → 直接成功免两帧校验**（`isSelfPairSettled(changes, expected)` 纯函数，内部复用 inferMove+moveMatches；dst→敌子的「落子即被反吃」形状已删——干净 diff 下不可达）——用户裁定「我方成败只由本步两格决定」；
  - (b) n ≤ 4 → `classifySelfFrame` 归类：SELF_DONE → 提交我步（夹带敌方仅提起格不进基线）；SELF_THEN_ENEMY → **稳定（变化格子集合与上帧逐格相同）+ T-D 复判 → `applySelfThenEnemy` 就地提交双着**（基线只刷四格），复判为 SELF_DONE 仅提交我步，未复现丢弃本帧；LIFTED → 持续 >T2(=firstWaitMs) → RETRY_DST 补点；SILENT(n==0) → 稳定 K1(=2) 帧 → RETRY_BOTH；NOISY → T-B 吞点击恢复 → RETRY_AFTER_ENEMY；
  - (c) n ∈ 5..`VERIFY_OCR_DIFF_CELLS`(30) → 动画/噪声灰区，静默继续；
  - (d) n > 30 → 大面积遮挡（和棋弹窗/结算遮罩/结束画面）→ `verifyEndgameCheck`（updateResign + confirmEndByOcr + dismissDrawDialog，OCR 类节流 `OCR_SUSPECT_SCAN_THROTTLE_MS`）；drawDialog 关闭 → RETRY_BOTH；
  - (e) 稳定未知模式持续超 `VERIFY_UNKNOWN_STABLE_MS`(2000) → 终局检查 + RETRY_BOTH（计入守卫）；liveness 硬顶 `VERIFY_HARD_CAP_MS`(15s) → RETRY_BOTH。
  - **帧间一致性 = 变化格子集合逐格比较**（同格同 old/new，**忽略 top1Prob**——cls 概率逐帧浮动，含它则 stable 永不成立，2026-09-12 与敌方链同款修复；两帧皆空也算稳定），不用自定义状态机记忆
  - **基线白名单（核心）**：所有提交点（`commitSelfSettled`/`commitSelfThenEnemy`/T-B/敌方提交）只刷新被提交着法覆盖的格子 + driftCells（`refreshBaselineCells` 合成 Change 保证落定格必刷）；其余变化格（敌方仅提起/伪影）一律留 diff 管线——防 17:59 类「无关格进基线 → 敌着两格对被拆散 → 误暂停」污染。原 SELF_DONE「方案 A filter」（按格类型排除，放行敌方落子格）与 SELF_THEN_ENEMY 全量刷基线均已废除
  - **敌着拼对规则**：敌源格只认「敌子→空格」，不认「敌子→lift」（提子格当敌源易误判）；敌落点认「空→敌子」或本步 dst（被反吃）。`inferMove` 源格条件 `new==null` 天然满足；classifyN3/N4 各分支同
  - doMove：attemptMove 退化为纯点击（原 isRetry 稳判预检删除，职责移入 verify——无时间窗后落定态必被观测，盲点重试不再有「重新提起已落子」问题）；RETRY_DST 首轮清零守卫、连续两轮计入（防补点死循环）；RETRY_BOTH 计入守卫；RETRY_AFTER_ENEMY 清零
- `doMove(): Boolean`：DONE_END 且 gameOver==true 返回 true，使绝杀后仍能进入 autoNext 自动下一局
- **空格 lift 飞行途经瞬态剔除（2026-09-06 02:27/03:02 复盘）**：cls 对「空格→lift」的读数是飞行棋子途经相邻格的动画伪影（空格不可能被提起，如炮 i2→g2 悬停 h2 上空被裁剪窗拍到）。语义约定：**空格只能变棋子或空格（动画遮挡），不能变 lift**。剔除在**扫描层**执行（recognizeBoardChanged：`old==null && new==LIFT` → 不进 changes、board 写回 committed、基线保持空格；2026-09-12 Q1 起该计数不再记账/落日志，剔除明细取消）——帧分类、噪声计数、提交与 cellImgs 基线全链路不再接触伪影；classifier.kt `stripTransitLift` 保留作纯函数层防御。真实提子（棋子→lift）不受影响
- **伪合法校验方向约定（2026-09-06 01:53 事故）**：本库网格恒定「我方在屏幕下半区（rows 5..9）」、敌方在上半区，与执红执黑无关（fenOfBoard/expectedStartSquares/detectSide 均按此约定翻转/交换）。rules.kt 的象半场、士/将九宫、兵方向与过河判定一律按「该子颜色==mySide」推屏幕方位，`isPseudoLegal(board, move, mySide)` 必传 mySide、无默认值——禁止按红黑写死绝对方向（执黑局曾因写死「红=rows 5..9」把敌方红相全部落定帧误杀成 NOISY → 误暂停）
- **~~verify 窗口动画顺延（2026-09-06）~~【v3 已废除】**：原 maxWaitMs+300ms 顺延/900ms 硬顶时间窗已随 v3 重构移除——落定帧不再可能落在窗外（verify 无限循环直到明确结论），吃子动画「起格空+落点空」中途态由灰区静默 + 稳定未知兜底覆盖
- `doMove(): Boolean`：DONE_END 且 gameOver==true 返回 true，使绝杀后仍能进入 autoNext 自动下一局

### 6. 纯函数模块直译（签名一一对应，便于翻译测试）
| python (game/) | Kotlin |
|---|---|
| state.py | state.kt：Side/Phase/Change/Move/FrameResult/FrameClass/VerifyOutcome/EnemyResult/ResignResult/GameState |
| opening.py | opening.kt：detectSide/detectPhase/inferTurn |
| moves.kt | moves.kt：infer/apply/matches/formatMove/formatChanges |
| classifier.py | classifier.kt：classifySelfFrame/classifyEnemyFrame/isResignSuspect/isSelfPairSettled（v3 快速成功判定）/cellLookup（格子查找表）；captured=r2_old 修正一并带入 |
| recognition.py | game/Recognition.kt：recognizeBoard（全量识别）+ vision/Recognizer.kt：correctBoard/analyzeCell/analyzeBoard |
| draw.py | draw.kt：decide(score, rejectCp) |
| auto_next.py | AutoNext.kt + SettleWaiter.kt（31 子持续等待 / 32 子新开局 / 将帅同现门控 / 其余连续 3 帧稳定；AutoNext 重构复用 SettleWaiter 删除内联重复）|

坐标系/FEN 规则文档照搬 board.py 头注释：网格固定屏幕左上角、记谱 ICCS、FEN 黑上红下。

### 7. 视觉资产与 ONNX 模型（2026-09-05 改造）
- **棋子识别 = YOLO cls**（assets/models/chess_pieces.onnx，6MB）：16 类（14 子 + empty + lift），输入 64×64 NCHW RGB，矫正空间格心裁块 argmax 直判（方案 A，无置信阈值）；lift 为帧分类瞬时态（Board 允许 "lift" 值，classifier 提子判定结合直接确认，提交点归一化为 null）
- **棋盘四角校准**：每次【全套遍历】assets/templates/set_NN 的 11 套皮肤角子模板（各含 b_r/r_R，对齐 templates_validate 选套思想按 4 峰均分选最优）→ 全败回退 YOLO det（assets/models/board_corners.onnx，11MB，1280 letterbox，conf=0.001 每类 argmax 取框心）→ 手动微调；**保存前强制 cls 32 子校验（含手动微调）**
- **运行时定位链不变**：Homography = 手动校准 JSON → Const.BOARD_CORNERS（第一道判断：含该分辨率则跳过校准）；运行时无自动定位
- **结算文字/和棋按钮识别 = PP-OCRv6 官方 ppocr-sdk**（2026-09-06 替代 templates/text、templates/draw 图片模板，二者已删除）：独立 module `ppocr-sdk/`（源自 PaddleOCR deploy/ppocr-android，Apache-2.0，纯 Kotlin 无 NDK，依赖已对齐 org.opencv:4.11.0 / onnxruntime:1.29.0）；模型 assets/ocr/{det.onnx, rec.onnx, rec.yml}（rec 字典在 yml 内由 SDK 直读）。TextMatcher 懒创建引擎（Mutex 双检，recScoreThresh=OCR_REC_SCORE_MIN=0.75），`ocr()` 返回整屏文本行（框中心坐标+置信度）；词匹配为**包含**语义（纯函数 matchScanWords/matchDrawDialog 可 JVM 单测）。选词优先级语义不变：遮罩词表（GAMEOVER_BACK_WORDS）优先于按钮词表（GAMEOVER_BUTTON_WORDS）、表内按序
- **和棋页面判定（2026-09-06 改 OCR 后新规则）**：「对方请求和棋」+「同意」+「拒绝」**三词同现**才算和棋页面（缺一可能是其他含同意/拒绝按钮的页面），点击 OCR 框中心
- **疑似结束画面 OCR 加速（T-OCR）**：updateResign SUSPECT 时 `confirmEndByOcr()` 整屏扫一次结算词（≥1s 节流），命中任一词 → **立即 finishGame**；未命中（结算动画尚无文字）回落原连续 RESIGN_CONFIRM_COUNT 帧棋盘信号确认，二者互补。接线三处：verify 尾部 / waitForEnemyMove NOISY SUSPECT / 噪声计满认输复检
- 结算文字/和棋按钮图片模板（templates/text、templates/draw）已删除；原 14 枚棋子 60×60 模板已删除（YOLO cls 替代）
- ONNX 推理封装：vision/OnnxRuntime.kt（Session 管理）+ CornerDetModel.kt（det）+ PieceClsModel.kt（cls）；依赖 onnxruntime-android（CPU EP）

### 8. 悬浮窗实现要点
- WindowManager.LayoutParams TYPE_APPLICATION_OVERLAY；内容为 ComposeView，挂到 WindowManager 时手工安装 LifecycleOwner/SavedStateRegistryOwner（OverlayHost 基类统一处理）
- **当前实际三个悬浮窗**：① 操控条（controlHost）② 信息框（infoHost）③ 棋盘小窗（BoardWindowOverlay）。**日志悬浮窗已废弃**——日志仅落文件（FileLogger）+ 操控条状态行显示，不再有独立日志窗（避免遮挡棋子且 LogBus 永不阻塞）
- 操控条/信息框/棋盘小窗统一深色主题基类；具体配色见第四节
- 校准悬浮截图条（CalibrationCaptureOverlay）复用同一封装；截图后**先 bringHome 再 dismiss**（Android 10+ 后台启动限制）
- 日志渲染 SharedFlow<LogEvent> 最近 N=100 条；`LogEvent(kind, tag, msg, time)` 双维度（kind→颜色；tag→[模块]前缀）；logcat 镜像带 `[KIND/TAG]` 前缀
- 位置持久化：overlay_control_x/y、overlay_info_x/y、overlay_board 记忆落盘，下次同位置还原（−1=未记忆用默认）

### 9. 性能预算
- 单帧全盘识别 ≤200ms（90 格优先匹配 prev 值 + 提前退出；必要时降采样优化，阈值不变）
- 截屏→识别→决策周期对齐 python 常量（MOVE_SETTLE_MS=500 等）；引擎 movetime 用 python 相同配置值
- **敌方走棋检测**：轻量 frameDiff 轮询跳过静止帧（静止只比帧差、变化才全量 OCR），敌方落子近乎即时感知；棋盘识别本身每帧全量（见第四节帧差常量）

### 10. Android↔Python 对照要点（审查结论）
**已修复的关键差异**：
| # | 等级 | 问题 | 修复 |
|---|---|---|---|
| R1 | 高 | 结算文字优先级丢失：python 遮罩词表优先于按钮词表，Kotlin 误改为全局分数排序 | `TextMatcher.findGameoverScan`：先 GAMEOVER_BACK_WORDS 再 BUTTON_WORDS |
| R2 | 高 | Mat 原生内存泄漏 ×3（每帧 ~2.7MB native）：AutoNext 计数帧、corrected 帧用完未 release | 全部补 try/finally release；返回给调用方的帧由调用方释放 |
| R3 | 中 | startFlow 仅捕获 EngineError，其他运行时异常绕过「异常终止」日志 | 改为捕获 Exception |
| R4 | 低 | 快速双击「开始」会把两次全量同步排入单线程队列 | start() 加 AtomicBoolean 防抖 |

**有意差异（非 bug）**：① EnemyFrame 用 sealed interface 替代联合类型 ② apply/infer 改名避 Kotlin 关键字、GameState 用类+private set ③ finishGame 调用路径堆栈日志简化 ④ 截屏为 MediaProjection 共享缓冲，识别侧立即拷入 Mat（理论单帧撕裂，实测未见）⑤ EvalFile 显式指定 filesDir 绝对路径（Android 无 cwd 可依赖）。

---

## 四、UI 与悬浮窗架构（最终态）

> 下文为 2026-08-30 当前实现，所有「待真机验证」类早期备注已并入「已知限制」。

### 操控条（ControlBarOverlay，固定宽 344dp、右缘常驻仅上下拖动）
三行结构：
- ① **状态行**：彩点（绿=运行 / 灰=暂停 / 琥珀=摆棋等待）+ 「阶段 · 阵营 · 状态」
- ② **引擎行**：三个胶囊——`📖/🐟 <iccs>(<depth>)`（开局库 depth=0 不显括号）· `🏆胜率%` · `📊评估分`
- ③ **按钮行**：`⏹开始/中断` · `⏭下一局⇄` · `▦棋盘⇄` · `⌃收缩` · `⌂返回`；中断/开关/棋盘不切换信息框，仅 `⌃收缩` 回信息框、`⌂返回` 退出

### 信息框（收起小窗，宽 168dp、两行）
- 第一行：状态点 + 状态字（左）· 右上「自动下一局」⏭ 绿圈（开启时显示，关闭不显）
- 第二行：走棋（📖/🐟 + 着法 mono + 深度括号）+ 评估分（右）；摆棋等待时显示「已等待 Ns」
- 交互：点击=展开操控条；长按(≥600ms，仅运行态)=中断并展开；整窗可拖动（落盘）

### 棋盘小窗（BoardWindowOverlay）
土黄底 / 黑线 / 红黑圆底白字（配色固定不受浅深主题影响）；动态 cellDp（按上方两角 y 均值反推，无校准回退 24dp）；独立拖动 TOP|START 锚定

- **着法箭头（红/黑方各一条，互不覆盖）**：`GameState` 分别记录 `selfHighlight` / `enemyHighlight`（每方仅保留各自最近一步）；`selfPlanned` 标记我方箭头阶段（走棋前圈标 TO / 走棋后圈标 FROM）。经 `BotRuntime.lastSelfMoveCells` / `lastEnemyMoveCells` / `lastSelfMovePlanned` + `mySideIsRed` 喂给 `BoardWindowContent`。
  - 我方箭头（两阶段，靠 `selfPlanned` 区分）：**走棋前**（引擎已算、未落子）圈标在**目标格(TO)**；**走棋后**（落子已确认）圈标在**起点格(FROM)**、目标格不再画圈。圈色 = 我方棋子色。
  - 敌方箭头：起点画**空心圈**（圈色 = 敌方棋子色）。
  - 绘制顺序：**先棋子后箭头**（箭头盖在棋子之上）。炮隔子吃子等长线不再被中间棋子截断、保持连贯；标记圈套在棋子外围（空心圆环 `radius=0.48·cell` > 棋子 `0.44·cell`）清晰可见。
  - 箭头为**锥形**（3dp 线身 + 宽三角头，头部半宽≈棋子半径 `headHalf=0.44·cell`、头长 `headLen=0.66·cell`），尖端停在终点棋子圆边外不被遮挡；短步自动按比例收敛避免越过起/终子。
  - 箭头配色：红方=红、黑方=蓝（蓝用于避开黑线/黑子混淆，与棋子圈色区分）。

### 手势统一（dragTapLongPress）
控制条与信息框共用同一 `Modifier.dragTapLongPress`：位移 > slop(10f) → 拖动（消费事件不触发点按/长按）；轻点抬起 → 点击；按住 ≥600ms → 长按（协程计时，静止按住也能触发）。根治早期自研手势的拖动抖动与长按不触发

### 主题与子页面
- 操控条/信息框跟随系统浅/深主题（双 BarPalette：浅 `0xEEF7F8FA` 底 / 深 `0xD114161C` 底 + 白 14% 描边 + 圆角）
- 子页面统一 `SubPageScaffold`（TopAppBar + 返回箭头 + 标题/副标题）承载设置页与校准页
- 设置项全部 ExposedDropdownMenu 下拉（单行左右），最大使用步数选项 6/8/10/…/20

### 敌方检测帧差常量（Const.kt，防「等待对方」卡死双保险）
- `ENEMY_FRAME_PIXEL_THRESHOLD = 25.0` 单像素灰度差阈值（0~255）
- `ENEMY_FRAME_CHANGED_MIN = 30` 触发识别的最小变化像素数（90×100=9000 格）
- `ENEMY_FORCE_RECOGNIZE_MS = 1000L` 安全网：即便 frameDiff 漏判也周期强制全量识别
- `ENEMY_IDLE_POLL_MS = 120L` 静止帧轮询间隔（全量 OCR 不必每帧跑）

---

## 五、开局库（OBK）

- **格式**：兵河五四 OBK（SQLite `start.obk`）。vkey = TChess 64 位 Zobrist、vmove=(from<<8)|to、负键 punned Double 存 REAL、正常 + 镜像双通道、着法伪合法校验
- **当前库**：`assets/start.obk` 单一优化副本（133.9MB / 293 万行 / 窄而深：起始仅 9 着、常见线路纵深更深；vscore 仅 0~5）。格式 100% 兼容；原库 idxkey 索引对 REAL 通道漏行，已用 `obk_optimize.py fix` 做 REINDEX + 清空 vmemo + VACUUM 修复并验证 0 漏行
- **查书**：`computeMove` 开局库优先（`bookEnabled` 启用即全局生效，每步先查书、未命中回落皮卡鱼；2026-09-08 删除「最大使用步数」上限，命中推书徽标）；执黑视角 `rotateBoard180` 归一化后再查书，返回经 squareToGrid 转回屏幕网格，两链路对称
- **换库检测**：`ObkBook` 比对资产精确长度（`assets.openFd().length`）而非存在性，换新库即生效；`build.gradle` 加 `noCompress += ["obk","nnue"]`（不压缩存储、`openFd` 可取长度）
- **校验脚本**（`scripts/`，Zobrist 常量硬编码、不依赖外部参考）：
  - `obk_check.py` OBK 格式全量校验（起始 vkey=7101337512282506414）
  - `obk_optimize.py` 索引修复 / 清空 vmemo / VACUUM 压缩
  - `obk_probe.py` 开局线路纵深覆盖探测
- **已知局限**：vscore 0~5 同分时排序靠 `vwin DESC` 可能取非主流着法（如起始 9 着里 g3g4/h2e2/g0e2 并列 5 分）；新库真机命中与选着质量待评估

---

## 六、项目结构

```
android/chess_bot/
├── AGENTS.md                        # 本文
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml        # 版本目录（以 AS 向导生成为基准微调）
├── ppocr-sdk/                       # PP-OCRv6 官方 OCR SDK（独立 library module，PaddleOCR deploy/ppocr-android 源码，依赖对齐本项目版本）
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml      # 悬浮窗/FGS(mediaProjection)/无障碍声明/通知/FileProvider
│       ├── assets/
│       │   ├── templates/           # 复用 python 模板 PNG
│       │   ├── pikafish.nnue
│       │   └── start.obk            # 开局库（优化副本，noCompress）
│       ├── jniLibs/arm64-v8a/libpikafish.so
│       └── java/com/chess/bot/
│           ├── MainActivity.kt           # Compose 主界面（权限/校准/对弈三段式 + 子页面路由）
│           ├── ui/
│           │   ├── Permissions.kt        # 权限引导页（4 项检测 + 跳转）
│           │   ├── PlayCard.kt           # 对弈段卡片（状态/开局库/开始·停止）
│           │   ├── CalibrationSession.kt # 校准状态机 HOME/STEP1/RECOGNIZING/RESULT/MANUAL
│           │   ├── CalibrationUi.kt      # 校准 Card / Step1 / 识别中 / 结果 / 微调 屏
│           │   ├── SettingsScreen.kt     # 运行设置页（全下拉单行左右）
│           │   ├── SubPage.kt            # 子页面脚手架 TopAppBar + 返回箭头 + 标题/副标题
│           │   └── theme/{Color,Theme,Type}.kt
│           ├── overlay/
│           │   ├── OverlayHost.kt        # WindowManager 封装 + Compose 生命周期桥
│           │   ├── OverlayManager.kt     # 操控条/信息框/棋盘窗 创建、拖动、回调、运行态
│           │   ├── ControlBarOverlay.kt  # 操控条三行 + 信息框两行（浅/深双配色）
│           │   ├── BoardWindowOverlay.kt # 棋盘小窗（土黄底/红黑棋子，动态 cellDp）
│           │   └── CalibrationCaptureOverlay.kt # 校准悬浮截图条（返回/截图）
│           ├── service/
│           │   ├── BotForegroundService.kt  # mediaProjection 前台服务总入口
│           │   ├── ScreenCaptureSource.kt   # VirtualDisplay/ImageReader 最新帧
│           │   └── Capture.kt               # 截屏→和棋弹窗循环→矫正→点按/返回
│           ├── accessibility/
│           │   ├── BotAccessibilityService.kt      # 手势点击
│           │   └── BotAccessibilityServiceHolder.kt
│           ├── engine/PikafishEngine.kt    # 子进程 UCI 客户端
│           ├── vision/
│           │   ├── VisionInit.kt           # OpenCV init + 角子模板套加载 + Store attach
│           │   ├── Homography.kt           # 矫正/逆映射/网格↔记谱（四角先查 Store 再回退硬编码）
│           │   ├── OnnxRuntime.kt          # ONNX Session 管理（onnxruntime-android CPU EP）
│           │   ├── CornerDetModel.kt       # YOLO det 四角（1280 letterbox + 每类 argmax decode）
│           │   ├── PieceClsModel.kt        # YOLO cls 棋子 16 类（64×64，empty→null/lift→"lift"）
│           │   ├── BoardCornerDetector.kt  # 校准四角：det 先行(几何校验) → ROI 模板精修 → 象限全量模板兜底 → cls 32 子校验
│           │   ├── Recognizer.kt           # 棋盘识别（correctBoard/cropCell64/analyzeCell(cls)/帧差）
│           │   └── TextMatcher.kt          # 结算/和棋文字 OCR（ppocr-sdk 封装：包含匹配+选词优先级+和棋三词判定）
│           ├── game/
│           │   ├── state.kt opening.kt moves.kt classifier.kt draw.kt GameState.kt Board.kt
│           │   ├── Recognition.kt          # recognizeBoard 全量识别（帧差由 BotSession 负责）
│           │   ├── Const.kt                # 全量常量（与 python config 一致）
│           │   ├── BotSession.kt           # 状态机核心粘合：字段+生命周期+悬浮窗推送 emit（2026-09-09 拆分后）
│           │   ├── BotSessionFlow.kt       # 主循环+我方走子：flowLoop/doMove/computeMove/unpackMove/attemptMove/ponder/evalDetail/autoNextGame（BotSession 扩展函数）
│           │   ├── BotSessionVerify.kt     # verify v3 校验链：verifyForSelfMove/提交/基线白名单/吞点击恢复（扩展函数）
│           │   ├── BotSessionEnemy.kt      # 敌方链：recoverOwnLift/waitForEnemyMove/reconfirmEnemyMoved/commitEnemyMove（扩展函数）
│           │   ├── BotSessionEndgame.kt    # 终局判定：confirmEndByOcr/updateResign/checkmateProbe/decideDraw（扩展函数）
│           │   ├── StartLoop.kt            # 统一启动循环：OCR 结算交互 + 摆棋稳定（原 AutoNext）
│           │   └── SettleWaiter.kt         # 摆棋等待共享（31/32/将帅门控/稳定四分支）
│           ├── book/
│           │   ├── ObkBook.kt              # OBK 查询（双通道 + 资产长度换库检测）
│           │   └── TChessZobrist.kt       # TChess 64 位 Zobrist 常量（与 scripts/obk_check.py 同源）
│           ├── data/
│           │   ├── BotSettings.kt          # DataStore：自动下一局 / 位置 / 棋盘绘制 等
│           │   └── BoardCornersStore.kt    # 校准四角持久化 board_corners.json（手动优先）
│           └── log/
│               ├── LogBus.kt               # LogEvent(kind+tag+time) SharedFlow + logcat 镜像
│               └── FileLogger.kt           # 文件日志（级别四档 + 5MB 分片轮转 + 保留 5 片 + FileProvider 导出；2026-09-12 起不再清空历史）
├── scripts/                        # Python 维护脚本（开局库校验 / 优化 / 探测）
│   ├── obk_check.py                # OBK 格式全量校验（Zobrist 常量硬编码）
│   ├── obk_optimize.py             # 索引修复 / 清空 vmemo / VACUUM 压缩
│   └── obk_probe.py                # 开局线路纵深覆盖探测
```

---

## 七、构建与外部依赖

1. **OpenCV for Android**：优先 maven artifact（org.opencv:opencv，4.10+）；仅用 imgproc（matchTemplate/warpPerspective/findHomography/getPerspectiveTransform）
2. **pikafish**：二进制直接入库（`app/src/main/jniLibs/arm64-v8a/libpikafish.so` + `app/src/main/assets/pikafish.nnue`，已提交 git；so 约 1.7MB、NNUE 约 50MB；构建期无拷贝任务）
3. **版本基准**：AGP/Kotlin/Compose BOM 以本地 Android Studio 向导生成的空项目为准，本文不硬编码版本号
4. **工程初始化**：AS 向导创建空项目（Empty Activity / Compose 模板 / Package `com.chess.bot` / minSdk API 31 / Kotlin DSL）
5. **构建验证**：必须用 JDK 17（`JAVA_HOME` 指向 JDK 17），默认 JDK 8 会失败；至少跑 `:app:compileDebugKotlin` + `:app:testDebugUnitTest`

---

## 八、编码规范

- 注释/日志中文；不加多余注释；LF 换行
- 枚举替代魔法字符串（VerifyOutcome/EnemyResult/ResignResult/MoveSource/BotStatus…）
- 常量集中在 Const.kt，数值与 python config.py 严格一致，禁止随手调参（手机 SoC 核心数少于 PC，Threads 有意偏离 python 的 12，勿擅自调回）
- 每完成一个里程碑必须跑 `gradlew :app:testDebugUnitTest`
- 改动涉及状态机行为时，先更新本文档对应章节

---

## 九、风险与对策

| 风险 | 对策 |
|---|---|
| MediaProjection 14+ 一次性授权 / 服务被杀需重新授权 | 「启动按钮」统一走完整授权流程；服务 onTimeout/onDestroy 引导重启 |
| 无障碍服务被厂商 ROM 回收 / 限制后台 | 引导页实时检测开启状态 |
| pikafish android 构建 | 用户提供的二进制直接入库；升级时替换对应路径文件 |
| 大体积 NNUE 进包 | assets 打包即可；上架市场再评估动态下发 |
| 悬浮窗 ComposeView 生命周期坑 | OverlayHost 统一封装 installOwner 逻辑 |
| 模板匹配性能不足 | 先按 python 优先匹配策略移植；超标再降采样/ROI，阈值不动 |

---

## 十、已知限制 / 后续可选

1. **分辨率覆盖**：`BOARD_CORNERS` 内置 1080x2376 / 1080x2400 / 1440x3200；首次运行自动识别并持久化当前分辨率四角（手动校准优先级最高），任意分辨率经一次校准后可用
2. **横竖屏**：按竖屏设计，未处理旋转后 VirtualDisplay 尺寸变化
3. **多用户/分身**：未适配
4. **开局库选着**：vscore 0~5 同分排序靠 `vwin DESC` 可能取非主流着法，待真机评估（见第五节）
5. **新库真机验证**：开局库命中与选着质量、提速后敌方检测反应与 CPU 占用、信息框指示可读性待真机复测

---

## 十一、已确认决策

| 决策点 | 结论 |
|---|---|
| minSdk | API 31（Android 12+），targetSdk 取向导默认最新 |
| pikafish 来源 | 二进制直接入库（jniLibs + assets，已提交 git） |
| 工程骨架 | AS 向导创建空项目，其上填充代码 |
| 日志窗 | 废弃独立悬浮日志窗，日志仅落文件 + 操控条状态行 |
| 配置控件 | 离散选项统一 ExposedDropdownMenu 下拉，不用分段胶囊 |
| 棋盘识别 | 每帧全量 recognizeBoard，frameDiff 仅作静止帧跳过触发 |

---

## 十二、开发演进与决策回滚（摘要）

> 仅保留结论与「曾反复/回滚」的关键点；逐行验证记录已从正文移除（当前编译/单测均绿）。

| 日期 | 里程碑 | 一句话结论 |
|---|---|---|
| 08-23 | M0–M6 骨架 + 全链路 | AS 空项目→权限/截屏/悬浮窗/视觉/状态机/引擎/自动下一局打通；F1–F3 审查裁决 + P1–P3 审计修复（Mat 泄漏/OOM） |
| 08-24 | M7 四角自动识别 | BoardCornerDetector + BoardCornersStore + 校准状态机/悬浮条/UI 落地 |
| 08-24 | M8 三段式主界面 + 截图崩溃修复 | 权限/校准/对弈三段式；灰度/BGR 模板匹配 -215 Assertion 修复 |
| 08-24 | M9 校准闭环 + 评估分上标题 | 截图时序 bringHome 先于 dismiss；微调崩溃修复；对弈按钮与校准解耦；状态行加评估分 |
| 08-24 | M10 日志体系 + 深色统一 | LogBus 双维度；操控条/日志窗/轮次弹窗统一深色主题 |
| 08-24 | M11 审计 R1–R5 + 开局库 | 文件日志/三行操控条/棋盘小窗/无限重试/开局库查书（mirrorSq 位运算 + rotate180 归一化修复） |
| 08-24 | M12 12 项 UI/UX 收尾 | 绝杀读 matePly；棋盘小窗动态缩放；**日志悬浮窗废弃**；位置持久化；手势统一 |
| 08-24 | 开局库换库 | start.obk 换 293 万行库 + idxkey REINDEX 修复 + ObkBook 长度换库检测 + noCompress |
| 08-29 | M13 子页脚手架 | SubPageScaffold 统一设置页/校准页；编译修复全绿 |
| 08-29 | M14 预览稿落地 | 两窗拆分/三行操控条/全下拉设置/提速（frameDiff 轻量轮询） |
| 08-30 | M15 信息框/帧差/绝杀 | 信息框 168dp + 深度；frameDiff 计数法 + 全量识别回退；selfMatePending 绝杀提前终局；doMove 返回修复 |
| 08-30 | 仓库整理 | docs/ / books/ / obk_ref/ 移除；脚本迁 scripts/ 并 Zobrist 常量硬编码 |

### 关键决策与回滚（解决正文中曾出现的反复/冲突）

- **日志悬浮窗**：M2/M10 引入并扩展 → M12 废弃，日志仅落文件（避免遮挡棋子且 LogBus 永不阻塞）。最终态见第三节.8 与第十一节
- **操控条行数/宽度**：M11 三行 → M12 两行 + 全宽 → M14 三行（固定 344dp）。最终 = 三行、固定 344dp（窄屏不换行）
- **棋盘识别**：M14 引入「格子像素差<阈值沿用旧子」增量识别省开销 → 实测敌方多帧动画使旧子污染 newBoard、终点帧收不到起点减子 → 永久卡死 → M15 **回退每帧全量 recognizeBoard**，frameDiff 仅作静止帧跳过触发（常量见第四节）。`prevCorrected` 仅作 frameDiff 基准，不参与棋子身份判定
- **轮次确认弹窗**：早期 TurnConfirmDialog 中央模态弹窗 → M11 删除，改为 `decideStartTurn` 三路径（不弹窗、不自动选择）
- **设置控件**：分段胶囊 → M12/M14 统一 ExposedDropdownMenu 下拉（窄屏放不下胶囊被迫换行）
- **开局库目录**：旧 43.8MB 库 + 190MB 原始库（books/ 备份）+ docs/obk_ref 历史来源 → 已优化为单一 `assets/start.obk` 副本，books/ 与 docs/ 整目录移除，obk_check.py 常量硬编码自包含
