from pathlib import Path

import uvicorn
from fastapi import FastAPI
from starlette.staticfiles import StaticFiles

from stock_save.web.routes import router


def create_app() -> FastAPI:
    app = FastAPI(title="股票分钟线数据服务")

    static_dir = Path(__file__).parent.parent.parent.parent / "static"
    app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")

    app.include_router(router)
    return app


def run() -> None:
    uvicorn.run(
        "stock_save.web.app:create_app", host="0.0.0.0", port=8000, factory=True
    )


def run_dev() -> None:
    uvicorn.run(
        "stock_save.web.app:create_app",
        host="0.0.0.0",
        port=8000,
        reload=True,
        factory=True,
    )


if __name__ == "__main__":
    run_dev()
