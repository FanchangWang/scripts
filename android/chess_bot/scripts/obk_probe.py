# -*- coding: utf-8 -*-
"""沿常见开局线路探测新旧库的纵深覆盖（每层双通道合计命中数与 Top1 着法）。"""
import sqlite3, struct, sys, importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("oc", ROOT / 'scripts/obk_check.py')
oc = importlib.util.module_from_spec(spec)
import types
# obk_check.py 顶层只有函数定义和常量解析，import 不执行 main
sys.argv = ['x']
spec.loader.exec_module(oc)

zobrist, mirror_sq, apply_iccs = oc.zobrist, oc.mirror_sq, oc.apply_iccs
START = oc.START

LINES = {
    '中炮过河车对屏风马': ['h2e2','h9g7','h0g2','i9h9','i0h0','c6c5','h0h6'],
    '中炮直车对横车':     ['h2e2','h9g7','h0g2','h9i9','i0h0','i9h9'],
    '飞相局':             ['g0e2','b9c7','g3g4','h9g7','h0g2'],
    '仙人指路':           ['g3g4','b9c7','h0g2','h9g7','i0h0'],
    '起马局':             ['h0g2','h9g7','g3g4','c6c5','i0h0'],
    '仕角炮':             ['b2e2','h9g7','h0g2','c6c5','i0h0'],
    '过宫炮':             ['h2e2','h9g7','h0g2','b9c7','i0h0'],
}

def q(cur, key):
    bind = key if key >= 0 else struct.unpack('<d', struct.pack('<q', key))[0]
    return cur.execute('SELECT vmove, vscore FROM bhobk WHERE +vkey=?', (bind,)).fetchall()

for db in sys.argv[1:] or ['app/src/main/assets/start.obk', 'app/src/main/assets/start.obk_bak']:
    con = sqlite3.connect(f'file:{ROOT / db}?mode=ro', uri=True)
    cur = con.cursor()
    print(f'===== {db} =====')
    for name, moves in LINES.items():
        board = [list(r) for r in START]
        red = True
        seq = []
        for mv in moves:
            hits = []
            for mirror in (False, True):
                k = zobrist(board, red, mirror)
                if mirror and k == zobrist(board, red, False):
                    continue
                for vm, vs in q(cur, k):
                    f, t = (mirror_sq(vm >> 8), mirror_sq(vm & 255)) if mirror else (vm >> 8, vm & 255)
                    hits.append((oc.SQ_TO_SAN[f] + oc.SQ_TO_SAN[t], vs))
            seq.append(f'{mv}:{len(hits)}')
            if not hits:
                break
            board = apply_iccs(board, mv)
            red = not red
        print(f'  {name}: {" ".join(seq)}')
    con.close()
