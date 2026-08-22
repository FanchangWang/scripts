"""对局状态机：同步 / 自动对弈 / 中断。

由服务端的单个后台 worker 线程调用；interrupt() 可从其它线程安全调用
（打断自动对弈循环与轮次确认等待）。日志与棋盘状态通过回调推给网页。

本类是薄控制层：持有 GameState + IO 类（Capture/AutoNext/Engine），
主循环编排「截图 → 识别 → 分类 → 应用 → 引擎 → 点击」，不承载纯决策逻辑
（分类/走法/开局分析见 classifier/moves/opening 等纯函数模块）。
"""

from __future__ import annotations

import sys
import threading
import time
import traceback
from collections.abc import Callable
from pathlib import Path

from numpy import ndarray

from xiangqi_bot import engine, vision
from xiangqi_bot.adb_client import Device
from xiangqi_bot.board import (
    Board,
    fen_of_board,
    grid_to_square,
    piece_label,
)
from xiangqi_bot.config import (
    AUTO_NEXT_GAME,
    DRAW_REJECT_CP,
    ENEMY_NOISY_MAX,
    ENEMY_RECHECK_WAIT_MS,
    ENGINE_MATE_PROBE_MS,
    ENGINE_MOVETIME_MS,
    MOVE_SETTLE_MS,
    MOVE_VERIFY_COUNT,
    RESIGN_CONFIRM_COUNT,
    RESIGN_SUSPECT_WAIT_MS,
    SELF_MOVE_ATTEMPTS,
    TAP_HOLD_INTERVAL_MS,
)
from xiangqi_bot.game import classifier, draw, moves, opening, recognition
from xiangqi_bot.game.auto_next import AutoNext
from xiangqi_bot.game.capture import Capture
from xiangqi_bot.game.state import (
    Change,
    DrawDecision,
    EnemyResult,
    FrameResult,
    GameState,
    Move,
    Phase,
    ResignResult,
    Side,
    VerifyOutcome,
)

LogFn = Callable[[str, str], None]  # (kind, 消息)，kind: info/ok/warn/error/move/enemy/gameover
StateFn = Callable[[dict], None]
AskTurnFn = Callable[[], None]  # 请求网页弹窗选择当前轮次


