"""common.py —— 共享配置与工具函数。

路径约定：
- PROJECT_ROOT = yolo/ 目录（本文件所在包的上两级）
- SHARED_ROOT = yolo/shared/（原始资源）
- CLS_ROOT = yolo/cls/（分类流水线产物）
- DET_ROOT = yolo/det/（检测流水线产物）
"""

from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import cv2
import numpy as np

# ---------------- 目录约定 ----------------
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
SHARED_ROOT = PROJECT_ROOT / "shared"
SHARED_RAW = SHARED_ROOT / "raw"
SHARED_TEMPLATES = SHARED_ROOT / "templates"
CORNERS_JSON = PROJECT_ROOT / "corners.json"
WEIGHTS_DIR = PROJECT_ROOT / "weights"

CLS_ROOT = PROJECT_ROOT / "cls"
CLS_CELLS = CLS_ROOT / "cells"
CLS_CELLS_DEDUP = CLS_ROOT / "cells_dedup"
CLS_DATASET = CLS_ROOT / "dataset"
CLS_RUNS = CLS_ROOT / "runs"
CLS_EXPORT = CLS_ROOT / "export"

DET_ROOT = PROJECT_ROOT / "det"
DET_DATASET = DET_ROOT / "dataset"
DET_RUNS = DET_ROOT / "runs"
DET_EXPORT = DET_ROOT / "export"


def resolve_model(name: str) -> str:
    """把 Ultralytics 模型名解析为本地权重路径。

    优先用 weights/（=Ultralytics 的 WEIGHTS_DIR）下的同名文件（避免从 github 下载）；
    用户给的是已存在路径或本地无同名文件时按原样返回（让 Ultralytics 回退下载）。
    """
    if Path(name).exists():
        return name
    local = WEIGHTS_DIR / Path(name).name
    if local.exists():
        return str(local)
    return name


# ---------------- adb ----------------
_ADB_SERIAL: str | None = None  # 本次运行记住的截图设备（仅存内存，跨运行不保留）

# ---------------- 棋盘几何 ----------------
COLS, ROWS = 9, 10
CORRECT_CELL = 100
CORRECT_W = CORRECT_CELL * COLS  # 900
CORRECT_H = CORRECT_CELL * ROWS  # 1000
CELL_OUT = 64
HALF = CELL_OUT // 2
EMPTY_MATCH_THRESHOLD = 0.8
MATCH_SEARCH_HALF = 10

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


# ---------------- 图像与状态遍历工具（cls/det 验证共用） ----------------
def imread(path: Path) -> np.ndarray | None:
    """中文路径安全的读图。"""
    b = np.fromfile(str(path), dtype=np.uint8)
    if b.size == 0:
        return None
    return cv2.imdecode(b, cv2.IMREAD_COLOR)


def prepare_output_dir(path: Path) -> None:
    """清空已存在的输出目录并重建。"""
    if path.exists():
        shutil.rmtree(path)
        print(f"[清空] 已删除旧输出目录: {path}")
    path.mkdir(parents=True, exist_ok=True)


def iter_state_images(states: list[str], output_root: Path):
    """按状态遍历截图，产出 (st, files, out_dir)；自动跳过不存在的空状态。

    - files: 该状态目录下的 png 文件列表（已排序）
    - out_dir: 对应输出子目录（自动创建）
    """
    for st in states:
        sdir = state_dir(st)
        if not sdir.exists():
            print(f"[跳过] 未找到 {sdir}")
            continue
        files = sorted(sdir.glob("*.png"))
        if not files:
            print(f"[跳过] {sdir} 下无 .png")
            continue
        out_dir = output_root / st
        out_dir.mkdir(parents=True, exist_ok=True)
        yield st, files, out_dir


# ---------------- 各状态的格子标注映射 ----------------
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
    import csv as _csv

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
    import csv as _csv

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


# ---------------- 矫正空间几何 ----------------
def corrected_center(r: int, c: int) -> tuple[float, float]:
    """网格 -> 矫正棋盘中心坐标（900x1000 空间）。"""
    return CORRECT_CELL * (c + 0.5), CORRECT_CELL * (r + 0.5)


def crop_cell(corrected: np.ndarray, r: int, c: int) -> np.ndarray | None:
    """从矫正棋盘裁出 (r,c) 处 64x64 棋子格；越界返回 None。"""
    h, w = corrected.shape[:2]
    cx, cy = corrected_center(r, c)
    x1 = int(max(0, min(w - CELL_OUT, round(cx - HALF))))
    y1 = int(max(0, min(h - CELL_OUT, round(cy - HALF))))
    cell = corrected[y1 : y1 + CELL_OUT, x1 : x1 + CELL_OUT]
    if cell.shape[:2] != (CELL_OUT, CELL_OUT):
        return None
    return cell


