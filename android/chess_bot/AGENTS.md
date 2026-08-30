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
| 我方走棋 | pikafish 算着法 → 点击起子/落子 → 5 帧逐帧校验分类（n==0/1/2/3/4/>4），提起未落补点、整步重试 |
| 敌方走棋检测 | 持续帧差分类（n==2 走法 / 提子 / 噪声 / 无变动），噪声达上限暂停 |
| 认输检测 | 双方将帅同时缺失连续 3 帧 → 结束 |
| 绝杀探测 | 仅 n==2 干净走棋后调 engine.is_mate（亦读引擎 `matePly==1` 直接判定） |
| 和棋弹窗 | 同意+拒绝双按钮同现才认定；按最近评估分 > DRAW_REJECT_CP 拒绝否则同意 |
| 自动下一局 | 结算文字交互（按钮点击/遮罩返回键，重试上限）→ 摆棋稳定等待 → 重新初始化；开关实时可切 |
| halfmove_clock | 吃子归零/非吃 +1，写入 FEN 供引擎自然限招 |

**UI 强制要求**：
1. 主界面三段式：① 权限与授权（4 项）② 棋盘四角校准 ③ 对弈；每段 Card 条目为「序号圆徽标 + 标题，右侧值/状态徽章」行样式
2. 对弈段启动按钮：点击后走 MediaProjection 授权 → 创建**悬浮操作条**；运行中按钮变「停止并退出悬浮窗」
3. 操作条两个控件：**开始/中断棋局** 按钮、**自动下一局** 开关（默认开）
4. 校准流程 2 步：① 进入人机模式（App 退后台、悬浮截图条）② 截屏识别（回 App 显示识别中→结果→可选手动微调）→ 保存回主界面
5. **配置项一律单行左右结构**（左文字、右控件）；离散选项统一用 ExposedDropdownMenu 下拉，不用分段胶囊

---

## 二、技术路线总览

| 关注点 | python 实现 | Android 实现 |
|---|---|---|
| 截屏 | `adb screencap` | MediaProjection + VirtualDisplay(ImageReader)，常驻缓存最新帧按需取用 |
| 点击注入 | ADB shell tap | AccessibilityService.dispatchGesture（免 root 标准方案） |
| 视觉识别 | cv2 模板匹配(TM_CCOEFF_NORMED) + warpPerspective | OpenCV for Android 同算法移植，模板 PNG 直接复用 python 的资产 |
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

### 5. 状态机（对应 session.py GameSession）
方法级对照（BotSession.kt）：start / startFlow / flowLoop / doMove / computeMove / unpackMove /
attemptMove / verify / waitForEnemyMove / applySelfMove / applySelfThenEnemy / applyEnemyMove /
updateResign / checkmateProbe / decideDraw / autoNextGame / initialize / confirmStart / finishGame / emit

- GameState 字段照搬（board/prevBoard/mySide/turn/phase/initialized/halfmoveClock/gameOver/
  highlight/lastMove/lastEvalScore/resignStreak/noisyCount/liftLogged/lastMoveDepth）
- mySide/turn/phase 非可选 + initialized 标志；flow 入口保证 turn 已定；computeMove 以 initialized 兜底防占位值流入 FEN
- confirmStart / 轮次判定：不再弹中央模态对话框，改为 `decideStartTurn` 三路径（32 子默认位红先 / inferTurn 推断 / 残局·排局无法推断时默认**我方**先走，非红方），不自动选择
- **绝杀提前终局**：引擎 `matePly==1` 置 `selfMatePending`，verify 落子后 `SELF_DONE` 或 `RESIGN_SUSPECT` 结束画面即 `finishGame("我方绝杀…")`，省二次引擎调用并阻断 doMove 重复点击死盘
- `doMove(): Boolean`：DONE_END 且 gameOver==true 返回 true，使绝杀后仍能进入 autoNext 自动下一局

### 6. 纯函数模块直译（签名一一对应，便于翻译测试）
| python (game/) | Kotlin |
|---|---|
| state.py | state.kt：Side/Phase/Change/Move/FrameResult/FrameClass/VerifyOutcome/EnemyResult/ResignResult/GameState |
| opening.py | opening.kt：detectSide/detectPhase/inferTurn |
| moves.kt | moves.kt：infer/apply/matches/formatMove/formatChanges |
| classifier.py | classifier.kt：classifySelfFrame/classifyEnemyFrame/isResignSuspect（captured=r2_old 修正一并带入）|
| recognition.py | game/Recognition.kt：recognizeBoard（全量识别）+ vision/Recognizer.kt：correctBoard/analyzeCell/analyzeBoard |
| draw.py | draw.kt：decide(score, rejectCp) |
| auto_next.py | AutoNext.kt + SettleWaiter.kt（31 子持续等待 / 32 子新开局 / 其余连续 3 帧稳定；AutoNext 重构复用 SettleWaiter 删除内联重复）|

坐标系/FEN 规则文档照搬 board.py 头注释：网格固定屏幕左上角、记谱 ICCS、FEN 黑上红下。

### 7. 视觉资产复用
- templates/*.png（14 枚棋子 60×60）、结算文字模板、和棋按钮模板 → 原样拷入 assets
- GAMEOVER_TEMPLATE_W=1080 归一化缩放逻辑移植；raw_screenshots/ 样张作回归素材

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
- **查书**：`computeMove` 开局库优先（`bookEnabled && moveCount < bookMaxMoves`，命中推书徽标；未命中回落引擎）；执黑视角 `rotateBoard180` 归一化后再查书，返回经 squareToGrid 转回屏幕网格，两链路对称
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
│           │   ├── VisionInit.kt           # OpenCV init + 模板加载 + Store attach
│           │   ├── Homography.kt           # 矫正/逆映射/网格↔记谱（四角先查 Store 再回退硬编码）
│           │   ├── BoardCornerDetector.kt  # 棋盘四角自动识别（角车模板多尺度匹配）
│           │   ├── TemplateMatcher.kt      # TM_CCOEFF_NORMED
│           │   ├── Recognizer.kt           # 棋盘识别（correctBoard/analyzeCell/analyzeBoard）
│           │   └── TextMatcher.kt          # 结算文字/和棋按钮灰度模板匹配（1080 归一化）
│           ├── game/
│           │   ├── state.kt opening.kt moves.kt classifier.kt draw.kt GameState.kt Board.kt
│           │   ├── Recognition.kt          # recognizeBoard 全量识别（帧差由 BotSession 负责）
│           │   ├── Const.kt                # 全量常量（与 python config 一致）
│           │   ├── BotSession.kt           # 状态机总控
│           │   ├── AutoNext.kt             # 结算交互 + 摆棋稳定
│           │   └── SettleWaiter.kt         # 摆棋等待共享（31/32/稳定三分支）
│           ├── book/
│           │   ├── ObkBook.kt              # OBK 查询（双通道 + 资产长度换库检测）
│           │   └── TChessZobrist.kt       # TChess 64 位 Zobrist 常量（与 scripts/obk_check.py 同源）
│           ├── data/
│           │   ├── BotSettings.kt          # DataStore：自动下一局 / 位置 / 棋盘绘制 等
│           │   └── BoardCornersStore.kt    # 校准四角持久化 board_corners.json（手动优先）
│           └── log/
│               ├── LogBus.kt               # LogEvent(kind+tag+time) SharedFlow + logcat 镜像
│               └── FileLogger.kt           # 文件日志（级别四档 + FileProvider 导出）
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
