# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""脚本1：交互采集棋子识别训练截图。

流程：
  1) 询问当前棋局状态（1 开局 / 2 绝杀 / 3 提子 / 4 被将 / 5 残局）。
  2) 输入数字回车 -> 选状态并截图；直接回车 -> 沿用上一轮状态；q 退出。
  3) 截图按状态保存到 cnn/raw/<状态号>/，文件名从 1 开始递增（0001.png ...）。
  4) 残局(5)用于采集任意中残局，后续由 step_2_cut_templates.py 生成的模板自动匹配标注。

运行：  uv run step_1_collect_screenshots.py
依赖：  仅标准库（无需安装 OpenCV）。截图通过 adb 获取。
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from yolo_common import (
    STATES, VALID_STATES, adb_screenshot, state_dir,
    LIFT_POINTS, append_lift_label,
)


def count_saved(d: Path) -> int:
    if not d.exists():
        return 0
    return sum(1 for p in d.iterdir() if p.is_file() and p.suffix.lower() in {".png", ".jpg", ".jpeg", ".bmp", ".webp"})


def next_index(d: Path) -> int:
    if not d.exists():
        return 1
    nums = []
    for p in d.iterdir():
        if p.is_file():
            try:
                nums.append(int(p.stem))
            except ValueError:
                pass
    return (max(nums) if nums else 0) + 1


def prompt_lift_point(default: tuple[int, int]) -> tuple[int, int]:
    """状态3 截图前询问提子点；回车用默认(红中兵 6,4)。"""
    print("提子点（状态3，共 7 个；全部位于第4列将帅竖线）：")
    for i, (r, c, name) in enumerate(LIFT_POINTS, 1):
        mark = " <== 上次" if default == (r, c) else ""
        print(f"  {i}. {name} ({r},{c}){mark}")
    print("输入 1-7 选择提子点 | 直接回车用默认(红中兵 6,4)")
    while True:
        s = input("提子点> ").strip()
        if s == "":
            return default
        try:
            i = int(s)
        except ValueError:
            print("请输入 1-7 或回车。")
            continue
        if 1 <= i <= len(LIFT_POINTS):
            return LIFT_POINTS[i - 1][0], LIFT_POINTS[i - 1][1]
        print("请输入 1-7。")


def main() -> int:
    print("=== 棋子识别数据采集（脚本1 / 3）===")
    print("截图设备: adb -s 将通过 yolo_common.ADB_SERIAL 配置")
    print(f"保存根目录: {state_dir(1).parent}")
    print("提示：把手机摆到对应棋局状态后回车截图；建议每类多采集几十张以增加泛化。\n")

    last: Optional[int] = None
    last_lift: tuple[int, int] = (6, 4)  # 状态3 提子点，默认红中兵
    while True:
        print("当前棋局状态：")
        for n, _key, desc in STATES:
            mark = "  <== 上次" if n == last else ""
            print(f"  {n}. {desc}{mark}")
        print("操作：输入 1-5 选择状态 | 直接回车沿用上次 | 输入 q 退出")

        try:
            inp = input("状态> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n已退出。")
            return 0

        if inp.lower() == "q":
            print("已退出。")
            return 0

        if inp == "":
            if last is None:
                print("请先输入一个数字选择状态。\n")
                continue
        else:
            try:
                n = int(inp)
            except ValueError:
                print("无效输入，请输入 1-5 或 q。\n")
                continue
            if n not in VALID_STATES:
                print("无效状态，请输入 1-5。\n")
                continue
            last = n

        # 状态3：每次截图前询问提子点（回车=上次/默认红中兵）
        lift = (6, 4)
        if last == 3:
            lift = prompt_lift_point(last_lift)
            last_lift = lift

        d = state_dir(last)
        idx = next_index(d)
        out = d / f"{idx:04d}.png"
        print(f"截图中 -> {out} ...")
        if adb_screenshot(out):
            if last == 3:
                append_lift_label(d, out.stem, lift[0], lift[1])
            print(f"  ✓ 已保存（{d.name}/ 共 {count_saved(d)} 张）\n")
        else:
            print("  ✗ 截图失败，未保存。\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
