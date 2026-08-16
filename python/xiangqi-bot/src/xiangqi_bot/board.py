"""棋盘状态与坐标转换。

网格 (r, c)：固定于屏幕（r 行 0..9 自上而下，c 列 0..8 自左而右），
网格 (0, 0) 恒为屏幕左上角格子，其屏幕坐标固定不变。

记谱 a-i/0-9 为 ICCS 绝对坐标系，与 pikafish UCI 方块一致，不受红黑方影响：
e9 恒为黑将、e0 恒为红帥。网格 <-> 记谱的换算与红黑方相关（棋盘打印/屏幕翻转）：
红方 file=a+c、rank=9-r；黑方 file=i-c、rank=r。

FEN 为 ICCS 绝对坐标系（黑方在上），不随我方红黑变化；
从网格生成 FEN 时按红黑方处理行列翻转。
"""

from xiangqi_bot import config

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

# 各棋子开局时的默认网格位置（红色在下、黑色在上），用于轮次推断
START_SQUARES: dict[str, tuple[tuple[int, int], ...]] = {
    "b_r": ((0, 0), (0, 8)),
    "b_n": ((0, 1), (0, 7)),
    "b_b": ((0, 2), (0, 6)),
    "b_a": ((0, 3), (0, 5)),
    "b_k": ((0, 4),),
    "b_c": ((2, 1), (2, 7)),
    "b_p": ((3, 0), (3, 2), (3, 4), (3, 6), (3, 8)),
    "r_R": ((9, 0), (9, 8)),
    "r_N": ((9, 1), (9, 7)),
    "r_B": ((9, 2), (9, 6)),
    "r_A": ((9, 3), (9, 5)),
    "r_K": ((9, 4),),
    "r_C": ((7, 1), (7, 7)),
    "r_P": ((6, 0), (6, 2), (6, 4), (6, 6), (6, 8)),
}


def corrected_center(r: int, c: int) -> tuple[float, float]:
    """网格 -> 矫正棋盘中心坐标（矫正空间恒为 900x1000，与源分辨率无关）"""
    return config.CORRECT_CELL * (c + 0.5), config.CORRECT_CELL * (r + 0.5)


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
