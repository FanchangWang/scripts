"""common/ui.py —— 交互式参数定义与 questionary 菜单。"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


# ---------------- 交互式参数 ----------------
@dataclass
class Param:
    """步骤参数定义。"""

    name: str
    type: str  # "int" | "float" | "str" | "bool" | "choice" | "multiselect"
    default: Any = None
    choices: list[str] = field(default_factory=list)
    choice_cn: dict[str, str] = field(default_factory=dict)
    cn: str = ""
    desc: str = ""


def _format_param_line(idx: int, p: Param, cur: Any) -> str:
    if p.type == "multiselect":
        selected = cur or []
        names = [p.choice_cn.get(c, c) for c in selected]
        text = "、".join(names) if names else "（未选）"
        return f"{idx}. {p.cn} = {text}  {p.desc}"
    if p.type == "choice" and p.choices:
        options = " / ".join(p.choices)
        return f"{idx}. {p.cn} = {cur}  {p.desc}  [{options}]"
    if p.type == "bool":
        mark = "是" if cur else "否"
        return f"{idx}. {p.cn} = {mark}  {p.desc}"
    return f"{idx}. {p.cn} = {cur}  {p.desc}"


def interactive_args(params: list[Param]) -> Any:
    """纯 questionary 选择菜单：列出参数 + 确认运行 / 返回主菜单。"""
    import questionary

    if not params:
        return _ns({})

    values: dict[str, Any] = {p.name: p.default for p in params}

    while True:
        items: list[questionary.Choice] = []
        for i, p in enumerate(params, 1):
            line = _format_param_line(i, p, values[p.name])
            items.append(questionary.Choice(title=line, value=("param", p.name)))

        confirm = questionary.Choice(title="✅ 确认运行", value="confirm")
        back = questionary.Choice(title="↩ 返回主菜单", value="back")
        items.extend([confirm, back])

        result = questionary.select(
            "参数设置（选择要修改的参数，或直接运行）：",
            choices=items,
            default=confirm,
        ).ask()

        if result is None or result == "back":
            return None

        if result == "confirm":
            return _ns(values)

        _param, name = result
        p = next(x for x in params if x.name == name)
        cur = values[name]

        if p.type == "multiselect" and p.choices:
            selected = set(cur or [])
            choices = [
                questionary.Choice(title=p.choice_cn.get(c, c), value=c, checked=(c in selected))
                for c in p.choices
            ]
            ans = questionary.checkbox(
                f"勾选 {p.cn}（方向键移动，空格勾选，回车确认）", choices=choices
            ).ask()
            if ans is not None:
                values[name] = ans

        elif p.type == "choice" and p.choices:
            ans = questionary.select(
                f"选择 {p.cn}：",
                choices=[questionary.Choice(title=f"{c}", value=c) for c in p.choices],
                default=str(cur) if cur in p.choices else None,
            ).ask()
            if ans is not None:
                values[name] = ans

        elif p.type == "bool":
            ans = questionary.confirm(f"{p.cn}", default=bool(cur)).ask()
            if ans is not None:
                values[name] = ans

        else:
            hint = f"  [{cur}]" if cur is not None else ""
            raw = questionary.text(f"{p.cn}{hint}").ask()
            if raw is not None and raw.strip() != "":
                converted = _convert(raw, p.type)
                if converted is not None:
                    values[name] = converted


def _convert(raw: str, typ: str) -> Any:
    try:
        if typ == "int":
            return int(raw)
        if typ == "float":
            return float(raw)
    except (ValueError, TypeError):
        return None
    return raw


def _ns(values: dict[str, Any]) -> Any:
    """把 dict 包成带属性访问的对象（兼容旧代码的 args.xxx）。"""

    class _NS:
        def __init__(self, d: dict[str, Any]) -> None:
            self.__dict__.update(d)

        def __getattr__(self, name: str) -> Any:
            try:
                return self.__dict__[name]
            except KeyError:
                return None

    return _NS(values)
