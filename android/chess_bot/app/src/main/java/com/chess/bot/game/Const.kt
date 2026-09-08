package com.chess.bot.game

/** 常量：与 python config.py 严格一致，禁止随手调参。 */
object Const {

    // ---------- 摆棋等待（开始棋局与自动下一局共用） ----------
    const val WAIT_BOARD_LOG_INTERVAL_S = 30 // 等待摆棋期间的周期性日志间隔（秒）

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
    const val CLS_LIFT_GATE = 0.30

    // 摆棋接受门几何容差（2026-09-08 缩小棋盘误开局防护）：摆棋稳定后用 det 重定位四角，
    // 与校准四角最大逐点偏差 ≤ 此值才接受，否则视为结束动画/缩放棋盘，继续等待。
    // det 一次推理仅在接受新局时执行（每局 1 次），不在热路径。
    const val BOARD_GEOMETRY_TOL_PX = 10

    // cls 变化格确认阈值（2026-09-07 真机实测）：修复双重 softmax 后静态棋子 top1 饱和 1.0，
    // 飞行中动画帧（棋子被提起/途经）实测 0.96 且错判、0.98+ 未发现错。低于此值的读数视为
    // 「未确认」：不进 changes（不参与帧分类/敌着两帧确认/提交）、board 沿用已提交值、基线
    // 不刷新——diff 循环下帧自动复检（~45ms/帧），等价免费的时序共识；持续低置信格由噪声
    // 守卫兜底暂停，日志可见。
    const val CLS_TRUST_MIN = 0.98f

    /** Board 格值的「提子」语义（cls lift 类）：帧分类瞬时态，提交点归一化为 null。 */
    const val LIFT = "lift"
    // lift 混淆门控（仅帧差触发格）：top1 为棋子但 lift 概率 ≥ 此值 → 判动画帧，按提子返回。
    // 真机日志显示走子动画/选中高亮会把提起中的棋子误判成其他棋子（如 黑象->黑車）；
    // 模型概率校准好（静止棋子 top1≈1.0、lift≈0），0.30 余量充足。
    // 注：2026-09-07 修复双重 softmax 前此门控是死代码（liftProb 被压到天花板 0.1534 < 0.30
    // 永不触发），修复后按模型真实概率工作，0.30 待真机实测确认。

    // ---------- 提子恢复（2026-09-08 game_start_lift_recovery_plan） ----------
    /** 我方半区起始行（屏幕网格约定：我方恒在 rows 5..9，与执红执黑无关）。 */
    const val OWN_HALF_MIN_ROW = 5

    /** 我方提子确认帧数：连续同位置 lift 达此帧数才触发恢复流程（过滤飞行途经瞬态伪影）。 */
    const val LIFT_CONFIRM_FRAMES = 2

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
    const val VERIFY_HARD_CAP_MS =
        15_000L // 兜底硬顶：单次 verify 总时长超此值 → RETRY_BOTH（防非稳定的持续动画模式永久悬挂，liveness 保护）

    // ---------- 我方走棋重试（无限重试 + 守卫） ----------
    // 不设次数上限；退出条件 = 对弈结束判断（终局/认输/将帅缺失）
    const val SELF_MOVE_ZERO_CHANGE_MAX =
        5 // 内置守卫：连续 N 个整步「零变化(SILENT)或无法归类(NOISY)」即走棋持续未确认 → 判异常暂停；LIFTED 为进度态重置计数
    const val RETRY_BACKOFF_START_MS =
        1000L // 点按注入失败时的重试延迟兜底（仅此一处）；正常走子路径由 verifyForSelfMove 约 700ms+ 节流，已废除指数退避

    // ---------- 引擎 ----------
    const val ENGINE_MOVETIME_MS = 500

    // 手机 SoC 核心数少于 PC：有意低于 python 的 12（勿在未确认目标机型核心数时调回）
    const val ENGINE_THREADS = 6
    const val ENGINE_HASH_MB = 1024
    const val ENGINE_MATE_PROBE_MS = 200 // 绝杀判断用的短时限探测（仅终局附近才触发）
    const val ENGINE_RULE60_MAX_PLY = 60 // 自然限招
    // F2-A（2026-09-08）：裸 go ponder 无限预搜的唯一时间闸门——敌方思考超此值仍未走子，
    // 提前 ponderhit 按质量门控收割预搜结果（走 Y 直接消费、走 Z 作废）。故意 > MOVETIME：
    // 给快敌手留「命中即瞬时取结果」的机会，同时封顶慢敌手下的 CPU 占用
    const val ENGINE_PONDER_CAP_MS = 3_000

    // ---------- 引擎质量门控（go infinite + 持续监听方案，2026-09-08） ----------
    // 伪影 time 阈值基线；实际取 max(此值, TARGET×10%)，避免快棋场景把合法行全滤掉
    const val ENGINE_MIN_VALID_INFO_MS = 100

    // 质量门控/伪影 A2 的节点下限（硬计算量指标，抗 CPU 波动）。
    // 注：单线程基准值；nodes 为全局累计计数，当前 Threads=6 下阈值仍适用，
    // 未来若改变线程/搜索架构需按实际 nps 重估。
    const val ENGINE_MIN_VALID_NODES = 10_000L

    // 质量门控 depth 下限（基础覆盖度；单看 seldepth 会被短线杀棋延伸骗过）
    const val ENGINE_DEPTH_MIN = 8

