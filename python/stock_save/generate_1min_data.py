import json
import os
from datetime import datetime, timedelta

def generate_1min_data():
    # 配置
    input_file = r'd:\Works\scripts\python\stock_save\data\2026-01-26-10m.json'
    output_dir = r'd:\Works\scripts\python\stock_save\data'

    # 读取10分钟粒度数据
    with open(input_file, 'r', encoding='utf-8') as f:
        data_10m = json.load(f)

    # 解析数据结构
    last_close = data_10m['data']['last_close']
    after = data_10m['data']['after']
    items_10m = data_10m['data']['items']

    # 生成1分钟粒度数据
    items_1m = []

    for i, item in enumerate(items_10m):
        # 获取当前10分钟数据的时间
        timestamp = item['timestamp']
        dt = datetime.fromtimestamp(timestamp / 1000)

        # 判断 11:30 与 15:00 这两个时间只保留一个，不进行10分钟处理
        if dt.time() == datetime.strptime('11:30', '%H:%M').time() or dt.time() == datetime.strptime('15:00', '%H:%M').time():
            items_1m.append(item)
            continue

        # 生成当前10分钟区间内的所有分钟数据（包括当前分钟）
        for minute_offset in range(10):
            # 计算1分钟数据的时间
            dt_1m = dt + timedelta(minutes=minute_offset)
            timestamp_1m = int(dt_1m.timestamp() * 1000)

            # 跳过已超过下一个10分钟数据的情况
            if i < len(items_10m) - 1:
                next_dt = datetime.fromtimestamp(items_10m[i+1]['timestamp'] / 1000)
                if dt_1m >= next_dt:
                    break

            # 创建1分钟数据条目，复制原数据并更新时间戳
            item_1m = item.copy()
            item_1m['timestamp'] = timestamp_1m
            items_1m.append(item_1m)

    # 构建输出数据结构
    output_data = {
        'data': {
            'last_close': last_close,
            'after': after,
            'items': items_1m,
            'items_size': len(items_1m)
        },
        'error_code': 0,
        'error_description': ''
    }

    # 保存为1分钟粒度JSON文件
    output_file = os.path.join(output_dir, '2026-01-26.json')
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(output_data, f, ensure_ascii=False, indent=2)

    print(f"生成1分钟粒度数据成功，保存到：{output_file}")
    print(f"原10分钟数据条目数：{len(items_10m)}")
    print(f"生成的1分钟数据条目数：{len(items_1m)}")

if __name__ == '__main__':
    generate_1min_data()
