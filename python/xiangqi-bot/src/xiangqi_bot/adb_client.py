"""ADB 封装：设备列表、无线连接、截图、点击。

列表/截图/点击走 ppadb（127.0.0.1:5037）；无线连接（`adb connect`）调用本机
platform-tools 的 adb.exe（ppadb 不支持配对/连接）。只支持已配对的设备。
"""

import re
import subprocess

import cv2
import numpy as np
from ppadb.client import Client as AdbClient
from ppadb.device import Device


class AdbError(RuntimeError):
    pass


KEYCODE_BACK = 4  # Android 返回键


def _client() -> AdbClient:
    return AdbClient(host="127.0.0.1", port=5037)


def list_devices() -> list[str]:
    """在线设备 serial 列表（USB 或已 connect 的无线设备）"""
    try:
        return [d.serial for d in _client().devices()]
    except (RuntimeError, OSError) as exc:
        raise AdbError(f"无法连接 ADB 服务（{exc}）") from exc


def connect(ip: str, port: int) -> str:
    """`adb connect ip:port`，成功返回 serial（形如 ip:port）"""
    result = subprocess.run(
        ["adb", "connect", f"{ip}:{port}"],
        capture_output=True,
        text=True,
        timeout=20,
    )
    out = (result.stdout or "").strip()
    if result.returncode != 0 or "connected" not in out.lower():
        raise AdbError(out or f"adb connect 失败（{result.returncode}）")
    return f"{ip}:{port}"


def disconnect(serial: str) -> None:
    subprocess.run(["adb", "disconnect", serial], capture_output=True, text=True, timeout=20)


def get_device(serial: str) -> Device:
    """按 serial 取在线设备"""
    try:
        for dev in _client().devices():
            if dev.serial == serial:
                return dev
    except (RuntimeError, OSError) as exc:
        raise AdbError(f"无法连接 ADB 服务（{exc}）") from exc
    raise AdbError(f"设备 {serial} 不在线")


def screencap(device: Device) -> np.ndarray | None:
    """截图并解码为 BGR 图像，失败返回 None"""
    try:
        data = device.screencap()
    except (RuntimeError, OSError) as exc:
        raise AdbError(f"截图失败：{exc}") from exc
    if not data:
        return None
    return cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)


def tap(device: Device, x: int, y: int) -> None:
    """模拟点击，失败抛 AdbError"""
    try:
        device.input_tap(x, y)
    except (RuntimeError, OSError) as exc:
        raise AdbError(f"模拟点击失败：{exc}") from exc


def keyevent(device: Device, keycode: int) -> None:
    """发送按键事件（如返回键 KEYCODE_BACK=4），失败抛 AdbError"""
    try:
        device.shell(f"input keyevent {keycode}")
    except (RuntimeError, OSError) as exc:
        raise AdbError(f"模拟按键失败：{exc}") from exc


def screen_size(device: Device) -> tuple[int, int] | None:
    """`wm size` 解析出的物理分辨率 (宽, 高)"""
    try:
        out = device.shell("wm size")
    except (RuntimeError, OSError) as exc:
        raise AdbError(f"获取分辨率失败：{exc}") from exc
    match = re.search(r"Physical size:\s*(\d+)x(\d+)", out)
    if match:
        return int(match.group(1)), int(match.group(2))
    return None
