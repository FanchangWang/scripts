"""yolo_common.py —— 三个脚本共用的配置与工具（仅依赖标准库，导入安全）。

注意：本模块在 *顶层* 不导入任何第三方包（cv2 / numpy 仅在函数内部懒加载），
因此脚本1（仅用标准库）也能安全 `import yolo_common` 而不必安装 OpenCV。

坐标约定（与 Android 侧 Kotlin Board.kt / 参考脚本一致）：
- 网格 (r, c)，(0,0) 恒为左上角格子；row 0 在顶部 = 黑方，row 9 在底部 = 红方。
- 矫正空间 900x1000，格边长 100，格心 = (100*(c+0.5), 100*(r+0.5))。
- 棋子 ID 采用 "b_"/"r_" 前缀（黑/红），与 Kotlin 的 PIECE_FEN / START_SQUARES 一致。
"""

from __future__ import annotations

import json
from pathlib import Path

# ---------------- 目录约定（全部位于 cnn/ 下）----------------
BASE_DIR = Path(__file__).resolve().parent
RAW_ROOT = BASE_DIR / "raw"            # 脚本1 截图落盘
DATASET_ROOT = BASE_DIR / "dataset_yolo"  # 脚本2 产出
EXPORT_ROOT = BASE_DIR / "export"      # 脚本3 导出模型
RUNS_ROOT = BASE_DIR / "runs"          # 脚本3 训练过程
CORNERS_JSON = BASE_DIR / "corners.json"  # 四角坐标缓存（按分辨率）
TEMPLATE_DIR = BASE_DIR / "templates"      # 脚本4 产出的棋子模板（14 棋子 + empty）
CELLS_CUT_DIR = BASE_DIR / "cells_cut"      # 脚本1 产出：raw 切割后的逐格小图（中间缓存）
CELLS_DEDUP_DIR = BASE_DIR / "cells_dedup"  # 脚本2 产出：去重后的逐格小图（中间缓存）

# ---------------- adb ----------------
ADB_SERIAL = "192.168.31.60:5555"

# ---------------- 棋盘几何 ----------------
COLS, ROWS = 9, 10
CORRECT_CELL = 100
CORRECT_W = CORRECT_CELL * COLS   # 900
CORRECT_H = CORRECT_CELL * ROWS   # 1000
CELL_OUT = 64                     # 切割格子边长
HALF = CELL_OUT // 2
EMPTY_MATCH_THRESHOLD = 0.8    # 参考 xiangqi-bot/config.py：棋子匹配分低于此值判为空格
MATCH_SEARCH_HALF = 10         # 模板匹配滑动半径（参考 config.MATCH_SEARCH_HALF）

# ---------------- 16 类定义（索引即 class id）----------------
# 14 种棋子 + 空格(empty) + 提子(lift)
CLASSES: list[str] = [
    "b_k", "r_K", "b_a", "r_A", "b_b", "r_B", "b_n", "r_N",
    "b_r", "r_R", "b_c", "r_C", "b_p", "r_P", "empty", "lift",
]
CLASS_CN: dict[str, str] = {
    "b_k": "黑将", "r_K": "红帥", "b_a": "黑士", "r_A": "红仕",
    "b_b": "黑象", "r_B": "红相", "b_n": "黑馬", "r_N": "红傌",
    "b_r": "黑車", "r_R": "红俥", "b_c": "黑砲", "r_C": "红炮",
    "b_p": "黑卒", "r_P": "红兵", "empty": "空格", "lift": "提子",
}
CLASS_IDX: dict[str, int] = {k: i for i, k in enumerate(CLASSES)}

# ---------------- 棋局状态 ----------------
# (编号, 目录名, 中文描述)
STATES: list[tuple[int, str, str]] = [
    (1, "opening", "开局（32子满盘，未走棋）"),
    (2, "mate", "绝杀（仅将帥在初始位）"),
    (3, "lift", "提子（将帥+红中兵初始位，兵位被提）"),
    (4, "check", "被将（黑将車红帥俥，红俥提子）"),
    (5, "endgame", "残局（任意中残局，用模板自动匹配标注）"),
]
VALID_STATES = {s[0] for s in STATES}


def state_dir(n: int) -> Path:
    """脚本1 截图按状态存放的目录（cnn/raw/<状态号>/）。"""
    return RAW_ROOT / f"{n}"