class GameSession:
    def __init__(
        self,
        device: Device,
        log: LogFn,
        on_state: StateFn | None = None,
        ask_turn: AskTurnFn | None = None,
    ) -> None:
        self.device: Device = device  # ADB 设备实例
        self._log = log  # 日志回调 (kind, msg)
        self._on_state = on_state  # 状态推送回调
        self._ask_turn_cb = ask_turn  # 请求网页确认轮次的回调
        self.templates = vision.load_templates()  # 棋子模板字典（14 张 60x60）
        self.engine = engine.Engine()  # pikafish UCI 引擎客户端

        # 棋局状态
        self.state = GameState()

        # IO 类
        self.capture = Capture(
            device,
            self.templates,
            log,
            decide_draw=self._decide_draw,
            should_continue=lambda: (
                self._running and not self._interrupt.is_set() and not self.state.game_over
            ),
        )
        self.auto_next_handler = AutoNext(
            device,
            self.capture,
            self.templates,
            log,
            should_continue=lambda: not self._interrupt.is_set(),
            auto_next_enabled=lambda: self.auto_next_game,
        )

        # 流程控制（非棋局状态）
        self._running = False  # 自动对弈循环是否进行中
        self._auto_next = False  # 自动下一局流程进行中（网页端据此保持按钮状态不变）
        self.auto_next_game = AUTO_NEXT_GAME  # 对局结束后是否自动下一局（网页开关可实时修改）
        self._interrupt = threading.Event()  # 中断自动对弈的事件
        self._turn_answer: str | None = None  # 网页弹窗返回的轮次确认答案
        self._turn_event = threading.Event()  # 等待轮次确认的事件

    # ---------- 公共接口（worker 线程调用） ----------

    def interrupt(self) -> None:
        """中断自动对弈（线程安全，从任意线程可调）"""
        self._running = False
        self._interrupt.set()

    def answer_turn(self, answer: str | None) -> None:
        """网页弹窗确认（answer 为 "start"/"no"）"""
        self._turn_answer = answer
        self._turn_event.set()

    def set_auto_next(self, enable: bool) -> None:
        """实时开关自动下一局（线程安全），对局结束判定时取最新值"""
        self.auto_next_game = bool(enable)
        self._emit()

    def close(self) -> None:
        """关闭引擎进程"""
        self.engine.close()

    def start(self) -> None:
        """开始棋局：截图同步棋盘状态，之后自动开始对弈。

        整个初始化流程包 try/except：任何阶段抛异常都打印 error + 堆栈，
        并推送 stopped 状态，前端可再点「开始棋局」重试。
        """
        self._interrupt.clear()
        try:
            corrected = self.capture.grab()
            if corrected is None:
                self._emit()
                return
            self.state.reset()
            if not self._initialize(corrected):
                self._emit()  # 判方失败
                return

            # 轮次：开局可自动推断（全默认位红先/对方刚走一步）；无法推断时弹窗确认
            inferred = opening.infer_turn(self.state.board, self.state.my_side, self.state.phase)
            confirmed = False
            if inferred is not None:
                self.state.turn = inferred
            elif self._confirm_start():
                confirmed = True
                self.state.turn = self.state.my_side  # 用户确认我方先走
            else:
                self._log("ok", "未开始对弈")
                self._emit()
                return

            self._emit()

            # 自动开始对弈（弹窗确认 或 开局自动）
            if confirmed or self.state.phase == Phase.OPENING:
                self._start_flow()
        except Exception as exc:  # noqa: BLE001 — 初始化阶段异常统一兜底
            self._log("error", f"启动棋局异常：{exc!r}")
            traceback.print_exc()
            self._running = False
            self._auto_next = False
            self._emit()

    # ---------- 自动对弈 ----------

    def _start_flow(self) -> None:
        """启动自动对弈：设置运行标志，进入主循环。

        外层 try/except/finally 保证任何业务异常都不击穿到上层 worker，
        finally 里必然把 _running 置 False + _emit()，前端按钮自动回到可用态。
        """
        self._running = True
        self._emit()
        try:
            self.engine.newgame()
            self._flow()
        except Exception as exc:  # noqa: BLE001 — 顶层兜所有业务异常
            self._log("error", f"自动对弈异常终止：{exc!r}")
            traceback.print_exc()
        finally:
            self._running = False
            self._auto_next = False
            self._emit()
        self._log("info", "flow 流程结束！")

    def _flow(self) -> None:
        """自动对弈主循环：我方走棋 <-> 敌方走棋检测交替，结束后按需自动下一局。"""
        while True:
            if not self._running:
                break
            if self._interrupt.is_set():
                break
            # 每轮次快照：固定当前 board 为变动对比基准
            self.state.snapshot_prev()
            if self.state.turn != self.state.my_side:
                self._wait_for_enemy_move()
            else:
                if not self._do_move():
                    # _do_move 返回 False：对局正常结束（game_over=True）或走棋失败
                    if self.state.game_over:
                        self._log("info", "我方走棋阶段检测到对局结束")
                    else:
                        self._running = False
                        self._log("info", "走棋失败，自动对弈已暂停，可点击「开始棋局」重试")
                        break
            if self.state.game_over:
                if self.auto_next_game:
                    if not self._auto_next_game():
                        break
                else:
                    self._log("warn", "自动下一局未开启")
                    break
        self._emit()

    # ---------- 初始化 ----------

    def _initialize(self, corrected: ndarray) -> bool:
        """从矫正图全量初始化棋盘（识别→判方→阶段→写 state→日志），返回是否成功。

        轮次（state.turn）不在此处理：start 与自动下一局各有后续逻辑
        （弹窗确认 / 残局固定红先），保持各自闭环。
        """
        board = vision.analyze_board(corrected, self.templates)
        my_side = opening.detect_side(board)
        if my_side is None:
            self._log(
                "error",
                "无法判断我方红黑方（未识别到将/帥），请检查棋盘画面后重新同步",
            )
            return False
        phase = opening.detect_phase(board, my_side)
        self.state.board = board
        self.state.my_side = my_side
        self.state.phase = phase
        self.state.initialized = True
        self._log("ok", f"我方为{my_side.cn}方，当前棋盘为{phase}")
        return True

    # ---------- 我方走棋 ----------

    def _do_move(self) -> bool:
        """我方走棋主流程：计算着法 → 2 次点击尝试 → 每次 MOVE_VERIFY_COUNT 帧校验分类。"""
        pending = self._compute_move()
        if pending is None:
            return False
        fen, move = pending
        r1, c1, r2, c2, piece = self._unpack_move(fen, move)
        if piece is None:
            return False
        self.state.resign_streak = 0
        for _ in range(SELF_MOVE_ATTEMPTS):
            if not self._attempt_move(r1, c1, r2, c2):
                continue
            outcome = self._verify(r1, c1, r2, c2, piece)
            if outcome == VerifyOutcome.DONE_OK:
                return True
            if outcome == VerifyOutcome.DONE_END:
                return False
            # 提起未落：补点一次落子，立刻重跑整轮校验（不消耗 attempts）
            if outcome == VerifyOutcome.LIFTED_ONLY:
                self._log("info", "尝试落子（提起未落）")
                self.capture.tap(r2, c2)
                time.sleep(TAP_HOLD_INTERVAL_MS / 1000)
                retry = self._verify(r1, c1, r2, c2, piece)
                if retry == VerifyOutcome.DONE_OK:
                    return True
                if retry == VerifyOutcome.DONE_END:
                    return False
                # retry 没成功：交给下一轮整步重走
            if outcome == VerifyOutcome.TRANSIENT:
                break  # 有变动但没分类，不建议重走
        self._log("warn", "走棋尝试失败，未检测到走棋成功")
        return False

    def _compute_move(self) -> tuple[str, str] | None:
        """调用引擎计算最优着法并返回 (FEN, move)。"""
        if not self.state.initialized:
            # 防御：占位 turn 不允许流入 FEN；正常流程各入口已保证 initialized + turn 已定
            self._log("warn", "_compute_move：棋盘未初始化，无法生成着法")
            return None
        fen = fen_of_board(
            self.state.board,
            self.state.my_side,
            self.state.turn,
            self.state.halfmove_clock,
        )
        self._log("info", f"生成 FEN：{fen}")
        self._log("info", "计算着法...")
        try:
            move, score = self.engine.best_move(fen)
        except engine.EngineError as exc:
            self._log("error", f"引擎错误：{exc}（_compute_move 返回 None）")
            return None
        if move is None:
            # 自愈：用较短 movetime 重试一次（过滤视觉误识别导致的临时无着法）
            short_time = ENGINE_MOVETIME_MS * 2 // 3
            self._log("warn", f"引擎无可用着法，用 {short_time}ms 短时限重试...")
            try:
                move, score = self.engine.best_move(fen, short_time)
            except engine.EngineError as exc:
                self._log("error", f"重试引擎错误：{exc}（_compute_move 返回 None）")
                return None
        if move is None:
            self._log("warn", "引擎无可用着法（对局可能已结束）")
            self._finish_game("引擎判定我方无路可走，对局结束")
            return None
        self.state.last_eval_score = score
        self._log("info", f"引擎着法：{move}（评估分 {score}）")
        return fen, move

    def _unpack_move(self, fen: str, move: str) -> tuple[int, int, int, int, str | None]:
        """解析 (fen, move) → (r1,c1,r2,c2,piece)，piece 无效时返回 None。"""
        from xiangqi_bot.board import square_to_grid

        my_side = self.state.my_side
        r1, c1 = square_to_grid(move[0:2], my_side)
        r2, c2 = square_to_grid(move[2:4], my_side)
        piece = self.state.board[r1][c1]
        if piece is None:
            self._log(
                "warn",
                f"引擎着法 {move} 起点无我方棋子，棋盘数据可能已过期，请点击「开始棋局」重同步",
            )
            return r1, c1, r2, c2, None
        self.state.last_move = move
        self.state.highlight = [(r1, c1), (r2, c2)]
        captured = self.state.board[r2][c2]
        capture_note = f"（吃{piece_label(captured)}）" if captured else ""
        self._log(
            "move",
            f"走棋 {move}：{piece_label(piece)} "
            f"{grid_to_square(r1, c1, my_side)} -> {grid_to_square(r2, c2, my_side)}{capture_note}",
        )
        return r1, c1, r2, c2, piece

    def _attempt_move(self, r1: int, c1: int, r2: int, c2: int) -> bool:
        """ADB 点击起子 + 延时 + 点击落子。"""
        if not self.capture.tap(r1, c1):
            return False
        time.sleep(TAP_HOLD_INTERVAL_MS / 1000)
        return self.capture.tap(r2, c2)

    def _verify(self, r1: int, c1: int, r2: int, c2: int, piece: str) -> VerifyOutcome:
        """MOVE_VERIFY_COUNT 帧逐帧校验分类，返回最终结论。"""
        expected = Move((r1, c1), (r2, c2), piece)
        stationary = True  # 全 n==0 则 True
        lifted_on_last = False  # 只认最后一帧命中提子未落
        for idx in range(MOVE_VERIFY_COUNT):
            time.sleep(MOVE_SETTLE_MS / 1000)
            grabbed = self._grab_board()
            if grabbed is None:
                continue
            if not self._running or self._interrupt.is_set() or self.state.game_over:
                return VerifyOutcome.DONE_END
            new_board, changes = grabbed
            fc = classifier.classify_self_frame(
                changes,
                new_board,
                expected,
                self.state.my_side,
                idx == MOVE_VERIFY_COUNT - 1,
            )
            if fc.result == FrameResult.SELF_DONE and fc.self_move is not None:
                self._apply_self_move(fc.self_move)
                self.state.resign_streak = 0
                self._checkmate_probe()
                return VerifyOutcome.DONE_OK
            if fc.result == FrameResult.SELF_THEN_ENEMY and fc.self_move and fc.enemy_move:
                self._apply_self_then_enemy(fc.self_move, fc.enemy_move)
                self.state.resign_streak = 0
                return VerifyOutcome.DONE_OK
            if fc.result == FrameResult.LIFTED_ONLY:
                lifted_on_last = True
                self.state.resign_streak = 0
                continue
            if fc.result == FrameResult.RESIGN_SUSPECT:
                resign = self._update_resign(new_board)
                if resign == ResignResult.CONFIRMED:
                    self._finish_game("检测到对局结束画面")
                    return VerifyOutcome.DONE_END
                # suspect/none：streak 已更新，继续后续帧
                continue
            if fc.result == FrameResult.TRANSIENT:
                stationary = False
                self.state.resign_streak = 0
            # STATIONARY：保持 stationary=True

        # 5 次分类全没命中，认输续帧确认
        while (
            self.state.resign_streak > 0
            and self._running
            and not self._interrupt.is_set()
            and not self.state.game_over
        ):
            time.sleep(MOVE_SETTLE_MS / 1000)
            grabbed = self._grab_board()
            if grabbed is None:
                continue
            if not self._running or self._interrupt.is_set() or self.state.game_over:
                return VerifyOutcome.DONE_END
            new_board, _updates = grabbed
            resign = self._update_resign(new_board)
            if resign == ResignResult.CONFIRMED:
                self._finish_game("检测到对局结束画面")
                return VerifyOutcome.DONE_END
            if resign == ResignResult.SUSPECT:
                time.sleep(RESIGN_SUSPECT_WAIT_MS / 1000)
                continue
            break

        if lifted_on_last:
            return VerifyOutcome.LIFTED_ONLY
        return VerifyOutcome.STATIONARY if stationary else VerifyOutcome.TRANSIENT

    # ---------- 敌方走棋检测 ----------

    def _wait_for_enemy_move(self) -> None:
        """检测敌方走棋：持续截图，直到敌方走棋完毕、对局结束或用户中断。"""
        self.state.resign_streak = 0
        self.state.noisy_count = 0
        self.state.lift_logged = False
        self._log("info", "检测敌方走棋")
        while self._running and not self._interrupt.is_set() and not self.state.game_over:
            grabbed = self._grab_board()
            if grabbed is None:
                continue
            if not self._running or self._interrupt.is_set() or self.state.game_over:
                break
            new_board, changes = grabbed
            result = classifier.classify_enemy_frame(changes, self.state.my_side)
            if isinstance(result, Move):
                self._apply_enemy_move(result)
                return
            if result == EnemyResult.LIFTED:
                if not self.state.lift_logged:
                    self.state.lift_logged = True
                    self._log("info", "检测到敌方提起棋子")
                self.state.noisy_count = 0
                continue
            if result == EnemyResult.SILENT:
                self.state.lift_logged = False
                self.state.noisy_count = 0
                continue
            # NOISY：认输检测 / 噪声计数
            resign = self._update_resign(new_board)
            if resign == ResignResult.CONFIRMED:
                self._finish_game("检测到对局结束画面")
                return
            if resign == ResignResult.SUSPECT:
                time.sleep(RESIGN_SUSPECT_WAIT_MS / 1000)
                continue
            self.state.lift_logged = False
            self.state.noisy_count += 1
            if changes:
                for line in moves.format_changes(changes, self.state.my_side):
                    self._log("info", line)
            if self.state.noisy_count >= ENEMY_NOISY_MAX:
                self._log(
                    "warn",
                    f"连续 {ENEMY_NOISY_MAX} 帧无法推断敌方完整走法，"
                    "暂停自动对弈，请点击「开始棋局」确认",
                )
                self._running = False
                self._emit()
                return
            time.sleep(ENEMY_RECHECK_WAIT_MS / 1000)
        self._log("info", "已中断检测敌方走棋")

    # ---------- 走棋应用 ----------

    def _apply_self_move(self, move: Move) -> None:
        """n==2/3a 成功：只完成我方走棋，轮到对方走。"""
        self.state.halfmove_clock = moves.apply(self.state.board, move, self.state.halfmove_clock)
        self.state.turn = self.state.my_side.opponent
        self.state.highlight = [move.src, move.dst]
        self._emit()

    def _apply_self_then_enemy(self, self_move: Move, enemy_move: Move) -> None:
        """我方走棋成功 + 敌方已完成一步，轮到我方走。"""
        self.state.halfmove_clock = moves.apply(
            self.state.board, self_move, self.state.halfmove_clock
        )
        self.state.halfmove_clock = moves.apply(
            self.state.board, enemy_move, self.state.halfmove_clock
        )
        self.state.turn = self.state.my_side
        self.state.highlight = [enemy_move.src, enemy_move.dst]
        self._log_move(enemy_move)
        self._emit()

    def _apply_enemy_move(self, move: Move) -> None:
        """敌方走棋：写入 board + 更新 clock + 切轮次 + 高亮 + 日志 + 推送。"""
        self.state.halfmove_clock = moves.apply(self.state.board, move, self.state.halfmove_clock)
        self.state.turn = self.state.my_side
        self.state.highlight = [move.src, move.dst]
        self._log_move(move)
        self._emit()

    def _log_move(self, move: Move) -> None:
        """输出敌方走棋日志并记录 last_move。"""
        self.state.last_move = (
            f"{grid_to_square(*move.src, self.state.my_side)}"
            f"-{grid_to_square(*move.dst, self.state.my_side)}"
        )
        self._log("enemy", moves.format_move(move, self.state.my_side))

    # ---------- 认输 / 绝杀 ----------

    def _update_resign(self, new_board: Board) -> ResignResult:
        """单帧认输检测 + streak 维护。"""
        my_side = self.state.my_side
        if classifier.is_resign_suspect(new_board, my_side):
            self.state.resign_streak += 1
            self._log(
                "info",
                f"疑似对局结束画面（{self.state.resign_streak}/{RESIGN_CONFIRM_COUNT}）",
            )
            if self.state.resign_streak >= RESIGN_CONFIRM_COUNT:
                return ResignResult.CONFIRMED
            return ResignResult.SUSPECT
        self.state.resign_streak = 0
        return ResignResult.NONE

    def _checkmate_probe(self) -> bool:
        """我方走棋成功后探测对方是否被绝杀（仅限 n==2 + infer 命中场景调用）。"""
        opp = self.state.my_side.opponent
        fen = fen_of_board(
            self.state.board,
            self.state.my_side,
            to_move=opp,
            halfmove_clock=self.state.halfmove_clock,
        )
        self._log("info", f"绝杀探测 FEN（{opp.cn}方行棋）：{fen}")
        try:
            mated = self.engine.is_mate(fen, ENGINE_MATE_PROBE_MS)
        except Exception as exc:  # noqa: BLE001 — 引擎假异常降级当未绝杀
            self._log("warn", f"引擎绝杀探测失败，当作未绝杀继续：{exc!r}")
            return False
        if not mated:
            self._log("info", "未绝杀，继续对局")
            return False
        self._finish_game(f"我方绝杀，{opp.cn}方无路可走")
        return True

    # ---------- 和棋决策 ----------

    def _decide_draw(self) -> DrawDecision:
        """根据我方最近一次走棋的引擎评估分决定同意/拒绝和棋。"""
        my_score = self.state.last_eval_score
        decision = draw.decide(my_score, DRAW_REJECT_CP)
        if decision == "reject":
            self._log("info", f"我方占优（{my_score}cp），拒绝和棋")
        else:
            self._log("info", f"均势/劣势（{my_score}cp），同意和棋")
        return decision

    # ---------- 自动下一局 ----------

    def _auto_next_game(self) -> bool:
        """对局结束后自动下一局：结算交互 + 等待摆棋 + 初始化 + engine.newgame。

        返回是否成功进入下一局（矫正帧已在方法内部消费，无需外传）。
        """
        if self._interrupt.is_set():
            return False
        self._log("info", "开始自动下一局")
        self._auto_next = True
        self._emit()
        try:
            corrected = self.auto_next_handler.scan_and_wait()
            if corrected is None:
                return False
            self.state.reset()
            if not self._initialize(corrected):
                return False
            self.engine.newgame()
            if self.state.phase == Phase.ENDGAME:
                # 残局关卡固定红方先行
                self.state.turn = Side.RED
                self._log("ok", "残局模式：轮到红方走棋")
            else:
                inferred = opening.infer_turn(
                    self.state.board, self.state.my_side, self.state.phase
                )
                if inferred is None:
                    # 摆棋中间态等无法推断轮次的局面：暂停自动对弈，等待手动「开始棋局」重新同步
                    self._log(
                        "error",
                        "turn 轮次无法推断，自动对弈已暂停，可点击「开始棋局」重试",
                    )
                    self._running = False
                    return True
                self.state.turn = inferred
                self._log("ok", f"下一局开始：轮到{self.state.turn.cn}方走棋")
            return True
        finally:
            self._auto_next = False
            self._emit()

    # ---------- 截图识别小工具 ----------

    def _grab_board(self) -> tuple[Board, list[Change]] | None:
        """截图 → 弹窗处理 → 矫正 → 识别棋盘，返回 (新布局, 变动列表)。"""
        corrected = self.capture.grab()
        if corrected is None:
            return None
        return recognition.analyze(corrected, self.templates, self.state.prev_board)

    # ---------- 交互 / 结束 / 推送 ----------

    def _confirm_start(self) -> bool:
        """无法推断轮次时弹窗确认：是否以我方先走开始对弈。"""
        if self._ask_turn_cb is None:
            return False
        self._turn_event.clear()
        self._turn_answer = None
        self._log("info", "无法自动判断当前轮到哪一方，请确认本轮是否我方开始走棋")
        self._ask_turn_cb()
        while not self._turn_event.wait(0.2):
            if self._interrupt.is_set():
                return False
        return self._turn_answer == "start"

    def _finish_game(self, reason: str) -> None:
        """标记对局结束并通知网页端（含调用路径日志）。"""
        self._log("info", f"[结束触发点] {reason}")
        frames = traceback.extract_stack()[:-1]
        parts = [f"→ {f.name} ({Path(f.filename).name}:{f.lineno})" for f in frames]
        project_frames = [p for p in parts if "xiangqi_bot" in p or "server.py" in p]
        tail = project_frames[-8:] if project_frames else parts[-8:]
        self._log("info", "调用路径：\n  " + "\n  ".join(tail))
        print(
            f"[finish_game] {reason}\n{''.join(traceback.format_stack()[:-1])}",
            file=sys.stderr,
            flush=True,
        )
        self.state.game_over = True
        self._log("gameover", reason)
        self._emit()

    def _status(self) -> str:
        """状态：idle/red/black/over/stopped/auto_next。"""
        if self._auto_next:
            return "auto_next"
        if self.state.game_over:
            return "over"
        if not self.state.initialized:
            return "idle"
        if not self._running:
            return "stopped"
        return "red" if self.state.turn == Side.RED else "black"

    def _emit(self) -> None:
        """向网页端推送当前状态"""
        if self._on_state is None:
            return
        self._on_state(
            {
                "my_side": self.state.my_side,
                "turn": self.state.turn,
                "phase": self.state.phase,
                "game_over": self.state.game_over,
                "status": self._status(),
                "auto_next": self.auto_next_game,
                "board": [list(row) for row in self.state.board],
                "highlight": [[r, c] for r, c in self.state.highlight],
                "last_move": self.state.last_move,
            }
        )
