import requests
import datetime
from datetime import timezone, timedelta
import json
import os

cookie = os.getenv("oshwhub1")
# 账号登录 Cookie
headers = {"Cookie": f"oshwhub_session={cookie}"}
# 假账号 测试一下
# headers = {"Cookie": "oshwhub_session=aaaaaaabbbbb"}

# GET 获取用户信息
response = requests.get("https://oshwhub.com/api/users", headers=headers)
print(response.text)
# 返回值
# {"success":true,"code":0,"result":{"uuid":"573ef6c4263c4291affbba6f3d716ba0","username":"guyuexuan","nickname":"guyuexuan","introduction":"","avatar":"//image.lceda.cn/avatars/2025/4/D2aKJqWImWPNU0ILsMgU9MXQYGT5l9sZhTT9Apuo.webp","info":{"type":"personal","country":"","education":"","school":"","company":"","companySize":0,"career":"其他","site":"","skills":[],"share":"0","auto_join":"0"},"follower":[],"following":[],"created_at":"2025-04-10T16:19:21.000Z","customerCode":"9478089A","accountCode":"J11183416","points":140,"is_fp_office_account":false,"can_copy_pro_project_to_oshwlab":false,"user_agreement_accepted":0}}
# 假账号 Cookie 的返回值 401 应该就是错误 session 或者 已过期？
# {"code":401,"success":false,"msg":""}

# # POST 提交签到
# response = requests.post("https://oshwhub.com/api/users/signIn", headers=headers)
# print(response.text)
# # 返回值 code: 0成功，其他失败 success:true成功 false失败 result:true签到成功 false签到失败(比如已经签到过了)
# # {"success":true,"code":0,"result":false}

# # GET 获取签到信息
# response = requests.get(
#     "https://oshwhub.com/api/users/getSignInProfile", headers=headers
# )
# print(response.text)
# # 返回值
# # {"success":true,"code":0,"result":{"total_point":140,"expiring_info":null,"isTodaySignIn":true,"latestSignInDate":"2025-07-02T17:13:17.638Z","week_signIn_days":2,"month_signIn_days":2,"userProjectCount":0,"validProjectCount":0,"task":{"project":0,"article":0,"invite":0,"activity":0,"post":0},"goodGiftStatus":{"sevenGoodGiftRecord":0,"monthGoodGiftRecord":0}}}

# # GET 获取签到记录
# current_date = datetime.date.today()
# start_of_month = datetime.datetime(current_date.year, current_date.month, 1)  # 当月1号
# end_of_month = (start_of_month + datetime.timedelta(days=32)).replace(day=1)  # 下月1号
# startTime = int(start_of_month.timestamp() * 1000)
# endTime = int(end_of_month.timestamp() * 1000)
# url = f"https://oshwhub.com/api/users/signInRecord?startTime={startTime}&endTime={endTime}"
# response = requests.get(url, headers=headers)
# print(response.text)
# # 返回值 result是签到数组，时间格式为 格林威治时间（GMT）
# # {"success":true,"code":0,"result":["2025-07-01T17:55:09.598Z","2025-07-02T17:13:17.638Z"]}
# # 解析JSON响应
# response_data = response.json()
# if response_data.get("success"):
#     gmt_time_list = response_data.get("result", [])
#     local_time_list = []
#     for gmt_time_str in gmt_time_list:
#         # 转换GMT时间字符串为datetime对象
#         # 兼容Python 3.6及以下版本的ISO时间解析
#         gmt_time_str = gmt_time_str.replace("Z", "+00:00")
#         gmt_time = datetime.datetime.strptime(gmt_time_str, "%Y-%m-%dT%H:%M:%S.%f%z")
#         # 转换为东八区（UTC+8）时间
#         local_time = gmt_time.astimezone(timezone(timedelta(hours=8)))
#         # 格式化时间字符串（保留三位毫秒）
#         formatted_time = local_time.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
#         local_time_list.append(formatted_time)
#     print("签到记录（本地时间）:", local_time_list)
# else:
#     print(f"请求失败: 错误码 {response_data.get('code')}")
