package com.chess.bot.game

/** 常量：与 python config.py 严格一致，禁止随手调参。 */
object Const {

    // ---------- 透视矫正 ----------
    // 四角格中心坐标按分辨率查表（矫正棋盘固定 900x1000，格边长 100）
    val BOARD_CORNERS: Map<Pair<Int, Int>, List<Pair<Double, Double>>> = mapOf(
        (1080 to 2376) to listOf(
            76.0 to 667.0,
            1004.0 to 667.0,
            67.0 to 1688.0,
            1014.0 to 1688.0,
        ),
        (1080 to 2400) to listOf(
            76.0 to 680.0,
            1004.0 to 680.0,
            67.0 to 1700.0,
            1014.0 to 1700.0,
        ),
        (1440 to 3200) to listOf(
            101.5 to 905.5,
            1339.5 to 905.5,
            89.0 to 2266.0,
            1352.0 to 2266.0,
        ),
    )

    const val CORRECT_CELL = 100
    const val CORRECT_W = 900
    const val CORRECT_H = 1000

    // ---------- YOLO ONNX 视觉（cls 棋子识别 / det 四角回退） ----------
    // cls：矫正空间格心裁 64x64 喂分类模型（训练输入即 64，勿改）
    const val CLS_CELL = 64

    // det 四角：letterbox 1280（推理 imgsz 必须严格等于训练 imgsz），极低 conf 阈值下每类 argmax
    const val DET_IMGSZ = 1280
    const val DET_CONF = 0.001

    // 摆棋接受门几何容差（2026-09-08 缩小棋盘误开局防护）：摆棋稳定后用 det 重定位四角，
    // 与校准四角最大逐点偏差 ≤ 此值才接受，否则视为结束动画/缩放棋盘，继续等待。
    // det 一次推理仅在接受新局时执行（每局 1 次），不在热路径。
    const val BOARD_GEOMETRY_TOL_PX = 10

    // cls 变化格确认阈值（D1 批复 2026-09-11：0.98 → 0.95）。低于此值的读数视为「未确认」：
    // 不进 changes（不参与帧分类/敌着两帧确认/提交）、board 沿用已提交值、基线不刷新——
    // diff 循环下帧自动复检（~45ms/帧），等价免费的时序共识；持续低置信格由噪声守卫兜底暂停，日志可见。
    // ⚠️ 与原 0.98 相比放宽：更多低置信读数被采信（识别侧收益 / 误判风险同升），
    // 2026-09-07 实测「飞行中动画帧 0.96 且错判」的记录即落在此阈值之下 → 需真机复验后再定去留。
    const val CLS_TRUST_MIN = 0.95f

    // empty 类分档（2026-09-10 D2=A）：empty 训练采样中动画帧截图少，被动画遮挡格的 empty
    // 置信度系统性偏低（真机 log.txt：未确认格中 new=empty 105 格、[0.95,0.98) 区间 18 格，
    // 为清盘动画渐进遮盖主力；棋子类同区间仅 3 格）→ empty 阈值放宽至 0.95。
    // lift 误确认直接触发 LIFTED 状态，代价不对称。原 D3=A 曾与棋子同档 0.98；D1 批复后棋子档
    // 亦降至 0.95（2026-09-11），两档数值重新巧合一致，但语义仍独立（empty / 棋子各一条规则）。
    const val CLS_TRUST_MIN_EMPTY = 0.95f

    /** Board 格值的「提子」语义（cls lift 类）：帧分类瞬时态，提交点归一化为 null。 */
    const val LIFT = "lift"

    // ---------- 提子恢复（2026-09-08 game_start_lift_recovery_plan） ----------
    /** 我方半区起始行（屏幕网格约定：我方恒在 rows 5..9，与执红执黑无关）。 */
    const val OWN_HALF_MIN_ROW = 5

    /** 我方提子确认帧数：连续同位置 lift 达此帧数才触发恢复流程（过滤飞行途经瞬态伪影）。 */
    const val LIFT_CONFIRM_FRAMES = 2

