import logging

import httpx

from stock_save.models import CookieConfig

XUEQIU_API_URL = "https://stock.xueqiu.com/v5/stock/chart/minute.json"

logger = logging.getLogger(__name__)


class XueqiuClient:
    def __init__(self, cookie: CookieConfig, symbol: str = "SH603533") -> None:
        self.cookie = cookie
        self.symbol = symbol

    def get_minute_data(self) -> dict | None:
        headers = {
            "Accept": "application/json",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
            "Cookie": self.cookie.header_value,
        }
        params = {"symbol": self.symbol, "period": "1d"}
        try:
            with httpx.Client(timeout=10) as client:
                resp = client.get(XUEQIU_API_URL, headers=headers, params=params)
                resp.raise_for_status()
                data = resp.json()
                logger.info(f"成功获取股票{self.symbol}的分钟线数据")
                return data
        except Exception as e:
            logger.error(f"获取分钟线数据失败: {e}")
            return None
