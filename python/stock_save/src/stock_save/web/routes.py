import datetime
import json
import logging
from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates

from stock_save.cli import save_data_to_file
from stock_save.config import get_cookie, get_settings, save_cookie
from stock_save.models import CookieConfig, DayData, StockData, WeekData
from stock_save.xueqiu import XueqiuClient

router = APIRouter()
templates = Jinja2Templates(directory=Path(__file__).parent / "templates")
logger = logging.getLogger(__name__)

WEEKDAY_NAMES = ["周一", "周二", "周三", "周四", "周五"]


def load_week_data(data_dir: Path, weeks: int = 4) -> StockData:
    tz = datetime.timezone(datetime.timedelta(hours=8))
    today = datetime.datetime.now(tz)
    stock_data = StockData(weeks=[])
    current = today

    week_labels = ["本周", "上周"] + [f"{i}周前" for i in range(3, weeks + 1)]

    for i in range(weeks):
        days_since_monday = current.weekday()
        monday = current - datetime.timedelta(days=days_since_monday)
        week_days: list[DayData] = []

        for j in range(5):
            date = monday + datetime.timedelta(days=j)
            if date.date() > today.date():
                break

            file_path = data_dir / f"{date.strftime('%Y-%m-%d')}.json"
            if not file_path.exists():
                continue

            try:
                with open(file_path, encoding="utf-8") as f:
                    raw = json.load(f)
            except (FileNotFoundError, json.JSONDecodeError) as e:
                logger.warning(f"读取文件失败: {file_path}: {e}")
                continue

            items = []
            for item in raw.get("data", {}).get("items", []):
                if ts := item.get("timestamp"):
                    dt = datetime.datetime.fromtimestamp(ts / 1000)
                    items.append(
                        {
                            "time": f"{WEEKDAY_NAMES[j]} {dt.strftime('%H:%M')}",
                            "price": item.get("current"),
                            "timestamp": ts,
                        }
                    )

            if items:
                week_days.append(DayData(date=date.strftime("%m-%d"), items=items))

        if week_days:
            stock_data.weeks.append(
                WeekData(
                    week_start=monday.strftime("%Y-%m-%d"),
                    week_display=week_labels[i]
                    if i < len(week_labels)
                    else f"{i + 1}周前",
                    days=week_days,
                )
            )

        current = monday - datetime.timedelta(days=1)

    return stock_data


@router.get("/", response_class=HTMLResponse)
async def index(request: Request):
    data_dir = get_settings().data_dir
    files = sorted(data_dir.glob("*.json"), reverse=True)[:5]
    weekdays = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
    recent_files = []
    for f in files:
        dt = datetime.datetime.strptime(f.stem, "%Y-%m-%d")
        recent_files.append({"date": f.stem, "weekday": weekdays[dt.weekday()]})
    return templates.TemplateResponse(
        request, "index.html.jinja", {"recent_files": recent_files}
    )


@router.get("/cookie", response_class=HTMLResponse)
async def cookie_page(request: Request):
    return templates.TemplateResponse(request, "cookie.html.jinja")


@router.get("/chart", response_class=HTMLResponse)
async def chart_page(request: Request):
    return templates.TemplateResponse(request, "chart.html.jinja")


@router.get("/api/stock-data")
async def get_stock_data():
    settings = get_settings()
    stock_data = load_week_data(settings.data_dir)
    weeks_json = []
    for w in stock_data.weeks:
        days_json = [{"date": d.date, "items": d.items} for d in w.days]
        weeks_json.append(
            {
                "week_start": w.week_start,
                "week_display": w.week_display,
                "days": days_json,
            }
        )
    return {"weeks": weeks_json}


@router.get("/api/files/recent")
async def get_recent_files():
    data_dir = get_settings().data_dir
    files = sorted(data_dir.glob("*.json"), reverse=True)[:5]
    return {"files": [f.stem for f in files]}


@router.put("/api/cookie")
async def update_cookie(data: dict):
    cfg = get_cookie()
    if xq := data.get("xq_a_token"):
        cfg.xq_a_token = xq
    if u := data.get("u"):
        cfg.u = u
    save_cookie(cfg)
    return {"status": "ok"}


@router.post("/api/cookie/test")
async def test_cookie(data: dict):
    saved = get_cookie()
    xq = data.get("xq_a_token") or saved.xq_a_token
    u = data.get("u") or saved.u
    cfg = CookieConfig(xq_a_token=xq or "", u=u or "")
    settings = get_settings()
    client = XueqiuClient(cfg, settings.stock_symbol)
    result = client.get_minute_data()
    if result and (items := result.get("data", {}).get("items")):
        ts = items[0]["timestamp"]
        dt = datetime.datetime.fromtimestamp(ts / 1000)
        date_str = dt.strftime("%Y-%m-%d")

        tz = datetime.timezone(datetime.timedelta(hours=8))
        now = datetime.datetime.now(tz)
        today_str = now.strftime("%Y-%m-%d")

        save_status: str | None = None
        if today_str != date_str or (today_str == date_str and now.hour >= 15):
            save_status = save_data_to_file(result, settings)

        return {"valid": True, "date": date_str, "save_status": save_status}
    return {"valid": False, "error": "Cookie 无效或网络错误"}
