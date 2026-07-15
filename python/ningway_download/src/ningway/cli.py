"""CLI 入口点：update-data 和 download。"""

import json
import sys
import threading
from collections import defaultdict
from pathlib import Path

from ningway.config import DATA_JSON, MAX_WORKERS, OUT_DIR
from ningway.display import display_loop
from ningway.downloader import (
    cleanup_tmp,
    counter,
    counter_lock,
    download_worker,
)
from ningway.logger import init_fail_log
from ningway.processor import process_series, process_videos
from ningway.video import get_domain_for_video


def cmd_update_data() -> None:
    """更新 data.json 的 CLI 入口。"""
    from ningway.updater import update_data

    update_data()


def cmd_download() -> None:
    """视频下载的 CLI 入口。"""
    if not DATA_JSON.exists():
        print(f"Error: {DATA_JSON} not found")
        sys.exit(1)

    cleanup_tmp(OUT_DIR)
    init_fail_log()

    data = json.loads(DATA_JSON.read_text(encoding="utf-8"))

    tasks: list[tuple[dict, Path, str]] = []
    process_series(data, tasks)
    process_videos(data, tasks)

    with counter_lock:
        counter["total"] = len(tasks)

    domain_groups: dict[str, list] = defaultdict(list)
    for task in tasks:
        video, _dest_dir, _filename = task
        domain = get_domain_for_video(video)
        domain_groups[domain].append(task)

    print(f"共 {len(tasks)} 个任务, {len(domain_groups)} 个域名")
    for domain, group in domain_groups.items():
        print(f"  {domain}: {len(group)}")
    answer = input("是否开始下载？(y/N) ").strip().lower()
    if answer not in ("y", "yes"):
        print("已取消。")
        return

    stop_event = threading.Event()
    display_thread = threading.Thread(
        target=display_loop,
        args=(
            stop_event,
            __import__("ningway.downloader").downloader.active_downloads,
            __import__("ningway.downloader").downloader.active_lock,
            counter,
            counter_lock,
        ),
        daemon=True,
    )
    display_thread.start()

    try:
        max_workers = min(len(domain_groups), MAX_WORKERS)
        executor = __import__(
            "concurrent.futures", fromlist=["ThreadPoolExecutor"]
        ).ThreadPoolExecutor(max_workers=max_workers)
        futures = []
        for domain, group in domain_groups.items():
            futures.append(executor.submit(download_worker, domain, group, stop_event))
        try:
            for f in __import__("concurrent.futures", fromlist=["as_completed"]).as_completed(
                futures
            ):
                f.result()
        except (KeyboardInterrupt, SystemExit):
            stop_event.set()
        executor.shutdown(wait=False)
    except (KeyboardInterrupt, SystemExit):
        stop_event.set()

    stop_event.set()
    import time

    time.sleep(1.5)

    cleanup_tmp(OUT_DIR)

    print(f"\nDone. {counter['done']}/{counter['total']} processed.")
