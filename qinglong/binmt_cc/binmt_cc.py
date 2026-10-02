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


# ---------------------------------------------------------------------------
# acw_sc__v2 WAF 盾求解（纯 Python，无需浏览器）
# bbs.binmt.cc 前置一道 JS 反爬挑战页：返回含 arg1 的混淆 JS，浏览器算出
# acw_sc__v2 cookie 后再 reload 才返回真实页面。下面复刻其算法，使请求能
# 自动过盾。密钥 p / 置换表 m 由挑战页 JS 反推得到，站点固定、长期有效。
# ---------------------------------------------------------------------------
import re

_ACW_P = "3000176000856006061501533003690027800375"
_ACW_M = [0xf, 0x23, 0x1d, 0x18, 0x21, 0x10, 0x1, 0x26, 0xa, 0x9, 0x13, 0x1f, 0x28,
          0x1b, 0x16, 0x17, 0x19, 0xd, 0x6, 0xb, 0x27, 0x12, 0x14, 0x8, 0xe, 0x15,
          0x20, 0x1a, 0x2, 0x1e, 0x7, 0x4, 0x11, 0x5, 0x3, 0x1c, 0x22, 0x25, 0xc, 0x24]


def _acw_permute(arg1: str) -> str:
    # q[z] = arg1[m[z] - 1]
    return ''.join(arg1[_ACW_M[z] - 1] for z in range(len(_ACW_M)))


def _acw_xor(u: str, p: str) -> str:
    n = min(len(u), len(p))
    out = ''
    for x in range(0, n, 2):
        a = int(u[x:x + 2], 16)
        b = int(p[x:x + 2], 16)
        out += f'{(a ^ b) & 0xff:02x}'
    return out


def solve_acw_sc__v2(arg1: str) -> str:
    return _acw_xor(_acw_permute(arg1), _ACW_P)


class AntiBotSession(requests.Session):
    """自动识别并解除 acw_sc__v2 挑战的 requests.Session。"""

    def request(self, method, url, **kwargs):
        resp = super().request(method, url, **kwargs)
        if self._is_challenge(resp):
            m = re.search(r"arg1='([^']+)'", resp.text)
            if m:
                self.cookies.set(
                    "acw_sc__v2", solve_acw_sc__v2(m.group(1)),
                    domain="bbs.binmt.cc")
                # 携带 clearance cookie 重试一次（至多一次，避免死循环）
                resp = super().request(method, url, **kwargs)
        return resp

    @staticmethod
    def _is_challenge(resp) -> bool:
        return (resp.status_code == 200
                and 'arg1=' in resp.text
                and 'acw_sc__v2' in resp.text)


