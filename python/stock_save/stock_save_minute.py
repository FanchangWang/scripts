"""
股票分钟线数据保存任务
功能: 从雪球API获取股票分钟线数据, 保存到JSON文件并发送通知

环境变量：
    STOCK_SYMBOL: str - 股票代码, 默认SH603533
    WXPUSHER_APP_TOKEN: str - WxPusher应用令牌
    WXPUSHER_UIDS: str - 接收通知的用户 UID
    XUEQIU_COOKIE: str - 雪球API所需的Cookie
"""
import os
import sys
import json
import logging
import datetime
from logging.handlers import TimedRotatingFileHandler
import requests
from dotenv import load_dotenv

env = os.environ

class StockDataSaver:
    def __init__(self):
        # 加载.env文件
        load_dotenv()
        # 读取配置
        self.stock_symbol = env.get('STOCK_SYMBOL', 'SH603533')
        self.wxpusher_app_token = env.get('WXPUSHER_APP_TOKEN')
        self.wxpusher_uids = env.get('WXPUSHER_UIDS')
        self.xueqiu_cookie = env.get('XUEQIU_COOKIE')

        # 初始化日志
        self._init_logging()

        # API配置
        self.api_url = 'https://stock.xueqiu.com/v5/stock/chart/minute.json'
        self.data_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data')
        os.makedirs(self.data_dir, exist_ok=True)

    def _init_logging(self):
        """初始化日志配置"""
        log_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'log')
        os.makedirs(log_dir, exist_ok=True)

        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - [PID:%(process)d] - %(levelname)s - %(message)s',
            handlers=[
                TimedRotatingFileHandler(
                    os.path.join(log_dir, 'run.log'),
                    when='D',
                    interval=1,
                    backupCount=7,
                    encoding='utf-8'
                ),
                logging.StreamHandler(sys.stdout)
            ]
        )
        self.logger = logging.getLogger(__name__)

    def get_minute_data(self):
        """从雪球API获取分钟线数据"""
        if not self.xueqiu_cookie:
            self.logger.error("未配置雪球Cookie")
            return None

        try:
            headers = {
                'Accept': 'application/json',
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0',
                'Cookie': self.xueqiu_cookie
            }
            params = {
                'symbol': self.stock_symbol,
                'period': '1d'
            }
            response = requests.get(self.api_url, headers=headers, params=params, timeout=10)
            response.raise_for_status()
            data = response.json()
            self.logger.info(f"成功获取股票{self.stock_symbol}的分钟线数据")
            self.logger.info(f"API响应数据: {data}")
            return data
        except Exception as e:
            self.logger.error(f"获取分钟线数据失败: {str(e)}")
            return None

    def save_data_to_file(self, data):
        """保存数据到JSON文件"""
        if not data or 'data' not in data or 'items' not in data['data'] or not data['data']['items']:
            self.logger.error("无效的数据格式")
            return False

        try:
            # 获取第一个数据点的时间戳
            timestamp = data['data']['items'][0]['timestamp']
            dt = datetime.datetime.fromtimestamp(timestamp / 1000)
            date_str = dt.strftime('%Y-%m-%d')
            file_name = f"{date_str}.json"
            file_path = os.path.join(self.data_dir, file_name)

            # 检查文件是否已存在
            if os.path.exists(file_path):
                try:
                    with open(file_path, 'r', encoding='utf-8') as f:
                        old_data = json.load(f)
                    # 比较数据是否相同
                    if old_data == data:
                        self.logger.info(f"股票分钟线数据已存在，文件内容相同，无需更新: {file_path}")
                        self.push_notification("股票分钟线数据已存在", f"股票 {self.stock_symbol} 的分钟线数据已存在，跳过保存")
                        return True
                    else:
                        self.logger.info(f"股票分钟线数据已存在，文件内容不同，覆盖写入: {file_path}")
                except Exception as e:
                    self.logger.warning(f"读取文件失败或文件格式错误: {str(e)}，将覆盖写入")

                # 保存数据到文件
                with open(file_path, 'w', encoding='utf-8') as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)

                self.logger.info(f"数据已覆盖保存到: {file_path}")
                self.push_notification("股票分钟线数据覆盖保存成功", f"股票 {self.stock_symbol} 的分钟线数据已存在，覆盖保存成功")
                return True
            else:
                # 保存数据到文件
                with open(file_path, 'w', encoding='utf-8') as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)

                self.logger.info(f"股票分钟线数据保存成功，保存路径: {file_path}")
                self.push_notification("股票分钟线数据保存成功", f"股票 {self.stock_symbol} 的分钟线数据保存成功，保存路径: {file_path}")
                return True
        except Exception as e:
            self.logger.error(f"保存数据失败: {str(e)}")
            self.push_notification("股票分钟线数据保存失败", f"股票 {self.stock_symbol} 的分钟线数据保存失败，错误信息: {str(e)}")
            return False

    def push_notification(self, title, message):
        """
        发送微信通知

        :param title: 通知标题
        :param message: 通知内容
        """
        if not self.wxpusher_app_token or not self.wxpusher_uids:
            self.logger.warning("WxPusher配置不完整, 无法发送通知")
            return

        try:
            url = 'https://wxpusher.zjiecode.com/api/send/message'
            headers = {'Content-Type': 'application/json'}
            data = {
                'appToken': self.wxpusher_app_token,
                'content': f'<h1>{title}</h1><br/><div style="white-space: pre-wrap;">{message}</div>',
                'summary': title,
                'contentType': 2,
                'uids': [self.wxpusher_uids]
            }
            response = requests.post(url, headers=headers, json=data, timeout=10)
            response.raise_for_status()
            result = response.json()
            if result.get('success'):
                self.logger.info("通知发送成功")
            else:
                self.logger.error(f"通知发送失败: {result.get('msg')}")
        except Exception as e:
            self.logger.error(f"发送通知失败: {str(e)}")

    def run(self):
        """主运行方法"""
        self.logger.info("股票分钟线数据保存任务开始")

        # 获取数据
        data = self.get_minute_data()
        if not data:
            self.logger.error("股票分钟线数据获取失败")
            self.push_notification("股票分钟线数据获取失败", f"股票 {self.stock_symbol} 的分钟线数据获取失败")
            return

        # 保存数据
        self.save_data_to_file(data)

        self.logger.info("股票分钟线数据保存任务结束")

if __name__ == '__main__':
    StockDataSaver().run()
