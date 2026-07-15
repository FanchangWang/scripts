"""视频编号编码逻辑（移植自 quality.js 的 nn() 函数）。"""

from ningway.config import ALPHABET, BASE


def nn_encode(no: str) -> str:
    """将视频编号编码为下载路径所需的格式。"""
    if not no:
        return ""
    reversed_no = no[::-1]
    encoded_bytes = reversed_no.encode("utf-8")
    key_str = ALPHABET[10:20]
    key_bytes = key_str.encode("utf-8")
    result = bytearray(len(encoded_bytes) + 1)
    result[0] = 0xFF
    for h in range(len(encoded_bytes)):
        u = key_bytes[h % len(key_bytes)]
        m = encoded_bytes[h] ^ u
        m = (m + h) % 256
        m = m ^ u
        result[h + 1] = m
    big_int = int.from_bytes(result, byteorder="big")
    if big_int == 0:
        return ALPHABET[0]
    s = ""
    while big_int > 0:
        s = ALPHABET[big_int % BASE] + s
        big_int //= BASE
    return s
