"""终端彩色棋盘打印。"""

import ctypes
import subprocess
import sys
from collections.abc import Iterable

from xiangqi_bot.board import COLS, PIECE_CN, ROWS, Board, piece_color

RED = "\x1b[31m"
CYAN = "\x1b[36m"
RESET = "\x1b[0m"
HIGHLIGHT = "\x1b[7m"

EMPTY_CHAR = "　"


def enable_vt() -> None:
    """Windows 终端启用 VT 转义，否则颜色不生效"""
    if sys.platform != "win32":
        return
    try:
        kernel32 = ctypes.windll.kernel32
        handle = kernel32.GetStdHandle(-11)
        mode = ctypes.c_uint32()
        if kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            kernel32.SetConsoleMode(handle, mode.value | 0x0004)
    except OSError:
        subprocess.run("", shell=True)


def _render_cell(piece_id: str | None, highlight: bool) -> str:
    if piece_id is None:
        char = EMPTY_CHAR
        color = ""
    elif piece_color(piece_id) == "red":
        char = PIECE_CN[piece_id]
        color = RED
    else:
        char = PIECE_CN[piece_id]
        color = CYAN
    code = HIGHLIGHT + color if highlight else color
    if code:
        return f" {code}{char}{RESET} "
    return f" {char} "


def print_board(
    board: Board,
    my_side: str | None = None,
    highlight: Iterable[tuple[int, int]] = (),
) -> None:
    """打印棋盘。

    屏幕下方即我方一侧，故始终按网格第 0 行在上、第 9 行在下打印。
    坐标标注为 ICCS 绝对坐标系：红方视角列 a..i、行 9..0（下为 0）；
    我方为黑方时标注翻转（列 i..a、行 0..9），保证我方（黑子）始终在下方。
    """
    hl = set(highlight)
    is_black = my_side == "black"
    labels = [chr(ord("i") - c) if is_black else chr(ord("a") + c) for c in range(COLS)]
    hline = "─" * 4
    axis = "      " + "".join(f"{lab}    " for lab in labels)
    print(axis)
    for i in range(ROWS):
        if i == 0:
            print("    " + "┌" + "┬".join([hline] * COLS) + "┐")
        else:
            print("    " + "├" + "┼".join([hline] * COLS) + "┤")
        rank = i if is_black else ROWS - 1 - i
        cells = [_render_cell(board[i][c], (i, c) in hl) for c in range(COLS)]
        print(f"  {rank} │" + "│".join(cells) + f"│ {rank}")
    print("    " + "└" + "┴".join([hline] * COLS) + "┘")
    print(axis)
