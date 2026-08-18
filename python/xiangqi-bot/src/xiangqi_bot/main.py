"""入口：启动 FastAPI 网页服务 + pywebview/浏览器。"""

import argparse
import socket
import threading
import time
from pathlib import Path

import uvicorn

ICON_PATH = Path(__file__).resolve().parent / "web" / "favicon.ico"


def _lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="xiangqi-bot",
        description="中国象棋 Bot 网页版 — 通过 ADB 控制手机自动下棋",
    )
    p.add_argument(
        "-l",
        "--listen",
        choices=["lan", "local"],
        default="lan",
        help="监听模式：lan=局域网(0.0.0.0) local=本机(127.0.0.1)（默认: lan）",
    )
    p.add_argument(
        "-p",
        "--port",
        type=int,
        default=8900,
        help="监听端口（默认: 8900）",
    )
    p.add_argument(
        "-n",
        "--no-open",
        action="store_true",
        help="不自动打开浏览器/窗口",
    )
    p.add_argument(
        "-b",
        "--browser",
        action="store_true",
        help="使用默认浏览器打开（默认: webview 独立窗口）",
    )
    return p.parse_args()


def main() -> None:
    args = _parse_args()
    host = "127.0.0.1" if args.listen == "local" else "0.0.0.0"
    port = args.port
    ip = _lan_ip()
    print("=" * 46)
    print("  中国象棋 Bot 网页版")
    print(f"  本机访问：http://127.0.0.1:{port}")
    if args.listen == "lan":
        print(f"  手机访问：http://{ip}:{port}  （需同一局域网）")
    print("  Ctrl+C 退出")
    print("=" * 46)

    if args.browser:
        import webbrowser

        url = f"http://127.0.0.1:{port}"
        threading.Thread(target=webbrowser.open, args=(url,), daemon=True).start()
        uvicorn.run("xiangqi_bot.server:app", host=host, port=port, log_level="info")
        return

    if args.no_open:
        uvicorn.run("xiangqi_bot.server:app", host=host, port=port, log_level="info")
        return

    server_thread = threading.Thread(
        target=uvicorn.run,
        args=("xiangqi_bot.server:app",),
        kwargs={"host": host, "port": port, "log_level": "info"},
        daemon=True,
    )
    server_thread.start()

    time.sleep(0.8)
    url = f"http://127.0.0.1:{port}"

    try:
        import webview

        webview.create_window(
            "中国象棋 Bot",
            url,
            width=1220,
            height=1000,
            min_size=(400, 300),
        )
        icon = str(ICON_PATH) if ICON_PATH.exists() else None
        webview.start(icon=icon)
    except ImportError:
        print("pywebview 未安装，回退到默认浏览器")
        import webbrowser

        webbrowser.open(url)
        server_thread.join()


if __name__ == "__main__":
    main()
