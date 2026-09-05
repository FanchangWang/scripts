"""common/templates.py —— 棋子模板套切割、模板匹配与同类去重聚类。"""

from __future__ import annotations

import shutil
from typing import Any

import cv2
import numpy as np

from yolo_chess.common.board import (
    CELL_OUT,
    COLS,
    ROWS,
    correct_board,
    corrected_center,
    crop_cell,
)
from yolo_chess.common.classes import STATE_OPENING, label_map_for_state, state_dir
from yolo_chess.common.paths import SHARED_TEMPLATES

EMPTY_MATCH_THRESHOLD = 0.8
MATCH_SEARCH_HALF = 10


# ---------------- 棋子模板 ----------------
def _set_name(i: int) -> str:
    return f"set_{i:02d}"


def cut_template_set_from_image(corrected: np.ndarray, lmap: dict) -> dict[str, np.ndarray]:
    """从已矫正开局图按 lmap 切出 14 子模板。"""
    out: dict[str, np.ndarray] = {}
    for (r, c), pid in lmap.items():
        if pid in ("empty", "lift"):
            continue
        cell = crop_cell(corrected, r, c)
        if cell is not None:
            out[pid] = cell
    return out


def build_all_template_sets(save: bool = True) -> dict[str, dict[str, np.ndarray]]:
    """遍历 raw/opening 每张开局图，各自独立切出一套 14 子模板。"""

    opening_dir = state_dir(STATE_OPENING)
    imgs = sorted(opening_dir.glob("*.png")) if opening_dir.exists() else []
    if not imgs:
        raise RuntimeError(f"raw/{STATE_OPENING} 开局截图缺失: {opening_dir}")

    lmap = label_map_for_state(STATE_OPENING)
    if save and SHARED_TEMPLATES.exists():
        shutil.rmtree(SHARED_TEMPLATES)
    sets: dict[str, dict[str, np.ndarray]] = {}
    j = 0
    for p in imgs:
        raw = np.fromfile(str(p), dtype=np.uint8)
        img = cv2.imdecode(raw, cv2.IMREAD_COLOR)
        if img is None:
            print(f"[跳过] 解码失败: {p.name}")
            continue
        try:
            corrected = correct_board(img)
        except Exception as e:
            print(f"[跳过] 矫正失败 {p.name}: {e}")
            continue
        tset = cut_template_set_from_image(corrected, lmap)
        if len(tset) < 14:
            print(f"[警告] {p.name} 仅切出 {len(tset)}/14 子，跳过该套")
            continue
        name = _set_name(j)
        sets[name] = tset
        if save:
            d = SHARED_TEMPLATES / name
            d.mkdir(parents=True, exist_ok=True)
            for key, arr in tset.items():
                cv2.imencode(".png", arr)[1].tofile(str(d / f"{key}.png"))
            (d / "meta.json").write_text(
                __import__("json").dumps(
                    {"source": p.name, "pieces": len(tset)},
                    ensure_ascii=False,
                    indent=2,
                ),
                encoding="utf-8",
            )
        j += 1
    if not sets:
        raise RuntimeError("未从 raw/opening 切出任何模板套")
    return sets


def load_template_sets() -> dict[str, dict[str, np.ndarray]]:
    """读取 templates/<set_NN>/*.png。"""
    out: dict[str, dict[str, np.ndarray]] = {}
    if not SHARED_TEMPLATES.exists():
        return out
    for d in sorted(SHARED_TEMPLATES.iterdir()):
        if not d.is_dir() or not d.name.startswith("set_"):
            continue
        tset: dict[str, np.ndarray] = {}
        for fp in sorted(d.glob("*.png")):
            raw = np.fromfile(str(fp), dtype=np.uint8)
            img = cv2.imdecode(raw, cv2.IMREAD_COLOR)
            if img is not None:
                tset[fp.stem] = img
        if tset:
            out[d.name] = tset
    return out


def ensure_template_sets() -> dict[str, dict[str, np.ndarray]]:
    """优先加载磁盘多套模板；缺失时由 raw/opening 重新切套。"""
    if SHARED_TEMPLATES.exists():
        sets = load_template_sets()
        if sets:
            return sets
    if not state_dir(STATE_OPENING).exists() or not any(state_dir(STATE_OPENING).glob("*.png")):
        raise RuntimeError(
            "raw/opening 开局截图缺失。请先运行 collect 采集开局图，或运行 templates 切割模板。"
        )
    return build_all_template_sets(save=True)


