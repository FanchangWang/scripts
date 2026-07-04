"""
bbs.binmt.cc MT论坛签到任务
功能: bbs.binmt.cc MT论坛自动签到, 查看积分

环境变量：
    BINMT_CC_USERNAME: str - bbs.binmt.cc 用户名
    BINMT_CC_PASSWORD: str - bbs.binmt.cc 密码

cron: 12 3 * * *
"""

import os

import requests
from bs4 import BeautifulSoup
from urllib3.exceptions import InsecurePlatformWarning, InsecureRequestWarning

# 禁用 SSL 警告
requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
requests.packages.urllib3.disable_warnings(InsecurePlatformWarning)


class Binmt:
    """bbs.binmt.cc 签到任务类"""

    # 基础配置
    NAME = "MT论坛签到"

    def __init__(self):
        self.session = requests.Session()
        self.base_url = "https://bbs.binmt.cc"
        self.logout_url = None
        self.log_content: str = ""  # 日志内容
        self.initial_gold = 0  # 初始金币数量
        self.initial_points = 0  # 初始积分数量
        self.sign_rank = None  # 签到排名

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

    def get_logout_url(self, soup):
        """获取退出链接"""
        logout_a = soup.find("a", string="退出")
        if logout_a:
            logout_href = logout_a["href"]
            self.logout_url = f"{self.base_url}/{logout_href}"
            print("退出链接: ", self.logout_url)

    def logout(self):
        """退出登录"""
        if self.logout_url:
            logout_response = self.session.get(self.logout_url)
            if "您已退出站点" in logout_response.text:
                self.log("✅ 退出成功！")
            else:
                self.log("❌ 退出失败！未发现退出成功关键字")
                print("退出响应: ")
                print(logout_response.text)
        else:
            self.log("❌ 退出失败！未发现退出链接")

    def login(self, username: str, password: str) -> bool:
        """登录账号"""
        try:
            # 第一步：获取登录表单
            login_page_response = self.session.get(
                f"{self.base_url}/member.php?mod=logging&action=login",
                headers={"Referer": f"{self.base_url}/k_misign-sign.html"},
            )
            soup = BeautifulSoup(login_page_response.text, "html.parser")
            # 获取登录表单
            form = soup.find("form", {"name": "login"})
            form_action = form["action"]
            formhash = form.find("input", {"name": "formhash"})["value"]
            referer = form.find("input", {"name": "referer"})["value"]
            # 提交登录表单
            login_data = {
                "formhash": formhash,
                "referer": referer,
                "loginfield": "username",
                "username": username,
                "password": password,
                "questionid": "0",
                "answer": "",
                "cookietime": "2592000",
            }

            login_post_response = self.session.post(
                f"{self.base_url}/{form_action}", data=login_data
            )
            # 如果 HTML 带有 “欢迎您回来” 字样，则登录成功
            if "欢迎您回来" in login_post_response.text:
                self.log("✅ 登录成功！")
                return True
            else:
                self.log("❌ 登录失败！未发现登录成功关键字")
                print("login_post_response 响应:")
                print(login_post_response.text)
                return False
        except Exception as e:
            self.log(f"❌ 登录失败！{e}")
            return False

    def sign(self):
        """签到方法，返回是否已签到"""
        already_signed = False
        try:
            # 获取签到页面
            sign_page_response = self.session.get(f"{self.base_url}/k_misign-sign.html")
            soup = BeautifulSoup(sign_page_response.text, "html.parser")
            self.get_logout_url(soup)
            # 检查签到状态
            if "您的签到排名" in sign_page_response.text:
                already_signed = True
                # 获取签到排名
                # <input type="hidden" class="hidnum" id="qiandaobtnnum" value="478">
                qiandaobtnnum = soup.find("input", {"id": "qiandaobtnnum"})["value"]
                self.sign_rank = qiandaobtnnum
                self.log(f"✅ 您的签到排名：{qiandaobtnnum}")
                # 获取签到信息
                lxdays = soup.find("input", {"id": "lxdays"})["value"]
                lxlevel = soup.find("input", {"id": "lxlevel"})["value"]
                lxreward = soup.find("input", {"id": "lxreward"})["value"]
                lxtdays = soup.find("input", {"id": "lxtdays"})["value"]
                self.log(
                    f"\n签到信息:\n连续签到: {lxdays} 天 签到等级: Lv.{lxlevel} 积分奖励: {lxreward} 总天数: {lxtdays}\n"
                )
            elif "您今天还没有签到" in sign_page_response.text:
                # 获取签到按钮
                sign_button = soup.find("a", {"id": "JD_sign"})
                if not sign_button:
                    self.log("❌ 签到失败！未发现签到按钮")
                    return already_signed
                sign_href = sign_button["href"]
                sign_post_response = self.session.get(f"{self.base_url}/{sign_href}")
                if "root" in sign_post_response.text:
                    self.log("✅ 签到成功！")
                else:
                    self.log("❌ 签到失败！未发现签到成功关键字")
                    print("sign_post_response 响应:")
                    print(sign_post_response.text)
            else:
                self.log("❌ 签到失败！未发现签到关键字")
                print("sign_page_response 响应:")
                print(sign_page_response.text)
        except Exception as e:
            self.log(f"❌ 签到失败！{e}")
        return already_signed

    def check_score_info(self):
        """检查并记录初始金币和积分信息"""
        try:
            score_info_response = self.session.get(
                f"{self.base_url}/home.php?mod=spacecp&ac=credit&op=base"
            )
            soup = BeautifulSoup(score_info_response.text, "html.parser")
            self.get_logout_url(soup)

            # 解析初始金币和积分
            score_info = soup.find("ul", {"class": "creditl"}).find_all("li")
            for score_info_li in score_info:
                li_text = score_info_li.text.strip()
                if "金币:" in li_text:
                    # 提取金币数量，例如："金币: 1160" -> 1160
                    self.initial_gold = int(li_text.split(":")[1].strip().split()[0])
                elif "积分:" in li_text:
                    # 提取积分数量，例如："积分: 867" -> 867
                    points_part = li_text.split(":")[1].strip()
                    if "(" in points_part:
                        points_part = points_part.split("(")[0].strip()
                    self.initial_points = int(points_part)

            self.log(f"✅ 记录初始积分信息：金币 {self.initial_gold}，积分 {self.initial_points}")
        except Exception as e:
            self.log(f"❌ 检查初始积分信息失败！{e}")

    def get_score_info(self):
        """获取积分信息并计算变化"""
        current_gold = 0
        current_points = 0
        try:
            score_info_response = self.session.get(
                f"{self.base_url}/home.php?mod=spacecp&ac=credit&op=base"
            )
            soup = BeautifulSoup(score_info_response.text, "html.parser")
            self.get_logout_url(soup)

            # 解析当前金币和积分
            score_info = soup.find("ul", {"class": "creditl"}).find_all("li")
            message = "积分信息:\n"
            for score_info_li in score_info:
                li_text = score_info_li.text
                if score_info_li.find("span"):
                    li_text = li_text.replace(score_info_li.find("span").text, "")
                li_text = li_text.strip()

                # 提取当前金币和积分数量
                if "金币:" in li_text:
                    current_gold = int(li_text.split(":")[1].strip().split()[0])
                elif "积分:" in li_text:
                    points_part = li_text.split(":")[1].strip()
                    if "(" in points_part:
                        points_part = points_part.split("(")[0].strip()
                    current_points = int(points_part)

            # 计算新增数量
            gold_increase = current_gold - self.initial_gold
            points_increase = current_points - self.initial_points

            # 更新NAME
            self.NAME = f"{self.NAME} 金币:{current_gold}(+{gold_increase}) 积分:{current_points}(+{points_increase})"

            # 打印包含新增值的积分信息
            message = f"金币：{current_gold} (+{gold_increase}) 积分：{current_points} (+{points_increase})\n"

            # 添加积分记录
            message += "\n积分记录:\n"
            table_list = soup.find("table", {"class": "mtm"}).find_all("tr")
            n = 0
            for table_tr in table_list:
                # 排除第一个 tr
                if table_tr.find("th"):
                    continue
                table_td = table_tr.find_all("td")
                message += f"{table_td[0].text} {table_td[1].text} {table_td[3].text}\n"
                n += 1
                if n >= 2:
                    break
            self.log(message)
        except Exception as e:
            self.log(f"❌ 获取积分信息失败！{e}")

    def run(self):
        """运行主程序"""

        try:
            from dotenv import load_dotenv

            load_dotenv()
            print("✅ dotenv 成功加载 .env 文件")
        except ImportError:
            print("⚠️ 缺少 dotenv 库, 青龙环境请忽略, 本地运行请安装此库")

        username = os.getenv("BINMT_CC_USERNAME", "")
        password = os.getenv("BINMT_CC_PASSWORD", "")
        if not username or not password:
            self.log("❌ 未设置 BINMT_CC_USERNAME 或 BINMT_CC_PASSWORD 环境变量")
            self.push_notification()
            return
        if self.login(username, password):
            self.check_score_info()  # 记录初始积分信息
            already_signed = self.sign()  # 签到
            if already_signed:
                # 已签到：NAME 显示排名，不再获取积分变化
                self.NAME = f"{self.NAME} 今日签到排名:{self.sign_rank}"
            else:
                self.get_score_info()  # 获取并计算积分变化
            self.logout()

        # 最后推送通知
        self.push_notification()


if __name__ == "__main__":
    Binmt().run()
