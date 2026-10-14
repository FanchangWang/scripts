"""CLI 入口：检查 winget 更新 → 过滤跳过项 → GUI 选择 → 执行更新。"""

import argparse
import sys
from pathlib import Path

from winget_updater import config as config_mod
from winget_updater.gui import run_gui
from winget_updater.winget import PROXY_URL, WingetError, check_upgrades


def build_parser() -> argparse.ArgumentParser:
    """构建命令行参数解析器。"""
    parser = argparse.ArgumentParser(
        prog="winget-updater",
        description="检查 winget 可用更新，GUI 选择后逐条更新",
    )
    parser.add_argument(
        "--show-skipped",
        "-s",
        action="store_true",
        help="强制显示配置中记录为「跳过本版本 / 永久跳过」的条目",
    )
    parser.add_argument(
        "--no-proxy",
        action="store_true",
        help="检查更新时不使用代理（默认使用代理）",
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=None,
        help="配置文件路径（默认：项目根目录 config.json）",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    """主流程：检查 → 过滤 → GUI。返回进程退出码。"""
    args = build_parser().parse_args(argv)
    config_path = args.config if args.config else config_mod.default_config_path()

    if args.no_proxy:
        print("正在检查 winget 更新（不使用代理）...")
    else:
        print(f"正在检查 winget 更新（代理 {PROXY_URL}）...")

    try:
        updates = check_upgrades(use_proxy=not args.no_proxy)
    except WingetError as exc:
        print(f"检查失败：{exc}", file=sys.stderr)
        return 1

    if not updates:
        print("所有软件均已是最新版本，无需更新。")
        return 0

    records = config_mod.load_records(config_path)
    visible = config_mod.visible_updates(updates, records, args.show_skipped)
    hidden = len(updates) - len(visible)
    if hidden:
        print(f"{hidden} 个更新已被跳过（--show-skipped 可强制显示）。")

    if not visible:
        print("没有需要提示的更新。")
        return 0

    for pkg in visible:
        pkg.action = config_mod.default_action(pkg, records)

    return run_gui(visible, config_path, records)


if __name__ == "__main__":
    sys.exit(main())