# ---------------- 各状态的格子标注映射 ----------------
def _start_squares() -> dict[tuple[int, int], str]:
    """标准开局：棋子 ID -> 网格位置列表（顶部黑 rows0-4，底部红 rows5-9）。"""
    squares = {
        "b_r": [(0, 0), (0, 8)], "b_n": [(0, 1), (0, 7)], "b_b": [(0, 2), (0, 6)],
        "b_a": [(0, 3), (0, 5)], "b_k": [(0, 4)], "b_c": [(2, 1), (2, 7)],
        "b_p": [(3, 0), (3, 2), (3, 4), (3, 6), (3, 8)],
        "r_R": [(9, 0), (9, 8)], "r_N": [(9, 1), (9, 7)], "r_B": [(9, 2), (9, 6)],
        "r_A": [(9, 3), (9, 5)], "r_K": [(9, 4)], "r_C": [(7, 1), (7, 7)],
        "r_P": [(6, 0), (6, 2), (6, 4), (6, 6), (6, 8)],
    }
    m: dict[tuple[int, int], str] = {}
    for pid, cells in squares.items():
        for (r, c) in cells:
            m[(r, c)] = pid
    return m


def label_map_for_state(n: int) -> dict[tuple[int, int], str]:
    """返回该状态下每格 (r,c) 的棋子 class key；缺省为 empty。

    状态特殊布局（均按“上方黑、下方红”）：
      2 绝杀: 将(0,4) 帥(9,4)
      3 提子: 将(0,4) 帥(9,4) 红中兵(6,4) 被提 -> lift
      4 被将: 将(0,4) 帥(9,4) 黑車(1,4) 红俥(8,4) 被提 -> lift
      5 残局: 任意中残局，无固定标注 —— 返回空 dict，由调用方改用
              step_2_cut_templates.py 生成的棋子模板做自动匹配（见 match_cell_to_class）。
    """
    if n == 1:
        return _start_squares()
    if n == 2:
        return {(0, 4): "b_k", (9, 4): "r_K"}
    if n == 3:
        return {(0, 4): "b_k", (9, 4): "r_K", (6, 4): "lift"}
    if n == 4:
        return {(0, 4): "b_k", (9, 4): "r_K", (1, 4): "b_r", (8, 4): "lift"}
    if n == 5:
        return {}  # 残局：无固定标注，走模板匹配
    raise ValueError(f"未知状态 {n}")


# ---------------- 状态3 提子点（labels.csv 驱动标注）----------------
# 提子点：红中兵(默认) + 将帥(0,4)/(9,4) + 士象(8,4)/(7,4)/(1,4)/(2,4)。
# 全部落于第4列将帅竖线；士象为走一步可达位。
# 约定（由用户确认）：
#   - 提子点∈{红中兵,红仕,红相,黑士,黑象} 时，棋盘共 3 子：提子点 + 将帥(0,4) + 帥(9,4)。
#   - 提子点∈{红帥(9,4),黑将(0,4)} 时，棋盘仅 2 子：提子点 + 对方将帥。
# 旧 raw/3 截图若无 labels.csv 记录，默认按红中兵(6,4) 标注（向后兼容）。
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
    """状态3 提子标签 sidecar 路径：raw/<状态>/labels.csv。"""
    return state_root / "labels.csv"


def load_lift_labels(state_root: Path) -> dict[str, tuple[int, int]]:
    """读取 raw/<状态>/labels.csv（stem,lift_row,lift_col）。
    缺失文件或字段非法返回空 dict；同 stem 仅取首次出现。"""
    import csv as _csv  # noqa: PLC0415
    csv = lift_label_csv(state_root)
    out: dict[str, tuple[int, int]] = {}
    if not csv.exists():
        return out
    with csv.open(newline="", encoding="utf-8") as f:
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
    """向 raw/<状态>/labels.csv 写入一条提子标签；stem 已存在则跳过（防覆盖/重复）。"""
    import csv as _csv  # noqa: PLC0415
    csv = lift_label_csv(state_root)
    csv.parent.mkdir(parents=True, exist_ok=True)
    existing = load_lift_labels(state_root)
    if stem in existing:
        return
    rows = [[s, r, c] for s, (r, c) in existing.items()]
    rows.append([stem, lr, lc])
    with csv.open("w", newline="", encoding="utf-8") as f:
        w = _csv.writer(f)
        w.writerow(["stem", "lift_row", "lift_col"])
        w.writerows(rows)


