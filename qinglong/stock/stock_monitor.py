"""
股票监控任务
功能: 监控股票实时数据, 检查交易事件, 计算持仓盈亏

环境变量：
    STOCK_SYMBOL: str - 股票代码, 默认SH603533
    STOCK_BUY_PRICE: float - 购买股票价格, 默认0
    STOCK_BUY_COUNT: int - 购买股票数量, 默认0

cron: */5 9-11,13-15 * * *
"""
import os
from datetime import datetime
import requests

class StockMonitor:
    def __init__(self):
        # 读取配置
        self.stock_symbol = os.getenv('STOCK_SYMBOL', 'SH603533')
        self.stock_buy_price = float(os.getenv('STOCK_BUY_PRICE', 0))
        self.stock_buy_count = int(os.getenv('STOCK_BUY_COUNT', 0))

        # 初始化日志内容
        self.log_content = ''
        self.NAME = '股票Monitor'

        # API配置
        self.api_url = 'https://stock.xueqiu.com/v5/stock/realtime/quotec.json'

    def log(self, content: str, print_to_console: bool = True) -> None:
        """添加日志"""
        if print_to_console:
            print(content)
        self.log_content += content + '\n'

    def push_notification(self) -> None:
        """推送通知"""
        try:
            QLAPI.notify(self.NAME, self.log_content)
        except NameError:
            print(f"\n\n🚀 推送通知\n\n{self.NAME}\n\n{self.log_content}")

    def get_stock_data(self):
        """获取股票实时数据"""
        try:
            headers = {
                'Accept': 'application/json',
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
            }
            params = {'symbol': self.stock_symbol}
            response = requests.get(self.api_url, headers=headers, params=params, timeout=10)
            response.raise_for_status()
            data = response.json()
            print(f"API 获取股票数据: {data}")
            return data['data'][0] if data['data'] else None
        except Exception as e:
            self.log(f"获取股票数据失败: {str(e)}")
            return None

    def check_event_type(self, stock_data):
        """
        检查股票数据事件：竞价、开盘、交易、休市、收盘

        参数:
            stock_data (dict): 股票实时数据

        返回:
            str: 事件类型 ('竞价', '开盘', '交易', '休市', '收盘')
        """
        # 获取当前时间
        api_timestamp = stock_data.get('timestamp')
        api_dt = datetime.fromtimestamp(api_timestamp / 1000)
        api_time = api_dt.strftime('%H:%M')

        if api_dt.hour == 9 and api_dt.minute < 30:
            return '竞价'
        if api_time == '09:30':
            return '开盘'
        if api_time == '15:00':
            return '收盘'
        if (api_dt.hour == 11 and api_dt.minute > 30) or api_dt.hour == 12:
            return '休市'
        return '交易'

    def run(self):
        """主运行方法"""
        # 获取当前时间
        now_dt = datetime.now()
        # 判断是否处于股票交易时间 9:15-11:30, 13:00-15:00
        if not (
            (now_dt.hour == 9 and now_dt.minute >= 15) or
            (now_dt.hour == 10) or
            (now_dt.hour == 11 and now_dt.minute <= 30) or
            (now_dt.hour == 13) or
            (now_dt.hour == 14) or
            (now_dt.hour == 15 and now_dt.minute == 0)
        ):
            self.log(f"时间: {now_dt.strftime('%Y-%m-%d %H:%M:%S')}")
            self.log("当前时间不在股票交易时间，脚本结束")
            return

        # 获取股票数据
        stock_data = self.get_stock_data()
        if not stock_data:
            self.log(f"时间: {now_dt.strftime('%Y-%m-%d %H:%M:%S')}")
            self.log("未能获取股票数据，脚本结束")
            return

        # 转换API时间戳为datetime对象
        api_timestamp = stock_data.get('timestamp')
        api_dt = datetime.fromtimestamp(api_timestamp / 1000)

        # 判断 now_dt 与 api_dt 是否在同一天
        if now_dt.date() != api_dt.date():
            self.log(f"时间: {now_dt.strftime('%Y-%m-%d %H:%M:%S')}")
            self.log("今天非交易日期，脚本结束")
            return

        # 检查事件类型
        event_type = self.check_event_type(stock_data)

        # 获取股票信息
        current_price = stock_data.get('current', 0)
        chg = stock_data.get('chg', 0)

        # 输出股票当前信息
        self.log(f"时间: {api_dt.strftime('%Y-%m-%d %H:%M:%S')}")
        self.log(f"股票 {self.stock_symbol} {event_type}")

        # 计算持仓盈亏
        if self.stock_buy_price > 0 and self.stock_buy_count > 0:
            profit = (current_price - self.stock_buy_price) * self.stock_buy_count
            profit_percent = (current_price / self.stock_buy_price - 1) * 100
            today_profit = chg * self.stock_buy_count
            self.log(f"成本: {self.stock_buy_price} 元/股")
            self.log(f"持仓: {self.stock_buy_count} 股")
            self.log(f"持仓盈亏: {profit:.2f} 元 ({profit_percent:.2f}%)")
            self.log(f"今日盈亏: {today_profit:.2f} 元")
            api_timestamp = stock_data.get('timestamp')
            # 转换为datetime对象
            self.NAME = f"股票:{event_type} {api_dt.strftime('%H:%M')} 价:{current_price} 今:{today_profit:.2f} 总:{profit:.2f}"
        else:
            self.NAME = f"股票:{event_type} {api_dt.strftime('%H:%M')} 价:{current_price} {'涨' if chg >= 0 else '跌'}:{chg} ({stock_data.get('percent', 0)}%)"

        self.log(f"{'收盘价' if event_type == '收盘' else '当前价'}: {current_price}")
        self.log(f"涨跌额: {chg} 元 {stock_data.get('percent', 0)}%")
        self.log(f"开盘价: {stock_data.get('open', 0)}")
        self.log(f"昨日收盘价: {stock_data.get('last_close', 0)}")
        self.log(f"今日最高价: {stock_data.get('high', 0)}")
        self.log(f"今日最低价: {stock_data.get('low', 0)}")

        # 推送通知
        self.push_notification()

if __name__ == '__main__':
    StockMonitor().run()
