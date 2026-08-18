"""FastAPI 服务端：静态网页 + REST API + WebSocket 事件推送。

后台 worker 线程串行执行对局命令（ADB/引擎都是阻塞调用），
日志、棋盘状态、轮次确认等事件通过 WebSocket 推给网页端。
"""

import asyncio
import queue
import threading
from collections.abc import Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from starlette.responses import Response

from xiangqi_bot import adb_client, config
from xiangqi_bot.game import GameSession


class ConnectReq(BaseModel):
    serial: str | None = None
    ip: str | None = None
    port: int | None = None


class TurnReq(BaseModel):
    turn: str | None = None


class AutoNextReq(BaseModel):
    enable: bool = True


class Hub:
    """持有当前设备与会话，管理 WebSocket 客户端与后台 worker 线程。"""

    def __init__(self) -> None:
        self.loop: asyncio.AbstractEventLoop | None = None
        self.clients: set[WebSocket] = set()
        self.device_name: str | None = None
        self.session: GameSession | None = None
        self._last_state: dict | None = None
        self._queue: queue.Queue[tuple[Callable[..., None], dict]] = queue.Queue()
        self._worker: threading.Thread | None = None

    # ---------- 生命周期 ----------

    def start(self, loop: asyncio.AbstractEventLoop) -> None:
        self.loop = loop
        self._worker = threading.Thread(target=self._run, daemon=True)
        self._worker.start()

    def _run(self) -> None:
        while True:
            fn, kwargs = self._queue.get()
            try:
                fn(**kwargs)
            except Exception as exc:  # noqa: BLE001
                self.log("error", f"后台任务异常：{exc}")

    def post(self, fn: Callable[..., None], **kwargs: object) -> None:
        self._queue.put((fn, kwargs))

    # ---------- 事件广播 ----------

    def broadcast(self, data: dict) -> None:
        if self.loop is None:
            return

        async def _send_all() -> None:
            for ws in list(self.clients):
                try:
                    await ws.send_json(data)
                except Exception:  # noqa: BLE001
                    self.clients.discard(ws)

        asyncio.run_coroutine_threadsafe(_send_all(), self.loop)

    def log(self, kind: str, msg: str) -> None:
        self.broadcast({"type": "log", "kind": kind, "msg": msg})

    def on_state(self, state: dict) -> None:
        self._last_state = state
        self.broadcast({"type": "state", "state": state})

    def ask_turn(self) -> None:
        self.broadcast({"type": "prompt_turn"})

    # ---------- 设备 / 会话 ----------

    def _teardown(self) -> None:
        if self.session is not None:
            self.session.interrupt()
            self.session.close()
            self.session = None
        self.device_name = None

    def open_session(self, device) -> None:
        """在 worker 线程中为已连接设备创建对局会话"""
        self.device_name = device.serial
        self.session = GameSession(device, self.log, self.on_state, self.ask_turn)
        self.log("ok", f"已连接设备 {self.device_name}")
        self.broadcast({"type": "connected", "serial": self.device_name})

    def disconnect(self) -> None:
        """在 worker 线程中断开会话（无线设备同时 adb disconnect）"""
        name = self.device_name
        self._teardown()
        if name is not None and ":" in name:
            try:
                adb_client.disconnect(name)
            except adb_client.AdbError as exc:
                self.log("warn", str(exc))
        self.log("info", "已断开设备")
        self.broadcast({"type": "disconnected"})

    def command(self, name: str) -> None:
        """在 worker 线程中执行对局命令"""
        if self.session is None:
            self.log("error", "尚未连接设备")
            return
        getattr(self.session, name)()

    def interrupt(self) -> None:
        if self.session is not None:
            self.session.interrupt()

    def answer_turn(self, turn: str | None) -> None:
        if self.session is not None:
            self.session.answer_turn(turn)

    def set_auto_next(self, enable: bool) -> None:
        """实时切换自动下一局开关（对弈过程中也允许修改）"""
        if self.session is not None:
            self.session.set_auto_next(enable)


hub = Hub()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    hub.start(asyncio.get_running_loop())
    yield
    if hub.session is not None:
        hub.session.close()


app = FastAPI(title="中国象棋 Bot", lifespan=lifespan)


# ---------- REST API ----------


@app.get("/api/devices")
async def api_devices() -> dict:
    try:
        devices = adb_client.list_devices()
    except adb_client.AdbError as exc:
        return {"devices": [], "connected": hub.device_name, "error": str(exc)}
    return {"devices": devices, "connected": hub.device_name}


@app.post("/api/connect")
def api_connect(req: ConnectReq) -> dict:
    """同步执行 ADB 连接并返回真实结果（阻塞调用，走 Starlette 线程池）"""
    if req.serial is None and (req.ip is None or req.port is None):
        return {"ok": False, "error": "需要 serial 或 ip:port"}
    had_session = hub.session is not None
    hub._teardown()
    try:
        if req.serial is not None:
            device = adb_client.get_device(req.serial)
        elif req.ip is not None and req.port is not None:
            serial = adb_client.connect(req.ip, req.port)
            device = adb_client.get_device(serial)
        else:
            return {"ok": False, "error": "需要 serial 或 ip:port"}
    except adb_client.AdbError as exc:
        hub.log("error", str(exc))
        if had_session:
            hub.broadcast({"type": "disconnected"})
        return {"ok": False, "error": str(exc)}
    hub.post(hub.open_session, device=device)
    return {"ok": True, "serial": device.serial}


@app.post("/api/disconnect")
async def api_disconnect() -> dict:
    hub.post(hub.disconnect)
    return {"ok": True}


@app.post("/api/start")
async def api_start() -> dict:
    hub.interrupt()  # 若自动对弈进行中，先中断
    hub.post(hub.command, name="start")
    return {"ok": True}


@app.post("/api/interrupt")
async def api_interrupt() -> dict:
    hub.interrupt()  # 需立即生效，不走 worker 队列
    return {"ok": True}


@app.post("/api/answer_turn")
async def api_answer_turn(req: TurnReq) -> dict:
    hub.answer_turn(req.turn)
    return {"ok": True}


@app.post("/api/auto_next")
async def api_auto_next(req: AutoNextReq) -> dict:
    hub.set_auto_next(req.enable)
    return {"ok": True}


# ---------- WebSocket ----------


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket) -> None:
    await ws.accept()
    hub.clients.add(ws)
    if hub.device_name is not None:
        await ws.send_json({"type": "connected", "serial": hub.device_name})
    if hub._last_state is not None:
        await ws.send_json({"type": "state", "state": hub._last_state})
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        hub.clients.discard(ws)


# ---------- 静态资源 ----------


class NoCacheStaticFiles(StaticFiles):
    """网页静态文件禁用缓存（避免浏览器沿用旧版 app.js/style.css）"""

    def file_response(self, *args, **kwargs) -> Response:
        response = super().file_response(*args, **kwargs)
        response.headers["Cache-Control"] = "no-cache"
        return response


app.mount("/pieces", StaticFiles(directory=str(config.TEMPLATES_DIR)), name="pieces")

if config.WEB_DIR.is_dir():
    app.mount("/", NoCacheStaticFiles(directory=str(config.WEB_DIR), html=True), name="web")
else:

    @app.get("/")
    async def _root() -> dict:
        return {"error": f"web 目录不存在：{config.WEB_DIR}"}