# ---------------- 棋盘四角 ----------------
DEFAULT_CORNERS: dict[tuple[int, int], tuple[tuple[float, float], ...]] = {
    (1080, 2376): ((76.0, 667.0), (1004.0, 667.0), (67.0, 1688.0), (1014.0, 1688.0)),
    (1080, 2400): ((76.0, 679.0), (1004.0, 679.0), (67.0, 1699.0), (1014.0, 1699.0)),
    (1440, 3200): ((101.5, 905.5), (1339.5, 905.5), (89.0, 2266.0), (1352.0, 2266.0)),
}

_H_CACHE: dict[tuple[int, int], np.ndarray] = {}


def load_corners_json() -> dict[str, list]:
    if CORNERS_JSON.exists():
        try:
            return json.loads(CORNERS_JSON.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def save_corners_json(data: dict[str, list]) -> None:
    CORNERS_JSON.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def _dst_points() -> np.ndarray:
    return np.array(
        [
            corrected_center(0, 0),
            corrected_center(0, COLS - 1),
            corrected_center(ROWS - 1, 0),
            corrected_center(ROWS - 1, COLS - 1),
        ],
        np.float32,
    )


def resolve_homography(img: np.ndarray) -> np.ndarray:
    """按源截图分辨率取 3x3 透视矩阵。

    优先查 DEFAULT_CORNERS，再查 corners.json 缓存。
    两者都无则抛出异常（不再支持交互选点）。
    """
    h, w = img.shape[:2]
    key = (int(w), int(h))
    if key in _H_CACHE:
        return _H_CACHE[key]

    data = load_corners_json()
    k = f"{w},{h}"
    if key in DEFAULT_CORNERS:
        src = np.array(DEFAULT_CORNERS[key], np.float32)
    elif k in data:
        src = np.array(data[k], np.float32)
    else:
        raise RuntimeError(
            f"分辨率 {k} 未收录于 DEFAULT_CORNERS 或 corners.json。\n"
            f'请手动编辑 {CORNERS_JSON}，添加键 "{k}": '
            "[[x1,y1],[x2,y2],[x3,y3],[x4,y4]]（顺序 左上,右上,左下,右下）后重试。"
        )

    H = cv2.getPerspectiveTransform(src, _dst_points())
    _H_CACHE[key] = H
    return H


def correct_board(img: np.ndarray) -> np.ndarray:
    """源截图 -> 矫正棋盘 (900x1000 BGR)。"""
    H = resolve_homography(img)
    return cv2.warpPerspective(img, H, (CORRECT_W, CORRECT_H))


# ---------------- adb 截图 ----------------
def saved_adb_serial() -> str | None:
    """返回本次运行记住的设备 serial（无则 None）。"""
    return _ADB_SERIAL


def save_adb_serial(serial: str) -> None:
    """把选定的截图设备记到本次运行的进程内存（下次运行脚本不保留）。"""
    global _ADB_SERIAL
    _ADB_SERIAL = serial


def _parse_adb_devices(text: str) -> list[tuple[str, str]]:
    """解析 `adb devices -l` 输出，返回在线设备 (serial, label) 列表。

    label 为 serial + 附加信息（model/product 等），无附加信息时即 serial。
    """
    out: list[tuple[str, str]] = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 2 or parts[1] != "device":
            continue
        serial = parts[0]
        extra = " ".join(parts[2:]) if len(parts) > 2 else ""
        label = f"{serial}  {extra}".strip() if extra else serial
        out.append((serial, label))
    return out


def list_adb_devices() -> list[tuple[str, str]]:
    """运行 `adb devices -l`，返回在线设备 (serial, label) 列表。"""
    try:
        proc = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True, timeout=10)
    except FileNotFoundError:
        print("[错误] 未找到 adb，请确认 adb 在 PATH 中")
        return []
    except Exception as e:
        print(f"[错误] 查询 adb 设备失败: {e}")
        return []
    if proc.returncode != 0:
        print(f"[adb错误] {proc.stderr.strip()}")
        return []
    return _parse_adb_devices(proc.stdout)


