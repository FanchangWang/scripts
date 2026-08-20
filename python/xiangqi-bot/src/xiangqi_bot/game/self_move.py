"""我方走棋流程（mixin）。"""

import time

from xiangqi_bot import adb_client, engine, vision
from xiangqi_bot.board import (
    Board,
    fen_of_board,
    grid_to_square,
    piece_color,
    piece_label,
)
from xiangqi_bot.config import (
    ENGINE_MOVETIME_MS,
    MOVE_SETTLE_MS,
    MOVE_VERIFY_COUNT,
    RESIGN_SUSPECT_WAIT_MS,
    TAP_HOLD_INTERVAL_MS,
)
from xiangqi_bot.game._base import Change, MoveResult, _SessionAttrs

SELF_MOVE_ATTEMPTS = 2

RED_CN = "红"
BLACK_CN = "黑"


class SelfMoveMixin(_SessionAttrs):
    """我方走棋：引擎计算、点击、逐帧分类校验并应用结果。"""

    # ---------- 对外：走一步棋 ----------

    def _do_move(self) -> bool:
        """我方走棋主流程：计算着法 → 2 次点击尝试 → 每次 MOVE_VERIFY_COUNT 帧校验分类。

        校验按变动格数分类（0/1/2/3/4/>4），成功分类直接写入内存+推送+绝杀探测后返回。
        MOVE_VERIFY_COUNT 次校验全部失败后，尝试认输检测续帧；仅当全为 n==0
        的"完全不动"帧才允许外层重新点击 ADB。
        """
        pending = self._compute_move()
        if pending is None:
            return False
        fen, move = pending
        r1, c1, r2, c2, piece = self._unpack_move(fen, move)
        if piece is None:
            return False
        self._resign_streak = 0
        for _ in range(SELF_MOVE_ATTEMPTS):
            if not self._attempt_move(r1, c1, r2, c2):
                continue
            only_stationary = self._verify_and_classify(r1, c1, r2, c2, piece)
            if only_stationary == "_done_ok_":
                return True
            if only_stationary == "_done_end_":
                return False
            # 提起未落：在外层只做一次落子补 tap（避免循环内反复点造成 UI 选中/取消抖动），
            # 补点后立刻重跑一整轮 MOVE_VERIFY_COUNT 校验；成功就返回，失败交给外层
            # SELF_MOVE_ATTEMPTS 继续重走（不 break 也不消耗次数）。
            if only_stationary == "_lifted_only_":
                self._tap_cell(r2, c2, "尝试落子（提起未落）")
                time.sleep(TAP_HOLD_INTERVAL_MS / 1000)
                retry_res = self._verify_and_classify(r1, c1, r2, c2, piece)
                if retry_res == "_done_ok_":
                    return True
                if retry_res == "_done_end_":
                    return False
                # retry 没成功：交给 SELF_MOVE_ATTEMPTS 下一轮整步重走（可能 ADB 坐标没响应）
            # 3 次校验失败的兜底直接重走，最终失败就让 flow 暂停；结算画面的认输检测能兜住真绝杀
            if only_stationary is False:
                break
        self._log("warn", "走棋尝试失败，未检测到走棋成功")
        return False

    # ---------- 辅助：着法解析 & ADB 点击 ----------

    def _unpack_move(self, fen: str, move: str) -> tuple[int, int, int, int, str | None]:
        """解析 (fen, move) → (r1,c1,r2,c2,piece)，piece 无效时打印返回 None。"""
        from xiangqi_bot.board import square_to_grid

        r1, c1 = square_to_grid(move[0:2], self.my_side or "red")
        r2, c2 = square_to_grid(move[2:4], self.my_side or "red")
        piece = self.board[r1][c1]
        if piece is None:
            self._log(
                "warn",
                f"引擎着法 {move} 起点无我方棋子，棋盘数据可能已过期，请点击「开始棋局」重同步",
            )
            return r1, c1, r2, c2, None
        self._last_move = move
        self._highlight = [(r1, c1), (r2, c2)]
        captured = self.board[r2][c2]
        capture_note = f"（吃{piece_label(captured)}）" if captured else ""
        self._log(
            "move",
            f"走棋 {move}：{piece_label(piece)} "
            f"{grid_to_square(r1, c1, self.my_side or 'red')} -> "
            f"{grid_to_square(r2, c2, self.my_side or 'red')}{capture_note}",
        )
        return r1, c1, r2, c2, piece

    def _attempt_move(self, r1: int, c1: int, r2: int, c2: int) -> bool:
        """ADB 点击起子 + 延时 + 点击落子。异常返回 False。"""
        if self._homography is None:
            self._log("error", "尚无棋盘坐标信息，请点击「开始棋局」")
            return False
        x1, y1 = vision.tap_xy(self._homography, r1, c1)
        x2, y2 = vision.tap_xy(self._homography, r2, c2)
        self._log("info", f"点击 ({x1},{y1}) -> ({x2},{y2})")
        try:
            adb_client.tap(self.device, x1, y1)
            time.sleep(TAP_HOLD_INTERVAL_MS / 1000)
            adb_client.tap(self.device, x2, y2)
        except adb_client.AdbError as exc:
            self._log("error", str(exc))
            return False
        return True

    def _compute_move(self) -> tuple[str, str] | None:
        """调用引擎计算最优着法并返回 (FEN, move)。

        容错链：
        1. 生成 FEN + INFO 日志（便于排查"引擎判无路可走"时的棋盘状态）
        2. EngineError 降级：打 error 日志返回 None（外层 _start_flow 兜底已不崩溃）
        3. 引擎返回 bestmove (none)：用较短 movetime 重试一次
           （过滤视觉误识别导致的临时无着法），仍 (none) 才判定对局结束。
        """
        if self.my_side is None:
            self._log(
                "warn",
                "_compute_move：my_side 未初始化，无法生成着法（请先「开始棋局」同步棋盘）",
            )
            return None
        fen = fen_of_board(self.board, self.my_side, self._turn)
        self._log("info", f"生成 FEN：{fen}")
        self._log("info", "计算着法...")
        try:
            move = self.engine.best_move(fen)
        except engine.EngineError as exc:
            self._log(
                "error",
                f"引擎错误：{exc}（_compute_move 返回 None，_do_move 将 return False 中止 flow）",
            )
            return None
        if move is None:
            # 自愈：用较短 movetime 重试一次（过滤视觉误识别导致的临时无着法）
            short_time = ENGINE_MOVETIME_MS * 2 // 3
            self._log(
                "warn",
                f"引擎无可用着法，用 {short_time}ms 短时限重试...",
            )
            try:
                move = self.engine.best_move(fen, short_time)
            except engine.EngineError as exc:
                self._log(
                    "error",
                    f"重试引擎错误：{exc}（_compute_move 返回 None）",
                )
                return None
        if move is None:
            self._log("warn", "引擎无可用着法（对局可能已结束）")
            self._finish_game("引擎判定我方无路可走，对局结束")
            return None
        self._log("info", f"引擎着法：{move}")
        return fen, move

    # ---------- 逐帧校验 + 分类 ----------

    def _verify_and_classify(self, r1: int, c1: int, r2: int, c2: int, piece: str) -> bool | str:
        """MOVE_VERIFY_COUNT 帧逐帧校验分类。

        返回值：
        - "_done_ok_"：校验成功并已写入状态
        - "_done_end_"：对局已结束（认输确认）
        - True：全部 n==0 帧，建议外层重走 ADB
        - False：出现过 n>0 帧，不建议外层重走（瞬态/动画干扰）

        认输检测策略：
        - 所有 n 均触发认输判定（残局结束画面棋子少时 n 可能 = 2/3）
        - suspect 额外延时 RESIGN_SUSPECT_WAIT_MS，避免连续 0.5s 瞬态（手部遮挡）
          连涨 3 次导致"无 1/3 2/3 计次直接 confirmed"
        """
        stationary = True  # 全 n==0 则 True
        # 只认「最后一帧命中 _is_lifted_only」才算提起未落：
        # 前 1~2 帧的单次命中通常是动画插值/手部遮挡的瞬态误判，
        # 最后一帧仍保持起点空+终点空才是真实的「游戏端吞第二次 tap → 真没落下」。
        lifted_on_last = False
        for idx in range(MOVE_VERIFY_COUNT):
            time.sleep(MOVE_SETTLE_MS / 1000)
            raw = self._take_screenshot()
            if raw is None:
                continue
            raw, _drawn = self._dismiss_draw(raw)
            if not self._running or self._interrupt.is_set() or self.game_over:
                return "_done_end_"
            corrected = self._correct_from_raw(raw)
            if corrected is None:
                continue
            new_board, updates = self._analyze_board_with_prev_board(corrected)
            n = len(updates)
            is_last_frame = idx == MOVE_VERIFY_COUNT - 1
            if n == 0:
                pass  # 保持 stationary=True，认输检测放在 3 次分类之后
            else:
                stationary = False
                if n == 1:
                    hit = self._is_lifted_only(updates[0], r1, c1, piece, r2, c2, new_board)
                    if hit and is_last_frame:
                        lifted_on_last = True
                        self._resign_streak = 0
                elif n == 2:
                    moved = self._infer_move(updates)
                    if moved is not None and self._moved_matches(moved, r1, c1, r2, c2, piece):
                        self._apply_self_move(moved)
                        self._resign_streak = 0
                        # 按用户方案：只在「我方走棋成功（n==2 且 _infer_move 命中）」时做绝杀校验，
                        # 其他任何分类成功场景（n=3/4 敌方反吃、敌方走棋后、校验兜底等）都不做
                        self._checkmate_probe()
                        return "_done_ok_"
                    # 兜底：我方走棋(吃子)+ 敌方在同一终点反吃，终点 old/new 同色
                    u_lookup2 = {(r, c): (o, n) for r, c, o, n in updates}
                    if (r1, c1) in u_lookup2:
                        r1_o, r1_n = u_lookup2[(r1, c1)]
                        if r1_o == piece and r1_n is None:
                            other = next(
                                ((r, c, o, n) for r, c, o, n in updates if (r, c) != (r1, c1)),
                                None,
                            )
                            if other is not None:
                                xr, xc, ep, xn = other
                                if (
                                    ep is not None
                                    and xn is None
                                    and piece_color(ep) != (self.my_side or "red")
                                    and new_board[r2][c2] == ep
                                ):
                                    self_moved2: MoveResult = ((r1, c1), (r2, c2), piece, None)
                                    enemy_move2: MoveResult = ((xr, xc), (r2, c2), ep, piece)
                                    self._apply_self_then_enemy(self_moved2, enemy_move2)
                                    self._resign_streak = 0
                                    return "_done_ok_"
                elif n == 3:
                    result = self._classify_n3(updates, new_board, r1, c1, r2, c2, piece)
                    if result is not None:
                        self._resign_streak = 0
                        return result
                elif n == 4:
                    result = self._classify_n4(updates, new_board, r1, c1, r2, c2, piece)
                    if result is not None:
                        self._resign_streak = 0
                        return result
                else:  # n > 4：变动过多（结算画面/棋盘重置），有可能是敌方投降
                    resign = self._detect_resignation_board(new_board)
                    if resign == "confirmed":
                        self._finish_game("检测到对局结束画面")
                        return "_done_end_"
                    # "suspect"：streak 已在方法内增长，交后续帧/结尾 while 续帧确认
                    # "none"：streak 已在方法内清零

        # ========== 3 次分类校验全没命中，才认输检测续帧 ==========
        # 走棋中间态（n>=1 分支命中时）已经清零 streak，这里 streak 只可能是 0
        # 或来自「3 次全 n==0 的结算画面」。续帧确认真结束才 finish_game，
        # 确认不是结束（streak 清零）就把结果返回给外层，由 SELF_MOVE_ATTEMPTS 再重走。
        while (
            self._resign_streak > 0
            and self._running
            and not self._interrupt.is_set()
            and not self.game_over
        ):
            time.sleep(MOVE_SETTLE_MS / 1000)
            raw = self._take_screenshot()
            if raw is None:
                continue
            raw, _drawn = self._dismiss_draw(raw)
            if not self._running or self._interrupt.is_set() or self.game_over:
                return False
            corrected = self._correct_from_raw(raw)
            if corrected is None:
                continue
            new_board, _updates = self._analyze_board_with_prev_board(corrected)
            resign = self._detect_resignation_board(new_board)
            if resign == "confirmed":
                self._finish_game("检测到对局结束画面")
                return "_done_end_"
            if resign == "suspect":
                time.sleep(RESIGN_SUSPECT_WAIT_MS / 1000)
                continue
            # resign == "none"：streak 被清零（真阳性消散 / 刚才 suspect 是瞬态）
            break

        # 为走棋失败做兜底：stationary=False（没命中任何分类）但 streak=0，说明「3 次分类全没
        # 命中 + 认输续帧也没 confirm」，此时可能是 n==0 居多（游戏端慢刷屏幕）但实际落子已经
        # 成功，不应在循环里再涨认输 streak（下次外层 attempts 重试能赶上最新帧）。
        # 返回优先级：最后一帧仍命中 "_lifted_only_" > stationary
        if lifted_on_last:
            return "_lifted_only_"
        return stationary

    # ---------- n==1 提子落子补点 ----------

    def _is_lifted_only(
        self,
        change: Change,
        r1: int,
        c1: int,
        piece: str,
        r2: int,
        c2: int,
        new_board: Board,
    ) -> bool:
        """n==1 恰好是我方起点提子未落。

        强约束避免误判：
        1. 起点 (r1,c1): old==我方piece, new==None（提起来了）
        2. 终点 (r2,c2): new_board[r2][c2] != 我方piece（还没落到终点；
           如果终点已是我方piece，但起点判空 → 起点是视觉瞬态误识别（手部遮挡），
           这种场景绝不能补点，否则会把已经落子的棋子又"选中提起"。）
        """
        ur, uc, uold, unew = change
        if (ur, uc) != (r1, c1):
            return False
        if uold != piece or unew is not None:
            return False
        return new_board[r2][c2] != piece

    def _tap_cell(self, r: int, c: int, reason: str) -> None:
        """单独点一个格（提起未落时补落子）。"""
        if self._homography is None:
            return
        x, y = vision.tap_xy(self._homography, r, c)
        self._log("info", f"{reason}：点击 ({x},{y})")
        try:
            adb_client.tap(self.device, x, y)
        except adb_client.AdbError as exc:
            self._log("error", str(exc))

    # ---------- n==2 校验 ----------

    def _moved_matches(
        self,
        moved: MoveResult,
        r1: int,
        c1: int,
        r2: int,
        c2: int,
        piece: str,
    ) -> bool:
        """推断的走法是否与我方着法完全吻合（起点/终点/棋子）。"""
        (mr1, mc1), (mr2, mc2), mp, _mc = moved
        return (mr1, mc1) == (r1, c1) and (mr2, mc2) == (r2, c2) and mp == piece

    # ---------- n==3 三子场景 ----------

    def _find_third_cell(
        self,
        updates: list[Change],
        r1: int,
        c1: int,
        r2: int,
        c2: int,
    ) -> Change | None:
        """从 3 格变动里找出不是 (r1,c1)(r2,c2) 的第三格。"""
        for ch in updates:
            ur, uc, _uo, _un = ch
            if (ur, uc) not in ((r1, c1), (r2, c2)):
                return ch
        return None

    def _classify_n3(
        self,
        updates: list[Change],
        new_board: Board,
        r1: int,
        c1: int,
        r2: int,
        c2: int,
        piece: str,
    ) -> str | None:
        """三子场景分类。成功返回 "_done_ok_"，失败返回 None（走认输检测下次校验）。"""
        u_lookup = {(r, c): (old, new) for r, c, old, new in updates}
        if (r1, c1) not in u_lookup or (r2, c2) not in u_lookup:
            return None
        r1_old, r1_new = u_lookup[(r1, c1)]
        _r2_old, r2_new = u_lookup[(r2, c2)]
        third = self._find_third_cell(updates, r1, c1, r2, c2)
        if third is None:
            return None
        xr, xc, x_old, x_new = third
        # 情况1：我方走棋成功（r1空 r2成piece）+ 额外x原敌方棋消失 → 吃子/动画
        if (
            r1_old == piece
            and r1_new is None
            and r2_new == piece
            and x_old is not None
            and x_new is None
            and piece_color(x_old) != self.my_side
        ):
            self_move: MoveResult = (
                (r1, c1),
                (r2, c2),
                piece,
                x_old if (xr, xc) == (r2, c2) else None,
            )
            self._apply_self_move(self_move)
            return "_done_ok_"
        # 情况2：我方走棋被敌方在 r2 吃掉（r1空 r2成敌方棋e）→ x 是 e 原格
        if (
            r1_old == piece
            and r1_new is None
            and r2_new is not None
            and piece_color(r2_new) != (self.my_side or "red")
        ):
            e_piece = r2_new
            if x_old == e_piece and x_new is None:
                self_moved_3a: MoveResult = ((r1, c1), (r2, c2), piece, e_piece)
                enemy_move3_2: MoveResult = ((xr, xc), (r2, c2), e_piece, piece)
                self._apply_self_then_enemy(self_moved_3a, enemy_move3_2)
                return "_done_ok_"
        # 情况3：我方走棋 r1→r2 成功，敌方另一子 x→r1 占我原位
        if r1_new is not None and piece_color(r1_new) != (self.my_side or "red"):
            e_piece = r1_new
            if r1_old == piece and r2_new == piece and x_old == e_piece and x_new is None:
                self_moved_3b: MoveResult = ((r1, c1), (r2, c2), piece, None)
                enemy_move3_3: MoveResult = ((xr, xc), (r1, c1), e_piece, None)
                self._apply_self_then_enemy(self_moved_3b, enemy_move3_3)
                return "_done_ok_"
        return None

    # ---------- n==4 四子场景 ----------

    def _classify_n4(
        self,
        updates: list[Change],
        new_board: Board,
        r1: int,
        c1: int,
        r2: int,
        c2: int,
        piece: str,
    ) -> str | None:
        """四子：我方 r1→r2（不吃）+ 敌方 x→y（不吃）。"""
        u_lookup = {(r, c): (old, new) for r, c, old, new in updates}
        if (r1, c1) not in u_lookup or (r2, c2) not in u_lookup:
            return None
        r1_old, r1_new = u_lookup[(r1, c1)]
        _r2_old, r2_new = u_lookup[(r2, c2)]
        if not (r1_old == piece and r1_new is None and r2_new == piece):
            return None
        rest = [ch for ch in updates if (ch[0], ch[1]) not in ((r1, c1), (r2, c2))]
        if len(rest) != 2:
            return None
        enemy_changes: list[Change] = rest
        enemy_move4 = self._infer_move(enemy_changes)
        if enemy_move4 is None:
            return None
        self_moved4: MoveResult = ((r1, c1), (r2, c2), piece, None)
        self._apply_self_then_enemy(self_moved4, enemy_move4)
        return "_done_ok_"

    # ---------- 成功写入 ----------

    def _apply_self_move(self, moved: MoveResult) -> None:
        """n==2/3a 成功：只完成我方走棋，轮到对方走。基准快照由 _flow 循环开头维护。

        不直接用 new_board 覆盖 board：如果 new_board 有瞬态误识别（手部遮挡其他棋子），
        会把内存布局污染；必须精确应用我方这一步的 2 个格子。

        注：绝杀校验不再放在这里，调用方按需调用（目前只有 n==2 + _infer_move 分支调用）。
        """
        (r1, c1), (r2, c2), piece, _cap = moved
        self.board[r1][c1] = None
        self.board[r2][c2] = piece
        self._turn = "black" if (self.my_side or "red") == "red" else "red"
        self._highlight = [(r1, c1), (r2, c2)]
        self._emit()

    def _apply_self_then_enemy(self, self_moved: MoveResult, enemy_moved: MoveResult) -> None:
        """我方走棋成功 + 敌方已完成一步，轮到我方走。

        精确应用：
        1. 我方走棋：self_moved 显式传入（不再依赖 self._highlight）
        2. 敌方走棋：enemy_moved 显式传入
        """
        (sr, sc), (dr, dc), my_piece, _mc = self_moved
        self.board[sr][sc] = None
        self.board[dr][dc] = my_piece
        (xr, xc), (yr, yc), ep, _ec = enemy_moved
        self.board[xr][xc] = None
        self.board[yr][yc] = ep
        self._turn = self.my_side
        self._highlight = [(xr, xc), (yr, yc)]
        self._log_move(enemy_moved)
        self._emit()
