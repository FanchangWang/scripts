"""mixin 共享属性与方法契约。

各 mixin 继承本类以获得共享属性的类型注解与跨 mixin 调用方法的签名，
GameSession 子类负责在 __init__ 中初始化属性、由各 mixin 提供方法实现。
本类不参与运行时初始化，仅供类型检查与 IDE 跳转使用，
故方法体均 `raise NotImplementedError`，子类未实现时调用会立即报错。
"""

from __future__ import annotations

import threading
from collections.abc import Callable
from typing import TYPE_CHECKING

from numpy import ndarray

if TYPE_CHECKING:
    from xiangqi_bot.adb_client import Device
    from xiangqi_bot.board import Board
    from xiangqi_bot.engine import Engine

# 变动格子：(行, 列, 旧棋子, 新棋子)
Change = tuple[int, int, str | None, str | None]
# 一步棋：(起子坐标, 落子坐标, 走动棋子, 被吃棋子)
MoveResult = tuple[tuple[int, int], tuple[int, int], str, str | None]


class _SessionAttrs:
    """所有 mixin 共享的属性与方法契约（由 GameSession 子类初始化与实现）"""

    # ---------- 属性 ----------
    device: Device  # ADB 设备实例
    templates: dict[str, ndarray]  # 棋子模板字典（14 张 60x60）
    engine: Engine  # pikafish UCI 引擎客户端
    _log: Callable[[str, str], None]  # 日志回调 (kind, msg)
    _on_state: Callable[[dict], None] | None  # 状态推送回调
    _ask_turn_cb: Callable[[], None] | None  # 请求网页确认轮次的回调
    _homography: ndarray | None  # 透视矫正单应矩阵
    board: Board  # 棋盘布局 10x9
    prev_board: Board | None  # 上一轮次开始时的棋盘布局快照（变动对比基准）
    my_side: str | None  # 我方红黑方（"red"/"black"）
    _turn: str | None  # 当前轮到哪方走棋
    phase: str | None  # 棋局阶段（开局/中局/残局）
    game_over: bool  # 对局是否结束
    _running: bool  # 自动对弈循环是否进行中
    _auto_next: bool  # 自动下一局流程进行中（网页端按钮状态保持不变）
    auto_next_game: bool  # 对局结束后是否自动下一局（网页开关可实时修改）
    _resign_streak: int  # 连续疑似对局结束的帧计数
    _lift_logged: bool  # 是否已提示过敌方提起棋子（防重复）
    _noisy_count: int  # 连续噪声帧计数
    halfmove_clock: (
        int  # 自上次吃子以来的半回合数（单方走一步+1，吃子归零），写入 FEN 供引擎自然限招判断
    )
    _highlight: list[tuple[int, int]]  # 走棋高亮格 [(r, c), ...]
    _last_move: str | None  # 最近一次着法的记谱表示
    _interrupt: threading.Event  # 中断自动对弈的事件
    _turn_answer: str | None  # 网页弹窗返回的轮次确认答案
    _turn_event: threading.Event  # 等待轮次确认的事件

    # ---------- 跨 mixin 调用的方法契约 ----------

    def _emit(self) -> None:
        raise NotImplementedError

    def _finish_game(self, reason: str) -> None:
        raise NotImplementedError

    def _capture(self) -> ndarray | None:
        raise NotImplementedError

    def _take_screenshot(self) -> ndarray | None:
        raise NotImplementedError

    def _correct_from_raw(self, img: ndarray) -> ndarray | None:
        raise NotImplementedError

    def _dismiss_draw(self, img: ndarray) -> tuple[ndarray, int]:
        raise NotImplementedError

    def _analyze_board_with_prev_board(
        self, corrected: ndarray
    ) -> tuple[list[list[str | None]], list[Change]]:
        raise NotImplementedError

    def _infer_move(self, changes: list[Change]) -> MoveResult | None:
        raise NotImplementedError

    def _apply_enemy_move(self, moved: MoveResult) -> None:
        """合并敌方走棋：把变动更新到 prev_board/board + 应用走棋结果（高亮/日志/轮次）。"""
        raise NotImplementedError

    def _log_move(self, moved: MoveResult) -> None:
        raise NotImplementedError

    def _log_updates(self, updates: list[Change], level: str = "info") -> None:
        raise NotImplementedError

    def _detect_resignation_board(self, new_board: list[list[str | None]]) -> str:
        raise NotImplementedError

    def _compute_move(self) -> tuple[str, str] | None:
        raise NotImplementedError

    def _do_move(self) -> bool:
        raise NotImplementedError

    def _reset(self) -> None:
        raise NotImplementedError

    def _init_from_corrected(self, corrected: ndarray) -> bool:
        raise NotImplementedError

    def _attempt_move(self, r1: int, c1: int, r2: int, c2: int) -> bool:
        raise NotImplementedError

    def _checkmate_probe(self) -> bool:
        raise NotImplementedError

    def _start_flow(self) -> None:
        raise NotImplementedError

    def _detect_side(self) -> str | None:
        raise NotImplementedError

    def _analyze_opening(self) -> tuple[str, str | None]:
        raise NotImplementedError
