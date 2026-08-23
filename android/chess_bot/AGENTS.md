# chess_bot — Android 中国象棋自动对弈 Bot（AGENTS.md）

> 移植自 `python/xiangqi-bot`：同一套「截屏识别 → 引擎计算 → 注入点击 → 多帧校验」逻辑，
> 运行形态从 **PC + ADB 控制手机** 变为 **手机本机 App 悬浮窗自动化**。
> 本文件是本项目的开发约定与技术方案；改架构先改这里。

---

## 一、项目目标

悬浮窗覆盖在手机上的象棋 App（JJ 象棋）上方运行，功能与 `python/xiangqi-bot` 对齐：

| 功能 | 说明 |
|---|---|
| 开始棋局 | 截图全量同步棋盘 → 判我方红黑 → 判阶段 → 推断轮次（未知时确认弹窗）→ 自动对弈 |
| 我方走棋 | pikafish 算着法 → 点击起子/落子 → 5 帧逐帧校验分类（n==0/1/2/3/4/>4），提起未落补点、整步重试 |
| 敌方走棋检测 | 持续帧差分类（n==2 走法 / 提子 / 噪声 / 无变动），噪声达上限暂停 |
| 认输检测 | 双方将帅同时缺失连续 3 帧 → 结束 |
| 绝杀探测 | 仅 n==2 干净走棋后调 engine.is_mate |
| 和棋弹窗 | 同意+拒绝双按钮同现才认定；按最近评估分 > DRAW_REJECT_CP 拒绝否则同意 |
| 自动下一局 | 结算文字交互（按钮点击/遮罩返回键，重试上限）→ 摆棋稳定等待 → 重新初始化；开关实时可切 |
| halfmove_clock | 吃子归零/非吃 +1，写入 FEN 供引擎自然限招 |

**UI 强制要求**：
1. 主界面有启动按钮；点击后创建**悬浮操作条**
2. 操作条两个控件：**开始/中断棋局** 按钮、**自动下一局** 开关（默认开）
3. **日志框悬浮在屏幕上方**（不遮挡棋子）：状态行（棋盘阶段/阵营/状态）+ 滚动操作日志

---

## 二、总体技术路线

| 关注点 | python 实现 | Android 实现 |
|---|---|---|
| 截屏 | `adb screencap` | MediaProjection + VirtualDisplay(ImageReader)，常驻缓存最新帧按需取用 |
| 点击注入 | ADB shell tap | AccessibilityService.dispatchGesture（免 root 标准方案） |
| 视觉识别 | cv2 模板匹配(TM_CCOEFF_NORMED) + warpPerspective | OpenCV for Android 同算法移植，模板 PNG 直接复用 python 的资产 |
| 象棋引擎 | pikafish 可执行文件子进程 stdio UCI | NDK 交叉编译 pikafish arm64 可执行文件打进 APK（jniLibs 方案），ProcessBuilder 子进程 stdio UCI——移植 engine.py 全部协议细节 |
| 控制台 | FastAPI + WebSocket 网页 | Compose 主界面 + WindowManager 悬浮窗；WebSocket 推送改为 StateFlow/SharedFlow |
| 状态机 | GameSession(6 mixin) 单 worker 线程 + interrupt Event | BotSession 单线程协程直译；AtomicBoolean 中断对齐 threading.Event 语义 |
| 配置 | config.py 常量 | Const.kt 全量搬移（阈值/间隔/重试上限一字不差） |

### 技术栈（最新 Google 推荐）

- Kotlin 2.x + Coroutines / Flow
- Jetpack Compose + Material 3（**全程无 XML 布局**，悬浮窗内也用 ComposeView）
- Foreground Service（Android 14 规范：`foregroundServiceType="mediaProjection"`）
- DataStore Preferences（持久化自动下一局开关等设置）
- OpenCV for Android 4.x
- Gradle Kotlin DSL + version catalog（libs.versions.toml）
- JUnit 单测：python 的纯函数测试场景逐一翻译
- 未引入 Hilt：依赖对象仅 4~5 个手工构造；规模上来后再议

---

## 三、关键机制设计

### 1. 权限与启动时序

```
主界面引导页依次检查/请求：
① POST_NOTIFICATIONS(33+)      FGS 通知
② SYSTEM_ALERT_WINDOW          悬浮窗特殊权限（跳系统设置）
③ 无障碍服务                    用户在系统设置中开启（BotAccessibilityService）
④ MediaProjection              createScreenCaptureIntent 授权 → 启动前台服务
   （Android 14+ 必须先起 mediaProjection 型前台服务再取 projection，
     且授权令牌一次性：服务被杀后需重新授权——统一从「启动按钮」走完整流程）
就绪后：创建悬浮操作条 + 日志悬浮窗
```

