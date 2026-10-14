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
    """更新成功后清除记录（版本已变，旧记录自然失效）。"""
    records.pop(package_id, None)


def default_action(pkg: PackageUpdate, records: dict[str, dict]) -> Action:
    """该包本次的默认动作：有更新类记录用记录，否则「更新(使用代理)」。"""
    record = records.get(pkg.package_id)
    if record is not None:
        action = record.get("action")
        if action in {a.value for a in _UPDATE_ACTIONS}:
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
    visible: list[PackageUpdate] = []
    for pkg in updates:
        record = records.get(pkg.package_id)
        if record is None:
            visible.append(pkg)
            continue
        action = record.get("action")
        if action == Action.PERMANENT_SKIP.value:
            continue
        if action == Action.SKIP_VERSION.value and record.get("version") == pkg.available:
            continue
        visible.append(pkg)
    return visible
