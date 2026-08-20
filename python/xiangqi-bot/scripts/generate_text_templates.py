"""从原始结算截图切割结算文字模板到 templates/text/。

裁剪范围来自交叉匹配报告的核验结果（按钮在对话框底部、段位提升为标题文字），
生成前先验证：自身截图应 ~1.0 命中，其它截图最高分须低于 GAMEOVER_TEXT_THRESHOLD。

用法: uv run python scripts/generate_text_templates.py
"""

import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from xiangqi_bot import config

RAW = config.PROJECT_ROOT / "raw_screenshots"
OUT_DIR = config.GAMEOVER_TEXT_DIR
DRAW_OUT_DIR = config.PROJECT_ROOT / "templates" / "draw"

# 文字 -> (来源截图, 裁剪范围)。按钮取对话框底部按钮文字；段位提升为顶部提示文字；领取为悬浮遮罩文字。
SOURCES: dict[str, tuple[str, tuple[int, int, int, int]]] = {
    "晋级赛": ("晋级赛_1080x2400.png", (430, 2230, 650, 2295)),
    "重新挑战": ("重新挑战_1080x2400.png", (400, 2160, 670, 2230)),
    "再来一局": ("再来一局3_1080x2400.png", (360, 2190, 725, 2280)),
    "下一关": ("下一关_1080x2400.png", (610, 2170, 815, 2225)),
    "段位提升": ("段位提升_1080x2400.png", (360, 70, 720, 165)),
    "铜钱": ("再来一局_铜钱_1080x2400.png", (430, 1150, 525, 1200)),
    "领取": ("再来一局_领取_1080x2400.png", (500, 1360, 645, 1425)),
}

# 和棋弹窗模板（独立目录，不与结算文字混用）
DRAW_SOURCES: dict[str, tuple[str, tuple[int, int, int, int]]] = {
    "拒绝": ("和棋_1080x2400.png", (285, 1280, 435, 1345)),
}


def imread(p: Path) -> np.ndarray:
    img = cv2.imdecode(np.fromfile(str(p), dtype=np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise RuntimeError(f"无法读取截图: {p}")
    return img


def main() -> int:
    shots = {p.name: imread(p) for p in RAW.glob("*.png")}
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    ok = True
    for word, (src, (x0, y0, x1, y1)) in SOURCES.items():
        if src not in shots:
            print(f"错误: 缺少来源截图 {src}")
            ok = False
            continue
        tpl = shots[src][y0:y1, x0:x1]
        tgray = cv2.cvtColor(tpl, cv2.COLOR_BGR2GRAY)
        # 自身截图命中（应在裁剪位置，分数 ~1.0）
        res_self = cv2.matchTemplate(
            cv2.cvtColor(shots[src], cv2.COLOR_BGR2GRAY), tgray, cv2.TM_CCOEFF_NORMED
        )
        self_score = float(res_self.max())
        # 其它所有截图上的最高分
        worst = 0.0
        worst_where = ""
        for oname, oimg in shots.items():
            if oname == src or word in oname:
                continue
            res = cv2.matchTemplate(
                cv2.cvtColor(oimg, cv2.COLOR_BGR2GRAY), tgray, cv2.TM_CCOEFF_NORMED
            )
            _, mx, _, _ = cv2.minMaxLoc(res)
            if float(mx) > worst:
                worst = float(mx)
                worst_where = oname
        verdict = "OK" if self_score >= 0.95 and worst < config.GAMEOVER_TEXT_THRESHOLD else "异常"
        if verdict != "OK":
            ok = False
        print(
            f"{word}: 自匹配 {self_score:.3f}，其它截图最高 {worst:.3f} ({worst_where}) -> {verdict}"
        )
        if verdict != "OK":
            continue
        out = OUT_DIR / f"{word}.png"
        success, buf = cv2.imencode(".png", tpl)
        if not success:
            print(f"错误: 无法编码 {word}.png")
            ok = False
            continue
        out.write_bytes(buf.tobytes())
        print(f"  已写入 {out}")
    print("结算文字模板已生成。异常/缺失请勿使用，需重新核验裁剪范围。")

    # 和棋弹窗模板
    DRAW_OUT_DIR.mkdir(parents=True, exist_ok=True)
    for word, (src, (x0, y0, x1, y1)) in DRAW_SOURCES.items():
        if src not in shots:
            print(f"错误: 缺少来源截图 {src}")
            ok = False
            continue
        tpl = shots[src][y0:y1, x0:x1]
        tgray = cv2.cvtColor(tpl, cv2.COLOR_BGR2GRAY)
        res_self = cv2.matchTemplate(
            cv2.cvtColor(shots[src], cv2.COLOR_BGR2GRAY), tgray, cv2.TM_CCOEFF_NORMED
        )
        self_score = float(res_self.max())
        worst = 0.0
        worst_where = ""
        for oname, oimg in shots.items():
            if oname == src or word in oname:
                continue
            res = cv2.matchTemplate(
                cv2.cvtColor(oimg, cv2.COLOR_BGR2GRAY), tgray, cv2.TM_CCOEFF_NORMED
            )
            _, mx, _, _ = cv2.minMaxLoc(res)
            if float(mx) > worst:
                worst = float(mx)
                worst_where = oname
        verdict = "OK" if self_score >= 0.95 and worst < config.GAMEOVER_TEXT_THRESHOLD else "异常"
        if verdict != "OK":
            ok = False
        print(
            f"[和棋] {word}: 自匹配 {self_score:.3f}，"
            f"其它截图最高 {worst:.3f} ({worst_where}) -> {verdict}"
        )
        if verdict != "OK":
            continue
        out = DRAW_OUT_DIR / f"{word}.png"
        success, buf = cv2.imencode(".png", tpl)
        if not success:
            print(f"错误: 无法编码 {word}.png")
            ok = False
            continue
        out.write_bytes(buf.tobytes())
        print(f"  已写入 {out}")
    print("和棋弹窗模板已生成。")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