class Binmt:
    """bbs.binmt.cc 签到任务类"""

    # 基础配置
    NAME = "MT论坛签到"

    def __init__(self):
        self.session = AntiBotSession()
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

    # ----- 验证码 / 错误处理辅助方法 -----

    def _extract_discuz_msg(self, html: str) -> str | None:
        """从 Discuz 错误页中提取 #messagetext 的提示文案。"""
        if not html:
            return None
        soup = BeautifulSoup(html, "html.parser")
        box = soup.find(id="messagetext")
        if box:
            return box.get_text(strip=True)
        return None

    def _need_captcha(self, html: str) -> bool:
        """判断服务器是否要求输入验证码。"""
        if not html:
            return False
        return (
            "seccodeverify" in html
            or "misc.php?mod=seccode" in html
            or "mod=seccode" in html
            or "验证码不正确" in html
            or "请填写验证码" in html
        )

    def _login_with_captcha(self, username, password, login_url, form_action, referer_fallback) -> bool:
        """触发验证码后，用 ddddocr 自动识别并提交，最多重试若干次。"""
        try:
            import ddddocr
        except ImportError:
            self.log("❌ 登录需要验证码，但环境中缺少 ddddocr 识别库，无法自动识别。")
            self.log("   请在依赖中安装 ddddocr（uv add ddddocr / pip install ddddocr）后重试；")
            self.log("   或先在网页手动登录以消除风控，再运行本脚本。")
            return False
        try:
            ocr = ddddocr.DdddOcr(show_ad=False)
        except Exception:
            self.log("❌ 登录需要验证码，但 ddddocr 初始化失败（可能缺少运行依赖）。")
            return False

        max_retry = 5
        for attempt in range(1, max_retry + 1):
            try:
                page = self.session.get(
                    login_url,
                    headers={"Referer": f"{self.base_url}/k_misign-sign.html"},
                )
            except Exception:
                self.log(f"⚠️ 第 {attempt} 次获取登录页网络异常，重试…")
                continue
            soup = BeautifulSoup(page.text, "html.parser")
            form = soup.find("form", {"name": "login"})
            if not form:
                self.log("❌ 登录失败！验证码模式下未找到登录表单（WAF 盾可能未完全绕过）")
                return False
            formhash = form.find("input", {"name": "formhash"})
            formhash = formhash["value"] if formhash else ""
            referer = form.find("input", {"name": "referer"})
            referer = referer["value"] if referer else referer_fallback

            img = soup.find("img", src=lambda s: s and "mod=seccode" in s)
            if not img:
                self.log("⚠️ 未找到验证码图片，可能风控已解除，尝试直接登录…")
                data = self._build_login_data(form, formhash, referer, username, password)
                try:
                    resp = self.session.post(f"{self.base_url}/{form_action}", data=data)
                except Exception:
                    self.log("⚠️ 直接登录提交网络异常，重试…")
                    continue
                if "欢迎您回来" in resp.text:
                    self.log("✅ 登录成功！")
                    return True
                if self._need_captcha(resp.text):
                    continue
                self.log("❌ 登录失败！账号或密码错误（服务器未要求验证码但仍登录失败）")
                return False

            img_url = img["src"]
            if img_url.startswith("//"):
                img_url = "https:" + img_url
            elif img_url.startswith("http"):
                pass  # 已是绝对地址
            elif img_url.startswith("/"):
                img_url = self.base_url + img_url
            else:
                img_url = self.base_url + "/" + img_url

            try:
                cap = self.session.get(img_url)
            except Exception:
                self.log(f"⚠️ 第 {attempt} 次获取验证码图片失败，重试…")
                continue
            try:
                code = ocr.classification(cap.content).strip()
            except Exception:
                self.log(f"⚠️ 第 {attempt} 次验证码识别异常，重试…")
                continue

            data = self._build_login_data(form, formhash, referer, username, password, seccode=code)
            try:
                resp = self.session.post(f"{self.base_url}/{form_action}", data=data)
            except Exception:
                self.log(f"⚠️ 第 {attempt} 次提交验证码网络异常，重试…")
                continue

            if "欢迎您回来" in resp.text:
                self.log(f"✅ 登录成功！（验证码「{code}」第 {attempt} 次识别通过）")
                return True
            if self._need_captcha(resp.text):
                self.log(f"⚠️ 第 {attempt} 次验证码「{code}」识别错误，换一张重试…")
                continue
            msg = self._extract_discuz_msg(resp.text) or "服务器返回登录失败"
            self.log(f"❌ 登录失败！验证码已通过，但账号或密码错误（{msg}）")
            return False

        self.log(f"❌ 登录失败！连续 {max_retry} 次验证码识别未通过。")
        self.log("   可能原因：Discuz 验证码超出自动识别能力，或账号/密码本身有误。")
        self.log("   建议先去 https://bbs.binmt.cc 网页手动登录，确认密码正确后再运行。")
        return False

    @staticmethod
    def _build_login_data(form, formhash, referer, username, password, seccode=None) -> dict:
        """构造登录 POST 数据。seccode 为 None 表示不带验证码。"""
        data = {
            "formhash": formhash,
            "referer": referer,
            "loginfield": "username",
            "username": username,
            "password": password,
            "questionid": "0",
            "answer": "",
            "cookietime": "2592000",
            "loginsubmit": "true",
        }
        if seccode is not None:
            data["seccodeverify"] = seccode
            h = form.find("input", {"name": "seccodehash"})
            if h and h.get("value"):
                data["seccodehash"] = h["value"]
            else:
                import re as _re
                m = _re.search(r"idhash=([^&'\"]+)", str(form))
                if m:
                    data["seccodehash"] = m.group(1)
        return data

    def login(self, username: str, password: str) -> bool:
        """登录账号（含 WAF 过盾 + 验证码自动识别）。"""
        login_url = f"{self.base_url}/member.php?mod=logging&action=login"
        try:
            login_page_response = self.session.get(
                login_url,
                headers={"Referer": f"{self.base_url}/k_misign-sign.html"},
            )
        except Exception:
            self.log("❌ 登录失败！无法连接 bbs.binmt.cc（请检查网络 / 代理配置）")
            return False

        soup = BeautifulSoup(login_page_response.text, "html.parser")
        form = soup.find("form", {"name": "login"})
        if not form:
            self.log("❌ 登录失败！未找到登录表单 —— WAF 盾可能未完全绕过，页面仍返回挑战页。")
            self.log("   若此前能正常过盾，可能是站点算法更新，需重新推导 acw 密钥。")
            return False
        try:
            form_action = form["action"]
            formhash = form.find("input", {"name": "formhash"})["value"]
            referer = form.find("input", {"name": "referer"})["value"]
        except Exception:
            self.log("❌ 登录失败！登录表单字段解析异常（页面结构可能已变化）")
            return False

        # 首次提交（不含验证码）
        data = self._build_login_data(form, formhash, referer, username, password)
        try:
            login_post_response = self.session.post(
                f"{self.base_url}/{form_action}", data=data
            )
        except Exception:
            self.log("❌ 登录失败！提交登录请求时网络异常")
            return False

        if "欢迎您回来" in login_post_response.text:
            self.log("✅ 登录成功！")
            return True

        # 需要验证码 → 走自动识别流程
        if self._need_captcha(login_post_response.text) or self._need_captcha(login_page_response.text):
            return self._login_with_captcha(username, password, login_url, form_action, referer)

        # 否则视为账号 / 密码错误
        msg = self._extract_discuz_msg(login_post_response.text) or "服务器返回登录失败"
        self.log(f"❌ 登录失败！账号或密码错误（{msg}）")
        self.log("   请确认 BINMT_CC_USERNAME / BINMT_CC_PASSWORD 是否正确，含中文时注意不要有空格。")
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
        except Exception:
            self.log("❌ 签到失败！签到过程出现异常（可能网络中断或页面结构变化，请稍后重试）")
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
        except Exception:
            self.log("❌ 检查初始积分信息失败！（可能网络中断或页面结构变化）")

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
        except Exception:
            self.log("❌ 获取积分信息失败！（可能网络中断或页面结构变化，签到本身可能已成功）")

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
            self.NAME = f"❌ {self.NAME} 未配置账号"
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
            self.NAME = f"✅ {self.NAME} 成功"
        else:
            self.NAME = f"❌ {self.NAME} 失败"

        # 最后推送通知
        self.push_notification()


if __name__ == "__main__":
    Binmt().run()