    /** grabBoard 慢帧阈值（2026-09-09 D1 门控；2026-09-12 Q1 收敛为唯一的性能行触发条件）：
     *  grab 超此值才打「grabBoard grab=Xms recog=Yms」——常态帧与安静期全静默，性能异常帧一条不丢。
     *  原异常行事件明细（diff/暂缓/剔除/漂移）与其漂移限频常量 GRAB_LOG_DRIFT_INTERVAL_MS 已删除。 */
    const val GRAB_LOG_SLOW_MS = 80L

    /** 提子卡死诊断门（G1=A，2026-09-08）：确认次数达到 N 帧仍未完成恢复（首次恢复 RETRY
     *  后的下帧即满足）→ SettleWaiter 发 OwnLiftStalled，调用方触发一次 det 几何诊断，
     *  判断是否缩小棋盘被误读为提子。 */
    const val LIFT_STALL_FRAMES = 2

    /** 提子卡死诊断门节流：det 诊断至少间隔此时长（缩小棋盘持续期间避免高频重复推理）。 */
    const val LIFT_STALL_CHECK_INTERVAL_MS = 10_000L

    /** 提子身份识别：从 lift 格中心向上扫描的 dy 范围与步长（px；一格高 100px）。
     *  原理：提子 3D 悬浮特效投影 2D 后棋子位于格子上半部（部分覆盖正上方格子），
     *  向上逐 dy 裁 64x64 送 cls，必有一档让悬浮棋子居中。 */
    const val LIFT_SCAN_MIN_DY_PX = 8

    // 2026-09-08 真机实测：有效识别带止于 dy≈58（棋子悬空不足一格），70 = 58+12px 余量；
    // 上限收紧后 dy>70 的「正上方格污染档」从源头消失（D2-B）。
    const val LIFT_SCAN_MAX_DY_PX = 70
    const val LIFT_SCAN_STEP_PX = 10

    /** 提子身份识别：单档读数接受阈值（悬浮棋子带阴影/特效，置信度低于静止棋子的 0.98）。 */
    const val LIFT_SCAN_MIN_PROB = 0.5f

    // ---------- 延时（毫秒） ----------
    // 落子间隔：按下到松开的最短保持时间；DataStore 持久化用户可在设置页「对弈」分组覆盖
    const val TAP_HOLD_MS = 50

    // verify 帧时序（2026-08-29 → 2026-08-30 调整）：首帧等待 = 走子动画公式
    // （提起+飞一格+落下最低 400ms，每多飞一格 +60ms）；走棋动画基准由 DataStore 持久化
    // 用户可在设置页「对弈」分组覆盖（原 VERIFY_ANIM_REDUNDANCY_MS 已废弃，改由 verify 的
    // firstWaitMs + 300ms 兜底覆盖，无需单独常量）。
    const val VERIFY_ANIM_BASE_MS = 350
    const val VERIFY_ANIM_PER_CELL_MS = 60L

    // 走棋检测间隔：相邻校验帧的短间隔；DataStore 持久化用户可在设置页「对弈」分组覆盖。
    // 该值仅作为 verify 循环内「首帧之后」的采样间隔；v3 重构（2026-09-06）已废除全局时间窗，
    // verify 的退出由 diff 数量分流 + 稳定未知兜底 + HARD_CAP 三重机制保证。
    // （原 MOVE_VERIFY_COUNT 固定次数、maxWaitMs 时间窗均已废弃。）
    const val VERIFY_NEXT_FRAME_MS = 30

    // v3 走棋检测分流阈值（2026-09-06 Q1 重构，方案见 .workbuddy/self_move_verify_redesign.md）：
    const val VERIFY_SILENT_K1 =
        2 // n==0 静止稳定帧数 ≥K1 → 判「两次点击均未生效」，重试两格（Retry(BOTH)）
    const val VERIFY_UNKNOWN_STABLE_MS =
        2000L // 稳定未知模式（变化格子与上帧逐格相同且不可行动）持续阈值 → 终局/和棋检查 + 重试兜底
    const val VERIFY_OCR_DIFF_CELLS =
        30 // diff cell 数 > 此值 → 大面积遮挡（和棋弹窗/结算遮罩/结束画面），触发 OCR/终局检查（节流）

