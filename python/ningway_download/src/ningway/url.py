"""URL 生成与编码。"""

import urllib.parse
from pathlib import PurePosixPath

from ningway.config import QUALITY_LEVELS
from ningway.encode import nn_encode
from ningway.video import is_classic, is_music, is_series


def make_download_url(video: dict, quality_size: int, codec: str = "h264") -> str:
    """生成官方下载链接。"""
    quality = next((q for q in QUALITY_LEVELS if q["size"] == quality_size), QUALITY_LEVELS[1])
    date = video.get("date", "")
    no = video["no"]
    title = video["title"]
    fmt = "mp4" if codec == "h264" else "m4v"
    suffix = f" H26{4 if codec == 'h264' else 5}-{quality['name']}-{quality_size}P.mp4"
    return (
        f"https://download.ziguijia.cn/media/{date} №{no} {title}{suffix}"
        f"?format={fmt}&width={quality_size}&code={no}#"
    )


def build_candidate_urls(video: dict) -> list[str]:
    """根据视频编号生成候选下载 URL 列表（按优先级排序）。"""
    no = video["no"]
    candidates: list[str] = []

    if is_music(no):
        candidates.append(f"https://r2.196212.xyz/audio/{no}.mp4")
        return candidates

    encoded_no = nn_encode(no)
    no_int = int(no) if no.isdigit() else 0

    if is_series(no):
        candidates.append(f"https://list.ningway.com/v/{no}")
    elif is_classic(no):
        candidates.append(f"https://r2.ningway.com/hvc1/{no}.mp4")
    elif 22000 <= no_int < 50000:
        candidates.append(f"https://sa.ningway.com/v/{no}")
    else:
        candidates.append(f"https://b2.ningway.com/file/ningway/hvc1/{encoded_no}.mp4")

    if is_series(no):
        candidates.append(f"https://list.ningway.com/v/{no}-ld")
    else:
        candidates.append(f"https://list.ningway.com/videos/360p/{no}.mp4")

    return candidates


def get_ext_from_url(url: str, no: str = "") -> str:
    """从 URL 推断文件扩展名。"""
    if is_music(no):
        return ".m4a"
    path = urllib.parse.urlsplit(url).path
    ext = PurePosixPath(path).suffix.lower()
    if ext == ".m4v":
        return ".mp4"
    if ext:
        return ext
    return ".mp4"


def encode_url(url: str) -> str:
    """对 URL 进行编码（保留路径分隔符和查询参数分隔符）。"""
    parsed = urllib.parse.urlsplit(url)
    path = urllib.parse.quote(parsed.path, safe="/")
    query = urllib.parse.quote(parsed.query, safe="=&#")
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, path, query, parsed.fragment))
