"""引擎自愈场景（4 个）。"""

from __future__ import annotations

import pytest

from xiangqi_bot.engine import Engine, EngineError

from .conftest import LogCollector


class FakeStream:
    def __init__(self, fail: bool = False) -> None:
        self.fail = fail
        self.buf: list[str] = []

    def write(self, s: str) -> None:
        if self.fail:
            raise OSError(22, "Invalid argument")
        self.buf.append(s)

    def flush(self) -> None:
        pass


class FakeProc:
    def __init__(self, fail: bool = False) -> None:
        self.stdin = FakeStream(fail)
        self.stdout = None

    def wait(self, timeout: float) -> int:
        return 0

    def kill(self) -> None:
        pass


def _make_engine(
    wait_fail: int = 0, write_fail: int = 0
) -> tuple[Engine, list[FakeProc], list[str]]:
    e = Engine()
    spawned: list[FakeProc] = []
    waits: list[str] = []

    def fake_start() -> None:
        p = FakeProc(fail=len(spawned) < write_fail)
        spawned.append(p)
        e._proc = p

    e.start = fake_start  # type: ignore[method-assign]

    def fake_wait(marker: str, timeout: float) -> None:
        waits.append(marker)
        if len(waits) <= wait_fail:
            raise EngineError(f"引擎响应超时（等待 {marker}）")
        e._lines.append("bestmove h2e2 ponder b9c7")

    e._wait_for = fake_wait  # type: ignore[method-assign]
    return e, spawned, waits


def test_engine(collector: LogCollector) -> None:
    """4 个引擎自愈场景顺序执行"""

    # 场景1：首次超时 -> 自动重启引擎重试成功
    e, spawned, _waits = _make_engine(wait_fail=1)
    move = e.best_move("fen1")
    assert move == "h2e2", f"重试后应返回 bestmove，实际 {move}"
    assert len(spawned) == 2, f"首次失败应重建进程，实际启动 {len(spawned)} 次"

    # 场景2：写管道报错（进程已死）-> 重启重试成功
    e, spawned, _waits = _make_engine(write_fail=1)
    move = e.best_move("fen2")
    assert move == "h2e2", f"重试后应返回 bestmove，实际 {move}"
    assert len(spawned) == 2, f"首次失败应重建进程，实际启动 {len(spawned)} 次"

    # 场景3：两次均失败 -> 抛 EngineError（而非裸 OSError）
    e, spawned, _waits = _make_engine(write_fail=2)
    with pytest.raises(EngineError, match="引擎"):
        e.best_move("fen3")
    assert len(spawned) == 2, f"应尝试两次，实际启动 {len(spawned)} 次"

    # 场景4：终局 (none) 返回 None，不重建
    e, spawned, _waits = _make_engine()

    def fake_wait_none(marker: str, timeout: float) -> None:
        e._lines.append("bestmove (none)")

    e._wait_for = fake_wait_none  # type: ignore[method-assign]
    move = e.best_move("fen4")
    assert move is None, f"终局应返回 None，实际 {move}"
    assert len(spawned) == 1, "正常场景不应重建进程"
    assert e.is_mate("fen5") is True, "is_mate 应复用 best_move"
