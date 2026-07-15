"""日志模块：控制台日志、失败日志记录。"""

import threading
import time

from ningway.config import FAILED_DOWNLOAD_LOG, MAX_LOG_LINES

# === 控制台日志 ===
_log_lines: list[str] = []
_log_lock = threading.Lock()


def add_log(msg: str) -> None:
    """添加一条带时间戳的控制台日志。"""
    ts = time.strftime("%H:%M:%S")
    with _log_lock:
        _log_lines.append(f"[{ts}] {msg}")
        if len(_log_lines) > MAX_LOG_LINES:
            _log_lines.pop(0)


def get_log_lines() -> list[str]:
    """获取最近的日志行（线程安全）。"""
    with _log_lock:
        return list(_log_lines[-20:])


# === 失败日志 ===
_fail_log_lock = threading.Lock()


def init_fail_log() -> None:
    """初始化失败日志文件（清空）。"""
    FAILED_DOWNLOAD_LOG.parent.mkdir(parents=True, exist_ok=True)
    FAILED_DOWNLOAD_LOG.write_text("", encoding="utf-8")


def write_fail_log(no: str, title: str, tried_urls: list[str], dest_path: str) -> None:
    """记录下载失败信息。"""
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    with _fail_log_lock, FAILED_DOWNLOAD_LOG.open("a", encoding="utf-8") as f:
        f.write(f"=== {no} {title} ===\n")
        f.write(f"  目标路径: {dest_path}\n")
        for i, url in enumerate(tried_urls, 1):
            f.write(f"  URL[{i}]: {url}\n")
        f.write(f"  时间: {ts}\n\n")
