"""数据更新：从 ningway.com API 拉取最新数据并与本地对比。"""

import json
import sys
import urllib.request

from ningway.config import API_HEADERS, API_URL, DATA_JSON, NINGWAY_APP_DATA_JSON


def update_data() -> None:
    """从 API 拉取数据，与 data.json 对比后决定是否覆盖。"""
    req = urllib.request.Request(API_URL)
    for k, v in API_HEADERS.items():
        req.add_header(k, v)

    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)

    DATA_JSON.parent.mkdir(parents=True, exist_ok=True)
    NINGWAY_APP_DATA_JSON.write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    series_count = len(data.get("series", []))
    videos_count = len(data.get("videos", []))
    print(f"OK. Saved to {NINGWAY_APP_DATA_JSON}")
    print(f"  series: {series_count} groups")
    print(f"  videos: {videos_count} items")

    if not DATA_JSON.exists():
        NINGWAY_APP_DATA_JSON.replace(DATA_JSON)
        print("\ndata.json not found, created from ningway-app-data.json")
        return

    old_data = json.loads(DATA_JSON.read_text(encoding="utf-8"))

    if data == old_data:
        NINGWAY_APP_DATA_JSON.unlink(missing_ok=True)
        print("\ningway-app-data.json == data.json, no update needed.")
        return

    print("\ningway-app-data.json is different from data.json.")
    ans = input("Overwrite data.json? [y/N] ").strip().lower()
    if ans == "y":
        NINGWAY_APP_DATA_JSON.replace(DATA_JSON)
        print(f"Overwritten data.json ({DATA_JSON.stat().st_size} bytes)")
    else:
        print("Skipped.")
