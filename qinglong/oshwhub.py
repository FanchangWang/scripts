"""
嘉立创开源硬件平台 自动任务脚本
功能: 自动完成签到、领取7天好礼、月度好礼

环境变量：
    oshwhub1/oshwhub2/oshwhub3: str - 嘉立创EDA Cookie "oshwhub_csrf=xxx; oshwhub_session=xxx; acw_tc=xxx" (每个账号一个变量)

cron: 10 5 * * *
"""

import os
import random
import time
import datetime
from datetime import timezone, timedelta, datetime
from typing import List, Dict, Any
import requests


from urllib3.exceptions import InsecureRequestWarning, InsecurePlatformWarning

# 禁用 SSL 警告
requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
requests.packages.urllib3.disable_warnings(InsecurePlatformWarning)


class Oshwhub:
    """嘉立创开源硬件平台自动任务类"""

    # 基础配置
    NAME = "嘉立创开源硬件平台 自动任务"
    BASE_URL = "https://oshwhub.com"

    # API endpoints
    API_USER_INFO = "/api/users"
    API_SIGN_In = "/api/users/signIn"
    API_SIGN_IN_PROFILE = "/api/users/getSignInProfile"

    def __init__(self):
        """初始化实例变量"""
        self.session = None  # 每个 user 都需要一个新的 session
        self.cookie: str = ""  # 当前用户 Cookie
        self.user: Dict[str, Any] = {}  # 当前用户信息
        self.users: List[Dict[str, Any]] = []  # 所有用户信息列表
        self.log_content: str = ""  # 日志内容

    def log(self, content: str, print_to_console: bool = True) -> None:
        """添加日志"""
        if print_to_console:
            print(content)
        self.log_content += content + "\n"

    def push_notification(self) -> None:
        """推送通知"""
        try:
            QLAPI.notify(self.NAME, self.log_content)
        except NameError:
            print(f"\n\n🚀 推送通知\n\n{self.NAME}\n\n{self.log_content}")

    def make_request(self, method: str, endpoint: str, **kwargs) -> Dict[str, Any]:
        """
        发送API请求
        Args:
            method: 请求方法 (GET/POST)
            endpoint: API端点
            **kwargs: 请求参数
        Returns:
            Dict[str, Any]: API响应数据
        """
        url = f"{self.BASE_URL}{endpoint}"
        try:
            response = self.session.request(method, url, **kwargs)
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as e:
            self.log(f"❌ API request failed: {str(e)}")
            return {"code": -1, "success": False, "msg": str(e)}

    def get_user_info(self) -> Dict[str, Any]:
        """
        获取用户信息
        Returns:
            Dict[str, Any]: 用户信息字典，获取失败返回空字典
        """
        # 每个 user 都需要一个新的 session
        self.session = requests.Session()
        headers = {"Cookie": self.cookie}
        response = self.make_request("GET", self.API_USER_INFO, headers=headers)
        print(f"get_user_info API response ——> {response}")

        if response["code"] == 0:
            data = response["result"]
            return {
                "cookie": self.cookie,
                "uuid": data["uuid"],
                "nickname": data["nickname"],
                "points": data["points"],
            }

        self.log(f"❌ 账号已失效, 请重新获取 Cookie: {self.cookie}")
        return {}

    def get_sign_profile(self) -> Dict[str, Any]:
        """获取签到信息"""
        response = self.make_request("GET", self.API_SIGN_IN_PROFILE)
        print(f"get_sign_profile API response ——> {response}")
        # {"success":true,"code":0,"result":{"total_point":140,"expiring_info":null,"isTodaySignIn":true,"latestSignInDate":"2025-07-02T17:13:17.638Z","week_signIn_days":2,"month_signIn_days":2,"userProjectCount":0,"validProjectCount":0,"task":{"project":0,"article":0,"invite":0,"activity":0,"post":0},"goodGiftStatus":{"sevenGoodGiftRecord":0,"monthGoodGiftRecord":0}}}
        if response["code"] == 0:
            data = response["result"]
            return {
                "isTodaySignIn": data["isTodaySignIn"],  # 今日是否已签到 true | false
                "total_point": data["total_point"],  # 总积分
                "week_signIn_days": data["week_signIn_days"],  # 本周已签到天数
                "month_signIn_days": data["month_signIn_days"],  # 本月已签到天数
                "sevenGoodGiftRecord": data["goodGiftStatus"]["sevenGoodGiftRecord"],
                "monthGoodGiftRecord": data["goodGiftStatus"]["monthGoodGiftRecord"],
            }
        self.log(f"❌ 获取签到信息失败, 账号: {self.user['nickname']}")
        return {}

    def sign_in(self) -> bool:
        """签到"""
        response = self.make_request("POST", self.API_SIGN_In)
        print(f"sign_in API response ——> {response}")
        if response["code"] == 0 and response["success"] and response["result"]:
            self.log("🎉 签到成功")
            return True
        self.log("❌ 签到失败")
        return False

    def run(self) -> None:
        """运行主程序"""
        # 使用字典保持插入顺序并实现去重（Python 3.7+ 字典有序）
        cookies_dict = {}

        # 从 oshwhub1/oshwhub2/oshwhub3 环境变量获取
        i = 1
        empty_count = 0  # 记录连续空值的数量
        while empty_count < 5:  # 连续5个空值才退出
            cookie = os.getenv(f"oshwhub{i}")
            if not cookie:
                empty_count += 1
            else:
                cookie = cookie.strip()
                if (
                    cookie and cookie not in cookies_dict
                ):  # 确保cookie不是空字符串且未重复
                    empty_count = 0  # 重置连续空值计数
                    cookies_dict[cookie] = None
            i += 1

        if not cookies_dict:
            self.log(
                "⛔️ 未获取到 cookies, 请检查环境变量 oshwhub1/oshwhub2/oshwhub3 是否填写"
            )
            self.push_notification()
            return

        self.log(f"👻 共获取到用户 cookie {len(cookies_dict)} 个")

        i = 0
        # 获取所有用户信息 # 遍历字典的键（即去重后的cookie值）
        # Python支持通过items()方法同时获取键和值: for key, value in cookies_dict.items()
        for cookie in cookies_dict:
            i += 1
            # 随机延迟
            if i > 1:
                print("\n进行下一个账号, 等待 5-10 秒...")
                time.sleep(random.randint(5, 10))
            self.log(f"\n======== ▷ 第 {i} 个账号 ◁ ========")
            self.cookie = cookie
            self.user = self.get_user_info()
            if self.user:
                # 打印用户信息
                self.log(
                    f"👻 用户: {self.user['nickname']} 💰 积分: {self.user['points']}\n"
                    f"🆔 uuid: {self.user['uuid']}"
                )
                # 检查签到信息
                sign_profile = self.get_sign_profile()
                if sign_profile:
                    if not sign_profile["isTodaySignIn"]:
                        self.log("准备执行签到, 等待 3-6 秒...")
                        time.sleep(random.randint(3, 6))
                        if self.sign_in():  # 执行签到
                            time.sleep(random.randint(3, 6))
                            sign_profile = self.get_sign_profile()
                    if sign_profile:
                        self.log(
                            f"🎉 总积分: {sign_profile['total_point']} | 今日: {sign_profile['isTodaySignIn']} | "
                            f"本周天数: {sign_profile['week_signIn_days']} | 本月天数: {sign_profile['month_signIn_days']} | "
                            f"周奖励: {sign_profile['sevenGoodGiftRecord']} | 月奖励: {sign_profile['monthGoodGiftRecord']}"
                        )
        # 最后推送通知
        self.push_notification()


if __name__ == "__main__":
    Oshwhub().run()
