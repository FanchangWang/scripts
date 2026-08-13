"""棋盘状态与坐标转换。

网格 (r, c)：固定于屏幕（r 行 0..9 自上而下，c 列 0..8 自左而右），
网格 (0, 0) 恒为屏幕左上角格子，其屏幕坐标固定不变。

记谱 a-i/0-9 为 ICCS 绝对坐标系，与 pikafish UCI 方块一致，不受红黑方影响：
e9 恒为黑将、e0 恒为红帥。网格 <-> 记谱的换算与红黑方相关（棋盘打印/屏幕翻转）：
红方 file=a+c、rank=9-r；黑方 file=i-c、rank=r。

FEN 为 ICCS 绝对坐标系（黑方在上），不随我方红黑变化；
从网格生成 FEN 时按红黑方处理行列翻转。
"""

import numpy as np

# ========== 10 行 x 9 列 静态网格中心点坐标矩阵 (NumPy 数组) ==========
GRID_CENTERS_NP = np.array(
    [
        # Row 0 (Y = 680)
        [
            [76.0, 680],
            [192.0, 680],
            [308.0, 680],
            [424.0, 680],
            [540.0, 680],
            [656.0, 680],
            [772.0, 680],
            [888.0, 680],
            [1004.0, 680],
        ],
        # Row 1 (Y = 792)
        [
            [75.0, 792],
            [191.25, 792],
            [307.5, 792],
            [423.75, 792],
            [540.0, 792],
            [656.25, 792],
            [772.5, 792],
            [888.75, 792],
            [1005.0, 792],
        ],
        # Row 2 (Y = 904)
        [
            [74.0, 904],
            [190.5, 904],
            [307.0, 904],
            [423.5, 904],
            [540.0, 904],
            [656.5, 904],
            [773.0, 904],
            [889.5, 904],
            [1006.0, 904],
        ],
        # Row 3 (Y = 1016)
        [
            [73.0, 1016],
            [189.75, 1016],
            [306.5, 1016],
            [423.25, 1016],
            [540.0, 1016],
            [656.75, 1016],
            [773.5, 1016],
            [890.25, 1016],
            [1007.0, 1016],
        ],
        # Row 4 (Y = 1129)
        [
            [72.0, 1129],
            [189.0, 1129],
            [306.0, 1129],
            [423.0, 1129],
            [540.0, 1129],
            [657.0, 1129],
            [774.0, 1129],
            [891.0, 1129],
            [1008.0, 1129],
        ],
        # Row 5 (Y = 1242)
        [
            [71.0, 1242],
            [188.25, 1242],
            [305.5, 1242],
            [422.75, 1242],
            [540.0, 1242],
            [657.25, 1242],
            [774.5, 1242],
            [891.75, 1242],
            [1009.0, 1242],
        ],
        # Row 6 (Y = 1356)
        [
            [70.0, 1356],
            [187.5, 1356],
            [305.0, 1356],
            [422.5, 1356],
            [540.0, 1356],
            [657.5, 1356],
            [775.0, 1356],
            [892.5, 1356],
            [1010.0, 1356],
        ],
        # Row 7 (Y = 1470)
        [
            [69.0, 1470],
            [186.75, 1470],
            [304.5, 1470],
            [422.25, 1470],
            [540.0, 1470],
            [657.75, 1470],
            [775.5, 1470],
            [893.25, 1470],
            [1011.0, 1470],
        ],
        # Row 8 (Y = 1585)
        [
            [68.0, 1585],
            [186.0, 1585],
            [304.0, 1585],
            [422.0, 1585],
            [540.0, 1585],
            [658.0, 1585],
            [776.0, 1585],
            [894.0, 1585],
            [1012.0, 1585],
        ],
        # Row 9 (Y = 1700)
        [
            [67.0, 1700],
            [185.25, 1700],
            [303.5, 1700],
            [421.75, 1700],
            [540.0, 1700],
            [658.25, 1700],
            [776.5, 1700],
            [894.75, 1700],
            [1013.0, 1700],
        ],
    ],
    dtype=np.float32,
)

# 棋子 ID（模板文件名去扩展名）-> FEN 字符（黑小写/红大写）
PIECE_FEN: dict[str, str] = {
    "b_r": "r",
    "b_n": "n",
    "b_b": "b",
    "b_a": "a",
    "b_k": "k",
    "b_c": "c",
    "b_p": "p",
    "r_R": "R",
    "r_N": "N",
    "r_B": "B",
    "r_A": "A",
    "r_K": "K",
    "r_C": "C",
    "r_P": "P",
}