    // 连续大面积遮挡帧上限（2026-09-12 用户批复）：连续帧数 > 此值（即第 7 帧）→ 判定对局结束。
    // 为什么要这一路：残局棋子本来就少，遮罩既可能凑不出「清盘 >6 格」、也可能读不出将帅（遮罩半透明
    // 导致置信度掉到确认门以下）——「画面连续多帧被大面积覆盖」本身就是终局证据。计数器跨 verify 重试
    // 累积（BotSession.occlusionStreak），中途出现任一帧 diffCells ≤ VERIFY_OCR_DIFF_CELLS 即清零（连续口径）。
    // 豁免（2026-09-12 用户批复）：和棋弹窗是唯一「大面积遮挡但不中止对局」的场景，而它在 verifyEndgameCheck
    // 内检出并处理——故该处检出后即把计数归零，且上限判定排在该检查之后（弹窗拿得到否决机会）。
    const val VERIFY_OCCLUSION_STREAK_MAX = 6
    const val VERIFY_HARD_CAP_MS =
        15_000L // 兜底硬顶：单次 verify 总时长超此值 → RETRY_BOTH（防非稳定的持续动画模式永久悬挂，liveness 保护）

    // 全量取帧切换阈值（2026-09-13 用户批复 P3/P4）：本步走棋尝试次数 ≥2 **或**首次成功点击起超此值
    // （取先到）→ verify 取帧由逐格 diff 切换为全量（grabBoardFull：跳过逐格 diff 门，90 格全部进复检）。
    // 动因：diff 基线可能失明——落点被误提起后 committed 该格为 null，读数「空→lift」被扫描层当飞行
    // 伪影剔除（Recognition.kt 的 `old == null && new == LIFT` 分支），该格从此不进 changes
    // → 真机 h6h4 卡死 39.7s。
    // ⚠️ 全量只放宽「进入复检」的门，changes / diffCells / driftCells 语义**全部不变**：飞行伪影照旧
    // 剔除、diffCells 仍只算像素真变格——否则恒 ≈90 会被下游 (d) 分支当成大面积遮挡 → 误判终局
    // （2026-09-13 用户批复）。
    // 进入全量后**本步后续每帧都保持全量**（P4：正确读数优先于速度），不退回 diff。
    const val VERIFY_FULL_TRIGGER_MS = 3_000L

    // ---------- 我方走棋重试（无限重试 + 总超时） ----------
    // 不设次数上限；退出条件 = 走棋成功 / 对弈结束 / 总超时。2026-09-10 D1=A 删除原
    // 「连续 5 轮零变化」守卫（SELF_MOVE_ZERO_CHANGE_MAX）：设备发烫卡顿时点击事件排队延迟，
    // log.txt 段一实证 5 次补点全部被吞、守卫暂停后数秒棋子自行走出——无限重试 + 总超时更稳。
    const val SELF_MOVE_TOTAL_TIMEOUT_MS =
        60_000L // 单步走棋总超时（D1=A）：超时直接异常暂停（点「开始」重新开始＝新一局），并打守卫布局供诊断
    const val SELF_RETRY_COOLDOWN_MS =
        1_000L // 重试点击后的冷却（D2=A：RETRY_DST/RETRY_BOTH 生效；RETRY_AFTER_ENEMY 立即重试不冷却），给卡顿设备排空输入队列
    const val RETRY_BACKOFF_START_MS =
        1000L // 点按注入失败时的重试延迟兜底（仅此一处）；正常走子路径由 verifyForSelfMove 约 700ms+ 节流，已废除指数退避

    // ---------- 引擎 ----------
    const val ENGINE_MOVETIME_MS = 500

