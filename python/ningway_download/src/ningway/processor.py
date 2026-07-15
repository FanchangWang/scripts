"""数据处理：解析 data.json，生成下载任务列表。"""

import re
from pathlib import Path

from ningway.config import OUT_DIR, VIDEO_DIR_MAP


def process_series_items(items: list, current_dir: Path, tasks: list) -> None:
    """递归处理系列条目，提取下载任务。"""
    for item in items:
        if isinstance(item, dict) and "no" in item and "title" in item:
            no = item["no"]
            title = item["title"].strip()
            tasks.append((item, current_dir, f"{no} {title}"))
        elif isinstance(item, dict):
            for key, value in item.items():
                if isinstance(value, list):
                    process_series_items(value, current_dir / key.strip(), tasks)


def process_series(data: dict, tasks: list) -> None:
    """处理 series 数据，生成系列下载任务。"""
    category_dir = OUT_DIR / "分类"
    series_list = data.get("series", [])
    for series_group in series_list:
        for key, value in series_group.items():
            if isinstance(value, list):
                process_series_items(value, category_dir / key.strip(), tasks)


def process_videos(data: dict, tasks: list) -> None:
    """处理 videos 数据，生成视频下载任务。"""
    videos = data.get("videos", [])

    for v in videos:
        no = v.get("no", "")
        title = v.get("title", "").strip()
        date = v.get("date")

        subdir_name = next((d for p, d in VIDEO_DIR_MAP if re.match(p, no)), None)
        if subdir_name:
            dest_dir = OUT_DIR / "列表" / subdir_name
        elif date:
            dest_dir = OUT_DIR / "日期" / date
        else:
            dest_dir = OUT_DIR / "列表" / "其他"

        tasks.append((v, dest_dir, f"{no} {title}"))
