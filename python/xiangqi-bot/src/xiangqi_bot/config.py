from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent  # 项目根目录（xiangqi-bot/）

PIKAFISH_DIR = PROJECT_ROOT / "pikafish"  # pikafish 引擎所在目录（引擎须在其中启动才能加载 nnue）
PIKAFISH_EXE = PIKAFISH_DIR / "pikafish-bmi2.exe"  # 引擎可执行文件

TEMPLATES_DIR = PROJECT_ROOT / "templates"  # 14 张 60x60 棋子模板所在目录（从矫正棋盘切割）
GAMEOVER_TEXT_DIR = PROJECT_ROOT / "templates" / "text"  # 结算文字模板所在目录（从原始截图切割）
WEB_DIR = PROJECT_ROOT / "src" / "xiangqi_bot" / "web"  # 网页前端静态文件

# 棋盘四角格中心坐标（透视矫正输入），按截图分辨率 (宽, 高) 查表。
# 每项为 (左上, 右上, 左下, 右下) 四个角格的网格中心坐标（像素）。
# 1080x2400 取自 GRID_CENTERS_NP；1440x3200 为前者等比放大 1.3333 倍。
BOARD_CORNERS: dict[tuple[int, int], tuple[tuple[float, float], ...]] = {
    (1080, 2400): ((76.0, 680.0), (1004.0, 680.0), (67.0, 1700.0), (1013.0, 1700.0)),
    (1440, 3200): ((101.3, 906.7), (1338.7, 906.7), (89.3, 2266.7), (1350.7, 2266.7)),
}

# 矫正棋盘尺寸（像素）：格子边长 100，9 列 x 10 行
CORRECT_CELL = 100
CORRECT_W = CORRECT_CELL * 9  # 900
CORRECT_H = CORRECT_CELL * 10  # 1000
CORRECT_TEMPLATE_SIZE = 60  # 矫正空间下的模板边长（棋子直径约 48px）

# 延时（毫秒）
TAP_HOLD_INTERVAL_MS = 400  # 点起子 -> 点落子之间的间隔
MOVE_SETTLE_MS = 500  # 落子后每次校验截图前的等待（3 次均 500ms）
MOVE_VERIFY_COUNT = 3  # 走棋校验截图次数（全部失败才判定走棋失败）
RECOVERY_WAIT_MS = 500  # 走棋失败恢复：检测到棋子被提起后，延迟该时长再次确认

# 引擎（毫秒）
ENGINE_MOVETIME_MS = 1000  # go movetime <ms>（思考时间，兼顾速度与响应）
ENGINE_THREADS = 12  # setoption name Threads
ENGINE_HASH_MB = 2048  # setoption name Hash（MB）
ENGINE_MATE_PROBE_MS = 200  # 绝杀判断用的短时限探测（无着法会立即返回 (none)）

# 自动检测敌方走棋（毫秒）
AUTO_DETECT_INTERVAL_MS = (
    0  # 截图间隔延时；ADB 截图本身耗时，不再额外延时（无次数限制，直到用户中断）
)
ENEMY_RECHECK_WAIT_MS = 500  # 多格变动/无法构成完整一步（疑似瞬态噪声）时延时复检
ENEMY_NOISY_MAX = 3  # 连续噪声帧上限，超过则按实际变动提交（避免永久卡住检测循环）

# 对局结束 / 敌方认输检测
RESIGN_PIECE_DROP_THRESHOLD = 3  # 可识别棋子数比内存布局至少少几枚，判为对局结束画面
RESIGN_CONFIRM_COUNT = 3  # 疑似对局结束画面需连续几帧稳定出现才确认（过滤瞬态误判）
RESIGN_SUSPECT_WAIT_MS = (
    1000  # 单帧疑似结束时延时再采样，避免快速连续截图把瞬态（敌方提子/手部遮挡）误判为结束
)

# 残局判断：可识别棋子总数少于该值视为残局（轮次无法静态推断，需用户确认）
ENDGAME_PIECE_COUNT = 20

# 自动下一局：对局结束后扫描结算文字（晋级赛/重新挑战/再来一局/下一关/段位提升）并交互，
# 等待下一局摆棋完毕后再自动开始对弈
AUTO_NEXT_GAME = True  # 对局结束后自动开始下一局
GAMEOVER_SCAN_MAX = 20  # 扫描结算文字 / 等待摆棋完毕的截图次数上限
GAMEOVER_SCAN_INTERVAL_MS = 1000  # 扫描间隔（毫秒）
GAMEOVER_TEXT_THRESHOLD = 0.75  # 结算文字模板匹配 TM_CCOEFF_NORMED 阈值
GAMEOVER_TEMPLATE_W = 1080  # 结算文字模板基准宽度（匹配前把原始截图等比缩放到该宽度）
GAMEOVER_TAP_VERIFY_MS = 2000  # 点击结算按钮后的校验延时（动画未结束时点击可能无响应，等待后复检）
GAMEOVER_TAP_RETRY_MAX = 2  # 同一结算按钮最多点击次数，仍不消失则中止自动下一局
GAMEOVER_BUTTON_WORDS = (
    "下一关",
    "晋级赛",
    "重新挑战",
    "再来一局",
)  # 按钮类（点击）：按优先级排列——「下一关」对话框同时含「重新挑战」按钮，必须先处理下一关
GAMEOVER_BACK_WORDS = ("段位提升",)  # 文字类：识别到即发送返回键（无按钮）
GAMEOVER_DISMISS_WORDS = (
    "领取",
)  # 悬浮遮罩文字：识别到须先发返回键消除，再处理按钮（直接点击按钮会被遮罩拦截）

# 图片识别（矫正棋盘空间，像素）
DIFF_WINDOW = 10  # 中心点 10x10 区域对比差异
DIFF_THRESHOLD = 8  # 10x10 区域平均绝对差超过此值视为"有变化"
MATCH_SEARCH_HALF = 10  # 模板匹配时在中心点 ±10px 窗口内滑动
EMPTY_MATCH_THRESHOLD = 0.8  # TM_CCOEFF_NORMED 低于此值判为空格
TEMPLATE_SIZE = CORRECT_TEMPLATE_SIZE  # 棋子模板边长（像素）