# ---------------- 模板匹配工具 ----------------
def _match_window(
    window: np.ndarray,
    templates: dict[str, np.ndarray],
    threshold: float = EMPTY_MATCH_THRESHOLD,
) -> tuple[str, float]:
    """在搜索窗口内对 14 模板匹配，返回 (class, 置信度)。"""
    best_key, best_score = "empty", -1.0
    for key, tmpl in templates.items():
        res = cv2.matchTemplate(window, tmpl, cv2.TM_CCOEFF_NORMED)
        _, max_val, _, _ = cv2.minMaxLoc(res)
        if max_val > best_score:
            best_score, best_key = max_val, key
    if best_score < threshold:
        return "empty", float(best_score)
    return best_key, float(best_score)


def match_cell_to_class(
    cell: np.ndarray,
    templates: dict[str, np.ndarray],
    threshold: float = EMPTY_MATCH_THRESHOLD,
) -> tuple[str, float]:
    """用模板匹配把单格标为最接近的 class。"""
    return _match_window(cell, templates, threshold)


def match_cell_in_corrected(
    corrected: np.ndarray,
    r: int,
    c: int,
    templates: dict[str, np.ndarray],
    threshold: float = EMPTY_MATCH_THRESHOLD,
) -> tuple[str, float]:
    """在已矫正棋盘的 (r,c) 处做带滑动窗口的模板匹配。"""
    cx, cy = corrected_center(r, c)
    px, py = round(cx), round(cy)
    half = MATCH_SEARCH_HALF + CELL_OUT // 2
    x1 = int(max(0, px - half))
    y1 = int(max(0, py - half))
    window = corrected[y1 : py + half, x1 : px + half]
    if window.shape[0] < CELL_OUT or window.shape[1] < CELL_OUT:
        cell = crop_cell(corrected, r, c)
        if cell is None:
            return "empty", 0.0
        return _match_window(cell, templates, threshold)
    return _match_window(window, templates, threshold)


def match_board_with_best_set(
    corrected: np.ndarray,
    template_sets: dict[str, dict[str, np.ndarray]],
    threshold: float = EMPTY_MATCH_THRESHOLD,
) -> tuple[str | None, list[tuple[int, int, str, float]]]:
    """残局：自动选匹配率最高的模板套。"""
    best_name, best_conf, best_mean = None, -1, -1.0
    best_cells: list[tuple[int, int, str, float]] = []
    total_cells = ROWS * COLS
    for name, tset in template_sets.items():
        cells: list[tuple[int, int, str, float]] = []
        conf = 0
        total = 0.0
        for r in range(ROWS):
            for c in range(COLS):
                key, score = match_cell_in_corrected(corrected, r, c, tset, threshold)
                cells.append((r, c, key, score))
                total += score
                if score >= threshold:
                    conf += 1
        mean = total / total_cells if total_cells else 0.0
        if conf > best_conf or (conf == best_conf and mean > best_mean):
            best_conf, best_mean, best_name, best_cells = conf, mean, name, cells
    if best_conf == 0:
        return None, []
    return best_name, best_cells


# ---------------- 同类内去重（连通分量聚类）----------------
def dedup_class(
    items: list[np.ndarray],
    thresh: float = 0.99,
    min_per_class: int = 8,
    on_progress: Any = None,
) -> list[np.ndarray]:
    """同类内去重：用连通分量聚类，每簇只留一张代表。"""
    if len(items) <= 1:
        return list(items)
    n = len(items)
    descs = []
    for cell in items:
        g = cv2.cvtColor(cell, cv2.COLOR_BGR2GRAY)
        g = cv2.resize(g, (32, 32), interpolation=cv2.INTER_AREA)
        descs.append(g.astype(np.float32).reshape(-1) / 255.0)
    descs = np.stack(descs)

    parent = list(range(n))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb

    bsz = 200
    for i in range(0, n, bsz):
        block = descs[i : i + bsz]
        diff = np.abs(block[:, None, :] - descs[None, :, :]).mean(axis=2)
        sim = 1.0 - diff
        for bi in range(block.shape[0]):
            row = sim[bi]
            js = np.where(row >= thresh)[0]
            cur = i + bi
            for j in js:
                j = int(j)
                if j != cur:
                    union(cur, j)
        if on_progress is not None:
            on_progress(min(i + bsz, n), n)

    comp_rep: dict[int, int] = {}
    for k in range(n):
        comp_rep.setdefault(find(k), k)
    kept_idx = list(comp_rep.values())
    kept_set = set(kept_idx)
    kept_descs = [descs[i] for i in kept_idx]

    if len(kept_idx) < min_per_class:
        for di in range(n):
            if di in kept_set:
                continue
            if len(kept_idx) >= min_per_class:
                break
            d = np.abs(descs[di] - np.stack(kept_descs)).mean(axis=1)
            if (1.0 - d).max() < thresh:
                kept_idx.append(di)
                kept_set.add(di)
                kept_descs.append(descs[di])

    return [items[i] for i in kept_idx]