def resolve_adb_serial() -> str | None:
    """解析要使用的截图设备 serial。

    - 无在线设备 -> 打印错误并返回 None
    - 仅一个在线设备 -> 默认使用并记住（本次运行）
    - 多个在线设备 -> 列出让用户选择（上次的标为默认）并记住（本次运行）
    记住的结果仅存于进程内存，本次运行内后续调用沿用，下次运行脚本重新选择。
    """
    devices = list_adb_devices()
    if not devices:
        print("[错误] 未检测到在线 adb 设备（adb devices 无结果）")
        return None

    if len(devices) == 1:
        serial, label = devices[0]
        print(f"检测到单个设备，默认使用: {label}")
        save_adb_serial(serial)
        return serial

    import questionary

    saved = saved_adb_serial()
    choices = [
        questionary.Choice(title=f"{label}  <== 上次" if s == saved else label, value=s)
        for s, label in devices
    ]
    result = questionary.select("检测到多个设备，请选择截图设备：", choices=choices).ask()
    if result is None:
        print("[已取消] 未选择设备")
        return None
    save_adb_serial(result)
    print(f"已记住设备: {result}")
    return result


def adb_screenshot(out_path: Path, serial: str | None = None) -> bool:
    """用 adb 抓取设备截图保存到 out_path。

    serial 不传时用 resolve_adb_serial() 解析（单设备默认 / 多设备交互选择并记住）。

    注意：多设备下每隔一段时间会自动重新解析，若担心每次截图都弹选择框，
    建议在循环外先 resolve_adb_serial() 一次，把 serial 显式传入。
    """
    if serial is None:
        serial = resolve_adb_serial()
        if serial is None:
            return False
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["adb", "-s", serial, "exec-out", "screencap", "-p"]
    try:
        with out_path.open("wb") as f:
            proc = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE, timeout=30)
        if proc.returncode != 0:
            msg = proc.stderr.decode(errors="ignore").strip()
            print(f"[adb错误] {msg or 'returncode=' + str(proc.returncode)}")
            if out_path.exists():
                out_path.unlink(missing_ok=True)
            return False
        if out_path.stat().st_size == 0:
            print("[adb错误] 截图为空，请检查设备连接 / 分辨率")
            out_path.unlink(missing_ok=True)
            return False
        return True
    except FileNotFoundError:
        print("[错误] 未找到 adb，请确认 adb 在 PATH 中")
        return False
    except Exception as e:
        print(f"[错误] 截图失败: {e}")
        if out_path.exists():
            out_path.unlink(missing_ok=True)
        return False


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
                json.dumps(
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


# ---------------- Ultralytics 训练指标解析（cls/det 训练共用） ----------------
def _csv_col(name: str, header: dict[str, str]) -> str | None:
    """在 Ultralytics results.csv 行里定位列；先精确匹配再模糊匹配。"""
    for k in header:
        if k.strip() == name:
            return k
    for k in header:
        if name in k:
            return k
    return None


def collect_train_metrics(
    run_dir: Path, cols: dict[str, tuple[list[str], int]], best: str | None = None
) -> dict:
    """解析 Ultralytics runs/<name>/results.csv 的训练指标。

    - cols: tag -> (候选列名列表, 小数位数)，按候选顺序取第一个命中；
      tag 产出 final_<tag>（最后一轮）。
    - best: 要追踪最佳值+epoch 的 tag，产出 best_<tag> 与 best_<tag>_epoch。
    """
    metrics: dict = {}
    csv_path = run_dir / "results.csv"
    if not csv_path.exists():
        return metrics
    try:
        import csv as _csv

        with csv_path.open(encoding="utf-8", newline="") as f:
            rows = list(_csv.DictReader(f))
        if not rows:
            return metrics
        last = rows[-1]

        def to_f(x: object) -> float | None:
            try:
                return float(str(x))  # type: ignore[arg-type]
            except (TypeError, ValueError):
                return None

        resolved: dict[str, str | None] = {}
        for tag, (cands, _prec) in cols.items():
            for name in cands:
                k = _csv_col(name, last)
                if k is not None:
                    resolved[tag] = k
                    break
            else:
                resolved[tag] = None

        metrics["final_epoch"] = int(float(last.get("epoch", len(rows))))
        for tag, k in resolved.items():
            if k is not None:
                v = to_f(last[k])
                if v is not None:
                    metrics[f"final_{tag}"] = round(v, cols[tag][1])

        if best is not None:
            bk = resolved.get(best)
            if bk is not None:
                best_v, best_e = -1.0, -1
                for r in rows:
                    v, e = to_f(r.get(bk)), to_f(r.get("epoch"))
                    if v is not None and v > best_v:
                        best_v = v
                        best_e = int(e) if e is not None else -1
                if best_v >= 0:
                    metrics[f"best_{best}"] = round(best_v, 4)
                    metrics[f"best_{best}_epoch"] = best_e if best_e >= 0 else None
    except Exception as e:
        metrics["_csv_parse_error"] = str(e)
    return metrics


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
