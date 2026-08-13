"""终端输入工具。"""


def ask(prompt: str = "") -> str:
    """读取用户输入，stdin 关闭（EOF）时静默退出"""
    try:
        return input(prompt)
    except EOFError:
        raise SystemExit(0) from None
