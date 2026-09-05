"""common/metrics.py —— Ultralytics 训练指标解析（det/pose 训练共用）。"""

from __future__ import annotations

import csv as _csv
from pathlib import Path


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