    // 超快棋兜底：TARGET < ENGINE_FAST_TARGET_MS 时 depth 门槛下调至此
    //（复杂中局可能到硬顶都搜不到 8 层，避免短时限全部以质量未达标收尾）
    const val ENGINE_DEPTH_MIN_FAST = 6
    const val ENGINE_FAST_TARGET_MS = 500

    // 质量门控 seldepth 下限（真实思考层次；D6-A：先固定 10，EngineResult 全量采样后再调）
    const val ENGINE_SELDEPTH_MIN = 10

    // 伪影 A2 规则：depth−seldepth 差距阈值 + 前置 depth 下限
    //（depth 低于前置值时深度差参考价值低，跳过判定）
    const val ENGINE_ARTIFACT_DEPTH_GAP = 15
    const val ENGINE_ARTIFACT_MIN_DEPTH = 20

    // D6 数据采样开关（临时）：开启时 drain 线程对每条可解析 info 行打「INFO样本」DEBUG 日志，
    // 供离线分析动态伪影阈值。2026-09-08 采样结论（14911 行 / 128 段）：现行 A1/A2 零误杀
    // （KEEP 行 nodes 最小 40935 ≫ 10000 门槛），阈值不动；采样代码保留备查，默认关闭。
    const val ENGINE_INFO_SAMPLE = false

    // 盲区提前止损（D6 数据实证）：TT 饱和时引擎 <100ms 内冲到伪深度（depth≥100 且
    // seldepth≤12）后整个搜索期沉默——盲区段 200ms 后 0 新 info（55 段实证）。
    // 止损条件 = elapsed≥此值 + 无有效 info + 已观测伪深度洪泛（指纹）：
    // 命中 91% 盲区段，晚 KEEP 正常段 0/14 误伤；无指纹的盲区段退回 TARGET 原时点止损。
    const val ENGINE_BLIND_EARLY_MS = 200
    const val ENGINE_BLIND_PSEUDO_DEPTH = 100
    const val ENGINE_BLIND_PSEUDO_SELDEPTH = 12

    // mate 步数 ≤ 此值提前停（仅精确 mate，bound 态 mate 不触发提前终止）
    const val ENGINE_MATE_STOP_PLY = 3

    // 硬顶追加式绝对上限：任意分段档位再 clamp 到 TARGET + 此值
    const val ENGINE_HARD_CAP_APPEND_MS = 5000

    // 发 stop 后等 bestmove 的上限（超时走现有 restart 兜底）
    const val ENGINE_STOP_BESTMOVE_TIMEOUT = 2000L

    // F4 info 停更超时：currentInfo 距上次更新超过此值且已过 TARGET → 判「先有评估后沉默」，止损停。
    // 防御性（2026-09-08 真机日志未见该形态），覆盖 TT 饱和的中间态（先输出部分有效行后引擎沉默）
    const val ENGINE_INFO_STALE_MS = 1000L

    // ---------- 开局库 ----------
    const val ENGINE_BOOK_ENABLED = true // 是否启用开局库（启用即全程生效：命中走书、未命中回落引擎）

    // 绝杀残差探测门槛：盘面棋子数超过此值视为中局，主搜已能覆盖将死，不再二次调用引擎验证（Option A）
    const val ENDGAME_PROBE_PIECE_MAX = 14

    // ---------- 和棋弹窗 ----------
    const val DRAW_REQUEST_WORD = "对方请求和棋" // 弹窗标题词，与两个按钮词三词同现才认定和棋页面
    const val DRAW_ACCEPT_WORD = "同意"
    const val DRAW_REJECT_WORD = "拒绝"
    const val DRAW_REJECT_CP = 1000 // 我方优势超过此值（厘兵）则拒绝，否则同意
    const val DRAW_CHECK_THROTTLE_MS = 1000L // 事件触发的和棋检查最小间隔（T1）
    const val DRAW_DIALOG_SETTLE_MS = 300L // 点击和棋按钮后等待弹窗消失

    // ---------- 敌方走棋检测 ----------
    const val ENEMY_RECHECK_WAIT_MS = 300L // 噪声帧延时复检
    const val ENEMY_NOISY_MAX = 3 // 连续噪声帧上限，超过则暂停自动对弈

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
        50L // 每轮全量识别后的短暂让步间隔（2026-09-07 D1=B：30→50，降低 grab 频率/GC 压力，敌着检出延迟 +~20ms）

    // ---------- 对局结束 / 认输检测 ----------
    const val RESIGN_CONFIRM_COUNT = 3 // 双方将帅缺失需连续几帧才确认
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
    val GAMEOVER_BUTTON_WORDS =
        listOf("下一关", "晋级赛", "重新挑战", "再来一局") // 按钮类（点击），按优先级
    val GAMEOVER_BACK_WORDS = listOf("段位提升", "铜钱", "领取") // 遮罩类（发返回键）
    val GAMEOVER_INTERRUPT_WORDS = listOf("体力x2") // 终止类（自动中断对弈），优先级高于遮罩/按钮

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
        "段位提升" to OcrRoi(0.20f, 0f, 0.80f, 0.10f),
        "铜钱" to OcrRoi(0.30f, 0.40f, 0.70f, 0.60f),
        "领取" to OcrRoi(0.30f, 0.50f, 0.70f, 0.65f),
        // 终止遮罩
        "体力x2" to OcrRoi(0.40f, 0.50f, 0.60f, 0.6f),
    )
}

/** OCR 词级搜索区域：全屏百分率矩形，(x1,y1)=左上、(x2,y2)=右下，取值 0~1（相对图宽/图高）。 */
data class OcrRoi(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
