"""视频分类与域名路由。"""

import re


def is_music(no: str) -> bool:
    """判断是否为音频（M 开头）。"""
    return bool(re.match(r"^[M]\d{4}$", no))


def is_series(no: str) -> bool:
    """判断是否为系列（S 开头）。"""
    return bool(re.match(r"^[S]\d{4}$", no))


def is_classic(no: str) -> bool:
    """判断是否为经典视频（A/B/C/E/F/G/W 开头，或 KC/4066 开头）。"""
    return bool(re.match(r"^(?:[ABCEFGW]\d{4}|KC\d{3}|4066\d)$", no))


def get_domain_for_video(video: dict) -> str:
    """根据视频编号确定下载域名。"""
    no = video["no"]
    if is_music(no):
        return "r2.196212.xyz"
    if is_series(no):
        return "list.ningway.com"
    if is_classic(no):
        return "r2.ningway.com"
    no_int = int(no) if no.isdigit() else 0
    if 22000 <= no_int < 50000:
        return "sa.ningway.com"
    return "b2.ningway.com"
