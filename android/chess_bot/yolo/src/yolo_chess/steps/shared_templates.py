"""shared_templates.py —— 从 raw/opening 开局截图切割 14 种棋子的平均模板。

用途：残局的棋子位置不固定，用模板做自动匹配标注。
"""

from __future__ import annotations

from yolo_chess.common import (
    CLASSES,
    SHARED_TEMPLATES,
    STATE_OPENING,
    build_all_template_sets,
    state_dir,
)


def main() -> int:
    """切割棋子模板主函数。"""
    print("=== 切割棋子模板 ===")
    opening_dir = state_dir(STATE_OPENING)
    imgs = sorted(opening_dir.glob("*.png")) if opening_dir.exists() else []
    if not imgs:
        print(f"[错误] raw/{STATE_OPENING} 开局截图缺失: {opening_dir}")
        print("        请先运行「采集截图」采集若干开局满盘截图。")
        return 1

    print(f"扫描到 {len(imgs)} 张开局截图，开始为每张切一套模板...")
    try:
        sets = build_all_template_sets(save=True)
    except Exception as e:
        print(f"[错误] 生成模板失败: {e}")
        return 1

    print(f"\n共生成 {len(sets)} 套模板，目录: {SHARED_TEMPLATES}")
    for name, tset in sets.items():
        missing = [c for c in CLASSES if c not in ("empty", "lift") and c not in tset]
        status = "OK" if not missing else f"缺 {missing}"
        print(f"  {name}: {len(tset)}/14 子  {status}")

    incomplete = [n for n, t in sets.items() if len(t) < 14]
    if incomplete:
        print(f"\n[提醒] {incomplete} 套未集齐 14 子，残局匹配可能漏标。")
        return 1

    print(f"\n完成：共 {len(sets)} 套 × 14 棋子模板。")
    return 0