def label_map_for_lift(lr: int, lc: int) -> dict[tuple[int, int], str]:
    """据提子点 (lr,lc) 生成状态3标注映射（其余格默认 empty）：
      - 提子点标 "lift"；
      - 若提子点不是 (0,4)，则 (0,4)=b_k；
      - 若提子点不是 (9,4)，则 (9,4)=r_K。
    当提子点本身就是将帥时，仅剩对方将帥（共2子），逻辑自动成立。"""
    m: dict[tuple[int, int], str] = {(lr, lc): "lift"}
    if (0, 4) != (lr, lc):
        m[(0, 4)] = "b_k"
    if (9, 4) != (lr, lc):
        m[(9, 4)] = "r_K"
    return m


def label_map_for_lift_image(state_root: Path, stem: str) -> dict[tuple[int, int], str]:
    """状态3 单张截图：从 labels.csv 取提子点，无记录默认红中兵(6,4)。"""
    labels = load_lift_labels(state_root)
    lr, lc = labels.get(stem, (6, 4))
    return label_map_for_lift(lr, lc)


# ---------------- 矫正空间几何 ----------------
def corrected_center(r: int, c: int) -> tuple[float, float]:
    """网格 -> 矫正棋盘中心坐标（恒为 900x1000 空间）。"""
    return CORRECT_CELL * (c + 0.5), CORRECT_CELL * (r + 0.5)


def crop_cell(corrected, r: int, c: int):
    """从矫正棋盘 (900x1000 BGR) 裁出 (r,c) 处 64x64 棋子格；越界返回 None。"""
    h, w = corrected.shape[:2]
    cx, cy = corrected_center(r, c)
    x1 = int(max(0, min(w - CELL_OUT, round(cx - HALF))))
    y1 = int(max(0, min(h - CELL_OUT, round(cy - HALF))))
    cell = corrected[y1:y1 + CELL_OUT, x1:x1 + CELL_OUT]
    if cell.shape[:2] != (CELL_OUT, CELL_OUT):
        return None
    return cell


# ---------------- 棋盘四角（源截图分辨率 -> (TL,TR,BL,BR) 格中心像素）----------------
# 拷贝自参考脚本 correct_init_screenshots.py（已验证的三套分辨率）。
DEFAULT_CORNERS: dict[tuple[int, int], tuple[tuple[float, float], ...]] = {
    (1080, 2376): ((76.0, 667.0), (1004.0, 667.0), (67.0, 1688.0), (1014.0, 1688.0)),
    (1080, 2400): ((76.0, 679.0), (1004.0, 679.0), (67.0, 1699.0), (1014.0, 1699.0)),
    (1440, 3200): ((101.5, 905.5), (1339.5, 905.5), (89.0, 2266.0), (1352.0, 2266.0)),
}

_H_CACHE: dict[tuple[int, int], object] = {}


