"""入口：启动 FastAPI 网页服务。"""

import socket
import threading
import webbrowser

import uvicorn

PORT = 8900  # 8000-8101 被 Hyper-V/WinNAT 排他端口段占用，改用 8900


def _lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def main() -> None:
    host, port = "0.0.0.0", PORT
    ip = _lan_ip()
    print("=" * 46)
    print("  中国象棋 Bot 网页版")
    print(f"  本机访问：http://127.0.0.1:{port}")
    print(f"  手机访问：http://{ip}:{port}  （需同一局域网）")
    print("  Ctrl+C 退出")
    print("=" * 46)
    threading.Timer(0.8, lambda: webbrowser.open(f"http://127.0.0.1:{port}")).start()
    uvicorn.run("xiangqi_bot.server:app", host=host, port=port)


if __name__ == "__main__":
    main()
