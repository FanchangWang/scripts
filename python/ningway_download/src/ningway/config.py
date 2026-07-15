"""项目配置：路径、常量、视频分类映射。"""

from pathlib import Path

# === 项目根目录 ===
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent

# === 目录路径 ===
DATA_DIR = PROJECT_ROOT / "data"
LOG_DIR = PROJECT_ROOT / "log"
OUT_DIR = PROJECT_ROOT / "out"

# 测试用
# OUT_DIR = Path.home() / "Downloads" / "视频" / "out"

# === 文件路径 ===
DATA_JSON = DATA_DIR / "data.json"
NINGWAY_APP_DATA_JSON = DATA_DIR / "ningway-app-data.json"
FAILED_DOWNLOAD_LOG = LOG_DIR / "failed_downloads.log"

# === 已知缺失的视频编号（网站本身缺失，跳过下载） ===
IGNORE_NOS: set[str] = {
    "10627",
    "10628",
    "10629",
    "10631",
    "20433",
    "30033",
    "30872",
    "50831",
    "M0104",
}

# === API 配置 ===
API_URL = "https://m.ningway.com/api/ningway-app-data.json"
API_HEADERS = {
    "host": "m.ningway.com",
    "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36 Edg/150.0.0.0",
    "referer": "https://m.ningway.com/series",
    "accept-language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
}

# === 编码常量 ===
ALPHABET = "0123456789bcdefghijklmnopqrstuvwxyz"
BASE = 35

# === 画质等级 ===
QUALITY_LEVELS = [
    {"name": "流畅", "size": 360},
    {"name": "高清", "size": 720},
    {"name": "超清", "size": 1080},
]

# === 视频编号前缀 → 输出子目录映射 ===
VIDEO_DIR_MAP: list[tuple[str, str]] = [
    (r"^A", "法华经"),
    (r"^B", "修证佛法讲义"),
    (r"^C", "了解佛教"),
    (r"^E", "学习佛法"),
    (r"^F", "金刚经"),
    (r"^G", "佛说无量寿经"),
    (r"^M", "音频"),
    (r"^W", "微博集合"),
    (r"^K", "视频摘录"),
    (r"^4066", "二零零九年佛教史略讲"),
]

# === 下载配置 ===
MAX_WORKERS = 8
DOWNLOAD_TIMEOUT = 60
CHUNK_SIZE = 8192
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

# === 显示配置 ===
MAX_LOG_LINES = 50
DISPLAY_REFRESH_INTERVAL = 1.0
