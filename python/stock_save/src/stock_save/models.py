from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class CookieConfig:
    xq_a_token: str
    u: str

    @property
    def header_value(self) -> str:
        return f"xq_a_token={self.xq_a_token}; u={self.u}"


@dataclass
class AppSettings:
    stock_symbol: str = "SH603533"
    wxpusher_app_token: str = ""
    wxpusher_uids: str = ""
    data_dir: Path = field(default_factory=lambda: Path("data"))
    cookie_config_path: Path = field(default_factory=lambda: Path("config/cookie.json"))


@dataclass
class DayData:
    date: str
    items: list[dict]


@dataclass
class WeekData:
    week_start: str
    week_display: str
    days: list[DayData]


@dataclass
class StockData:
    weeks: list[WeekData]