### 2. 截屏管线（对应 CaptureMixin/Capture 类）

- ImageReader 持续收帧，仅保留最新 Bitmap（丢弃旧帧），`grab()` 语义对齐 python：
  截图 → 和棋弹窗检测循环（should_continue 控制）→ 矫正
- `_correct` 缓存 homography 与 python 相同；分辨率预设表从 config/vision 移植
- 取帧节拍由消费方控制（MOVE_SETTLE_MS 等），管线本身只维护最新帧

### 3. 点击管线（对应 _attempt_move/_tap_cell）

- grid(r,c) --逆单应映射--> 屏幕(x,y)（复用 vision.tap_xy 数学）
- dispatchGesture 单击 stroke；ADB 断连类错误不存在，失败源变为手势回调 result=false
- TAP_HOLD_INTERVAL_MS 起落间隔语义不变

### 4. 引擎进程（对应 engine.py Engine 类）

- 二进制放置：APK jniLibs/<abi>/libpikafish.so → 安装后在 nativeLibraryDir 下可直接 exec
  （规避 targetSdk 29+ 不能 exec 应用数据目录文件的 W^X 限制）
- NNUE：assets/pikafish.nnue 首启拷贝至 filesDir，启动参数指定 EvalFile
- 方法一一对应：best_move(fen, movetime) / is_mate(fen, ms) / newgame() / close()；
  ucinewgame→go movetime→bestmove 解析、(none) 重试短时限、EngineError 异常类型全部对齐

### 5. 状态机（对应 session.py GameSession，含第六~九轮整改结论）

方法级对照（BotSession.kt）：start / startFlow / flowLoop / doMove / computeMove /
unpackMove / attemptMove / verify / waitForEnemyMove / applySelfMove /
applySelfThenEnemy / applyEnemyMove / updateResign / checkmateProbe / decideDraw /
autoNextGame / initialize / confirmStart / finishGame / emit

- GameState 字段照搬（board/prevBoard/mySide/turn/phase/**initialized**/halfmoveClock/
  gameOver/highlight/lastMove/lastEvalScore/resignStreak/noisyCount/liftLogged）
- mySide/turn/phase 非可选 + initialized 标志的结论沿用；flow 入口保证 turn 已定；
  computeMove 以 initialized 兜底防占位值流入 FEN
- confirmStart 弹窗：悬浮日志窗内嵌确认卡片（「我方先走开始」/「暂不开始」两按钮），
  替代原网页 prompt_turn；同样可被中断
- finishGame 的调用路径日志简化为协程调用栈（可选保留）

### 6. 纯函数模块直译（保持签名一一对应，便于翻译测试）

| python (game/) | Kotlin (game/) |
|---|---|
| state.py | state.kt：Side(StrEnum→enum)/Phase/Change/Move/FrameResult/FrameClass/VerifyOutcome/EnemyResult+EnemyFrame/ResignResult/GameState |
| opening.py | opening.kt：detectSide/detectPhase/inferTurn |
| moves.kt | moves.kt：infer/apply/matches/formatMove/formatChanges |
| classifier.py | classifier.kt：classifySelfFrame/classifyEnemyFrame/isResignSuspect（captured=r2_old 修正一并带入）|
| recognition.py | vision/Recognition.kt：analyze(corrected, templates, prevBoard) |
| draw.py | draw.kt：decide(score, rejectCp) |

坐标系/FEN 规则文档照搬 board.py 头注释：网格固定屏幕左上角、记谱 ICCS、FEN 黑上红下。

### 7. 视觉资产复用