    // 手机 SoC 核心数少于 PC：有意低于 python 的 12（勿在未确认目标机型核心数时调回）
    const val ENGINE_THREADS = 6
    const val ENGINE_HASH_MB = 1024
    const val ENGINE_RULE60_MAX_PLY = 90 // 自然限招

    // position 滚动重锚窗口（2026-09-12 D1=B）：movesList 达此手数即重拍基线 FEN（fen 滚动生成）、
    // moves 清零 → 引擎 position 行只携带窗口内着法（携带上限 = 窗口−1 = 5）。
    // 取舍：窗口越大重复局面/长打感知越深，越小 position 行越短——6 为「有界+保留近期重复感知」折中。
    const val ENGINE_MOVE_WINDOW = 6

    // 硬顶追加值（R6）：硬顶 = TARGET + 此值。go movetime 由引擎自停、正常路径永不触顶；
    // 此值只为 go ponder 与引擎假死/管道堵塞兜底，仅留 1s 收尾余量。
    const val ENGINE_HARD_CAP_APPEND_MS = 1000

    // 主搜「思考时间过短」闸门（2026-09-11 04:24 真机：引擎 15ms 返回 500ms 请求，
    // depth=245/seldepth=2/nodes=8026/score=0 提示把局面判成和棋瞬时收手）：
    // 实测耗时 < target * ENGINE_MIN_THINK_RATIO 视为异常提前返回 → go() 内重发 position+go 重搜，
    // 最多 ENGINE_MIN_THINK_RETRIES 次。用比例而非绝对阈值，因 movetime 是「时间下限」、正常搜
    // 永不低于 40%；唯一着法/将死等合法瞬时返回由重试封顶后接受（着法本就正确），避免死循环。
    // ponder 收割（ponderHit）走 monitorSearch 直连、快速返回是设计预期，不触发本闸门。
    const val ENGINE_MIN_THINK_RATIO = 0.4f
    const val ENGINE_MIN_THINK_RETRIES = 2

    // 发 stop 后等 bestmove 的上限（超时走现有 restart 兜底）
    const val ENGINE_STOP_BESTMOVE_TIMEOUT = 2000L

    // UCI 收发埋点（原 D4 开关 2026-09-11）：开启后每行发出的 UCI 命令与 bestmove 到达时刻
    // 各落一条 DEBUG（bestmove 行附「自最近一次 go 起的耗时」，配对即可直接量出单步真实搜索时长）。
    // 用途：真机观察点 1（单步耗时是否 ≈ 设定思考时间）取证 + go/ponderhit 是否真送达引擎。
    // ⚠️ 会显著增加日志量（每步约 +3~5 行）。本开关已迁移为运行时设置 BotConfigData.debugUciTrace
    // （默认 false＝关闭，设置页「调试日志」分组可临时打开），不再由 Const 硬编码常量控制。

    // ---------- 开局库 ----------
    const val ENGINE_BOOK_ENABLED = true // 是否启用开局库（启用即全程生效：命中走书、未命中回落引擎）

    // ---------- 和棋弹窗 ----------
    const val DRAW_REQUEST_WORD = "对方请求和棋" // 弹窗标题词，与两个按钮词三词同现才认定和棋页面
    const val DRAW_ACCEPT_WORD = "同意"
    const val DRAW_REJECT_WORD = "拒绝"
    const val DRAW_REJECT_CP = 1000 // 我方优势超过此值（厘兵）则拒绝，否则同意
    const val DRAW_CHECK_THROTTLE_MS = 1000L // 事件触发的和棋检查最小间隔（T1）
    const val DRAW_DIALOG_SETTLE_MS = 300L // 点击和棋按钮后等待弹窗消失

