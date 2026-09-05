"""cli.py —— 统一主入口 + questionary 主菜单。

运行方式：
  uv run yolo-chess
  uv run python -m yolo_chess
"""

from __future__ import annotations

from collections.abc import Callable

import questionary

from yolo_chess.steps import (
    cls_cells,
    cls_dataset,
    cls_dedup,
    cls_train,
    cls_validate,
    det_dataset,
    det_train,
    det_validate,
    pose_dataset,
    pose_train,
    pose_validate,
    shared_collect,
    shared_templates,
    templates_validate,
)

STEPS: list[tuple[str, str, Callable[..., int | None]]] = [
    ("采集截图 (collect)", "shared_collect", shared_collect.main),
    ("切割棋子模板 (templates)", "shared_templates", shared_templates.main),
    ("模板匹配验证 (templates_validate)", "templates_validate", templates_validate.main),
    ("cls切割逐格小图 (cls_cells)", "cls_cells", cls_cells.main),
    ("cls去重逐格小图 (cls_dedup)", "cls_dedup", cls_dedup.main),
    ("cls构建分类数据集 (cls_dataset)", "cls_dataset", cls_dataset.main),
    ("cls训练分类模型 (cls_train)", "cls_train", cls_train.main),
    ("cls分类模型验证 (cls_validate)", "cls_validate", cls_validate.main),
    ("det构建四角数据集 (det_dataset)", "det_dataset", det_dataset.main),
    ("det训练四角检测模型 (det_train)", "det_train", det_train.main),
    ("det四角精度验证 (det_validate)", "det_validate", det_validate.main),
    ("pose构建四角数据集 (pose_dataset)", "pose_dataset", pose_dataset.main),
    ("pose训练四角定位模型 (pose_train)", "pose_train", pose_train.main),
    ("pose四角精度验证 (pose_validate)", "pose_validate", pose_validate.main),
]


def _build_choices() -> list[questionary.Choice]:
    choices = []
    for label, value, _fn in STEPS:
        choices.append(questionary.Choice(title=label, value=value))
    choices.append(questionary.Choice(title="退出", value="exit"))
    return choices


def _find_main(step_id: str) -> Callable[..., int | None] | None:
    for _label, value, fn in STEPS:
        if value == step_id:
            return fn
    return None


def main() -> None:
    choices = _build_choices()
    print("╔══════════════════════════════════╗")
    print("║  YOLO 棋子/四角训练工具          ║")
    print("╚══════════════════════════════════╝")

    while True:
        try:
            result = questionary.select(
                "请选择要执行的步骤：",
                choices=choices,
            ).ask()
        except (KeyboardInterrupt, EOFError):
            print("\n已退出。")
            break

        if result is None or result == "exit":
            print("已退出。")
            break

        fn = _find_main(result)
        if fn is None:
            print(f"[错误] 未知步骤: {result}")
            continue

        try:
            ret = fn()
            if ret is not None and ret != 0:
                print(f"[步骤返回错误码 {ret}]")
        except KeyboardInterrupt:
            print("\n[已中断当前步骤]")
        except Exception as e:
            print(f"[步骤执行异常] {e}")


if __name__ == "__main__":
    main()
