"""棋盘变动帧分类（纯函数）。

输入变动列表/新棋盘/预期着法，输出分类结果；不做 IO、不维护跨帧状态。
连续帧的认输 streak 计数由控制层（GameSession）维护，本模块只做单帧判断。
"""

from __future__ import annotations

from xiangqi_bot.board import Board, piece_color
from xiangqi_bot.game import moves
from xiangqi_bot.game.state import (
    Change,
    EnemyFrame,
    EnemyResult,
    FrameClass,
    FrameResult,
    Move,
    Side,
)


def is_resign_suspect(board: Board, my_side: Side) -> bool:
    """双方将/帥同时缺失（单帧疑似结束）。连续帧确认由控制层 streak 负责。"""
    my_general = "r_K" if my_side == Side.RED else "b_k"
    has_my = any(my_general in row for row in board)
    enemy_general = "b_k" if my_side == Side.RED else "r_K"
    has_enemy = any(enemy_general in row for row in board)
    return not has_my and not has_enemy


def classify_self_frame(
    changes: list[Change],
    new_board: Board,
    expected: Move,
    my_side: Side,
    is_last_frame: bool,
) -> FrameClass:
    """我方走棋后单帧分类，按变动格数 n==0/1/2/3/4/>4 分派。"""
    n = len(changes)
    if n == 0:
        return FrameClass(FrameResult.STATIONARY)
    if n == 1:
        if is_last_frame and _is_lifted_only(changes[0], expected, new_board):
            return FrameClass(FrameResult.LIFTED_ONLY)
        return FrameClass(FrameResult.TRANSIENT)
    if n == 2:
        moved = moves.infer(changes)
        if moved is not None and moves.matches(moved, expected):
            return FrameClass(FrameResult.SELF_DONE, self_move=moved)
        enemy = _enemy_recapture_n2(changes, new_board, expected, my_side)
        if enemy is not None:
            # 我方 captured = 落点原有棋子（若为敌子）
            r2_old = next((o for r, c, o, _ in changes if (r, c) == expected.dst), None)
            captured = r2_old if (r2_old is not None and piece_color(r2_old) != my_side) else None
            self_moved = Move(expected.src, expected.dst, expected.piece, captured)
            return FrameClass(FrameResult.SELF_THEN_ENEMY, self_move=self_moved, enemy_move=enemy)
        return FrameClass(FrameResult.TRANSIENT)
    if n == 3:
        result = _classify_n3(changes, expected, my_side)
        if result is not None:
            return result
        return FrameClass(FrameResult.TRANSIENT)
    if n == 4:
        result = _classify_n4(changes, expected, my_side)
        if result is not None:
            return result
        return FrameClass(FrameResult.TRANSIENT)
    # n > 4：变动过多（结算画面/棋盘重置）
    if is_resign_suspect(new_board, my_side):
        return FrameClass(FrameResult.RESIGN_SUSPECT)
    return FrameClass(FrameResult.TRANSIENT)


def classify_enemy_frame(changes: list[Change], my_side: Side) -> EnemyFrame:
    """敌方走棋检测单帧分类：返回 Move / LIFTED / NOISY / SILENT。"""
    n = len(changes)
    if n == 0:
        return EnemyResult.SILENT
    if n == 2:
        moved = moves.infer(changes)
        if moved is not None:
            return moved
        return EnemyResult.NOISY
    if n == 1:
        _r, _c, old, _new = changes[0]
        if old is not None and _new is None and piece_color(old) != my_side:
            return EnemyResult.LIFTED
        return EnemyResult.NOISY
    return EnemyResult.NOISY


# ---------- 内部 ----------


def _is_lifted_only(change: Change, expected: Move, new_board: Board) -> bool:
    """n==1 恰好是我方起点提子未落（强约束避免误判）。"""
    ur, uc, uold, unew = change
    r1, c1 = expected.src
    r2, c2 = expected.dst
    if (ur, uc) != (r1, c1):
        return False
    if uold != expected.piece or unew is not None:
        return False
    return new_board[r2][c2] != expected.piece


def _find_third_cell(changes: list[Change], expected: Move) -> Change | None:
    """从 3 格变动里找出不是预期起/终点的第三格。"""
    for ch in changes:
        ur, uc, _uo, _un = ch
        if (ur, uc) not in (expected.src, expected.dst):
            return ch
    return None


