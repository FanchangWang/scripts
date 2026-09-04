"""shared_collect.py —— 交互采集棋子识别训练截图。

流程：
  1) questionary 选择棋局状态（opening/mate/lift/endgame）
  2) 截图按状态保存到 shared/raw/<状态英文名>/
  3) 状态 lift 每次截图前 questionary 选择提子点
"""

from __future__ import annotations

import contextlib
from pathlib import Path

import questionary

from yolo_chess.common import (
    LIFT_POINTS,
    STATE_LIFT,
    STATE_OPENING,
    STATES,
    adb_screenshot,
    append_lift_label,
    resolve_adb_serial,
    state_dir,
)

LAST_STATE: str | None = None
LAST_LIFT: tuple[int, int] = (6, 4)


def count_saved(d: Path) -> int:
    if not d.exists():
        return 0
    return sum(
        1
        for p in d.iterdir()
        if p.is_file() and p.suffix.lower() in {".png", ".jpg", ".jpeg", ".bmp", ".webp"}
    )


def next_index(d: Path) -> int:
    if not d.exists():
        return 1
    nums = []
    for p in d.iterdir():
        if p.is_file():
            with contextlib.suppress(ValueError):
                nums.append(int(p.stem))
    return (max(nums) if nums else 0) + 1


def _ask_state() -> str | None:
    """questionary 选择棋局状态，返回状态英文 key 或 None（退出）。"""
    global LAST_STATE
    choices = []
    for key, desc in STATES:
        title = f"{desc}  <== 上次" if key == LAST_STATE else desc
        choices.append(questionary.Choice(title=title, value=key))
    choices.append(questionary.Choice(title="返回主菜单", value="exit"))

    result = questionary.select("选择棋局状态：", choices=choices, default=LAST_STATE).ask()
    if result is None or result == "exit":
        return None
    LAST_STATE = result
    return result


def _ask_lift_point() -> tuple[int, int] | None:
    """questionary 选择提子点（状态3），取消时返回 None。"""
    global LAST_LIFT
    choices = []
    for _i, (r, c, name) in enumerate(LIFT_POINTS, 1):
        title = f"{name} ({r},{c})" + ("  <== 上次" if (r, c) == LAST_LIFT else "")
        choices.append(questionary.Choice(title=title, value=(r, c)))
    choices.append(questionary.Choice(title="使用默认(红中兵 6,4)", value=(6, 4)))

    try:
        result = questionary.select("选择提子点：", choices=choices).ask()
    except KeyboardInterrupt:
        return None
    if result is None:
        return None
    LAST_LIFT = result
    return result


def main() -> int:
    """采集截图主函数。"""
    print("=== 棋子识别数据采集 ===")
    serial = resolve_adb_serial()
    if serial is None:
        return 1
    print(f"截图设备: adb -s {serial}")
    print(f"保存根目录: {state_dir(STATE_OPENING).parent}")
    print("提示：把手机摆到对应棋局状态后回车截图。\n")

    while True:
        state = _ask_state()
        if state is None:
            return 0

        lift = (6, 4)
        if state == STATE_LIFT:
            pick = _ask_lift_point()
            if pick is None:
                print("已取消提子点选择，本次未截图。\n")
                continue
            lift = pick

        d = state_dir(state)
        idx = next_index(d)
        out = d / f"{idx:04d}.png"
        print(f"截图中 -> {out} ...")
        if adb_screenshot(out, serial):
            if state == STATE_LIFT:
                append_lift_label(d, out.stem, lift[0], lift[1])
            print(f"  ✓ 已保存（{d.name}/ 共 {count_saved(d)} 张）\n")
        else:
            print("  ✗ 截图失败，未保存。\n")

    return 0
