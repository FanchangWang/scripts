from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent  # 项目根目录（xiangqi-bot/）

PIKAFISH_DIR = PROJECT_ROOT / "pikafish"  # pikafish 引擎所在目录（引擎须在其中启动才能加载 nnue）
PIKAFISH_EXE = PIKAFISH_DIR / "pikafish-bmi2.exe"  # 引擎可执行文件

TEMPLATES_DIR = PROJECT_ROOT / "templates"  # 14 张 60x60 棋子模板所在目录

TARGET_RESOLUTION = (1080, 2400)  # 目标手机分辨率（宽 x 高），不匹配则脚本结束

# 延时（毫秒）
TAP_HOLD_INTERVAL_MS = 300  # 点起子 -> 点落子之间的间隔
MOVE_SETTLE_MS = 500  # 落子后每次校验截图前的等待（3 次均 500ms）
MOVE_VERIFY_COUNT = 3  # 走棋校验截图次数（全部失败才判定走棋失败）

# 引擎（毫秒）
ENGINE_MOVETIME_MS = 1000  # go movetime <ms>（思考时间，兼顾速度与响应）
ENGINE_THREADS = 12  # setoption name Threads
ENGINE_HASH_MB = 2048  # setoption name Hash（MB）
ENGINE_MATE_PROBE_MS = 200  # 绝杀判断用的短时限探测（无着法会立即返回 (none)）

# 自动检测敌方走棋（毫秒）
AUTO_DETECT_INTERVAL_MS = 500  # 每 500ms 截图一次
AUTO_DETECT_MAX_COUNT = 30  # 最大检测次数（30 次 x 0.5 秒 = 15 秒）

# 图片识别（像素）
DIFF_WINDOW = 10  # 中心点 10x10 区域对比差异
DIFF_THRESHOLD = 8  # 10x10 区域平均绝对差超过此值视为"有变化"
MATCH_SEARCH_HALF = 10  # 模板匹配时在中心点 ±10px 窗口内滑动
EMPTY_MATCH_THRESHOLD = 0.8  # TM_CCOEFF_NORMED 低于此值判为空格
TEMPLATE_SIZE = 60  # 棋子模板边长（像素）
