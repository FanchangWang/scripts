"""对局状态与数据结构。

集中定义跨模块传递的棋局数据：红黑方、走法、格子变动、帧分类结果、对局状态。
控制层（GameSession）读写 GameState，纯函数模块只读。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Literal, NamedTuple

from xiangqi_bot.board import Board, make_empty_board


class Side(StrEnum):
    """红黑方。StrEnum 兼容现有 "red"/"black" 字符串与 JSON 序列化。"""

    RED = "red"
    BLACK = "black"

    @property
    def opponent(self) -> Side:
        return Side.BLACK if self is Side.RED else Side.RED

    @property
    def cn(self) -> str:
        return "红" if self is Side.RED else "黑"


class Phase(StrEnum):
    """对局阶段。StrEnum 兼容现有 "开局"/"残局" 字符串与 JSON 序列化。"""

    OPENING = "开局"
    ENDGAME = "残局"


class Change(NamedTuple):
    """一格变动：(行, 列, 旧棋子, 新棋子)。"""

    r: int
    c: int
    old: str | None
    new: str | None


@dataclass(frozen=True)
class Move:
    """一步棋：起子格 -> 落子格，走动棋子，被吃棋子（如有）。"""

    src: tuple[int, int]
    dst: tuple[int, int]
    piece: str
    captured: str | None = None


# ---------- 帧分类结果（classifier 纯函数返回） ----------


class FrameResult(StrEnum):
    """单帧棋盘变动分类结果。"""

    SELF_DONE = "self_done"  # 我方走棋成功
    SELF_THEN_ENEMY = "self_then_enemy"  # 我方走完 + 敌方也走完
    LIFTED_ONLY = "lifted_only"  # 提起未落（仅最后一帧有效）
    STATIONARY = "stationary"  # 无变动
    TRANSIENT = "transient"  # 有变动但无法分类
    RESIGN_SUSPECT = "resign_suspect"  # 双方将帅缺失（疑似结束）


@dataclass(frozen=True)
class FrameClass:
    """单帧分类结果 + 附带的走法数据。"""

    result: FrameResult
    self_move: Move | None = None
    enemy_move: Move | None = None


class VerifyOutcome(StrEnum):
    """_verify 多帧校验后的最终结论（控制层返回给 _do_move）。"""

    DONE_OK = "done_ok"
    DONE_END = "done_end"
    LIFTED_ONLY = "lifted_only"
    STATIONARY = "stationary"  # 全 n==0，建议外层重走 ADB
    TRANSIENT = "transient"  # 有变动没分类，不建议重走


class EnemyResult(StrEnum):
    """敌方检测单帧的非走法结论。"""

    LIFTED = "lifted"  # 提起未落
    NOISY = "noisy"  # 有变动但无法推断完整走法
    SILENT = "silent"  # 无变动


# 敌方检测单帧结论：推断出走法 / 提子 / 噪声 / 无变动
EnemyFrame = Move | EnemyResult


class ResignResult(StrEnum):
    """认输/结束检测单帧结论。"""

    CONFIRMED = "confirmed"  # 连续帧达阈值，确认对局结束
    SUSPECT = "suspect"  # 疑似结束，继续观察
    NONE = "none"  # 将帅健在，无嫌疑


# 和棋决策
DrawDecision = Literal["accept", "reject"]


# ---------- 对局状态 ----------


@dataclass
class GameState:
    """一局棋的全部棋局状态。控制层读写，纯函数只读。

    my_side/turn/phase 非可选：未初始化期间为占位值，仅 initialized=True 后有意义；
    flow 各入口保证 turn 已定（start 推断/确认、自动下一局残局固定红先）。
    """

    board: Board = field(default_factory=make_empty_board)
    prev_board: Board | None = None
    my_side: Side = Side.RED
    turn: Side = Side.RED  # 行棋方（默认红先占位）
    phase: Phase = Phase.OPENING
    initialized: bool = False  # 本轮 _initialize 是否已成功同步棋盘（驱动 _status idle）
    halfmove_clock: int = 0
    game_over: bool = False
    highlight: list[tuple[int, int]] = field(default_factory=list)
    last_move: str | None = None
    last_eval_score: int = 0
    # 检测瞬态（每次检测会话由控制层重置）
    resign_streak: int = 0
    noisy_count: int = 0
    lift_logged: bool = False

    def reset(self) -> None:
        """重置到新建状态（全量同步/下一局前调用）。"""
        self.board = make_empty_board()
        self.prev_board = None
        self.my_side = Side.RED
        self.turn = Side.RED
        self.phase = Phase.OPENING
        self.initialized = False
        self.halfmove_clock = 0
        self.game_over = False
        self.highlight = []
        self.last_move = None
        self.last_eval_score = 0
        self.resign_streak = 0
        self.noisy_count = 0
        self.lift_logged = False

    def snapshot_prev(self) -> None:
        """把当前 board 深拷贝为 prev_board（每轮次开头调用）。"""
        self.prev_board = [row[:] for row in self.board]
