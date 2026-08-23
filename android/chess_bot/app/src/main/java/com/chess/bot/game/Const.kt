package com.chess.bot.game

/** 常量：与 python config.py 严格一致，禁止随手调参。 */
object Const {

    // ---------- 透视矫正 ----------
    // 四角格中心坐标按分辨率查表（矫正棋盘固定 900x1000，格边长 100）
    val BOARD_CORNERS: Map<Pair<Int, Int>, List<Pair<Double, Double>>> = mapOf(
        (1080 to 2400) to listOf(
            76.0 to 680.0,
            1004.0 to 680.0,
            67.0 to 1700.0,
            1013.0 to 1700.0,
        ),
        (1440 to 3200) to listOf(
            101.3 to 906.7,
            1338.7 to 906.7,
            89.3 to 2266.7,
            1350.7 to 2266.7,
        ),
    )

    const val CORRECT_CELL = 100
    const val CORRECT_W = 900
    const val CORRECT_H = 1000
    const val CORRECT_TEMPLATE_SIZE = 60

    // ---------- 延时（毫秒） ----------
    const val TAP_HOLD_INTERVAL_MS = 400L // 点起子 -> 点落子之间的间隔
    const val MOVE_SETTLE_MS = 500L // 落子后每次校验截图前的等待
    const val MOVE_VERIFY_COUNT = 5 // 走棋校验截图次数
    const val SELF_MOVE_ATTEMPTS = 2 // 整步重试上限

    // ---------- 引擎 ----------
    const val ENGINE_MOVETIME_MS = 1000
    // 手机 SoC 核心数少于 PC：有意低于 python 的 12（勿在未确认目标机型核心数时调回）
    const val ENGINE_THREADS = 8
    const val ENGINE_HASH_MB = 2048
    const val ENGINE_MATE_PROBE_MS = 200 // 绝杀判断用的短时限探测
    const val ENGINE_RULE60_MAX_PLY = 60 // 自然限招

    // ---------- 和棋弹窗 ----------
    const val DRAW_TEXT_THRESHOLD = 0.75
    const val DRAW_REJECT_CP = 1000 // 我方优势超过此值（厘兵）则拒绝，否则同意

    // ---------- 敌方走棋检测 ----------
    const val ENEMY_RECHECK_WAIT_MS = 500L // 噪声帧延时复检
    const val ENEMY_NOISY_MAX = 3 // 连续噪声帧上限，超过则暂停自动对弈

    // ---------- 对局结束 / 认输检测 ----------
    const val RESIGN_CONFIRM_COUNT = 3 // 双方将帅缺失需连续几帧才确认
    const val RESIGN_SUSPECT_WAIT_MS = 1000L // 单帧疑似结束时延时再采样

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
