"""common/adb.py —— adb 设备发现与截图。"""

from __future__ import annotations

import subprocess
from pathlib import Path

_ADB_SERIAL: str | None = None  # 本次运行记住的截图设备（仅存内存，跨运行不保留）


def saved_adb_serial() -> str | None:
    """返回本次运行记住的设备 serial（无则 None）。"""
    return _ADB_SERIAL


def save_adb_serial(serial: str) -> None:
    """把选定的截图设备记到本次运行的进程内存（下次运行脚本不保留）。"""
    global _ADB_SERIAL
    _ADB_SERIAL = serial


def _parse_adb_devices(text: str) -> list[tuple[str, str]]:
    """解析 `adb devices -l` 输出，返回在线设备 (serial, label) 列表。

    label 为 serial + 附加信息（model/product 等），无附加信息时即 serial。
    """
    out: list[tuple[str, str]] = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 2 or parts[1] != "device":
            continue
        serial = parts[0]
        extra = " ".join(parts[2:]) if len(parts) > 2 else ""
        label = f"{serial}  {extra}".strip() if extra else serial
        out.append((serial, label))
    return out


def list_adb_devices() -> list[tuple[str, str]]:
    """运行 `adb devices -l`，返回在线设备 (serial, label) 列表。"""
    try:
        proc = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True, timeout=10)
    except FileNotFoundError:
        print("[错误] 未找到 adb，请确认 adb 在 PATH 中")
        return []
    except Exception as e:
        print(f"[错误] 查询 adb 设备失败: {e}")
        return []
    if proc.returncode != 0:
        print(f"[adb错误] {proc.stderr.strip()}")
        return []
    return _parse_adb_devices(proc.stdout)


def resolve_adb_serial() -> str | None:
    """解析要使用的截图设备 serial。

    - 无在线设备 -> 打印错误并返回 None
    - 仅一个在线设备 -> 默认使用并记住（本次运行）
    - 多个在线设备 -> 列出让用户选择（上次的标为默认）并记住（本次运行）
    记住的结果仅存于进程内存，本次运行内后续调用沿用，下次运行脚本重新选择。
    """
    devices = list_adb_devices()
    if not devices:
        print("[错误] 未检测到在线 adb 设备（adb devices 无结果）")
        return None

    if len(devices) == 1:
        serial, label = devices[0]
        print(f"检测到单个设备，默认使用: {label}")
        save_adb_serial(serial)
        return serial

    import questionary

    saved = saved_adb_serial()
    choices = [
        questionary.Choice(title=f"{label}  <== 上次" if s == saved else label, value=s)
        for s, label in devices
    ]
    result = questionary.select("检测到多个设备，请选择截图设备：", choices=choices).ask()
    if result is None:
        print("[已取消] 未选择设备")
        return None
    save_adb_serial(result)
    print(f"已记住设备: {result}")
    return result


def adb_screenshot(out_path: Path, serial: str | None = None) -> bool:
    """用 adb 抓取设备截图保存到 out_path。

    serial 不传时用 resolve_adb_serial() 解析（单设备默认 / 多设备交互选择并记住）。

    注意：多设备下每隔一段时间会自动重新解析，若担心每次截图都弹选择框，
    建议在循环外先 resolve_adb_serial() 一次，把 serial 显式传入。
    """
    if serial is None:
        serial = resolve_adb_serial()
        if serial is None:
            return False
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["adb", "-s", serial, "exec-out", "screencap", "-p"]
    try:
        with out_path.open("wb") as f:
            proc = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE, timeout=30)
        if proc.returncode != 0:
            msg = proc.stderr.decode(errors="ignore").strip()
            print(f"[adb错误] {msg or 'returncode=' + str(proc.returncode)}")
            if out_path.exists():
                out_path.unlink(missing_ok=True)
            return False
        if out_path.stat().st_size == 0:
            print("[adb错误] 截图为空，请检查设备连接 / 分辨率")
            out_path.unlink(missing_ok=True)
            return False
        return True
    except FileNotFoundError:
        print("[错误] 未找到 adb，请确认 adb 在 PATH 中")
        return False
    except Exception as e:
        print(f"[错误] 截图失败: {e}")
        if out_path.exists():
            out_path.unlink(missing_ok=True)
        return False