FEN_TO_PIECE: dict[str, str] = {v: k for k, v in PIECE_FEN.items()}

# 棋子 ID -> 中文显示字（全角，保证等宽）
PIECE_CN: dict[str, str] = {
    "b_r": "車",
    "b_n": "馬",
    "b_b": "象",
    "b_a": "士",
    "b_k": "將",
    "b_c": "砲",
    "b_p": "卒",
    "r_R": "俥",
    "r_N": "傌",
    "r_B": "相",
    "r_A": "仕",
    "r_K": "帥",
    "r_C": "炮",
    "r_P": "兵",
}

Board = list[list[str | None]]

ROWS = 10
COLS = 9


def grid_to_square(r: int, c: int, my_side: str = "red") -> str:
    """网格 -> 记谱（红方：file=a+c、rank=9-r；黑方：file=i-c、rank=r）

    例：红方 (7,7) -> h2；黑方 (0,2) -> g0。
    """
    if my_side == "black":
        return f"{chr(ord('i') - c)}{r}"
    return f"{chr(ord('a') + c)}{9 - r}"


def square_to_grid(square: str, my_side: str = "red") -> tuple[int, int]:
    """记谱 -> 网格（红方：r=9-rank、c=file-a；黑方：r=rank、c=i-file）

    例：红方 h2 -> (7,7)；黑方 g0 -> (0,2)。
    """
    file = square[0]
    rank = int(square[1:])
    if my_side == "black":
        return rank, ord("i") - ord(file)
    return 9 - rank, ord(file) - ord("a")


def make_empty_board() -> Board:
    return [[None for _ in range(COLS)] for _ in range(ROWS)]


def board_from_fen(fen: str, my_side: str = "red") -> Board:
    """FEN 字符串 -> 棋盘布局（网格为屏幕朝向，与 fen_of_board 互逆）"""
    board = make_empty_board()
    rows = fen.split()[0].split("/")
    for row_idx, row in enumerate(rows):
        grid_r = ROWS - 1 - row_idx if my_side == "black" else row_idx
        c = 0
        for ch in row:
            if ch.isdigit():
                c += int(ch)
            else:
                grid_c = COLS - 1 - c if my_side == "black" else c
                board[grid_r][grid_c] = FEN_TO_PIECE[ch]
                c += 1
    return board


def fen_of_board(board: Board, side: str, to_move: str | None = None) -> str:
    """棋盘布局 -> FEN 字符串。

    `side` 为我方颜色（决定屏幕棋盘行列翻转）；`to_move` 为行棋方（默认等于 side），
    用于"走棋后判断对手是否被绝杀"等需要对方行棋的局面。
    FEN 为 ICCS 绝对坐标系（黑方在 FEN 上方），不随我方红黑变化；
    我方为黑方时（屏幕棋盘翻转），行列均反转后再写入 FEN。
    """
    side_char = {"red": "w", "black": "b"}[to_move if to_move is not None else side]
    rows = range(ROWS - 1, -1, -1) if side == "black" else range(ROWS)
    lines: list[str] = []
    for r in rows:
        parts: list[str] = []
        empty = 0
        cols = range(COLS - 1, -1, -1) if side == "black" else range(COLS)
        for c in cols:
            piece = board[r][c]
            if piece is None:
                empty += 1
            else:
                if empty:
                    parts.append(str(empty))
                    empty = 0
                parts.append(PIECE_FEN[piece])
        if empty:
            parts.append(str(empty))
        lines.append("".join(parts))
    return f"{'/'.join(lines)} {side_char} - - 0 1"


def piece_color(piece_id: str) -> str:
    """棋子 ID -> 'red'/'black'"""
    return "red" if piece_id.startswith("r_") else "black"


def piece_label(piece_id: str) -> str:
    """棋子 ID -> 中文名（如 b_r -> 黑車）"""
    color = "红" if piece_color(piece_id) == "red" else "黑"
    return f"{color}{PIECE_CN[piece_id]}"


def center_screen(r: int, c: int) -> tuple[int, int]:
    """网格 -> 屏幕坐标（四舍五入取整，用于点击/截图）"""
    x, y = GRID_CENTERS_NP[r, c]
    return round(float(x)), round(float(y))