    // ---------- 敌方走棋检测 ----------
    // 2026-09-10 waitForEnemyMove 重构（参考 verifyForSelfMove v3）：废除「连续噪声帧暂停」体系
    //（原 ENEMY_NOISY_MAX/ENEMY_RECHECK_WAIT_MS 及 GameState.noisyCount），改为 n 分流 +
    // 稳定未知兜底 + 总超时三层收敛（结算动画等长噪声序列不再误暂停）。
    const val ENEMY_WAIT_TOTAL_TIMEOUT_MS =
        180_000L // 等待对方走棋总超时（对方单步限时 ≤120s + 动画余量）；超时先 OCR 强扫（force）再暂停
    const val ENEMY_STABLE_SCAN_THROTTLE_MS =
        2000L // 稳定未知模式触发「终局检查+OCR 强扫」的外层节流（confirmEndByOcr(force=true) 绕内层 1s 节流）

    // 敌着两帧一致确认（T-D，2026-09-06）：首帧 MOVED 可能是动画中途帧（如車 C0→C9 途经 C5，
    // 几何合法、伪合法校验拦截不了），复抓复判、两帧同着法才提交。原显式延时 ENEMY_MOVE_SETTLE_MS
    // （=30ms 半格飞行时间）已于 2026-09-06 02:30 去除：实测单次 grabBoard 耗时 ~70-100ms，
    // 复抓本身已足够越过半格飞行窗口，无需额外等待。verify 的 N3/N4（SELF_THEN_ENEMY）敌着同规则确认。

    // 敌方走棋检测（2026-08-30 调整）：已移除 frameDiff 轻量帧差轮询——实测对单步走棋子差不敏感、
    // 整局 100% 漏判，回退成定时强制识别反而每步多等 ~200ms。现改为 waitForEnemyMove 每轮直接全量
    // recognizeBoard，检出延迟收敛到「一次识别耗时」(~250ms)，以更高 CPU 占用换稳定即时检出。
    // 下列 ENEMY_FRAME_* / ENEMY_FORCE_RECOGNIZE_MS 为 frameDiff 时代遗留常量，当前已不再被读取，仅留作参考。
    const val ENEMY_FRAME_PIXEL_THRESHOLD = 18.0 // 【已废弃】原 frameDiff 单像素灰度差阈值
    const val ENEMY_FRAME_CHANGED_MIN = 12      // 【已废弃】原 frameDiff 触发识别的最小变化像素数
    const val ENEMY_FORCE_RECOGNIZE_MS = 200L // 【已废弃】原 frameDiff 兜底强制识别间隔，现每轮均全量识别
    const val ENEMY_IDLE_POLL_MS =
        40L // 每轮全量识别后的短暂让步间隔（2026-09-07 D1=B：30→50，降低 grab 频率/GC 压力，敌着检出延迟 +~20ms）

    // ---------- 对局结束 / 认输检测 ----------
    const val RESIGN_CONFIRM_COUNT = 3 // 任一将/帥离盘（或清盘信号）需连续几帧才确认（2026-09-12 起原「双方将帅缺失」改为单侧）
    const val RESIGN_SUSPECT_WAIT_MS = 1000L // 单帧疑似结束时延时再采样
    const val RESIGN_EMPTY_DROP_MAX = 6 // 单帧「棋子变空」的格子数超过此值即疑似结束（>6）；清盘动画强于将帅遮挡信号

    // ---------- 图片识别（矫正棋盘空间，像素） ----------
    const val DIFF_WINDOW = 10
    const val DIFF_THRESHOLD = 8

    // 【已废弃】模板匹配棋子识别已由 YOLO cls 替代（2026-09-05）；仅四角校准仍用模板匹配（BoardCornerDetector 自带阈值）
    const val MATCH_SEARCH_HALF = 10
    const val EMPTY_MATCH_THRESHOLD = 0.8

    // ---------- 自动下一局 ----------
    const val AUTO_NEXT_GAME = true
    const val AUTO_NEXT_TIMEOUT_S = 180 // 结算交互+摆棋总超时（秒）
    const val GAMEOVER_SCAN_INTERVAL_MS = 300L // 扫描间隔
    const val BOARD_STABLE_THRESHOLD = 3 // 结算文字消失后连续相同棋盘帧数
    const val GAMEOVER_RETRY_MAX = 3 // 同一按钮/遮罩操作上限

