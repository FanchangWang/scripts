import json
import os
from pathlib import Path

from dotenv import load_dotenv

from stock_save.models import AppSettings, CookieConfig

env = os.environ


def get_settings() -> AppSettings:
    load_dotenv()
    return AppSettings(
        stock_symbol=env.get("STOCK_SYMBOL", "SH603533"),
        wxpusher_app_token=env.get("WXPUSHER_APP_TOKEN", ""),
        wxpusher_uids=env.get("WXPUSHER_UIDS", ""),
    )


def get_cookie(cookie_path: str | Path = "config/cookie.json") -> CookieConfig:
    p = Path(cookie_path)
    if not p.exists():
        return CookieConfig(xq_a_token="", u="")
    with open(p, encoding="utf-8") as f:
        data = json.load(f)
    return CookieConfig(**data)


def save_cookie(
    cfg: CookieConfig, cookie_path: str | Path = "config/cookie.json"
) -> None:
    p = Path(cookie_path)
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(
            {"xq_a_token": cfg.xq_a_token, "u": cfg.u}, f, ensure_ascii=False, indent=2
        )