- templates/*.png（14 枚棋子 60×60）、结算文字模板、和棋按钮模板 → 原样拷入 assets
- GAMEOVER_TEMPLATE_W=1080 归一化缩放逻辑移植（不同分辨率下先归一化再匹配）
- raw_screenshots/ 样张可作为回归素材（手动比对识别输出）

### 8. 悬浮窗实现要点

- WindowManager.LayoutParams TYPE_APPLICATION_OVERLAY；内容为 ComposeView
- ComposeView 挂到 WindowManager 时需手工安装 LifecycleOwner/SavedStateRegistryOwner
  （封装 OverlayWindow 基类处理，两处窗口共用）
- 操作条：底部居中可拖动；日志窗：顶部，最大高度不超过屏幕 1/4，可折叠成单行状态条
- 日志渲染 SharedFlow<LogEvent> 最近 N=100 条；kind→颜色映射对齐网页样式

### 9. 性能预算

- 单帧全盘识别 ≤200ms（90 格优先匹配 prev 值 + 提前退出；必要时降采样优化，阈值不变）
- 截屏→识别→决策周期对齐 python 常量（MOVE_SETTLE_MS=500 等）
- 引擎 movetime 用 python 相同配置值

---

## 四、项目结构

```
android/chess_bot/
├── AGENTS.md                        # 本文
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml        # 版本目录（以 AS 向导生成为基准微调）
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml      # 悬浮窗/FGS(mediaProjection)/无障碍声明/通知权限
│       ├── assets/
│       │   ├── templates/           # 复用 python 模板 PNG
│       │   └── pikafish.nnue
│       ├── jniLibs/arm64-v8a/libpikafish.so
│       └── java/com/chess/bot/
│           ├── MainActivity.kt           # Compose：权限引导 + 完整日志 + 启动按钮
│           ├── ui/                       # 主题/组件
│           ├── overlay/
│           │   ├── OverlayHost.kt        # WindowManager 封装 + Compose 生命周期桥
│           │   ├── ControlBarOverlay.kt  # 操作条：开始/中断 + 自动下一局开关
│           │   └── LogPanelOverlay.kt    # 顶部日志窗：状态行 + 滚动日志 + 确认轮次卡片
│           ├── service/
│           │   ├── BotForegroundService.kt   # mediaProjection 型前台服务总入口
│           │   └── ScreenCaptureSource.kt    # VirtualDisplay/ImageReader 最新帧
│           ├── accessibility/
│           │   └── BotAccessibilityService.kt # 手势点击
│           ├── engine/PikafishEngine.kt       # 子进程 UCI 客户端
│           ├── vision/
│           │   ├── Homography.kt / BoardGeometry.kt   #矫正/逆映射/网格↔记谱
│           │   ├── TemplateMatcher.kt                 #TM_CCOEFF_NORMED
│           │   └── Recognition.kt
│           ├── game/
│           │   ├── state.kt opening.kt moves.kt classifier.kt draw.kt   # 纯函数直译
│           │   └── BotSession.kt                                        # 状态机
│           ├── data/BotSettings.kt               # DataStore：自动下一局默认值等
│           └── log/LogBus.kt                     # LogKind + SharedFlow
```

---

## 五、构建与外部依赖

1. **OpenCV for Android**：优先 maven artifact（org.opencv:opencv，4.10+）；不可用时退回官方 sdk 解包 aar。仅用到 imgproc（matchTemplate/warpPerspective/findHomography/getPerspectiveTransform）
2. **pikafish**（最终方案：**二进制直接入库**，third_party 中转目录已废弃）：
   - `app/src/main/jniLibs/arm64-v8a/libpikafish.so` —— 直接入库
     （必须以 lib 开头、.so 结尾命名；配合 `useLegacyPackaging=true` 解压后可 exec）
   - `app/src/main/assets/pikafish.nnue` —— 直接入库
   - NNUE 约 50MB、so 约 1.7MB，均已提交 git；构建期无拷贝任务
3. **版本基准**：AGP/Kotlin/Compose BOM 以你本地 Android Studio（Quail 2026.1.3）向导生成的空项目为准，本文不硬编码具体版本号，避免工具链错配
4. **工程初始化方式**（已定：**AS 向导创建空项目**）：创建参数见第九节

---

## 六、里程碑（每步可独立验证）

| 阶段 | 内容 | 验证 |
|---|---|---|
| M0 ✅ 2026-08-23 | AS 空项目骨架 + Gradle 接线（OpenCV/coroutines/DataStore/lifecycle-compose 依赖、third_party→jniLibs/assets 拷贝任务、abiFilters=arm64-v8a）+ Manifest 权限/服务声明 + 桩类（LogBus/BotSettings/BotForegroundService/ScreenCaptureSource/BotAccessibilityService.tap） | assembleDebug 通过；APK 含 libpikafish.so(1.7MB)/libopencv_java4.so/pikafish.nnue(50.7MB)；testDebugUnitTest 通过 |
| M1 ✅ 2026-08-23 代码完成 | 权限引导页（通知/悬浮窗/无障碍三步检测 + 跳转，Compose）＋ MediaProjection 授权 → 前台服务（先 startForeground 再取 projection，满足 14+ 规范）→ ScreenCaptureSource 真实管线（VirtualDisplay+ImageReader 最新帧缓存，行 stride 对齐处理，系统回收自动清理）；主界面截图预览 | assembleDebug + testDebugUnitTest 通过；**待真机验证**：走完四步授权后主界面应显示屏幕预览 |
| M2 ✅ 2026-08-23 真机验证通过 | 悬浮窗框架：OverlayHost（WindowManager+ComposeView+手工生命周期桥）＋ OverlayManager（操作条/日志窗创建、拖动、回调、BotRuntime 运行态）；操作条=开始中断+测试点击⌖+自动下一局开关（DataStore 持久化默认开）；顶部日志窗=状态行+最近100条日志，可折叠、高度≤1/4屏；无障碍服务补 intent-filter 修复不显示问题 | 真机全部操作项验证通过 |
| M2 补丁 ✅ | **退出方案**：操作条新增「✕」→ 发 ACTION_STOP 停前台服务（收悬浮窗+停截屏+撤通知）；ScreenCaptureSource.active StateFlow 同步 UI，主界面 CTA 在运行中变为「停止并退出悬浮窗」 | 代码完成，随 M3 一并真机验证 |
| M3 ✅ 2026-08-23 代码完成 | 视觉移植：Const.kt 全量常量；game/Board.kt（网格/记谱/FEN/START_SQUARES/PIECE_CN）；VisionInit（OpenCV initLocal + assets 模板加载）；Homography（分辨率四角表→getPerspectiveTransform 缓存 + 逆映射 tapXy）；Recognizer（correctBoard/analyzeCell/analyzeCellWithPriority/analyzeBoard/formatLayout）；主界面「识别棋盘」按钮打印 r9..r0 布局到日志。**踩坑记录**：Kotlin 块注释嵌套——KDoc 中路径 `/*.png` 的 `/*` 会开启嵌套注释吞掉整个文件 | assembleDebug + testDebugUnitTest 通过；**待真机验证**：JJ 象棋棋盘界面点「识别棋盘」，打印布局应与 python 版一致（90 格/32 子） |
| M4 ✅ 2026-08-23 代码完成 | ① 纯函数直译：GameState.kt / opening.kt / moves.kt / classifier.kt（captured=r2_old 修正带入）/ draw.kt / Recognition.kt；Side/Phase 用 Kotlin enum，EnemyFrame 用 sealed interface（Moved/Lifted/Noisy/Silent）② PikafishEngine.kt：ProcessBuilder UCI 客户端逐特性移植（uci/isready 握手、Threads/Hash/EvalFile/Rule60MaxPly、go movetime+1s 超时、3 次自愈重试、score cp/mate 映射、quit 仅在 close）；二进制取 nativeLibraryDir，NNUE 首启从 assets 拷 filesDir 并显式 setoption EvalFile ③ JUnit 翻译 python 测试场景 37 个（Opening5/Moves6/Self12/Enemy8/Fen+Draw 等）④ 主界面新增「引擎冒烟」按钮 | assembleDebug + testDebugUnitTest 全绿（37 tests passed）；真机验证通过 |
| M5 ✅ 2026-08-23 代码完成 | **BotSession 全流程状态机**（python session.py 逐方法移植）：start/startFlow/flowLoop/doMove/computeMove/unpackMove/attemptMove/verify(5帧+认输续帧)/waitForEnemyMove/updateResign/checkmateProbe/decideDraw/autoNextGame/initialize/confirmStart/finishGame/emit；配套 Capture.kt（截屏→和棋弹窗循环→矫正、tap/back 经无障碍）、AutoNext.kt（结算交互+摆棋稳定）、TextMatcher.kt（结算文字/和棋按钮灰度模板匹配，1080 归一化）；悬浮条接真实状态机（开始=启动会话，中断=interrupt），日志窗内嵌轮次确认卡片；botScope 单线程调度器避免 ANR | assembleDebug + testDebugUnitTest 全绿；**待真机验证**：完整对局若干盘零人工干预（含敌方走棋/认输/绝杀/和棋弹窗/自动下一局） |
| M5 补丁 ✅ | ① 修复 capture() 工厂方法导致 homography 缓存丢失（改为会话级 lazy 单例）② OpenCV init 前置到 Capture.grab/correct（修复新进程首点开始即 UnsatisfiedLinkError）③ botScope 加 CoroutineExceptionHandler（后台异常不再杀进程）④ **操作条停靠右缘 + 收起/展开**：「▶」收起为右缘「◀」小圆钮，「◀」展开；开始棋局后自动收起，中断后自动展开；END 停靠拖动方向修正 ⑤ 操作条精简：按钮标题「开始/中断」、移除测试点击⌖、顺序 [开始/中断][下一局][▶][✕] 保证退出恒在最右可见；**仅允许上下拖动**（常贴右缘）⑥ LogBus 每条日志同步镜像 adb logcat（tag=ChessBot，kind→优先级映射）⑦ 轮次确认改屏幕中央模态弹窗 ⑧ 收尾：stopWithTask + shutdown 关闭孤儿引擎进程 | 真机对局验证通过 |
| M6 ✅ 2026-08-23 | 自动下一局/设置持久化/日志折叠与镜像 logcat 均已随 M2~M5 落地；**收尾加固**：service `stopWithTask="true"`（划掉 App 即退出）+ onDestroy 时 `OverlayManager.shutdown()`（中断会话+关闭 pikafish 子进程，杜绝孤儿引擎进程） | 真机连续多局验证通过（用户确认） |
| M6 补丁 ✅ | ① **修复残局自动下一局死循环**：Kotlin 数组 `==` 是引用比较（python 列表是逐值比较），摆棋静止后稳定计数永不增长——改用 `contentDeepEquals` ② 日志窗加 `FLAG_LAYOUT_NO_LIMITS`，可拖进通知栏区域，不再遮挡上沿棋子 ③ **修复摆棋中间态误判终态导致「无法推断轮次」暂停**：32 子快速返回前校验 `plausibleNewGame`（detectSide+OPENING+可推断轮次），24~30 子中间帧不再进入稳定计数（记一次性日志继续等待，180s 超时兜底不变） ④ 日志窗默认高度 25%→**20% 屏高**，默认位置即不遮挡上沿棋子；需要更大空间仍可拖入通知栏 ⑤ **稳定性返回增加「双将俱全」校验**：无将/帥的画面（选关预览等）即使子数<24 且静止也不返回，日志标注缺哪个将帅 ⑥ **initialize 失败时打印完整识别布局**（r9..r0 + 棋子总数），用于定位是将帥误识别还是画面特殊 ⑦ 移除主界面截屏预览/识别棋盘/引擎冒烟调试组件（悬浮窗模式下无法使用；切回主界面预览只会显示自身） ⑧ **修复 use-after-free**：② 泄漏修复时把 `finally{release()}` 包住了 `return corrected`——返回的帧先被释放、initialize 读到全空判方失败；改为 handOffToCaller 所有权移交模式（返回路径不释放、其余路径 finally 释放） ⑨ ScreenCaptureSource.latest() 返回**独立副本**而非共享缓冲引用，消除采集线程覆写导致的串帧竞态 | 待真机复测：残局连续过关 + 日志窗拖至状态栏 + 中间态不再触发暂停 |

## 十一、Android↔Python 全量对比审查（2026-08-23）

> 审查方式：python 源文件逐行 ↔ Kotlin 对应文件对照；常量逐一核对；协议/时序/守卫条件比对。

### 发现并已修复

| # | 等级 | 问题 | 修复 |
|---|---|---|---|
| R1 | 高 | **结算文字优先级丢失**：python 遮罩词表优先于按钮词表、同类按列表顺序选取；Kotlin 版误改为全局分数排序——可能出现该发返回键时却点了按钮 | 新增 `TextMatcher.findGameoverScan`：先遍历 GAMEOVER_BACK_WORDS 再 BUTTON_WORDS，各词取最高分 |
| R2 | 高 | **Mat 原生内存泄漏 ×3**：AutoNext 计数帧、start/autoNextGame 的 corrected 帧用完未 release（每帧 ~2.7MB native）；摆棋等待期每 300ms 泄漏一次，长跑必然 OOM | 全部补 try/finally release；返回给调用方的帧由调用方释放（grabBoard 已有此约定） |
| R3 | 中 | startFlow 仅捕获 EngineError，其他运行时异常绕过「自动对弈异常终止」日志路径 | 改为捕获 Exception（外层 start 兜底不变） |
| R4 | 低 | 快速双击「开始」会把两次全量同步排入单线程队列依次执行 | start() 加 AtomicBoolean 防抖，进行中忽略并告警 |

### 确认语义等价（逐项比对无回归）

常量表全值一致；fenOfBoard 行列翻转/halfmove 字段；opening 三函数判定与 deviates/singlePieceMoved；moves infer/apply/matches/formatting；classifier 六类帧分类全部守卫条件（含 captured=r2_old、n2 反吃兜底、n3 三情况、n4）、敌方四类结论；verify 五帧循环+LIFTED_ONLY 补点不消耗 attempts+认输续帧 while；enemy 循环 lift-once/noisy 计数上限/suspect 复检顺序；updateResign streak；checkmateProbe 仅 n==2 触发+异常降级；draw 阈值边界（>拒绝）；auto_next 31/32 特判+稳定阈值+重试上限+超时；engine 协议（marker 行首匹配、reversed 扫描、movetime+1s、3 次自愈、mate 分数映射、quit 仅 close）；capture 双按钮同现+决策缓存+点击失败中止。

### 有意差异 / 已知限制（非 bug）

1. EnemyFrame 用 sealed interface 替代 `Move | Literal` 联合类型
2. apply/infer 等函数改名（applyMove/inferMove）避免 Kotlin 关键字冲突；GameState 用类+private set 而非 dataclass
3. finishGame 的调用路径堆栈日志简化为 LogKind.GAMEOVER 一条（网页端专用调试信息未移植）
4. 截屏来源为 MediaProjection 共享缓冲，识别侧拿到引用后立即拷入 Mat，理论存在单帧撕裂窗口（实测未见）
5. 引擎 EvalFile 显式指定 filesDir 绝对路径（python 依赖 cwd 默认名，Android 无 cwd 可依赖）


## 十、已知限制 / 后续可选

1. **分辨率覆盖**：`BOARD_CORNERS` 仅内置 1080x2400 与 1440x3200；其他分辨率启动时将报「未配置四角坐标」。如需支持更多设备，按 python scripts/detect_board_corners 流程补表即可
2. **横竖屏**：按竖屏游戏设计，未处理旋转后 VirtualDisplay 尺寸变化
3. **多用户/分身**：未适配
4. **pikafish 二进制已入库**（用户决策）：so+NNUE 随 git 分发，克隆即构建；后续若引擎升级直接替换对应路径文件

---

## 七、编码规范

- 注释/日志中文；不加多余注释；LF 换行
- 枚举替代魔法字符串（沿用 python 第六~九轮整改结论：VerifyOutcome/EnemyResult/ResignResult…）
- 常量集中在 Const.kt，数值与 python config.py 严格一致，禁止随手调参
- 每完成一个里程碑必须跑 `gradlew :app:testDebugUnitTest`
- 改动涉及状态机行为时，先更新本文档对应章节

## 八、风险与对策

| 风险 | 对策 |
|---|---|
| MediaProjection 14+ 一次性授权/服务被杀需重新授权 | 「启动按钮」统一走完整授权流程；服务 onTimeout/onDestroy 引导重启 |
| 无障碍服务被厂商 ROM 回收/限制后台 | 引导页实时检测开启状态；文档列出 MIUI/EMUI 已知项 |
| pikafish android 构建 | 已定：用户提供的二进制直接入库；如未来需自编译再走 NDK 源码交叉编译（脚本化） |
| 大体积 NNUE 进包 | assets 打包即可；如后续上架应用市场再评估动态下发 |
| 悬浮窗 ComposeView 生命周期坑 | OverlayHost 统一封装 installOwner 逻辑，一次解决两处窗口 |
| 模板匹配性能不足 | 先按 python 优先匹配策略移植；实测超标再引入降采样/ROI 裁剪，阈值不动 |

## 九、已确认决策（2026-08-22）

| 决策点 | 结论 |
|---|---|
| minSdk | **API 31（Android 12+）**，targetSdk 取向导默认最新 |
| pikafish 来源 | ~~third_party 中转~~ **最终：二进制直接入库**（`app/src/main/jniLibs/arm64-v8a/libpikafish.so` + `app/src/main/assets/pikafish.nnue`，已提交 git） |
| 工程骨架 | **AS 向导创建空项目**，我随后在其上填充代码 |

**Android Studio 向导创建参数**：

| 项 | 值 |
|---|---|
| Template | Empty Activity（新版向导即 Compose 模板） |
| Name | ChessBot |
| Package name | `com.chess.bot` |
| Save location | `D:\Works\scripts\android\chess_bot` |
| Minimum SDK | API 31（Android 12） |
| Build configuration language | Kotlin DSL（默认） |

> 注：目录里已有 AGENTS.md，若向导提示目录非空，先把 AGENTS.md 移到
> `android\` 下暂存，创建完成后移回。
