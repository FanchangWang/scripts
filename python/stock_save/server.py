#!/usr/bin/env python3
"""
股票数据服务器
功能: 提供HTTP服务，暴露股票交易数据给HTML页面使用
"""
import os
import json
import datetime
from http.server import HTTPServer, SimpleHTTPRequestHandler

class StockDataHandler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/api/stock-data':
            self.handle_stock_data()
        else:
            super().do_GET()

    def handle_stock_data(self):
        """处理股票数据请求"""
        # 设置响应头
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()

        # 获取数据目录
        data_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data')

        # 获取最近四周的日期
        today = datetime.date.today()
        weeks = []

        # 收集最近4周的数据
        for i in range(4):
            # 获取当前周的周一
            days_since_monday = today.weekday()
            monday = today - datetime.timedelta(days=days_since_monday)

            # 收集本周的交易日期（周一到周五）
            week_data = []
            for j in range(5):
                date = monday + datetime.timedelta(days=j)
                if date > today:
                    break  # 如果超过今天，跳过

                # 构建文件名
                file_name = f"{date.strftime('%Y-%m-%d')}.json"
                file_path = os.path.join(data_dir, file_name)

                # 检查文件是否存在
                if os.path.exists(file_path):
                    try:
                        with open(file_path, 'r', encoding='utf-8') as f:
                            data = json.load(f)

                        # 处理数据，只保留交易时间段的点
                        processed_data = {
                            'date': date.strftime('%m-%d'),
                            'items': []
                        }

                        # 使用j直接作为星期索引（0-4对应周一到周五）
                        for item in data.get('data', {}).get('items', []):
                            timestamp = item.get('timestamp')
                            if timestamp:
                                dt = datetime.datetime.fromtimestamp(timestamp / 1000)
                                weekday = ['周一', '周二', '周三', '周四', '周五'][j]
                                time_str = f"{weekday} {dt.strftime('%H:%M')}"
                                processed_data['items'].append({
                                    'time': time_str,
                                    'price': item.get('current'),
                                    'timestamp': timestamp
                                })

                        if processed_data['items']:
                            week_data.append(processed_data)
                    except Exception as e:
                        print(f"Error reading {file_path}: {e}")

            if week_data:
                weeks.append({
                    'week_start': monday.strftime('%Y-%m-%d'),
                    'week_display': ['本周', '上周', '三周', '四周'][i],
                    'days': week_data
                })

            # 移动到上一周
            today = monday - datetime.timedelta(days=1)

        # 返回数据
        response = {
            'weeks': weeks
        }

        self.wfile.write(json.dumps(response, ensure_ascii=False).encode('utf-8'))

def run_server():
    """启动服务器"""
    # 设置当前工作目录为脚本所在目录
    os.chdir(os.path.dirname(os.path.abspath(__file__)))

    server_address = ('', 8000)
    httpd = HTTPServer(server_address, StockDataHandler)
    print('Server running at http://localhost:8000/')
    print('API endpoint: http://localhost:8000/api/stock-data')
    httpd.serve_forever()

if __name__ == '__main__':
    run_server()
