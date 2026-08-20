from pathlib import Path

# 路径
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
PIKAFISH_DIR = PROJECT_ROOT / "pikafish"
PIKAFISH_EXE = PIKAFISH_DIR / "pikafish-bmi2.exe"
TEMPLATES_DIR = PROJECT_ROOT / "templates"
GAMEOVER_TEXT_DIR = TEMPLATES_DIR / "text"
WEB_DIR = Path(__file__).resolve().parent / "web"

# 矫正棋盘：按截图分辨率 (宽, 高) 查四角格中心坐标 (左上, 右上, 左下, 右下)
BOARD_CORNERS: dict[tuple[int, int], tuple[tuple[float, float], ...]] = {
    (1080, 2400): ((76.0, 680.0), (1004.0, 680.0), (67.0, 1700.0), (1013.0, 1700.0)),
    (1440, 3200): ((101.3, 906.7), (1338.7, 906.7), (89.3, 2266.7), (1350.7, 2266.7)),
}
CORRECT_CELL = 100  # 格边长
CORRECT_W = CORRECT_CELL * 9  # 900
CORRECT_H = CORRECT_CELL * 10  # 1000
TEMPLATE_SIZE = 60  # 棋子模板边长

# 图片识别（矫正空间，像素）
DIFF_WINDOW = 10  # 中心点对比窗口边长
DIFF_THRESHOLD = 8  # 平均绝对差超过此值视为有变化
MATCH_SEARCH_HALF = 10  # 模板匹配滑动半径
EMPTY_MATCH_THRESHOLD = 0.8  # 低于此值判为空格

# 我方走棋
TAP_HOLD_INTERVAL_MS = 400  # 起子→落子间隔
MOVE_SETTLE_MS = 500  # 落子后校验截图前等待
MOVE_VERIFY_COUNT = 5  # 校验截图次数

# 敌方走棋检测
ENEMY_RECHECK_WAIT_MS = 500  # 噪声帧延时复检
ENEMY_NOISY_MAX = 3  # 连续噪声帧上限，超过则暂停自动对弈

# 认输检测
RESIGN_CONFIRM_COUNT = 3  # 连续几帧确认
RESIGN_SUSPECT_WAIT_MS = 1000  # 单帧疑似结束时延时再采样

# 引擎
ENGINE_MOVETIME_MS = 1000  # go movetime（毫秒）
ENGINE_THREADS = 12
ENGINE_HASH_MB = 2048
ENGINE_MATE_PROBE_MS = 200  # 绝杀探测短时限

# 残局判断
ENDGAME_PIECE_COUNT = 24  # 少于此值视为残局
ENDGAME_MODE_PIECE_COUNT = 31  # 自动下一局残局模式棋子数上限

# 自动下一局
AUTO_NEXT_GAME = True  # 对局结束后自动开始下一局
AUTO_NEXT_TIMEOUT_S = 180  # 总超时（秒）
GAMEOVER_SCAN_INTERVAL_MS = 300  # 扫描间隔
BOARD_STABLE_THRESHOLD = 3  # 连续相同棋盘帧数
GAMEOVER_RETRY_MAX = 3  # 同一按钮/遮罩操作上限
GAMEOVER_TEXT_THRESHOLD = 0.75  # 模板匹配阈值
GAMEOVER_TEMPLATE_W = 1080  # 模板基准宽度
GAMEOVER_BUTTON_WORDS = ("下一关", "晋级赛", "重新挑战", "再来一局")  # 按钮类（点击）
GAMEOVER_BACK_WORDS = ("段位提升", "铜钱", "领取")  # 遮罩类（发返回键）

# 和棋弹窗
DRAW_TEXT_DIR = TEMPLATES_DIR / "draw"
DRAW_TEXT_THRESHOLD = 0.75  # 模板匹配阈值