def _enemy_recapture_n2(
    changes: list[Change],
    new_board: Board,
    expected: Move,
    my_side: Side,
) -> Move | None:
    """n==2 兜底：我方起点空 + 另一格敌方起点空 + 终点是敌方棋（敌方同终点反吃）。"""
    u_lookup = {(r, c): (o, n) for r, c, o, n in changes}
    r1, c1 = expected.src
    r2, c2 = expected.dst
    if (r1, c1) not in u_lookup:
        return None
    r1_old, r1_new = u_lookup[(r1, c1)]
    if r1_old != expected.piece or r1_new is not None:
        return None
    other = next(ch for ch in changes if (ch[0], ch[1]) != (r1, c1))
    xr, xc, ep, xn = other
    if ep is not None and xn is None and piece_color(ep) != my_side and new_board[r2][c2] == ep:
        return Move((xr, xc), (r2, c2), ep, expected.piece)
    return None


def _classify_n3(
    changes: list[Change],
    expected: Move,
    my_side: Side,
) -> FrameClass | None:
    """三子场景分类。"""
    u_lookup = {(r, c): (o, n) for r, c, o, n in changes}
    r1, c1 = expected.src
    r2, c2 = expected.dst
    if (r1, c1) not in u_lookup or (r2, c2) not in u_lookup:
        return None
    r1_old, r1_new = u_lookup[(r1, c1)]
    r2_old, r2_new = u_lookup[(r2, c2)]
    third = _find_third_cell(changes, expected)
    if third is None:
        return None
    xr, xc, x_old, x_new = third
    piece = expected.piece

    # 情况1：我方走棋成功（r1空 r2成piece）+ 第三格敌方棋消失（敌方他子提起未落/识别闪动）。
    # 若落点原格是敌方棋则记录吃子；第三格按 _find_third_cell 约定必非起/终点，与吃子无关。
    if (
        r1_old == piece
        and r1_new is None
        and r2_new == piece
        and x_old is not None
        and x_new is None
        and piece_color(x_old) != my_side
    ):
        captured = r2_old if (r2_old is not None and piece_color(r2_old) != my_side) else None
        return FrameClass(
            FrameResult.SELF_DONE, self_move=Move(expected.src, expected.dst, piece, captured)
        )

    # 情况2：我方走棋被敌方在 r2 吃掉（r1空 r2成敌方棋e）→ x 是 e 原格。
    # 我方 captured = 落子前该格内容 r2_old（e_piece 是随后反吃的敌方棋，不属于我方这步）。
    if r1_old == piece and r1_new is None and r2_new is not None and piece_color(r2_new) != my_side:
        e_piece = r2_new
        if x_old == e_piece and x_new is None:
            captured = r2_old if (r2_old is not None and piece_color(r2_old) != my_side) else None
            self_moved = Move(expected.src, expected.dst, piece, captured)
            enemy_moved = Move((xr, xc), expected.dst, e_piece, piece)
            return FrameClass(
                FrameResult.SELF_THEN_ENEMY, self_move=self_moved, enemy_move=enemy_moved
            )

    # 情况3：我方走棋 r1→r2 成功，敌方另一子 x→r1 占我原位（敌方不吃子）
    if r1_new is not None and piece_color(r1_new) != my_side:
        e_piece = r1_new
        if r1_old == piece and r2_new == piece and x_old == e_piece and x_new is None:
            captured = r2_old if (r2_old is not None and piece_color(r2_old) != my_side) else None
            self_moved = Move(expected.src, expected.dst, piece, captured)
            enemy_moved = Move((xr, xc), expected.src, e_piece, None)
            return FrameClass(
                FrameResult.SELF_THEN_ENEMY, self_move=self_moved, enemy_move=enemy_moved
            )

    return None


def _classify_n4(changes: list[Change], expected: Move, my_side: Side) -> FrameClass | None:
    """四子：我方 r1→r2 + 敌方 x→y（双方各自走棋）。"""
    u_lookup = {(r, c): (o, n) for r, c, o, n in changes}
    r1, c1 = expected.src
    r2, c2 = expected.dst
    if (r1, c1) not in u_lookup or (r2, c2) not in u_lookup:
        return None
    r1_old, r1_new = u_lookup[(r1, c1)]
    r2_old, r2_new = u_lookup[(r2, c2)]
    if not (r1_old == expected.piece and r1_new is None and r2_new == expected.piece):
        return None
    rest = [ch for ch in changes if (ch[0], ch[1]) not in (expected.src, expected.dst)]
    if len(rest) != 2:
        return None
    enemy_move = moves.infer(rest)
    if enemy_move is None:
        return None
    captured = r2_old if (r2_old is not None and piece_color(r2_old) != my_side) else None
    self_moved = Move(expected.src, expected.dst, expected.piece, captured)
    return FrameClass(FrameResult.SELF_THEN_ENEMY, self_move=self_moved, enemy_move=enemy_move)
