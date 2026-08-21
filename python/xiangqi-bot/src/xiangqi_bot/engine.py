"""pikafish UCI 客户端（长进程复用）。

每局只启动一个引擎子进程，走棋之间复用连接（`position fen` + `go movetime`），
避免反复重建进程的开销。`quit` 只在引擎结束前发送一次。
引擎无响应或进程退出（Windows 写管道可能抛 [Errno 22]）时会自动重建并重试一次，
失败一律抛 EngineError，避免裸 OSError 击穿调用方。
"""

import subprocess
import threading
import time
from collections import deque
from contextlib import suppress
from typing import IO

from xiangqi_bot import config


class EngineError(RuntimeError):
    pass


class Engine:
    """pikafish 长进程 UCI 会话（线程安全：调用方需用同一线程串行，内部亦有锁）"""

    # 引擎输出行缓冲上限（防止后台 _drain 线程在无 best_move 调用时无限增长）
    _MAX_LINES = 2000

    def __init__(self) -> None:
        self._proc: subprocess.Popen[str] | None = None
        self._lines: deque[str] = deque(maxlen=self._MAX_LINES)
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
            # 必须严格按"行首是 marker"匹配（去掉两端空白），避免 info 行含 marker 子串
            # （如 "info string hash bestmove cache 1234"）导致提前命中却找不到真实响应。
            stripped = (ln.strip() for ln in reversed(self._lines))
            if any(ln.startswith(marker + " ") or ln == marker for ln in stripped):
                return
            time.sleep(0.005)  # 5ms 轮询：本地管道收发是 ms 级，不需 20ms
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
                self._write(
                    proc.stdin,
                    f"setoption name Rule60MaxPly value {config.ENGINE_RULE60_MAX_PLY}",
                )
                self._write(proc.stdin, "isready")
                self._wait_for("readyok", 15)
            except EngineError:
                self._kill(proc)
                self._proc = None
                raise

    def newgame(self) -> None:
        """通知引擎新对局开始（UCI ucinewgame），清空哈希与搜索状态。

        UCI 最佳实践：每局开始前发送。引擎未启动时先启动；必须在引擎空闲时调用
        （worker 线程串行保证，调用方需确保上一着 bestmove 已返回）。
        """
        self.start()
        assert self._proc is not None
        assert self._proc.stdin is not None
        with self._lock:
            self._lines.clear()
            self._write(self._proc.stdin, "ucinewgame")
            self._write(self._proc.stdin, "isready")
            self._wait_for("readyok", 15)

    def _go(self, fen: str, movetime_ms: int) -> list[str]:
        """发送 position+go 并等待 bestmove，返回本次输出行快照（含 info/bestmove）。

        引擎无响应或进程退出时自动重建并重试（共 3 次），仍失败抛 EngineError。
        """
        for attempt in range(3):
            self.start()
            assert self._proc is not None
            assert self._proc.stdin is not None
            try:
                with self._lock:
                    self._lines.clear()
                    self._write(self._proc.stdin, f"position fen {fen}")
                    self._write(self._proc.stdin, f"go movetime {movetime_ms}")
                    self._wait_for("bestmove", movetime_ms / 1000 + 1)
                    return list(self._lines)
            except (EngineError, OSError) as exc:
                self._restart()
                if attempt < 2:
                    time.sleep(0.5)
                    continue
                raise EngineError(f"引擎异常：{exc}") from exc
        raise EngineError("引擎未返回 bestmove")

    @staticmethod
    def _parse_score(lines: list[str]) -> int:
        """从引擎 info 行解析局面分数（厘兵，正=当前行棋方占优）。

        score cp N 直接取 N；score mate N 映射为 ±(100000-|N|)；无 score 行返回 0。
        取最后一条 info score（最深搜索结果）。
        """
        score = 0
        for line in lines:
            if not line.startswith("info"):
                continue
            tokens = line.split()
            for i, tok in enumerate(tokens):
                if tok == "score" and i + 2 < len(tokens):
                    kind = tokens[i + 1]
                    try:
                        val = int(tokens[i + 2])
                    except ValueError:
                        continue
                    if kind == "cp":
                        score = val
                    elif kind == "mate":
                        score = 100000 - val if val > 0 else -100000 - val
        return score

    def best_move(
        self, fen: str, movetime_ms: int = config.ENGINE_MOVETIME_MS
    ) -> tuple[str | None, int]:
        """发送局面并返回 (bestmove, score)；无着法（终局）返回 (None, 0)。

        score 为引擎 info score cp/mate（厘兵，正=当前行棋方占优；mate 映射 ±100000）。
        引擎无响应（超时）或进程退出（写管道报错）时自动重建进程并重试两次；
        仍失败抛 EngineError。restart 后额外 sleep 0.5s 等待 Windows 子进程资源释放，
        避免短时间连续重启造成管道/句柄串扰。

        等待超时 = 思考时间 + 1 秒缓冲：缓冲覆盖「go movetime 写入 + 线程启动/NNUE 热身
        + stdout 管道 flush + readline 调度」的非思考开销。本地进程管道通信是 ms 级，
        1 秒余量已足够覆盖 Windows 调度抖动 + 管道 flush 延迟；
        重试 3 次意味着即使某一次引擎假卡住（Hash/管道异常），重启后也能恢复。
        """
        lines = self._go(fen, movetime_ms)
        score = self._parse_score(lines)
        for line in lines:
            if line.startswith("bestmove"):
                tokens = line.split()
                if len(tokens) < 2:
                    return None, score
                move = tokens[1]
                return (None if move == "(none)" else move), score
        return None, score

    def is_mate(self, fen: str, movetime_ms: int = config.ENGINE_MATE_PROBE_MS) -> bool:
        """对方在该局面是否无路可走（绝杀/困毙）"""
        move, _score = self.best_move(fen, movetime_ms)
        return move is None

    def close(self) -> None:
        """结束引擎进程（`quit` 只在收到所有 bestmove 后发出，避免浅层搜索）"""
        with self._lock:
            proc = self._proc
            self._proc = None
            if proc is not None:
                self._kill(proc)