def load_corners_json() -> dict[str, list]:
    if CORNERS_JSON.exists():
        try:
            return json.loads(CORNERS_JSON.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def save_corners_json(data: dict[str, list]) -> None:
    CORNERS_JSON.write_text(
        json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8"
    )


def _dst_points():
    import numpy as np
    return np.array(
        [
            corrected_center(0, 0),
            corrected_center(0, COLS - 1),
            corrected_center(ROWS - 1, 0),
            corrected_center(ROWS - 1, COLS - 1),
        ],
        np.float32,
    )


def _interactive_pick(img, w: int, h: int):
    """交互式选取四角（左上→右上→左下→右下），返回 np.float32 (4,2)。"""
    import cv2
    import numpy as np

    info: dict[str, list] = {"pts": []}
    scale = min(1.0, 1000.0 / max(w, h))
    disp = img.copy()
    if scale < 1.0:
        disp = cv2.resize(disp, (int(w * scale), int(h * scale)))
    win = "pick 4 corners (TL,TR,BL,BR); then press any key"

    def on_mouse(ev, x, y, _flags, _param):
        if ev == cv2.EVENT_LBUTTONDOWN and len(info["pts"]) < 4:
            fx, fy = x / scale, y / scale
            info["pts"].append((fx, fy))
            cv2.circle(disp, (x, y), 10, (0, 0, 255), -1)
            cv2.imshow(win, disp)
            print(f"  已选点 {len(info['pts'])}/4: ({fx:.0f}, {fy:.0f})")

    cv2.namedWindow(win)
    cv2.setMouseCallback(win, on_mouse)
    print("请在弹窗中依次点击棋盘四角：左上 → 右上 → 左下 → 右下；选满4个后按任意键确认。")
    print("（若无法弹窗，请手动在 corners.json 写入该分辨率四角，格式见下文报错）")
    while True:
        cv2.imshow(win, disp)
        k = cv2.waitKey(50)
        if len(info["pts"]) >= 4 and k != -1:
            break
        if k in (ord("q"), ord("Q")):
            cv2.destroyAllWindows()
            raise RuntimeError("用户取消选点")
    cv2.destroyAllWindows()
    return np.array(info["pts"], np.float32)


def resolve_homography(img) -> object:
    """按源截图分辨率取 3x3 透视矩阵；未知分辨率时交互选取并缓存到 corners.json。"""
    import cv2
    import numpy as np

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
        try:
            src = _interactive_pick(img, w, h)
        except Exception as e:
            print(f"[无法交互选点] {e}")
            print(
                f"请手动编辑 {CORNERS_JSON}，添加键 \"{k}\": "
                "[[x1,y1],[x2,y2],[x3,y3],[x4,y4]]（顺序 左上,右上,左下,右下，"
                "像素为源截图像素坐标）后重试。"
            )
            raise
        data[k] = [[float(x), float(y)] for (x, y) in src.tolist()]
        save_corners_json(data)
        print(f"[已缓存四角] 分辨率 {k} -> {CORNERS_JSON}")

    H = cv2.getPerspectiveTransform(src, _dst_points())
    _H_CACHE[key] = H
    return H


def correct_board(img) -> object:
    """源截图 -> 矫正棋盘 (900x1000 BGR)。"""
    import cv2

    H = resolve_homography(img)
    return cv2.warpPerspective(img, H, (CORRECT_W, CORRECT_H))


# ---------------- adb 截图 ----------------
def adb_screenshot(out_path: Path) -> bool:
    """用 adb 抓取设备截图保存到 out_path，成功返回 True。"""
    import subprocess

    out_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["adb", "-s", ADB_SERIAL, "exec-out", "screencap", "-p"]
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
        print("[错误] 未找到 adb，请确认 adb 在 PATH 中（或修改 yolo_common.ADB_SERIAL 为绝对路径）")
        return False
    except Exception as e:  # noqa: BLE001
        print(f"[错误] 截图失败: {e}")
        if out_path.exists():
            out_path.unlink(missing_ok=True)
        return False


# ---------------- 棋子模板（脚本4 生成，残局自动标注用）----------------
# 结构：templates/<set_NN>/<class>.png —— 每张开局图独立切出一套（支持多套皮肤）。
# 残局匹配时由 match_board_with_best_set 自动挑匹配率最高的那套。

def _set_name(i: int) -> str:
    """模板套目录名，如 set_00。"""
    return f"set_{i:02d}"


def cut_template_set_from_image(corrected, lmap: dict) -> dict:
    """从已矫正开局图(900x1000)按 lmap（32 子固定位置）切出 14 子模板 {class: 64x64 BGR}。"""
    out: dict[str, object] = {}
    for (r, c), pid in lmap.items():
        if pid in ("empty", "lift"):
            continue
        cell = crop_cell(corrected, r, c)
        if cell is not None:
            out[pid] = cell
    return out


def build_all_template_sets(save: bool = True) -> dict:
    """遍历 raw/1 每张开局图，各自独立切出一套 14 子模板（不平均，保留皮肤特征）。

    返回 {set_name: {class_key: 64x64 BGR ndarray}}。
    设计：raw/1 可能含多种棋子皮肤，每张图独立成一套；残局匹配时由
    match_board_with_best_set 自动挑匹配率最高的那套。
    若 save=True，先清空旧 templates/ 再写 templates/<set_NN>/<key>.png + meta.json。
    """
    import shutil

    import cv2
    import numpy as np

    opening_dir = state_dir(1)
    imgs = sorted(opening_dir.glob("*.png")) if opening_dir.exists() else []
    if not imgs:
        raise RuntimeError(f"raw/1 开局截图缺失，无法生成模板: {opening_dir}")

    lmap = label_map_for_state(1)  # 32 子固定位置
    if save and TEMPLATE_DIR.exists():
        shutil.rmtree(TEMPLATE_DIR)
    sets: dict[str, dict] = {}
    j = 0
    for p in imgs:
        raw = np.fromfile(str(p), dtype=np.uint8)
        img = cv2.imdecode(raw, cv2.IMREAD_COLOR)
        if img is None:
            print(f"[跳过] 解码失败: {p.name}")
            continue
        try:
            corrected = correct_board(img)
        except Exception as e:  # noqa: BLE001
            print(f"[跳过] 矫正失败 {p.name}: {e}")
            continue
        tset = cut_template_set_from_image(corrected, lmap)
        if len(tset) < 14:
            print(f"[警告] {p.name} 仅切出 {len(tset)}/14 子，跳过该套")
            continue
        name = _set_name(j)
        sets[name] = tset
        if save:
            d = TEMPLATE_DIR / name
            d.mkdir(parents=True, exist_ok=True)
            for key, arr in tset.items():
                cv2.imencode(".png", arr)[1].tofile(str(d / f"{key}.png"))
            (d / "meta.json").write_text(
                json.dumps({"source": p.name, "pieces": len(tset)},
                           ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
        j += 1
    if not sets:
        raise RuntimeError("未从 raw/1 切出任何模板套，请检查开局图与四角坐标。")
    return sets


def load_template_sets() -> dict:
    """读取 templates/<set_NN>/*.png 为 {set_name: {class_key: 64x64 BGR ndarray}}。"""
    import cv2
    import numpy as np

    out: dict[str, dict] = {}
    if not TEMPLATE_DIR.exists():
        return out
    for d in sorted(TEMPLATE_DIR.iterdir()):
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


def ensure_template_sets(rebuild: bool = False) -> dict:
    """优先加载磁盘多套模板；缺失或被要求重建时由 raw/1 重新切套。"""
    if not rebuild and TEMPLATE_DIR.exists():
        sets = load_template_sets()
        if sets:
            return sets
    # 需要重建：raw/1 必须已采集
    if not state_dir(1).exists() or not any(state_dir(1).glob("*.png")):
        raise RuntimeError(
            "raw/1 开局截图缺失，无法生成模板。请先运行 step_1_collect_screenshots.py 采集开局图，"
            "或先运行 step_2_cut_templates.py。"
        )
    return build_all_template_sets(save=True)


def _match_window(window, templates: dict, threshold: float = EMPTY_MATCH_THRESHOLD) -> tuple[str, float]:
    """在搜索窗口内对 14 个棋子模板逐张 TM_CCOEFF_NORMED 匹配，返回 (class, 置信度)。

    对齐 xiangqi-bot/vision.analyze_cell：只匹配 14 棋子模板，取最高分；
    最高分低于 threshold 则判为空格(empty)。模板集不含 lift，残局不会产出 lift。
    """
    import cv2

    best_key, best_score = "empty", -1.0
    for key, tmpl in templates.items():
        res = cv2.matchTemplate(window, tmpl, cv2.TM_CCOEFF_NORMED)
        _, max_val, _, _ = cv2.minMaxLoc(res)
        if max_val > best_score:
            best_score, best_key = max_val, key
    if best_score < threshold:
        return "empty", float(best_score)
    return best_key, float(best_score)


def match_cell_to_class(cell, templates: dict, threshold: float = EMPTY_MATCH_THRESHOLD) -> tuple[str, float]:
    """用模板匹配把单格 (64x64 BGR) 标为最接近的 class（14 棋子 / empty）。

    等价于在单张 64x64 窗口内匹配（窗口与模板同尺寸，无滑动）。返回 (class_key, 置信度)。
    """
    return _match_window(cell, templates, threshold)


def match_cell_in_corrected(corrected, r: int, c: int, templates: dict,
                            threshold: float = EMPTY_MATCH_THRESHOLD) -> tuple[str, float]:
    """在已矫正棋盘 (900x1000) 的 (r,c) 处做带滑动窗口的模板匹配（推荐用于残局标注）。

    与 xiangqi-bot/vision.analyze_cell 完全一致：以格心为中心取
    (MATCH_SEARCH_HALF + CELL_OUT/2) 半径的搜索窗口，模板在其中滑动匹配，
    容忍棋子未完全居中的亚像素偏移；最高分低于 threshold 判空格。
    """

    cx, cy = corrected_center(r, c)
    px, py = int(round(cx)), int(round(cy))
    half = MATCH_SEARCH_HALF + CELL_OUT // 2
    x1 = int(max(0, px - half))
    y1 = int(max(0, py - half))
    window = corrected[y1:py + half, x1:px + half]
    if window.shape[0] < CELL_OUT or window.shape[1] < CELL_OUT:
        cell = crop_cell(corrected, r, c)
        if cell is None:
            return "empty", 0.0
        return _match_window(cell, templates, threshold)
    return _match_window(window, templates, threshold)


def match_board_with_best_set(corrected, template_sets: dict,
                              threshold: float = EMPTY_MATCH_THRESHOLD) -> tuple:
    """残局：自动选「自信识别为棋子的格子最多」的那套模板（0.8 闸门防误用错误皮肤）。

    选套指标：对每套模板，统计全 90 格中 best_score >= threshold 的格子数
    （即被该皮肤自信识别为棋子的格子数）；选该数最大者。正确皮肤下棋子普遍
    0.9x（最低 0.8x），错误皮肤仅 0.6x，故正确皮肤会显著胜出，杜绝误用错误模板。
    平局时用全格力均值做二级比较，偏好匹配质量更高的皮肤。
    若所有套的自信棋子数都为 0（raw/1 缺对应皮肤），返回 (None, []) 供上层报警。

    返回 (best_set_name, [(r,c,class_key,score), ...])；无匹配皮肤时 (None, [])。
    """
    best_name, best_conf, best_mean, best_cells = None, -1, -1.0, None
    total_cells = ROWS * COLS
    for name, tset in template_sets.items():
        cells: list = []
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


# ---------------- 同类内去重（连通分量聚类，避免链式近重复）----------------
def dedup_class(items: list, thresh: float = 0.99, min_per_class: int = 8) -> list:
    """同类内去重：用连通分量聚类（相似度 >= thresh 归同一簇，每簇只留一张代表）。

    相似度 = 1 - mean(|a-b|)（灰度 32x32 描述子，0~1）。thresh=0.99 即“至少 99% 相似”判重。
    连通分量（union-find）可避免贪心比对的“链式近重复”漏洞：A、B、C 两两未必都 >=0.99，
    但 A-B、B-C 各 >=0.99 时会经 B 归并到同一簇，最终只留一张，杜绝 train/val 泄漏。

    floor（min_per_class）：去重后若某类 < 该值，按出现顺序尽量回补“彼此且与所有已留项都
    < thresh”的样本——绝不引入近重复，故不会产生泄漏；若没有足够不同的样本则只补到能补的。

    说明：跨截屏的同一棋子/空格并非字节一致（亚像素/抗锯齿/压缩差异），故用真实相似度而非哈希；
    同类内去重语义安全（标签相同，保留一张代表即可）。
    """
    if len(items) <= 1:
        return list(items)
    n = len(items)
    import cv2
    import numpy as np
    descs = []
    for cell in items:
        g = cv2.cvtColor(cell, cv2.COLOR_BGR2GRAY)
        g = cv2.resize(g, (32, 32), interpolation=cv2.INTER_AREA)
        descs.append(g.astype(np.float32).reshape(-1) / 255.0)
    descs = np.stack(descs)  # (n, 1024)

    # ---- union-find 连通分量（分块计算相似度，避免大矩阵爆内存）----
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

    B = 200
    for i in range(0, n, B):
        block = descs[i:i + B]
        diff = np.abs(block[:, None, :] - descs[None, :, :]).mean(axis=2)  # (b, n)
        sim = 1.0 - diff
        for bi in range(block.shape[0]):
            row = sim[bi]
            js = np.where(row >= thresh)[0]
            cur = i + bi
            for j in js:
                j = int(j)
                if j != cur:
                    union(cur, j)

    # 每簇取首个为代表
    comp_rep: dict[int, int] = {}
    for k in range(n):
        comp_rep.setdefault(find(k), k)
    kept_idx = list(comp_rep.values())
    kept_set = set(kept_idx)
    kept_descs = [descs[i] for i in kept_idx]

    # ---- floor：仅回补彼此/与已留项都 < thresh 的样本（不引入泄漏）----
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
