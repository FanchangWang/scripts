"""common/classes.py —— 16 类棋子定义、棋局状态与格子标注映射。"""

from __future__ import annotations

import csv as _csv
from pathlib import Path

from yolo_chess.common.paths import SHARED_RAW

# ---------------- 16 类定义（索引即 class id）----------------
CLASSES: list[str] = [
    "b_k",
    "r_K",
    "b_a",
    "r_A",
    "b_b",
    "r_B",
    "b_n",
    "r_N",
    "b_r",
    "r_R",
    "b_c",
    "r_C",
    "b_p",
    "r_P",
    "empty",
    "lift",
]
CLASS_CN: dict[str, str] = {
    "b_k": "黑将",
    "r_K": "红帥",
    "b_a": "黑士",
    "r_A": "红仕",
    "b_b": "黑象",
    "r_B": "红相",
    "b_n": "黑馬",
    "r_N": "红傌",
    "b_r": "黑車",
    "r_R": "红俥",
    "b_c": "黑砲",
    "r_C": "红炮",
    "b_p": "黑卒",
    "r_P": "红兵",
    "empty": "空格",
    "lift": "提子",
}
CLASS_IDX: dict[str, int] = {k: i for i, k in enumerate(CLASSES)}

# ---------------- 棋局状态 ----------------
# 状态标识统一用小写英文（也是磁盘目录名），绝不用数字，避免混淆。
STATE_OPENING = "opening"
STATE_MATE = "mate"
STATE_LIFT = "lift"
STATE_ENDGAME = "endgame"
STATES: list[tuple[str, str]] = [
    (STATE_OPENING, "开局（32子满盘，未走棋）"),
    (STATE_MATE, "绝杀（仅将帥在初始位）"),
    (STATE_LIFT, "提子（将帥+红中兵初始位，兵位被提）"),
    (STATE_ENDGAME, "残局（任意中残局，用模板自动匹配标注）"),
]
VALID_STATES = {s[0] for s in STATES}
STATE_CN: dict[str, str] = dict(STATES)


def state_cn(name: str) -> str:
    """状态英文 key -> 中文名。"""
    return STATE_CN.get(name, name)


def state_dir(name: str) -> Path:
    """截图按状态存放的目录（shared/raw/<状态英文名>/）。"""
    return SHARED_RAW / name


def _start_squares() -> dict[tuple[int, int], str]:
    """标准开局：棋子 ID -> 网格位置列表。"""
    squares = {
        "b_r": [(0, 0), (0, 8)],
        "b_n": [(0, 1), (0, 7)],
        "b_b": [(0, 2), (0, 6)],
        "b_a": [(0, 3), (0, 5)],
        "b_k": [(0, 4)],
        "b_c": [(2, 1), (2, 7)],
        "b_p": [(3, 0), (3, 2), (3, 4), (3, 6), (3, 8)],
        "r_R": [(9, 0), (9, 8)],
        "r_N": [(9, 1), (9, 7)],
        "r_B": [(9, 2), (9, 6)],
        "r_A": [(9, 3), (9, 5)],
        "r_K": [(9, 4)],
        "r_C": [(7, 1), (7, 7)],
        "r_P": [(6, 0), (6, 2), (6, 4), (6, 6), (6, 8)],
    }
    m: dict[tuple[int, int], str] = {}
    for pid, cells in squares.items():
        for r, c in cells:
            m[(r, c)] = pid
    return m


def label_map_for_state(name: str) -> dict[tuple[int, int], str]:
    """返回该状态下每格 (r,c) 的棋子 class key；缺省为 empty。"""
    if name == STATE_OPENING:
        return _start_squares()
    if name == STATE_MATE:
        return {(0, 4): "b_k", (9, 4): "r_K"}
    if name == STATE_LIFT:
        return {(0, 4): "b_k", (9, 4): "r_K", (6, 4): "lift"}
    if name == STATE_ENDGAME:
        return {}
    raise ValueError(f"未知状态 {name}")


# ---------------- 状态3 提子点（labels.csv 驱动标注）----------------
LIFT_POINTS: list[tuple[int, int, str]] = [
    (6, 4, "红中兵(默认)"),
    (9, 4, "红帥"),
    (0, 4, "黑将"),
    (8, 4, "红仕"),
    (7, 4, "红相"),
    (1, 4, "黑士"),
    (2, 4, "黑象"),
]


def lift_label_csv(state_root: Path) -> Path:
    return state_root / "labels.csv"


def load_lift_labels(state_root: Path) -> dict[str, tuple[int, int]]:
    """读取 raw/<状态>/labels.csv（stem,lift_row,lift_col）。"""
    csv_path = lift_label_csv(state_root)
    out: dict[str, tuple[int, int]] = {}
    if not csv_path.exists():
        return out
    with csv_path.open(newline="", encoding="utf-8") as f:
        for row in _csv.DictReader(f):
            stem = (row.get("stem") or "").strip()
            if not stem:
                continue
            try:
                lr = int(str(row["lift_row"]).strip())
                lc = int(str(row["lift_col"]).strip())
            except (KeyError, ValueError, TypeError):
                continue
            out.setdefault(stem, (lr, lc))
    return out


def append_lift_label(state_root: Path, stem: str, lr: int, lc: int) -> None:
    """向 raw/<状态>/labels.csv 写入一条提子标签。"""
    csv_path = lift_label_csv(state_root)
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    existing = load_lift_labels(state_root)
    if stem in existing:
        return
    rows = [[s, r, c] for s, (r, c) in existing.items()]
    rows.append([stem, lr, lc])
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        w = _csv.writer(f)
        w.writerow(["stem", "lift_row", "lift_col"])
        w.writerows(rows)


def label_map_for_lift(lr: int, lc: int) -> dict[tuple[int, int], str]:
    """据提子点 (lr,lc) 生成状态3标注映射。"""
    m: dict[tuple[int, int], str] = {(lr, lc): "lift"}
    if (lr, lc) != (0, 4):
        m[(0, 4)] = "b_k"
    if (lr, lc) != (9, 4):
        m[(9, 4)] = "r_K"
    return m


def label_map_for_lift_image(state_root: Path, stem: str) -> dict[tuple[int, int], str]:
    """状态3 单张截图：从 labels.csv 取提子点，无记录默认红中兵(6,4)。"""
    labels = load_lift_labels(state_root)
    lr, lc = labels.get(stem, (6, 4))
    return label_map_for_lift(lr, lc)
