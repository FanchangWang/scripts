import json
import datetime

# 定义文件路径
five_day_file = r"d:\Works\scripts\qinglong\stock\data\2026-01-21-5d.json"
output_file = r"d:\Works\scripts\qinglong\stock\data\2026-01-26.json"

# 目标日期：2026-01-26
target_date = datetime.date(2026, 1, 26)

# 读取5天数据文件
with open(five_day_file, 'r', encoding='utf-8') as f:
    data = json.load(f)

# 提取目标日期的数据和计算last_close
filtered_items = []
last_close_item = None

for item in data['data']['items']:
    # 将时间戳转换为日期
    timestamp = item['timestamp']
    dt = datetime.datetime.fromtimestamp(timestamp / 1000)
    item_date = dt.date()

    # 筛选目标日期的数据
    if item_date == target_date:
        # 为了保持与1分钟数据格式一致，我们需要处理数据
        # 注意：5天数据的时间粒度是10分钟，而目标格式是1分钟
        # 由于我们没有1分钟的原始数据，只能使用现有的10分钟数据
        # 这里我们可以简单地保留原始数据，只修改必要的字段

        # 复制原始数据
        filtered_item = item.copy()

        # 处理capital字段，确保它不为null
        # 参考其他日期的格式，设置一个默认值
        if filtered_item['capital'] is None:
            filtered_item['capital'] = {
                "small": 0.0,
                "medium": 0.0,
                "large": 0.0,
                "xlarge": 0.0
            }

        # 处理volume_compare字段，使其与其他日期的格式一致
        # 参考其他日期的格式，调整volume_sum的值
        filtered_item['volume_compare']['volume_sum'] = item['volume_total'] or 0

        filtered_items.append(filtered_item)

    # 寻找目标日期之前最后一个数据
    elif item_date < target_date:
        if last_close_item is None or item['timestamp'] > last_close_item['timestamp']:
            last_close_item = item

# 确定last_close的值
if last_close_item:
    last_close = last_close_item['current']
else:
    # 如果没有找到之前的数据，使用原始数据的last_close
    last_close = data['data']['last_close']

# 构建输出数据结构
output_data = {
    "data": {
        "last_close": last_close,
        "after": data['data']['after'],
        "items": filtered_items,
        "items_size": len(filtered_items)
    },
    "error_code": 0,
    "error_description": ""
}

# 保存为新文件
with open(output_file, 'w', encoding='utf-8') as f:
    json.dump(output_data, f, ensure_ascii=False, indent=2)

print(f"已成功提取2026-01-26的数据并保存到 {output_file}")
print(f"提取了 {len(filtered_items)} 条数据")
