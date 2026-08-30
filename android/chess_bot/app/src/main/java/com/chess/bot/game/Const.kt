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
    const val CORRECT_TEMPLATE_SIZE = 60

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
    // 该值仅作为 verify 循环内「首帧之后」的采样间隔；verify 总检测时长由 firstWaitMs + 300ms
    // 时间窗控制（与 VERIFY_NEXT_FRAME_MS 解耦，无论多小都能保证至少 300ms 复检）。
    // （原 MOVE_VERIFY_COUNT 固定次数已废弃，改时间窗控制。）
    const val VERIFY_NEXT_FRAME_MS = 30

    // ---------- 我方走棋重试（无限重试 + 守卫） ----------
    // 不设次数上限；退出条件 = 对弈结束判断（终局/认输/将帅缺失）
    const val SELF_MOVE_ZERO_CHANGE_MAX =
        5 // 内置守卫：连续 N 个整步「零变化(SILENT)或无法归类(NOISY)」即走棋持续未确认 → 判异常暂停；LIFTED 为进度态重置计数
    const val RETRY_BACKOFF_START_MS =
        1000L // 点按注入失败时的重试延迟兜底（仅此一处）；正常走子路径由 verifyForSelfMove 约 700ms+ 节流，已废除指数退避

    // ---------- 引擎 ----------
    const val ENGINE_MOVETIME_MS = 500
    const val ENGINE_DEPTH = 40 // 引擎搜索最大层数（优先于 movetime 触发的限制之一）

    // 手机 SoC 核心数少于 PC：有意低于 python 的 12（勿在未确认目标机型核心数时调回）
    const val ENGINE_THREADS = 6
    const val ENGINE_HASH_MB = 1024
    const val ENGINE_MATE_PROBE_MS = 200 // 绝杀判断用的短时限探测（仅终局附近才触发）
    const val ENGINE_RULE60_MAX_PLY = 60 // 自然限招

    // ---------- 开局库 ----------
    const val ENGINE_BOOK_ENABLED = true // 是否启用开局库
    const val ENGINE_BOOK_MAX_MOVES = 14 // 开局库最大着法数（超过则进入引擎计算）

    // 绝杀残差探测门槛：盘面棋子数超过此值视为中局，主搜已能覆盖将死，不再二次调用引擎验证（Option A）
    const val ENDGAME_PROBE_PIECE_MAX = 14

    // ---------- 和棋弹窗 ----------
    const val DRAW_TEXT_THRESHOLD = 0.75
    const val DRAW_REJECT_CP = 1000 // 我方优势超过此值（厘兵）则拒绝，否则同意
    const val DRAW_CHECK_THROTTLE_MS = 1000L // 事件触发的和棋检查最小间隔（T1）
    const val DRAW_DIALOG_SETTLE_MS = 300L // 点击和棋按钮后等待弹窗消失

    // ---------- 敌方走棋检测 ----------
    const val ENEMY_RECHECK_WAIT_MS = 300L // 噪声帧延时复检
    const val ENEMY_NOISY_MAX = 3 // 连续噪声帧上限，超过则暂停自动对弈

    // 敌方走棋检测（2026-08-30 调整）：已移除 frameDiff 轻量帧差轮询——实测对单步走棋子差不敏感、
    // 整局 100% 漏判，回退成定时强制识别反而每步多等 ~200ms。现改为 waitForEnemyMove 每轮直接全量
    // recognizeBoard，检出延迟收敛到「一次识别耗时」(~250ms)，以更高 CPU 占用换稳定即时检出。
    // 下列 ENEMY_FRAME_* / ENEMY_FORCE_RECOGNIZE_MS 为 frameDiff 时代遗留常量，当前已不再被读取，仅留作参考。
    const val ENEMY_FRAME_PIXEL_THRESHOLD = 18.0 // 【已废弃】原 frameDiff 单像素灰度差阈值
    const val ENEMY_FRAME_CHANGED_MIN = 12      // 【已废弃】原 frameDiff 触发识别的最小变化像素数
    const val ENEMY_FORCE_RECOGNIZE_MS = 200L // 【已废弃】原 frameDiff 兜底强制识别间隔，现每轮均全量识别
    const val ENEMY_IDLE_POLL_MS = 30L // 每轮全量识别后的短暂让步间隔（节流；识别本身已 ~250ms）

    // ---------- 对局结束 / 认输检测 ----------
    const val RESIGN_CONFIRM_COUNT = 3 // 双方将帅缺失需连续几帧才确认
    const val RESIGN_SUSPECT_WAIT_MS = 1000L // 单帧疑似结束时延时再采样
    const val RESIGN_EMPTY_DROP_MAX = 6 // 单帧「棋子变空」的格子数超过此值即疑似结束（>6）；清盘动画强于将帅遮挡信号

    // ---------- 图片识别（矫正棋盘空间，像素） ----------
    const val DIFF_WINDOW = 10
    const val DIFF_THRESHOLD = 8
    const val MATCH_SEARCH_HALF = 10
    const val EMPTY_MATCH_THRESHOLD = 0.8

    // ---------- 自动下一局 ----------
    const val AUTO_NEXT_GAME = true
    const val AUTO_NEXT_TIMEOUT_S = 180 // 结算交互+摆棋总超时（秒）
    const val GAMEOVER_SCAN_INTERVAL_MS = 300L // 扫描间隔
    const val BOARD_STABLE_THRESHOLD = 3 // 结算文字消失后连续相同棋盘帧数
    const val GAMEOVER_RETRY_MAX = 3 // 同一按钮/遮罩操作上限
    const val GAMEOVER_TEXT_THRESHOLD = 0.75
    const val GAMEOVER_TEMPLATE_W = 1080
    val GAMEOVER_BUTTON_WORDS =
        listOf("下一关", "晋级赛", "重新挑战", "再来一局") // 按钮类（点击），按优先级
    val GAMEOVER_BACK_WORDS = listOf("段位提升", "铜钱", "领取") // 遮罩类（发返回键）
}
