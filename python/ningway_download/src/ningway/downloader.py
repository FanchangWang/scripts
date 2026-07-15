"""下载逻辑：文件下载、重试、状态管理。"""

import os
import threading
import time
import urllib.request
from pathlib import Path

from ningway.config import CHUNK_SIZE, DOWNLOAD_TIMEOUT, IGNORE_NOS, USER_AGENT
from ningway.logger import add_log, write_fail_log
from ningway.url import build_candidate_urls, encode_url, get_ext_from_url
from ningway.video import get_domain_for_video

# === 全局下载状态 ===
active_downloads: dict[int, dict] = {}
active_lock = threading.Lock()
counter: dict[str, int] = {"attempted": 0, "done": 0, "total": 0}
counter_lock = threading.Lock()


def find_existing_file(directory: Path, no: str) -> Path | None:
    """在目录中查找已存在的文件（按编号前缀匹配），同时清理残留的 .tmp 文件。"""
    if not directory.is_dir():
        return None
    try:
        for f in directory.iterdir():
            if f.name.startswith(f"{no} ") or f.name.startswith(f"{no}."):
                if f.suffix == ".tmp":
                    f.unlink(missing_ok=True)
                    continue
                return f
    except OSError:
        pass
    return None


def cleanup_tmp(base_dir: Path) -> int:
    """清理目录下所有 .tmp 文件，返回清理数量。"""
    count = 0
    for root, _dirs, files in os.walk(base_dir):
        for f in files:
            if f.endswith(".tmp"):
                try:
                    Path(root, f).unlink()
                    count += 1
                except OSError:
                    pass
    if count:
        add_log(f"清理 {count} 个 .tmp 文件")
    return count


def _download_file(url: str, dest: Path, stop_event: threading.Event) -> bool:
    """下载单个文件，返回是否成功。"""
    tid = threading.get_ident()
    encoded = encode_url(url)
    try:
        req = urllib.request.Request(encoded)
        req.add_header("User-Agent", USER_AGENT)
        with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT) as resp:
            if resp.status == 200:
                total = int(resp.headers.get("Content-Length", 0))
                dest.parent.mkdir(parents=True, exist_ok=True)
                done = 0
                with dest.open("wb") as f:
                    while not stop_event.is_set():
                        chunk = resp.read(CHUNK_SIZE)
                        if not chunk:
                            break
                        f.write(chunk)
                        done += len(chunk)
                        with active_lock:
                            if tid in active_downloads:
                                active_downloads[tid]["done"] = done
                                active_downloads[tid]["total"] = total
                return not stop_event.is_set()
    except Exception:
        pass
    return False


def try_download(
    video: dict,
    dest_dir: Path,
    filename: str,
    stop_event: threading.Event,
    task_num: int = 0,
) -> bool:
    """尝试下载单个视频，自动重试候选 URL。"""
    no = video["no"]
    title = video["title"]

    if no in IGNORE_NOS:
        add_log(f"IGNORE: {no}")
        with counter_lock:
            counter["done"] += 1
        return True

    existing = find_existing_file(dest_dir, no)
    if existing:
        add_log(f"SKIP: {no}")
        with counter_lock:
            counter["done"] += 1
        return True

    candidates = build_candidate_urls(video)
    tid = threading.get_ident()
    domain = get_domain_for_video(video)
    tried_urls: list[str] = []

    for url in candidates:
        ext = get_ext_from_url(url, no)
        dest = dest_dir / f"{filename}{ext}"
        tmp_dest = dest.with_suffix(f"{ext}.tmp")
        tried_urls.append(url)

        with active_lock:
            active_downloads[tid] = {
                "no": no,
                "domain": domain,
                "title": title,
                "done": 0,
                "total": 0,
                "url": url,
                "start_time": time.time(),
                "task_num": task_num,
            }

        if stop_event.is_set():
            break

        if _download_file(url, tmp_dest, stop_event):
            try:
                tmp_dest.replace(dest)
            except OSError:
                pass
            add_log(f"OK: {filename}{ext}")
            with active_lock:
                active_downloads.pop(tid, None)
            with counter_lock:
                counter["done"] += 1
            return True

        if tmp_dest.exists():
            tmp_dest.unlink(missing_ok=True)
        time.sleep(0.5)

    with active_lock:
        active_downloads.pop(tid, None)
    add_log(f"FAIL: {no}")

    ext_final = get_ext_from_url(candidates[0], no) if candidates else ".mp4"
    final_path = dest_dir / f"{filename}{ext_final}"
    write_fail_log(no, title, tried_urls, str(final_path))

    with counter_lock:
        counter["done"] += 1
    return False


def download_worker(
    domain: str,
    domain_tasks: list[tuple[dict, Path, str]],
    stop_event: threading.Event,
) -> None:
    """单个域名的下载工作线程。"""
    for video, dest_dir, filename in domain_tasks:
        if stop_event.is_set():
            break
        with counter_lock:
            counter["attempted"] += 1
            num = counter["attempted"]
        try_download(video, dest_dir, filename, stop_event, task_num=num)
