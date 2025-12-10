"""
股票监控任务
功能: 监控股票实时数据, 检查交易事件, 计算持仓盈亏

环境变量：
    STOCK_SYMBOL: str - 股票代码, 默认SH603533
    STOCK_BUY_PRICE: float - 购买股票价格, 默认0
    STOCK_BUY_COUNT: int - 购买股票数量, 默认0

cron: 0,30 9-11,13-15 * * *
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
            return data['data'][0] if data['data'] else None
        except Exception as e:
            self.log(f"获取股票数据失败: {str(e)}")
            return None

    def check_event_type(self, stock_data):
        """检查事件类型"""
        if not stock_data:
            return None

        # 获取当前时间
        now = datetime.now()
        current_time = now.strftime('%H:%M')

        # 检查开盘事件
        if current_time == '09:30' and stock_data.get('is_trade', False):
            return '开盘'

        # 检查收盘事件
        if current_time == '15:00' and not stock_data.get('is_trade', True):
            # 计算当天15:00的13位时间戳
            close_time = datetime(now.year, now.month, now.day, 15, 0, 0)
            close_timestamp = int(close_time.timestamp() * 1000)
            if stock_data.get('timestamp') == close_timestamp:
                return '收盘'

        # 检查交易事件
        api_timestamp = stock_data.get('timestamp')
        if api_timestamp:
            # 将API时间戳转换为datetime对象
            api_dt = datetime.fromtimestamp(api_timestamp / 1000)
            # 比较年月日时分是否匹配当前时间
            if api_dt.strftime('%Y-%m-%d %H:%M') == now.strftime('%Y-%m-%d %H:%M'):
                return '交易'

        return None

    def run(self):
        """主运行方法"""
        self.log(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}]")

        # 获取股票数据
        stock_data = self.get_stock_data()
        if not stock_data:
            self.log("未能获取股票数据，脚本结束")
            return

        # 检查事件类型
        event_type = self.check_event_type(stock_data)

        # 获取股票信息
        current_price = stock_data.get('current', 0)
        chg = stock_data.get('chg', 0)

        # 输出股票当前信息
        self.log(f"股票 {self.stock_symbol} {event_type if event_type else '休市'}")

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
            api_dt = datetime.fromtimestamp(api_timestamp / 1000) if api_timestamp else datetime.now()
            self.NAME = f"股票{event_type} {api_dt.strftime('%H:%M')} ¥:{current_price} 今:{today_profit:.2f} 总:{profit:.2f}"

        self.log(f"{'收盘价' if event_type == '收盘' else '当前价格'}: {current_price}")
        self.log(f"涨跌幅: {stock_data.get('percent', 0)}%")
        self.log(f"涨跌额: {chg}")
        self.log(f"开盘价: {stock_data.get('open', 0)}")
        self.log(f"昨日收盘价: {stock_data.get('last_close', 0)}")
        self.log(f"今日最高价: {stock_data.get('high', 0)}")
        self.log(f"今日最低价: {stock_data.get('low', 0)}")

        # 推送通知
        if event_type:
            self.push_notification()

if __name__ == '__main__':
    StockMonitor().run()
