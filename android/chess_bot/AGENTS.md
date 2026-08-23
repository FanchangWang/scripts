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
2. **pikafish**（已定：**由你提供二进制**）：文件放 `third_party/pikafish/`，
   - `third_party/pikafish/libpikafish.so` —— Gradle 拷入 `app/src/main/jniLibs/arm64-v8a/`
     （**必须以 lib 开头、.so 结尾命名**才能被打包且安装后可 exec）
   - `third_party/pikafish/pikafish.nnue` —— Gradle 拷入 `app/src/main/assets/`
   - NNUE 约 40MB 级别，assets 打包即可（AAB 分发不受影响）
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

## 十、已知限制 / 后续可选

1. **分辨率覆盖**：`BOARD_CORNERS` 仅内置 1080x2400 与 1440x3200；其他分辨率启动时将报「未配置四角坐标」。如需支持更多设备，按 python scripts/detect_board_corners 流程补表即可
2. **横竖屏**：按竖屏游戏设计，未处理旋转后 VirtualDisplay 尺寸变化
3. **多用户/分身**：未适配
4. **pikafish 二进制不入库**：`third_party/pikafish/*.nnue` 已 gitignore；`.so` 如也不希望入库可同样处理
| M1 | 权限引导页 + 前台服务 + 截屏管线 | 屏幕截图预览显示在主界面 |
| M2 | 悬浮窗框架（操作条 + 日志窗）+ 无障碍点击 | 点按钮可在任意界面注入点击并打日志 |
| M3 | 视觉移植：矫正 + 模板匹配 + 布局打印 | 对 JJ 象棋实况打印 10x9 布局与 python 一致 |
| M4 | 纯函数直译 + JUnit（翻译 python 测试场景）+ 引擎冒烟 | gradlew testDebugUnitTest 全绿；bestmove 冒烟通过 |
| M5 | BotSession 全流程：走棋/敌方/认输/绝杀/和棋 | 真机完整对局若干盘零人工干预 |
| M6 | 自动下一局 + 设置持久化 + 日志打磨 | 连续多局自动开下一局 |

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
| pikafish android 构建 | 双路径：官方 release 有 android 包则直接集成；否则 NDK 源码交叉编译（脚本化） |
| 大体积 NNUE 进包 | assets 打包即可；如后续上架应用市场再评估动态下发 |
| 悬浮窗 ComposeView 生命周期坑 | OverlayHost 统一封装 installOwner 逻辑，一次解决两处窗口 |
| 模板匹配性能不足 | 先按 python 优先匹配策略移植；实测超标再引入降采样/ROI 裁剪，阈值不动 |

## 九、已确认决策（2026-08-22）

| 决策点 | 结论 |
|---|---|
| minSdk | **API 31（Android 12+）**，targetSdk 取向导默认最新 |
| pikafish 来源 | **你提供二进制**，放 `third_party/pikafish/`（libpikafish.so + pikafish.nnue），Gradle 拷入 jniLibs/assets |
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
