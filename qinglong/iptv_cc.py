"""
iptv.cc 签到任务
功能: iptv.cc 自动签到, 自动回复帖子, 查看积分

环境变量：
    IPTV_CC_USERNAME: str - iptv.cc 用户名
    IPTV_CC_PASSWORD: str - iptv.cc 密码

cron: 48 3 * * *
"""

import os
import random
import requests
import time
from bs4 import BeautifulSoup
from urllib3.exceptions import InsecureRequestWarning, InsecurePlatformWarning

# 禁用 SSL 警告
requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
requests.packages.urllib3.disable_warnings(InsecurePlatformWarning)


class IPTV:
    """iptv.cc 签到任务类"""

    # 基础配置
    NAME = "iptv.cc 签到任务"

    def __init__(self):
        self.session = requests.Session()
        self.base_url = "https://iptv.cc"
        self.logout_url = None
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
                headers={"Referer": f"{self.base_url}/dsu_paulsign-sign.html"},
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
                "username": username,
                "password": password,
                "questionid": "0",
                "answer": "",
                "loginsubmit": "yes",
            }

            login_post_response = self.session.post(
                f"{self.base_url}/{form_action}", data=login_data
            )

            # 如果 HTML 带有 “欢迎您回来” 字样，则登录成功
            if "欢迎您回来" in login_post_response.text:
                self.log("✅ 登录成功！")
                soup = BeautifulSoup(login_post_response.text, "html.parser")
                logout_a = soup.find("a", string="退出")
                if logout_a:
                    logout_href = logout_a["href"]
                    self.logout_url = f"{self.base_url}/{logout_href}"
                    print("退出链接: ", self.logout_url)
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
        """签到方法"""
        try:
            # 获取签到页面
            sign_page_response = self.session.get(
                f"{self.base_url}/dsu_paulsign-sign.html"
            )

            soup = BeautifulSoup(sign_page_response.text, "html.parser")
            # 检查签到状态
            if "您今天已经签到过了或者签到时间还未开始" in sign_page_response.text:
                self.log("✅ 今天已签到过了")
                # 获取签到信息
                p_tags = soup.find("div", {"class": "mn"}).find_all("p")
                self.log(f"签到信息:")
                for p_tag in p_tags:
                    self.log(p_tag.text)
            elif "今天签到了吗" in sign_page_response.text:
                # 获取签到表单
                form = soup.find("form", {"name": "qiandao"})
                form_action = form["action"]
                formhash = form.find("input", {"name": "formhash"})["value"]
                qiandao_data = {"formhash": formhash, "qdxq": "kx"}
                sign_post_response = self.session.post(
                    f"{self.base_url}/{form_action}", data=qiandao_data
                )

                if "恭喜你签到成功" in sign_post_response.text:
                    soup = BeautifulSoup(sign_post_response.text, "html.parser")
                    sign_info = soup.find("div", {"class": "c"})
                    self.log(f"✅ {sign_info.text.replace('[点此返回]', '').strip()}")
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

    def view_thread(self):
        """浏览帖子并回复获取积分"""
        try:
            thread_page_response = self.session.get(
                f"{self.base_url}/forum.php?mod=forumdisplay&fid=6&filter=author&orderby=dateline"
            )
            soup = BeautifulSoup(thread_page_response.text, "html.parser")
            thread_list = soup.find("table", {"id": "threadlisttableid"}).find_all(
                "tbody"
            )
            thread_count = 0
            random.shuffle(thread_list)
            for thread_tbody in thread_list:
                # 检查 thread_tbody 是否存在 id 属性，且 id 是否包含 normalthread_
                if (
                    "id" not in thread_tbody.attrs
                    or "normalthread_" not in thread_tbody["id"]
                ):
                    continue
                # 检查 thread_tbody 的 html 中是否包含关键字
                if any(
                    keyword in str(thread_tbody)
                    for keyword in [
                        "关闭的主题",
                        "阅读权限",
                        "售价",
                        "悬赏",
                        "积分",
                        "帖子被减分",
                    ]
                ):
                    continue
                # 检查 thread_tbody 的 是否是正确的帖子
                thread_a = thread_tbody.find("a", {"class": "xst"})
                if not thread_a:
                    continue
                thread_title = thread_a.text
                thread_href = f"{self.base_url}/{thread_a['href']}"

                if self.view_thread_content(thread_title, thread_href):
                    # 跳出循环
                    break
                else:
                    # 记录循环次数
                    thread_count += 1
                    if thread_count >= 3:
                        self.log("❌ 多次浏览帖子失败，跳出循环")
                        break
                # 等待 20 秒
                time.sleep(20)
        except Exception as e:
            self.log(f"❌ 浏览帖子失败！{e}")

    def view_thread_content(self, thread_title: str, thread_href: str) -> bool:
        """浏览帖子内容"""
        try:
            self.log(f"浏览并回复帖子: {thread_title}")
            thread_content_response = self.session.get(thread_href)
            soup = BeautifulSoup(thread_content_response.text, "html.parser")
            if thread_title in thread_content_response.text:
                form = soup.find("form", {"id": "vfastpostform"})
                if not form:
                    self.log("❌ 浏览帖子失败！未发现回复表单")
                    return False
                form_action = form["action"]
                formhash = form.find("input", {"name": "formhash"})["value"]
                reply_data = {"formhash": formhash, "message": "谢谢楼主的分享"}
                reply_post_response = self.session.post(
                    f"{self.base_url}/{form_action}", data=reply_data
                )
                if "回复发布成功" in reply_post_response.text or (
                    "发表于" in reply_post_response.text
                    and (
                        "刚刚" in reply_post_response.text
                        or "秒前" in reply_post_response.text
                    )
                ):
                    self.log("✅ 回复帖子成功！")
                    return True
                else:
                    self.log("❌ 回复帖子失败！未发现回复成功关键字")
                    print("reply_post_response 响应:")
                    print(reply_post_response.text)
                    return False
            else:
                self.log("❌ 浏览帖子失败！未发现帖子标题")
                print("thread_content_response 响应:")
                print(thread_content_response.text)
                return False
        except Exception as e:
            self.log(f"❌ 浏览帖子失败！{e}")
            return False

    def get_score_info(self, check_reply: bool = False) -> bool:
        """获取积分信息"""
        has_reply = False
        message = ""
        try:
            score_info_response = self.session.get(
                f"{self.base_url}/home.php?mod=spacecp&ac=credit&op=base"
            )
            soup = BeautifulSoup(score_info_response.text, "html.parser")
            score_info = soup.find("ul", {"class": "creditl"}).find_all("li")
            message += f"积分信息:\n"
            for score_info_li in score_info:
                # 如果 li 中存在 span,移除 li 中的 span
                score_info_li_text = score_info_li.text
                if score_info_li.find("span"):
                    score_info_li_text = score_info_li_text.replace(
                        score_info_li.find("span").text, ""
                    )
                message += f"{score_info_li_text.strip()}\n"

            message += f"\n积分列表:\n"
            table_list = soup.find("table", {"class": "mtm"}).find_all("tr")
            for table_tr in table_list:
                # 排除第一个 tr
                if table_tr.find("th"):
                    continue
                table_td = table_tr.find_all("td")
                message += f"{table_td[1].text} {table_td[2].text} {table_td[3].text}\n"
                if "发表回复" in table_td[2].text and table_td[3].text.startswith(
                    time.strftime("%Y-%m-%d")
                ):
                    has_reply = True
            if check_reply:
                if has_reply:
                    self.log("✅ 今天已回复过帖子")
                    self.log(message)
            else:
                self.log(message)
        except Exception as e:
            self.log(f"❌ 获取积分信息失败！{e}")
        return has_reply

    def run(self):
        """运行主程序"""
        username = os.getenv("IPTV_CC_USERNAME", "")
        password = os.getenv("IPTV_CC_PASSWORD", "")
        if not username or not password:
            self.log("❌ 未设置 IPTV_CC_USERNAME 或 IPTV_CC_PASSWORD 环境变量")
            self.push_notification()
            return
        if self.login(username, password):
            self.sign()
            if not self.get_score_info(check_reply=True):
                self.view_thread()
                self.get_score_info()
            self.logout()

        # 最后推送通知
        self.push_notification()


if __name__ == "__main__":
    IPTV().run()
