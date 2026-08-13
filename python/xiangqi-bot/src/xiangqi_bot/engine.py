"""pikafish UCI 客户端。"""

import subprocess
import threading
import time
from contextlib import suppress
from typing import IO

from xiangqi_bot import config


class EngineError(RuntimeError):
    pass


def _write(stream: IO[str], line: str) -> None:
    stream.write(line + "\n")
    stream.flush()


def _drain(stream: IO[str], lines: list[str]) -> None:
    """后台线程持续读取引擎 stdout，避免管道写满阻塞"""
    with suppress(OSError, ValueError):
        lines.extend(stream)


def _wait_for(lines: list[str], marker: str, timeout: float) -> None:
    """等待某个标记出现在引擎输出中，超时抛 EngineError"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(marker in ln for ln in lines):
            return
        time.sleep(0.02)
    raise EngineError(f"引擎响应超时（等待 {marker}）")


def best_move(fen: str, movetime_ms: int = config.ENGINE_MOVETIME_MS) -> str | None:
    """向 pikafish 发送局面并返回 bestmove，无着法（终局）返回 None

    注意：quit 必须在收到 bestmove 之后再发，否则会提前中断搜索导致棋力骤降。
    """
    if not config.PIKAFISH_EXE.exists():
        raise EngineError(f"找不到引擎: {config.PIKAFISH_EXE}")
    try:
        proc = subprocess.Popen(
            [str(config.PIKAFISH_EXE)],
            cwd=str(config.PIKAFISH_DIR),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
    except OSError as exc:
        raise EngineError(f"启动引擎失败: {exc}") from exc
    if proc.stdin is None or proc.stdout is None:
        proc.kill()
        raise EngineError("引擎管道初始化失败")
    stdin = proc.stdin
    stdout = proc.stdout
    lines: list[str] = []
    threading.Thread(target=_drain, args=(stdout, lines), daemon=True).start()
    try:
        _write(stdin, "uci")
        _wait_for(lines, "uciok", 15)
        _write(stdin, f"setoption name Threads value {config.ENGINE_THREADS}")
        _write(stdin, f"setoption name Hash value {config.ENGINE_HASH_MB}")
        _write(stdin, "isready")
        _wait_for(lines, "readyok", 15)
        _write(stdin, f"position fen {fen}")
        _write(stdin, f"go movetime {movetime_ms}")
        _wait_for(lines, "bestmove", movetime_ms / 1000 + 20)
    finally:
        with suppress(OSError):
            _write(stdin, "quit")
        try:
            proc.wait(timeout=5)
        except (subprocess.TimeoutExpired, OSError):
            proc.kill()
    for line in lines:
        if line.startswith("bestmove"):
            tokens = line.split()
            if len(tokens) < 2:
                return None
            move = tokens[1]
            return None if move == "(none)" else move
    raise EngineError("引擎未返回 bestmove")
