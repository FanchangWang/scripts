"""
北京现代 APP 自动任务脚本
功能：自动完成签到、浏览文章、每日答题等任务

环境变量：
    BJXD: str - 北京现代 APP api token (多个账号用英文逗号分隔，建议每个账号一个变量)
    BJXD1/BJXD2/BJXD3: str - 北京现代 APP api token (每个账号一个变量)
    BJXD_ANSWER: str - 预设答案 (可选, ABCD 中的一个)
    HUNYUAN_API_KEY: str - 腾讯混元AI APIKey (可选)

cron: 25 6 * * *
"""

import os
import random
import time
from datetime import datetime
from typing import List, Optional, Dict, Any
import requests
from urllib3.exceptions import InsecureRequestWarning, InsecurePlatformWarning
import os
import configparser
from pathlib import Path
import sys

# 禁用 SSL 警告
requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
requests.packages.urllib3.disable_warnings(InsecurePlatformWarning)


class BeiJingHyundai:
    """北京现代APP自动任务类"""

    # 基础配置
    NAME = "北京现代 APP 自动任务"
    BASE_URL = "https://bm2-api.bluemembers.com.cn"
    API_ENDPOINTS = {
        "user_info": "/v1/app/account/users/info",
        "my_score": "/v1/app/user/my_score",
        "task_list": "/v1/app/user/task/list",
        "sign_list": "/v1/app/user/reward_list",
        "sign_submit": "/v1/app/user/reward_report",
        "article_list": "/v1/app/white/article/list2",
        "article_detail": "/v1/app/white/article/detail_app/{}",
        "task_score": "/v1/app/score",
        "question_info": "/v1/app/special/daily/ask_info",
        "question_submit": "/v1/app/special/daily/ask_answer",
    }

    # HTTP 请求头
    DEFAULT_HEADERS = {
        "token": "",
        "device": "mp",
    }

    # 腾讯混元AI配置
    HUNYUAN_API_URL = "https://api.hunyuan.cloud.tencent.com/v1/chat/completions"
    HUNYUAN_MODEL = "hunyuan-turbo"

    # 预设的备用 share_user_hid 列表
    BACKUP_HIDS = [
        "bb8cd2e44c7b45eeb8cc5f7fa71c3322",
        "5f640c50061b400c91be326c8fe0accd",
    ]

    def __init__(self):
        """初始化实例变量"""
        self.article_ids: List[str] = []
        self.correct_answer: str = ""  # 正确答案
        self.preset_answer: str = ""  # 新增: 预设答案
        self.ai_api_key: str = ""  # 腾讯混元AI APIKey
        self.ai_answer: str = ""  # 腾讯混元AI 答案
        self.wrong_answers: set = set()  # 错误答案集合
        self.log_content: str = ""  # 日志内容
        self.users: List[Dict[str, Any]] = []  # 用户信息列表
        self.headers: Dict[str, str] = self.DEFAULT_HEADERS.copy()  # HTTP 请求头
        self.ai_failed: bool = False  # 腾讯混元AI 答题失败

    def log(self, content: str, print_to_console: bool = True) -> None:
        """添加日志"""
        if print_to_console:
            print(content)
        self.log_content += content + "\n"

    def push_notification(self) -> None:
        """推送通知"""
        try:
            QLAPI.notify(self.NAME, self.log_content.replace("\n", "<br/>"))
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
            response = requests.request(method, url, headers=self.headers, **kwargs)
            response.raise_for_status()
            result = response.json()

            # 找到对应的 API_ENDPOINTS key
            endpoint_key = next(
                (
                    key
                    for key, value in self.API_ENDPOINTS.items()
                    if value == endpoint
                    or value.format("*") == endpoint.split("/")[0] + "/*"
                ),
                None,
            )

            # 排除不需要打印的接口
            if endpoint_key and endpoint_key not in ["article_list", "article_detail"]:
                print(f"{endpoint_key} response ——> {result}")

            return result
        except requests.exceptions.RequestException as e:
            self.log(f"❌ API请求失败: {str(e)}")
            return {"code": -1, "msg": str(e)}

    def fetch_user_info(self) -> Dict[str, Any]:
        """
        获取用户信息
        Returns:
            Dict[str, Any]: 用户信息字典，获取失败返回空字典
        """
        response = self.make_request("GET", self.API_ENDPOINTS["user_info"])

        if response["code"] == 0:
            data = response["data"]
            # 直接生成掩码后的手机号
            masked_phone = f"{data['phone'][:3]}******{data['phone'][-2:]}"
            return {
                "token": self.headers["token"],
                "hid": data["hid"],
                "nickname": data["nickname"],
                "phone": masked_phone,  # 直接存储掩码后的手机号
                "score_value": data["score_value"],
                "share_user_hid": "",
                "task": {"sign": False, "view": False, "question": False},
            }

        self.log(f"❌ 账号已失效，请重新抓包；token: {self.headers['token']}")
        return {}

    def show_score_details(self) -> None:
        """显示积分详情，包括总积分、今日变动和最近记录"""
        params = {"page_no": "1", "page_size": "5"}  # 获取最近5条记录
        response = self.make_request(
            "GET", self.API_ENDPOINTS["my_score"], params=params
        )

        if response["code"] == 0:
            data = response["data"]
            # 先获取今日记录
            today = datetime.now().strftime("%Y-%m-%d")
            today_records = [
                record
                for record in data["points_record"]["list"]
                if record["created_at"].startswith(today)
            ]

            # 计算今日积分变化
            today_score = sum(
                int(record["score_str"].strip("+")) for record in today_records
            )
            today_score_str = f"+{today_score}" if today_score > 0 else str(today_score)
            self.log(f"🎉 总积分: {data['score']} | 今日积分变动: {today_score_str}")

            # 输出今日积分记录
            if today_records:
                self.log("今日积分记录：")
                for record in today_records:
                    self.log(
                        f"{record['created_at']} {record['desc']} {record['score_str']}"
                    )
            else:
                self.log("今日暂无积分变动")

    # 任务相关
    def check_task_status(self, user: Dict[str, Any]) -> None:
        """检查任务状态"""
        response = self.make_request("GET", self.API_ENDPOINTS["task_list"])

        if response["code"] != 0:
            self.log(f'❌ 获取任务列表失败: {response["msg"]}')
            return

        actions = response.get("data", {})

        # 检查签到任务
        if "action4" in actions:
            user["task"]["sign"] = actions["action4"].get("status") == 1
        else:
            self.log("❌ task list action4 签到任务 不存在")

        # 检查浏览文章任务
        if "action12" in actions:
            user["task"]["view"] = actions["action12"].get("status") == 1
        else:
            self.log("❌ task list action12 浏览文章任务 不存在")

        # 检查答题任务
        if "action39" in actions:
            user["task"]["question"] = actions["action39"].get("status") == 1
        else:
            self.log("❌ task list action39 答题任务 不存在")

    # 签到相关
    def execute_sign_task(self) -> None:
        """执行签到任务"""
        max_attempts = 5  # 最大尝试次数
        best_score = 0
        best_params = None

        for attempt in range(max_attempts):
            response = self.make_request("GET", self.API_ENDPOINTS["sign_list"])

            if response["code"] != 0:
                self.log(f'❌ 获取签到列表失败: {response["msg"]}')
                break

            data = response["data"]
            hid = data["hid"]
            reward_hash = data["rewardHash"]

            for item in data["list"]:
                if item["hid"] == hid:
                    current_score = item["score"]
                    print(
                        f"第{attempt + 1}次获取签到列表：score={current_score} hid={hid} rewardHash={reward_hash}"
                    )

                    if current_score > best_score:
                        best_score = current_score
                        best_params = (hid, reward_hash, current_score)
                    print(f"当前可获得签到积分: {best_score}")
                    break

            if attempt < max_attempts - 1:  # 不是最后一次循环
                print(f"继续尝试获取更高积分，延时5-10s")
                time.sleep(random.randint(5, 10))
            else:  # 最后一次循环 即将提交签到
                print(f"即将提交签到，延时3-4s")
                time.sleep(random.randint(3, 4))

        if best_params:
            self.submit_sign(*best_params)
        else:
            self.log("❌ 未能获取到有效的签到参数")

    def submit_sign(self, hid: str, reward_hash: str, score: int) -> None:
        """提交签到"""
        json_data = {
            "hid": hid,
            "hash": reward_hash,
            "sm_deviceId": "",
            "ctu_token": None,
        }
        response = self.make_request(
            "POST", self.API_ENDPOINTS["sign_submit"], json=json_data
        )

        if response["code"] == 0:
            self.log(f"✅ 签到成功 | 积分+{score}")
        else:
            self.log(f'❌ 签到失败: {response["msg"]}')

    # 文章浏览相关
    def read_article(self) -> None:
        """浏览文章"""
        if not self.article_ids:
            self.log("❌ 没有可用的文章ID")
            return

        article_id = random.choice(self.article_ids)
        self.article_ids.remove(article_id)
        print(f"浏览文章 | 文章ID: {article_id}")

        endpoint = self.API_ENDPOINTS["article_detail"].format(article_id)
        self.make_request("GET", endpoint)

    def fetch_article_list(self) -> None:
        """获取文章列表"""
        params = {
            "page_no": "1",
            "page_size": "20",
            "type_hid": "",
        }
        response = self.make_request(
            "GET", self.API_ENDPOINTS["article_list"], params=params
        )

        if response["code"] == 0:
            self.article_ids = [item["hid"] for item in response["data"]["list"]]
        else:
            self.log(f'❌ 获取文章列表失败: {response["msg"]}')

    def submit_article_score(self) -> None:
        """提交文章积分"""
        json_data = {
            "ctu_token": "",
            "action": 12,
        }
        response = self.make_request(
            "POST", self.API_ENDPOINTS["task_score"], json=json_data
        )

        if response["code"] == 0:
            score = response["data"]["score"]
            self.log(f"✅ 浏览文章成功 | 积分+{score}")
        else:
            self.log(f'❌ 浏览文章失败: {response["msg"]}')

    # 答题相关
    def execute_question_task(self, share_user_hid: str) -> None:
        """执行答题任务"""
        params = {"date": datetime.now().strftime("%Y%m%d")}
        response = self.make_request(
            "GET", self.API_ENDPOINTS["question_info"], params=params
        )
        if response["code"] != 0:
            self.log(f'❌ 获取问题失败: {response["msg"]}')
            return
        # response['data']['state'] 1=表示未答题 2=已答题且正确 3=答错且未有人帮忙答题 4=答错但有人帮忙答题
        if response["data"].get("state") != 1:
            if response["data"].get("answer"):
                answer = response["data"]["answer"][0]
                if answer in ["A", "B", "C", "D"]:
                    self.correct_answer = answer
                    self.log(f"今日已答题，跳过，答案：{answer}")
                    return
            self.log("今日已答题，但未获取到答案，跳过")
            return

        question_info = response["data"]["question_info"]
        questions_hid = question_info["questions_hid"]

        # 构建问题字符串
        question_str = f"{question_info['content']}\n"
        for option in question_info["option"]:
            question_str += f'{option["option"]}. {option["option_content"]}\n'

        print(f"问题:\n{question_str}")

        answer = self.get_question_answer(question_str)

        time.sleep(random.randint(3, 5))
        self.submit_question_answer(questions_hid, answer, share_user_hid)

    def get_ai_answer(self, question: str) -> str:
        """获取AI答案"""
        headers = {
            "Authorization": f"Bearer {self.ai_api_key}",
            "Content-Type": "application/json",
        }
        prompt = f"你是一个专业的北京现代汽车专家，请直接给出这个单选题的答案，并且不要带'答案'等其他内容。\n{question}"
        json_data = {
            "model": self.HUNYUAN_MODEL,
            "messages": [{"role": "user", "content": prompt}],
            "enable_enhancement": True,
            "force_search_enhancement": True,
            "enable_instruction_search": True,
        }

        try:
            response = requests.post(
                self.HUNYUAN_API_URL, headers=headers, json=json_data
            )
            response.raise_for_status()

            response_json = response.json()
            print(f"腾讯混元AI response ——> {response_json}")

            answer = response_json["choices"][0]["message"]["content"]
            if answer in ["A", "B", "C", "D"]:
                return answer

            print(f"腾讯混元AI 无效答案: {answer}")
        except Exception as e:
            print(f"腾讯混元AI 请求失败: {str(e)}")

        return ""

    def get_question_answer(self, question: str) -> str:
        """获取答题答案"""
        if self.correct_answer:
            answer = self.correct_answer
            self.log(f"使用历史正确答案：{answer}")
        elif self.preset_answer:  # 新增: 使用预设答案
            answer = self.preset_answer
            self.log(f"使用预设答案：{answer}")
        elif self.ai_api_key and not self.ai_failed:
            if self.ai_answer:
                answer = self.ai_answer
                self.log(f"使用历史 AI 答案：{answer}")
            else:
                answer = self.get_ai_answer(question)
                if not answer:
                    answer = self.get_random_answer()
                    self.log(f"AI 返回答案错误，改为随机答题, 随机答案: {answer}")
                else:
                    self.ai_answer = answer
                    self.log(f"本次使用 AI 回答，答案：{answer}")
        else:
            answer = self.get_random_answer()
            self.log(f"本次随机答题, 随机答案: {answer}")
        return answer

    def get_random_answer(self) -> str:
        """获取随机答案，排除已知错误答案"""
        available_answers = set(["A", "B", "C", "D"]) - self.wrong_answers
        if not available_answers:
            self.wrong_answers.clear()
            available_answers = set(["A", "B", "C", "D"])
        return random.choice(list(available_answers))

    def get_answered_answer(self) -> None:
        """从已答题账号获取答案"""
        params = {"date": datetime.now().strftime("%Y%m%d")}

        response = self.make_request(
            "GET", self.API_ENDPOINTS["question_info"], params=params
        )
        if response["code"] != 0:
            self.log(f'❌ 从已答题账号获取问题失败: {response["msg"]}')
            return
        # response['data']['state'] 1=表示未答题 2=已答题且正确 4=已答题但错误
        if response["code"] == 0 and response["data"].get("answer"):
            answer = response["data"]["answer"][0]
            if answer in ["A", "B", "C", "D"]:
                self.correct_answer = answer
                self.log(f"从已答题账号获取到答案：{answer}")
                return
        self.log("从已答题账号获取答案失败")

    def submit_question_answer(
        self, question_id: str, answer: str, share_user_hid: str
    ) -> None:
        """提交答题答案"""
        json_data = {
            "answer": answer,
            "questions_hid": question_id,
            "ctu_token": "",
        }
        if share_user_hid:
            json_data["date"] = datetime.now().strftime("%Y%m%d")
            json_data["share_user_hid"] = share_user_hid

        response = self.make_request(
            "POST", self.API_ENDPOINTS["question_submit"], json=json_data
        )

        if response["code"] == 0:
            data = response["data"]
            if data["state"] == 3:
                self.wrong_answers.add(answer)
                if self.ai_answer == answer:  # 腾讯混元AI 答案错误
                    self.ai_failed = True
                    self.ai_answer = ""
                if self.preset_answer == answer:  # 新增: 清除错误的预设答案
                    self.preset_answer = ""
                    self.log("❌ 预设答案错误，已清除")
                self.log("❌ 答题错误")
            elif data["state"] == 2:
                if self.correct_answer != answer:
                    self.correct_answer = answer
                score = data["answer_score"]
                self.log(f"✅ 答题正确 | 积分+{score}")
        else:
            self.log(f'❌ 答题失败: {response["msg"]}')

    def get_backup_share_hid(self, user_hid: str) -> str:
        """从备用 hid 列表中获取一个不同于用户自身的 hid"""
        available_hids = [hid for hid in self.BACKUP_HIDS if hid != user_hid]
        return random.choice(available_hids) if available_hids else ""

    def run(self) -> None:
        """运行主程序"""
        tokens = []

        # 方式1: 从BJXD环境变量获取(逗号分隔的多个token)
        token_str = os.getenv("BJXD")
        if token_str:
            tokens.extend(token_str.split(","))

        # 方式2: 从BJXD1/BJXD2/BJXD3等环境变量获取
        i = 1
        empty_count = 0  # 记录连续空值的数量
        while empty_count < 5:  # 连续5个空值才退出
            token = os.getenv(f"BJXD{i}")
            if not token:
                empty_count += 1
            else:
                empty_count = 0  # 重置连续空值计数
                tokens.append(token)
            i += 1

        if not tokens:
            self.log(
                "⛔️ 未获取到 tokens：请检查环境变量 BJXD 或 BJXD1/BJXD2/... 是否填写"
            )
            self.push_notification()
            return

        self.log(f"👻 共获取到用户 token {len(tokens)} 个")

        self.ai_api_key = os.getenv("HUNYUAN_API_KEY", "")
        self.log(
            "💯 已获取到腾讯混元AI APIKey，使用腾讯混元AI答题"
            if self.ai_api_key
            else "😭 未设置腾讯混元AI HUNYUAN_API_KEY 环境变量，使用随机答题"
        )

        # 获取预设答案
        self.preset_answer = os.getenv("BJXD_ANSWER", "").upper()
        if self.preset_answer:
            if self.preset_answer in ["A", "B", "C", "D"]:
                self.log(f"📝 已获取预设答案: {self.preset_answer}")
            else:
                self.preset_answer = ""
                self.log("❌ 预设答案格式错误，仅支持 A/B/C/D")

        # 获取所有用户信息
        for token in tokens:
            self.headers["token"] = token
            user = self.fetch_user_info()
            if user:
                self.users.append(user)

        # 设置分享用户ID
        for i, user in enumerate(self.users):
            prev_index = (i - 1) if i > 0 else len(self.users) - 1
            # 如果有多个用户且上一个用户不是自己，使用上一个用户的 hid
            if len(self.users) > 1 and self.users[prev_index]["hid"] != user["hid"]:
                user["share_user_hid"] = self.users[prev_index]["hid"]
            else:
                # 否则从备用 hid 列表中选择一个
                user["share_user_hid"] = self.get_backup_share_hid(user["hid"])

        # 执行任务
        self.log("\n============ 执行任务 ============")
        for i, user in enumerate(self.users, 1):
            if i > 1:
                print("\n进行下一个账号, 等待 5-10 秒...")
                time.sleep(random.randint(5, 10))

            self.log(f"\n======== ▷ 第 {i} 个账号 ◁ ========")
            self.headers["token"] = user["token"]

            # 打印用户信息
            self.log(
                f"👻 用户名: {user['nickname']} | "
                f"手机号: {user['phone']} | "
                f"积分: {user['score_value']}\n"
                f"🆔 用户hid: {user['hid']}\n"
                f"🆔 分享hid: {user['share_user_hid']}"
            )

            # 检查任务状态
            self.check_task_status(user)
            self.log(f"任务状态: {user['task']}")

            # 重置任务未完成状态用于单独测试任务
            # user["task"]["sign"] = False
            # user["task"]["view"] = False
            # user["task"]["question"] = False

            # 重置任务完成状态用于单独测试任务跳过任务
            # user["task"]["sign"] = True
            # user["task"]["view"] = True
            # user["task"]["question"] = True

            # 执行未完成的任务
            if not user["task"]["sign"]:
                self.log("签到任务 未完成，开始执行任务")
                self.execute_sign_task()
                time.sleep(random.randint(5, 10))
            else:
                self.log("✅ 签到任务 已完成，跳过")

            if not user["task"]["view"]:
                self.log("浏览文章任务 未完成，开始执行任务")
                self.fetch_article_list()
                for _ in range(3):
                    self.read_article()
                    time.sleep(random.randint(10, 15))
                self.submit_article_score()
                time.sleep(random.randint(5, 10))
            else:
                self.log("✅ 浏览文章任务 已完成，跳过")

            if not user["task"]["question"]:
                self.log("答题任务 未完成，开始执行任务")
                self.execute_question_task(user["share_user_hid"])
            else:
                self.log("✅ 答题任务 已完成，跳过")
                if not self.correct_answer:
                    self.get_answered_answer()

        self.log("\n============ 积分详情 ============")
        for i, user in enumerate(self.users, 1):
            if i > 1:
                print("\n进行下一个账号, 等待 5-10 秒...")
                time.sleep(random.randint(5, 10))

            self.log(f"\n======== ▷ 第 {i} 个账号 ◁ ========")

            # 设置当前用户的 token
            self.headers["token"] = user["token"]

            # 打印用户信息
            self.log(f"👻 用户名: {user['nickname']} | 手机号: {user['phone']}")

            # 显示积分详情
            self.show_score_details()

        # 最后推送通知
        self.push_notification()


if __name__ == "__main__":
    BeiJingHyundai().run()
