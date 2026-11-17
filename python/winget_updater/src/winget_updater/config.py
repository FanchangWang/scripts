"""config.json 读写与跳过过滤逻辑。

记录规则（对应 GUI 五个选项）：
- update_with_proxy / update_no_proxy：落盘，作为该包下次的默认动作；
- skip_this_time：不记录，同时清除该包旧记录（下次运行仍提示）；
- skip_version：落盘并记录跳过的可用版本，版本变化后自动重新提示；
- permanent_skip：落盘，除非 --show-skipped 否则不再提示。
"""

import json
from datetime import UTC, datetime
from pathlib import Path

from winget_updater.models import Action, PackageUpdate

CONFIG_VERSION = 1
_CONFIG_NAME = "config.json"

_UPDATE_ACTIONS = frozenset({Action.UPDATE_WITH_PROXY, Action.UPDATE_NO_PROXY})


def default_config_path() -> Path:
    """默认配置路径：项目根目录下的 config.json。"""
    return Path(__file__).resolve().parent.parent.parent / _CONFIG_NAME


def load_records(path: Path) -> dict[str, dict]:
    """读取配置；文件不存在或损坏时返回空记录。"""
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    packages = data.get("packages")
    return packages if isinstance(packages, dict) else {}


def save_records(path: Path, records: dict[str, dict]) -> None:
    """写回配置（原子性要求低，个人工具直接覆盖）。"""
    payload = {"version": CONFIG_VERSION, "packages": records}
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def record_action(records: dict[str, dict], pkg: PackageUpdate) -> None:
    """GUI 确认后写入单条记录。"""
    if pkg.action is Action.SKIP_THIS_TIME:
        records.pop(pkg.package_id, None)
        return
    record: dict[str, str] = {"action": pkg.action.value}
    if pkg.action is Action.SKIP_VERSION:
        record["version"] = pkg.available
    record["updated_at"] = datetime.now(UTC).isoformat(timespec="seconds")
    records[pkg.package_id] = record


def forget_package(records: dict[str, dict], package_id: str) -> None:
    """清除单条记录（保留给外部/测试用；GUI 更新成功后已不再自动清除）。

    更新选项是对「这个包怎么更新」的偏好，成功更新后仍需保留，否则下次
    启动会回到「更新(使用代理)」；旧版本记录不再适用的判断交给
    visible_updates / default_action。
    """
    records.pop(package_id, None)


def is_skipped(pkg: PackageUpdate, records: dict[str, dict]) -> bool:
    """该条目是否被记录标为「跳过」（永久跳过，或跳过本版本且版本未变）。

    与 visible_updates 的隐藏判定、default_action 的默认动作同源。
    「跳过本版本」记录的是当时那个可用版本，出现新版本后不再算跳过。
    """
    record = records.get(pkg.package_id)
    if record is None:
        return False
    action = record.get("action")
    if action == Action.PERMANENT_SKIP.value:
        return True
    return action == Action.SKIP_VERSION.value and record.get("version") == pkg.available


def default_action(pkg: PackageUpdate, records: dict[str, dict]) -> Action:
    """该包本次的默认动作：沿用记录里用户上次的选择。

    - 更新类记录（使用代理 / 不用代理）：直接沿用；
    - 跳过类记录（permanent_skip / 版本未变的 skip_version）：沿用跳过动作
      ——用 --show-skipped 或窗口开关把跳过项显示出来时，行必须仍是跳过
      状态，否则点「开始更新」会把用户明确不想装的包装上去；
    - skip_version 但出现了新版本：回到「更新(使用代理)」重新提示
      （与 visible_updates 的重新提示一致）。
    """
    record = records.get(pkg.package_id)
    if record is None:
        return Action.UPDATE_WITH_PROXY
    action = record.get("action")
    if action in {a.value for a in _UPDATE_ACTIONS}:
        return Action(action)
    if is_skipped(pkg, records):
        return Action(action)
    return Action.UPDATE_WITH_PROXY


def visible_updates(
    updates: list[PackageUpdate],
    records: dict[str, dict],
    show_skipped: bool,
) -> list[PackageUpdate]:
    """按配置过滤出本次需要提示的条目。"""
    if show_skipped:
        return list(updates)
    return [pkg for pkg in updates if not is_skipped(pkg, records)]
