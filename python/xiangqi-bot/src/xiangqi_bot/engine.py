"""pikafish UCI 客户端（长进程复用）。

每局只启动一个引擎子进程，走棋之间复用连接（`position fen` + `go movetime`），
避免反复重建进程的开销。`quit` 只在引擎结束前发送一次。
引擎无响应或进程退出（Windows 写管道可能抛 [Errno 22]）时会自动重建并重试一次，
失败一律抛 EngineError，避免裸 OSError 击穿调用方。
"""

import subprocess
import threading
import time
from contextlib import suppress
from typing import IO

from xiangqi_bot import config


class EngineError(RuntimeError):
    pass


class Engine:
    """pikafish 长进程 UCI 会话（线程安全：调用方需用同一线程串行，内部亦有锁）"""

    def __init__(self) -> None:
        self._proc: subprocess.Popen[str] | None = None
        self._lines: list[str] = []
        self._lock = threading.Lock()

    def _write(self, stream: IO[str], line: str) -> None:
        try:
            stream.write(line + "\n")
            stream.flush()
        except (OSError, ValueError) as exc:
            raise EngineError(f"引擎进程已退出：{exc}") from exc

    def _drain(self, stream: IO[str]) -> None:
        while True:
            try:
                line = stream.readline()
            except (OSError, ValueError):
                break
            if not line:
                break
            self._lines.append(line)

    def _wait_for(self, marker: str, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if any(marker in ln for ln in self._lines):
                return
            time.sleep(0.02)
        raise EngineError(f"引擎响应超时（等待 {marker}）")

    def _kill(self, proc: subprocess.Popen[str]) -> None:
        with suppress(OSError, EngineError):
            if proc.stdin is not None:
                self._write(proc.stdin, "quit")
            try:
                proc.wait(timeout=5)
            except (subprocess.TimeoutExpired, OSError):
                proc.kill()

    def _restart(self) -> None:
        """强制结束当前引擎进程并清空引用（下次调用 start() 会重建新进程）"""
        with self._lock:
            proc = self._proc
            self._proc = None
            if proc is not None:
                self._kill(proc)

    def start(self) -> None:
        """启动引擎子进程并完成 UCI 初始化（幂等）"""
        with self._lock:
            if self._proc is not None:
                return
            if not config.PIKAFISH_EXE.exists():
                raise EngineError(f"找不到引擎: {config.PIKAFISH_EXE}")
            try:
                proc = subprocess.Popen(
                    [str(config.PIKAFISH_EXE)],
                    cwd=str(config.PIKAFISH_DIR),
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    encoding="utf-8",
                    errors="replace",
                )
            except OSError as exc:
                raise EngineError(f"启动引擎失败: {exc}") from exc
            if proc.stdin is None or proc.stdout is None:
                proc.kill()
                raise EngineError("引擎管道初始化失败")
            self._proc = proc
            threading.Thread(target=self._drain, args=(proc.stdout,), daemon=True).start()
            try:
                self._write(proc.stdin, "uci")
                self._wait_for("uciok", 15)
                self._write(proc.stdin, f"setoption name Threads value {config.ENGINE_THREADS}")
                self._write(proc.stdin, f"setoption name Hash value {config.ENGINE_HASH_MB}")
                self._write(proc.stdin, "isready")
                self._wait_for("readyok", 15)
            except EngineError:
                self._kill(proc)
                self._proc = None
                raise

    def best_move(self, fen: str, movetime_ms: int = config.ENGINE_MOVETIME_MS) -> str | None:
        """发送局面并返回 bestmove；无着法（终局）返回 None。

        引擎无响应（超时）或进程退出（写管道报错）时自动重建进程并重试一次；
        仍失败抛 EngineError。
        """
        for _attempt in range(2):
            self.start()
            assert self._proc is not None
            assert self._proc.stdin is not None
            try:
                with self._lock:
                    self._lines.clear()
                    self._write(self._proc.stdin, f"position fen {fen}")
                    self._write(self._proc.stdin, f"go movetime {movetime_ms}")
                    self._wait_for("bestmove", movetime_ms / 1000 + 20)
                    for line in self._lines:
                        if line.startswith("bestmove"):
                            tokens = line.split()
                            if len(tokens) < 2:
                                return None
                            move = tokens[1]
                            return None if move == "(none)" else move
            except (EngineError, OSError) as exc:
                self._restart()
                if _attempt == 0:
                    continue
                raise EngineError(f"引擎异常：{exc}") from exc
        raise EngineError("引擎未返回 bestmove")

    def is_mate(self, fen: str, movetime_ms: int = config.ENGINE_MATE_PROBE_MS) -> bool:
        """对方在该局面是否无路可走（绝杀/困毙）"""
        return self.best_move(fen, movetime_ms) is None

    def close(self) -> None:
        """结束引擎进程（`quit` 只在收到所有 bestmove 后发出，避免浅层搜索）"""
        with self._lock:
            proc = self._proc
            self._proc = None
            if proc is not None:
                self._kill(proc)