    // OCR 结算交互（按钮点击/遮罩返回键）成功后的延时（2026-09-09 V2 D1=A 用户要求）：
    // 页面切换动画帧需要时间，延时结束后再恢复棋盘检测（原 300ms 扫描间隔不够）
    const val OCR_INTERACTION_SETTLE_MS = 800L
    val GAMEOVER_BUTTON_WORDS =
        listOf("下一关", "晋级赛", "重新挑战", "再来一局") // 按钮类（点击），按优先级
    val GAMEOVER_BACK_WORDS = listOf("段位提升", "结算奖励", "快速升级") // 遮罩类（发返回键）
    val GAMEOVER_INTERRUPT_WORDS = listOf("体力获取", "复活") // 终止类（自动中断对弈），优先级高于遮罩/按钮

    // ---------- OCR（PP-OCRv6 官方 ppocr-sdk，2026-09-06 替代 draw/text 模板） ----------
    // rec 置信度过滤线：实证关键词命中 0.95+，遮罩/动画中的字 0.85~0.92；有「包含匹配」强过滤兜底，0.75 足够保守
    const val OCR_REC_SCORE_MIN = 0.75f

    // 「疑似对局结束画面」时的 OCR 扫描节流（命中立即终局，未命中回落连续棋盘信号确认）
    const val OCR_SUSPECT_SCAN_THROTTLE_MS = 1000L

    // 词级 ROI 预设（2026-09-06 #3）：全屏百分率矩形 (x1,y1)-(x2,y2)，0~1（宽/高百分比）。
    // 同一次扫描只对「本次词表命中配置」的 ROI 求并集后裁剪再 OCR——范围外文字不参与识别（提速+降误报）；
    // 未配置的词回落全图查找（兜底：新词没配 ROI 行为同旧版，不会漏检）。
    // ⚠️ 当前值为初版目测（含较宽边距防 UI 位移），待按实际截图逐页校准替换。
    val OCR_WORD_ROIS: Map<String, OcrRoi> = mapOf(
        // 和棋弹窗（屏幕中部：标题 + 双按钮）
        "对方请求和棋" to OcrRoi(0.10f, 0.4f, 0.70f, 0.6f),
        "同意" to OcrRoi(0.1f, 0.5f, 0.5f, 0.6f),
        "拒绝" to OcrRoi(0.5f, 0.5f, 0.9f, 0.6f),
        // 结算按钮（底部按钮区）
        "下一关" to OcrRoi(0.5f, 0.85f, 0.9f, 1f),
        "晋级赛" to OcrRoi(0.2f, 0.85f, 0.8f, 1f),
        "重新挑战" to OcrRoi(0.2f, 0.85f, 0.8f, 1f),
        "再来一局" to OcrRoi(0.2f, 0.85f, 0.8f, 1f),
        // 结算遮罩（中部提示区）
        "段位提升" to OcrRoi(0.2f, 0f, 0.8f, 0.1f),
        // "铜钱" to OcrRoi(0.3f, 0.4f, 0.7f, 0.6f),  (0.29,0.33)-(0.64,0.37)
        "结算奖励" to OcrRoi(0.2f, 0.3f, 0.7f, 0.4f),
        // "领取" to OcrRoi(0.3f, 0.5f, 0.7f, 0.65f), (0.33,0.34)-(0.66,0.38)
        "快速升级" to OcrRoi(0.3f, 0.3f, 0.7f, 0.4f),
        // 终止遮罩
        // "体力x2" to OcrRoi(0.4f, 0.5f, 0.6f, 0.6f),
        "体力获取" to OcrRoi(0.3f, 0.3f, 0.7f, 0.4f),
        "复活" to OcrRoi(0.3f, 0.3f, 0.7f, 0.5f),
    )
}

/** OCR 词级搜索区域：全屏百分率矩形，(x1,y1)=左上、(x2,y2)=右下，取值 0~1（相对图宽/图高）。 */
data class OcrRoi(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
