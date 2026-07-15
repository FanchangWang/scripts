"""终端 UI：进度条、实时下载状态显示。"""

import sys
import threading
import time

from ningway.config import DISPLAY_REFRESH_INTERVAL
from ningway.logger import get_log_lines


def fmt_size(b: int) -> str:
    """格式化文件大小。"""
    if b < 1024:
        return f"{b}B"
    if b < 1024 * 1024:
        return f"{b / 1024:.1f}KB"
    return f"{b / 1024 / 1024:.1f}MB"


def progress_bar(done: int, total: int, width: int = 20) -> str:
    """生成进度条字符串。"""
    if total <= 0:
        return ""
    ratio = min(done / total, 1.0)
    filled = int(width * ratio)
    bar = "=" * filled + ">" + " " * (width - filled - 1)
    return f"[{bar}] {ratio:.0%} {fmt_size(done)}/{fmt_size(total)}"


def _enable_ansi() -> None:
    """在 Windows 上启用 ANSI 转义序列。"""
    if sys.platform == "win32":
        try:
            import ctypes

            kernel32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
            handle = kernel32.GetStdHandle(-11)
            mode = ctypes.c_ulong()
            kernel32.GetConsoleMode(handle, ctypes.byref(mode))
            kernel32.SetConsoleMode(handle, mode.value | 0x0004)
        except Exception:
            pass


def _display_once(
    active_downloads: dict,
    active_lock: threading.Lock,
    counter: dict,
    counter_lock: threading.Lock,
) -> None:
    """刷新一次终端显示。"""
    with active_lock:
        active = dict(active_downloads)

    with counter_lock:
        done = counter["done"]
        total = counter["total"]

    lines: list[str] = []
    lines.append("\033[H\033[2J")
    lines.append("\033[?25l")
    lines.append(f"=== 下载进度 [{len(active)} 并发] [{done}/{total} 完成] ===\n")

    if active:
        for info in active.values():
            done_b = info["done"]
            total_b = info["total"]
            elapsed = time.time() - info["start_time"]
            speed = done_b / elapsed if elapsed > 0 and done_b > 0 else 0
            lines.append(
                f"[{info['task_num']}/{total}] {info['domain']} | {info['no']} {info['title']}"
            )
            lines.append(f"    {info['url'][:72]}")
            if total_b > 0:
                bar = progress_bar(done_b, total_b)
                lines.append(f"    {bar} {fmt_size(int(speed))}/s")
            else:
                lines.append("    连接中...")
            lines.append("")
    else:
        lines.append("  (等待中...)\n")

    lines.append("--- 日志 ---")
    lines.extend(get_log_lines())

    sys.stdout.write("\n".join(lines))
    sys.stdout.write("\033[?25h")
    sys.stdout.flush()


def display_loop(
    stop_event: threading.Event,
    active_downloads: dict,
    active_lock: threading.Lock,
    counter: dict,
    counter_lock: threading.Lock,
) -> None:
    """持续刷新终端显示的循环（在后台线程运行）。"""
    _enable_ansi()
    while not stop_event.is_set():
        try:
            _display_once(active_downloads, active_lock, counter, counter_lock)
        except Exception:
            pass
        stop_event.wait(DISPLAY_REFRESH_INTERVAL)
    try:
        _display_once(active_downloads, active_lock, counter, counter_lock)
    except Exception:
        pass
