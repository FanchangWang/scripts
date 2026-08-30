# -*- coding: utf-8 -*-
"""start.obk 维护脚本：索引一致性诊断 / 清空 vmemo / 修复 idxkey / VACUUM 压缩。

背景（实证结论）：
- 本库 idxkey 索引与表数据不一致（走索引的点查全部漏行），idxmv 健康；
- PRAGMA quick_check 检测不出此问题（quick_check 跳过索引-表内容一致性检查），
  必须用本脚本的「索引 vs 强制全表」抽样对比来验证；
- vmemo（来源备注文本，~16.5MB）与 vindex（工具元数据）对开局库读取无用途，清空可减体积。

用法：
  uv run python scripts/obk_optimize.py check [库路径]      # 只读诊断
  uv run python scripts/obk_optimize.py fix <输入> <输出>   # 清空 vmemo + REINDEX + VACUUM，产出优化副本
                                                         # 追加 --drop-idxmv 可同时删除用不到的 idxmv 索引
"""
import shutil
import sqlite3
import sys
import time
from pathlib import Path

DEFAULT = Path(__file__).resolve().parent.parent / 'app/src/main/assets/start.obk'


def check(path, sample=30):
    p = Path(path)
    con = sqlite3.connect(f'file:{p}?mode=ro', uri=True)
    cur = con.cursor()
    print(f'== 诊断 {p}（{p.stat().st_size/1e6:.1f} MB）==')

    # 索引一致性：走索引 vs 强制全表（+ 列名使表达式不可索引）
    rows = cur.execute(
        "SELECT typeof(vkey), vkey FROM bhobk WHERE rowid IN "
        "(SELECT rowid FROM bhobk ORDER BY RANDOM() LIMIT ?)", (sample,)).fetchall()
    bad = 0
    for t, v in rows:
        if t == 'null':
            continue
        a = cur.execute("SELECT COUNT(*) FROM bhobk WHERE vkey=?", (v,)).fetchone()[0]
        b = cur.execute("SELECT COUNT(*) FROM bhobk WHERE +vkey=?", (v,)).fetchone()[0]
        if a != b:
            bad += 1
    n = len([r for r in rows if r[0] != 'null'])
    verdict = '损坏（必须 REINDEX 或查询时用 +vkey/cast 规避）' if bad else '正常'
    print(f'  idxkey 点查抽样 {n}/{sample}：不一致 {bad} 个 → {verdict}')

    rows = cur.execute(
        "SELECT vmove FROM bhobk WHERE rowid IN "
        "(SELECT rowid FROM bhobk ORDER BY RANDOM() LIMIT 10)").fetchall()
    bad_mv = sum(1 for (v,) in rows
                 if cur.execute("SELECT COUNT(*) FROM bhobk WHERE vmove=?", (v,)).fetchone()[0]
                 != cur.execute("SELECT COUNT(*) FROM bhobk WHERE +vmove=?", (v,)).fetchone()[0])
    print(f'  idxmv  点查抽样 {len(rows)}：不一致 {bad_mv} 个 → {"损坏" if bad_mv else "正常"}')

    stats = cur.execute(
        "SELECT typeof(vmemo), COUNT(*), SUM(LENGTH(vmemo)) FROM bhobk GROUP BY 1").fetchall()
    memo_bytes = sum(s[2] or 0 for s in stats)
    print(f'  vmemo：{stats}（可清空约 {memo_bytes/1e6:.1f} MB 原始数据）')
    print(f'  总行数：{cur.execute("SELECT COUNT(*) FROM bhobk").fetchone()[0]}')
    con.close()
    return bad == 0


def fix(src, dst, drop_idxmv=False):
    src, dst = Path(src), Path(dst)
    if dst.exists():
        dst.unlink()
    t0 = time.time()
    shutil.copyfile(src, dst)
    con = sqlite3.connect(dst)
    con.execute('PRAGMA journal_mode=DELETE')
    n0 = con.execute('SELECT COUNT(*) FROM bhobk').fetchone()[0]
    con.execute('UPDATE bhobk SET vmemo=NULL')
    if drop_idxmv:
        con.execute('DROP INDEX IF EXISTS idxmv')
    con.execute('REINDEX')  # 重建全部索引（idxkey 已损坏，idxmv 重建无害）
    con.commit()
    con.execute('VACUUM')
    n1 = con.execute('SELECT COUNT(*) FROM bhobk').fetchone()[0]
    con.close()
    assert n0 == n1, f'行数变化！{n0} -> {n1}'
    print(f'== 优化完成 {src.name}（{src.stat().st_size/1e6:.1f} MB）'
          f' -> {dst.name}（{dst.stat().st_size/1e6:.1f} MB），'
          f'耗时 {time.time()-t0:.1f}s，行数 {n0} 不变，vmemo 已清空，索引已重建'
          f'{"，idxmv 已删除" if drop_idxmv else ""}')
    return dst


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    drop_idxmv = '--drop-idxmv' in sys.argv
    if not args or args[0] == 'check':
        ok = check(args[1] if len(args) > 1 else DEFAULT)
        sys.exit(0 if ok else 1)
    if args[0] == 'fix':
        if len(args) < 3:
            print(__doc__)
            sys.exit(2)
        dst = fix(args[1], args[2], drop_idxmv)
        print('\n== 对优化副本复检 ==')
        ok = check(dst)
        # 起始局面键走索引验证（修复前 REAL 通道负键走索引 0 行；行数随库而异——
        # 宽库 28 着（旧 77.5 万行库）/ 窄而深库 9 着（293 万行库），>0 即索引恢复）
        con = sqlite3.connect(f'file:{dst}?mode=ro', uri=True)
        n = con.execute('SELECT COUNT(*) FROM bhobk WHERE vkey=?',
                        (7101337512282506414,)).fetchone()[0]
        print(f'  起始局面 vkey 走 idxkey 索引点查：{n} 行（>0 即索引已恢复）')
        con.close()
        sys.exit(0 if ok and n > 0 else 1)
    print(__doc__)
    sys.exit(2)


if __name__ == '__main__':
    main()
