"""ADB 封装：设备选择、截图、点击、分辨率校验。"""

import re

import cv2
import numpy as np
from ppadb.client import Client as AdbClient
from ppadb.device import Device

from xiangqi_bot import config
from xiangqi_bot.console import ask


def select_device() -> Device | None:
    """选择 ADB 设备，失败/无设备返回 None"""
    client = AdbClient(host="127.0.0.1", port=5037)
    error: str | None = None
    try:
        devices = client.devices()
    except (RuntimeError, OSError) as exc:
        error = f"无法连接 ADB 服务（{exc}），请确认 adb server 已启动"
    if error is not None:
        print(error)
        return None
    if not devices:
        print("未检测到 ADB 设备")
        return None
    if len(devices) == 1:
        print(f"使用设备：{devices[0].serial}")
        return devices[0]
    print("检测到多台设备：")
    for i, dev in enumerate(devices, 1):
        print(f"  {i}. {dev.serial}")
    while True:
        raw = ask("请选择设备编号：").strip()
        if raw.isdigit() and 1 <= int(raw) <= len(devices):
            return devices[int(raw) - 1]
        print("输入无效，请重新输入")


def check_resolution(device: Device) -> bool:
    """校验设备分辨率是否为 1080x2400"""
    error: str | None = None
    try:
        out = device.shell("wm size")
    except (RuntimeError, OSError) as exc:
        error = f"获取分辨率失败：{exc}"
    if error is not None:
        print(error)
        return False
    match = re.search(r"Physical size:\s*(\d+)x(\d+)", out)
    if match and (int(match.group(1)), int(match.group(2))) == config.TARGET_RESOLUTION:
        return True
    print(
        f"分辨率不符：需要 {config.TARGET_RESOLUTION[0]}x{config.TARGET_RESOLUTION[1]}，实际 {out.strip()}"
    )
    return False


def screencap(device: Device) -> np.ndarray | None:
    """截图并解码为 BGR 图像，失败返回 None"""
    error: str | None = None
    try:
        data = device.screencap()
    except (RuntimeError, OSError) as exc:
        error = f"截图失败：{exc}"
    if error is not None:
        print(error)
        return None
    if not data:
        return None
    return cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)


def tap(device: Device, x: int, y: int) -> bool:
    """模拟点击，失败返回 False"""
    error: str | None = None
    try:
        device.input_tap(x, y)
    except (RuntimeError, OSError) as exc:
        error = f"模拟点击失败：{exc}"
    if error is not None:
        print(error)
        return False
    return True
