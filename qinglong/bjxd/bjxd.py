"""
北京现代 APP 自动任务脚本
功能：自动完成签到、浏览文章、每日答题等任务；答题支持联网搜索辅助 AI 分析。

环境变量：
    BJXD: str - 北京现代 APP api token (多个账号用英文逗号分隔，建议每个账号一个变量)
    BJXD1/BJXD2/BJXD3: str - 北京现代 APP api token (每个账号一个变量)
    BJXD_ANSWER: str - 预设答案 (可选, ABCD 中的一个)
    AI_API_KEY: str - 通用 AI APIKey (可选)
    AI_REQUEST_URL: str - 通用 AI 请求 URL (可选)
    AI_MODEL: str - 通用 AI 模型名称 (可选)
    AI_REQUEST_PARAMS: str - 通用 AI 请求参数 (可选, JSON 格式字符串)
    SEARCH_API_KEY: str - 百度 AI 搜索的 API Key (bce-v3/... 格式, 每月免费 1500 次)
    SEARCH_RESULT_COUNT: int - 搜索结果条数 (可选, 默认 5)
    SEARCH_TIMEOUT: int - 搜索超时秒数 (可选, 默认 30)
    HUNYUAN_API_KEY: str - 腾讯混元 AI APIKey (已废弃，不建议使用)
    GLM_API_KEY: str - 智谱 GLM AI APIKey (已废弃，不建议使用)

cron: 25 6 * * *
"""

import json
import os
import random
import re
import time
import json
import base64
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional, Tuple

import requests
from urllib3.exceptions import InsecureRequestWarning

try:
    from urllib3.exceptions import InsecurePlatformWarning
except ImportError:  # pragma: no cover - 新版 urllib3 可能已移除
    InsecurePlatformWarning = None  # type: ignore[assignment,misc]

# 禁用 SSL 警告
requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
if InsecurePlatformWarning is not None:
    requests.packages.urllib3.disable_warnings(InsecurePlatformWarning)


# ---------------------------------------------------------------------------
# 联网搜索：把题目相关资料一起交给 AI，弥补模型无内置搜索的短板
# ---------------------------------------------------------------------------


class WebSearcher:
    """
    百度 AI 搜索（千帆）联网检索：官方 API、国内直连、结果结构化。

    - 端点：POST https://qianfan.baidubce.com/v2/ai_search/web_search
    - 鉴权：Authorization: Bearer <bce-v3/ALTAK-...>（百度智能云实名后创建的 API Key）
    - 免费额度：每月 1500 次（约每天 50 次），本脚本一天一次，长期免费
    - 同一题干只搜一次，结果缓存在实例内供多账号复用

    任何异常都只记告警并返回空串，绝不影响签到等主任务。
    """

    API_URL = "https://qianfan.baidubce.com/v2/ai_search/web_search"
    MATERIAL_MAX_CHARS = 8000
    QUERY_MAX_CHARS = 70  # 百度只检索前 72 字符（汉字算 2 个，约 36 汉字）

    def __init__(
        self,
        log: Callable[..., None],
        api_key: str = "",
        result_count: int = 5,
        timeout: int = 30,
    ) -> None:
        self._log = log
        self.api_key = (api_key or "").strip()
        self.result_count = max(1, min(result_count, 10))
        self.timeout = max(1, timeout)
        self._cache: Dict[str, str] = {}

    @property
    def enabled(self) -> bool:
        return bool(self.api_key)

    def search(self, query: str) -> str:
        """返回可直接拼进 prompt 的资料文本；失败或无结果返回空串。"""
        if not self.enabled or not query.strip():
            return ""
        if query in self._cache:
            self._log(f"💾 复用已缓存的搜索资料（{len(self._cache[query])} 字）")
            return self._cache[query]

        try:
            payload = {
                "messages": [{"role": "user", "content": query[: self.QUERY_MAX_CHARS]}],
                "search_source": "baidu_search_v2",
                "resource_type_filter": [{"type": "web", "top_k": self.result_count}],
            }
            response = requests.post(
                self.API_URL,
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
            result = response.json()

            # 百度以 code != 0 表示业务错误（如 QUOTA_USER_DAILY_FREE 免费额度用完）
            code = result.get("code")
            if code not in (None, 0):
                self._log(f"⚠️ 百度搜索返回错误码 {code}: {result.get('message', '')}")
                return ""

            references = result.get("references", [])
            blocks = []
            for item in references[: self.result_count]:
                # 百度千帆该接口实际返回 content / website；兼容 description / site_name
                content = item.get("content") or item.get("description") or ""
                if content:
                    title = item.get("title", "")
                    site = item.get("website") or item.get("site_name") or ""
                    header = f"{title}（{site}）" if site else title
                    blocks.append(f"{header}\n{content}".strip())
            material = "\n\n".join(blocks)[: self.MATERIAL_MAX_CHARS]

            if not material:
                self._log("⚠️ 联网搜索未取得有效资料，仅用题目询问 AI")
                return ""
            self._cache[query] = material
            self._log(f"📚 联网搜索取得 {len(material)} 字资料（{len(references)} 条结果）")
            return material
        except Exception as exc:
            self._log(f"⚠️ 联网搜索失败（{exc}），仅用题目询问 AI")
            return ""


# ---------------------------------------------------------------------------
# 主任务
# ---------------------------------------------------------------------------


class BeiJingHyundai:
    """北京现代APP自动任务类"""

    # 基础配置
    NAME = "北京现代 APP 自动任务"
    BASE_URL = "https://bm2-api.bluemembers.com.cn"

    # API endpoints
    API_USER_INFO = "/v1/app/account/users/info"
    API_MY_SCORE = "/v1/app/user/my_score"
    API_TASK_LIST = "/v1/app/user/task/list"
    API_SIGN_LIST = "/v1/app/user/reward_list"
    API_SIGN_SUBMIT = "/v1/app/user/reward_report"
    API_ARTICLE_LIST = "/v1/app/white/article/list2"
    API_ARTICLE_DETAIL = "/v1/app/white/article/detail_app/{}"
    API_ARTICLE_SCORE_SUBMIT = "/v1/app/score"
    API_QUESTION_INFO = "/v1/app/special/daily/ask_info"
    API_QUESTION_SUBMIT = "/v1/app/special/daily/ask_answer"

    # 预设的备用 share_user_hid 列表
    BACKUP_HIDS = [
        "a6688ec1a9ee429fa7b68d50e0c92b1f",
        "bb8cd2e44c7b45eeb8cc5f7fa71c3322",
        "5f640c50061b400c91be326c8fe0accd",
        "55a5d82dacd9417483ae369de9d9b82d",
    ]

    SYSTEM_PROMPT = (
        "你是一位北京现代汽车品牌的专家，对车型配置非常熟悉。\n"
        "以下是一道单选题，请只从题目实际列出的选项里选择正确答案。\n"
        "注意：题目可能只给出 2 个或 3 个选项，并非永远 4 个。\n"
        "请仅输出对应选项的那个英文字母，不要输出任何其他字符。"
    )

    def __init__(self) -> None:
        """初始化实例变量"""
        self.token: str = ""  # 当前用户token
        self.user: Dict[str, Any] = {}  # 当前用户信息
        self.users: List[Dict[str, Any]] = []  # 所有用户信息列表
        self.correct_answer: str = ""  # 正确答案
        self.preset_answer: str = ""  # 预设答案
        self.ai_hunyuan_api_key: str = ""  # 腾讯混元AI APIKey（兼容旧环境变量）
        self.ai_glm_api_key: str = ""  # 智谱 GLM AI APIKey（兼容旧环境变量）
        self.ai_api_key: str = ""  # 通用 AI APIKey
        self.ai_request_url: str = ""  # AI 请求地址
        self.ai_model: str = ""  # AI 模型
        self.ai_request_params: str = ""  # AI 请求参数（JSON字符串格式）
        self.searcher: Optional[WebSearcher] = None  # 联网搜索器
        self.wrong_answers: set = set()  # 错误答案集合
        self.log_content: str = ""  # 日志内容

    def log(self, content: str, print_to_console: bool = True) -> None:
        """添加日志"""
        if print_to_console:
            print(content)
        self.log_content += content + "\n"

    def push_notification(self) -> None:
        """推送通知"""
        try:
            QLAPI.notify(self.NAME, self.log_content)  # type: ignore[name-defined]
        except NameError:
            print(f"\n\n🚀 推送通知\n\n{self.NAME}\n\n{self.log_content}")

    def make_request(self, method: str, endpoint: str, **kwargs: Any) -> Dict[str, Any]:
        """发送API请求"""
        url = f"{self.BASE_URL}{endpoint}"
        headers = {"token": self.token, "device": "mp"}
        if "headers" not in kwargs:
            kwargs["headers"] = headers
        else:
            kwargs["headers"].update(headers)
        try:
            response = requests.request(method, url, timeout=30, **kwargs)
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as exc:
            self.log(f"❌ API request failed: {exc}")
            return {"code": -1, "msg": str(exc)}

    # -- 用户信息 / 积分 -----------------------------------------------------

    def get_user_info(self) -> Dict[str, Any]:
        """获取用户信息，失败返回空字典"""
        response = self.make_request("GET", self.API_USER_INFO)
        print(f"get_user_info API response ——> {response}")

        if response.get("code") == 0:
            data = response.get("data", {})
            phone = data.get("phone", "")
            masked_phone = f"{phone[:3]}******{phone[-2:]}"
            return {
                "token": self.token,
                "hid": data.get("hid", ""),
                "nickname": data.get("nickname", ""),
                "phone": masked_phone,
                "score_value": data.get("score_value", 0),
                "share_user_hid": "",
                "task": {"sign": False, "view": False, "question": False},
            }

        self.log(f"❌ 账号已失效, 请重新获取 token: {self.token}")
        return {}

    def get_score_details(self) -> None:
        """显示积分详情，包括总积分、今日变动和最近记录"""
        params = {"page_no": "1", "page_size": "10"}
        response = self.make_request("GET", self.API_MY_SCORE, params=params)
        print(f"get_score_details API response ——> {response}")

        if response.get("code") != 0:
            return

        data = response.get("data", {})
        today = datetime.now().strftime("%Y-%m-%d")
        points_record = data.get("points_record", {})
        today_records = [
            record
            for record in points_record.get("list", [])
            if record.get("created_at", "").startswith(today)
        ]

        today_score = sum(
            int(str(record.get("score_str", "0")).strip().lstrip("+"))
            for record in today_records
        )
        today_score_str = f"+{today_score}" if today_score > 0 else str(today_score)
        self.log(
            f"🎉 总积分: {data.get('score', 0)} | 今日积分变动: {today_score_str} | "
            f"当月到期: {data.get('current_month', 0)} | 半年内到期: {data.get('half_year', 0)}"
        )

        if today_records:
            self.log("今日积分记录：")
            for record in today_records:
                self.log(
                    f"{record.get('created_at', '')} {record.get('desc', '')} {record.get('score_str', '')}"
                )
        else:
            self.log("今日暂无积分变动")

    # -- 任务状态 -----------------------------------------------------------

    def check_task_status(self, user: Dict[str, Any]) -> None:
        """检查任务状态"""
        response = self.make_request("GET", self.API_TASK_LIST)
        print(f"get_task_status API response ——> {response}")

        if response.get("code") != 0:
            self.log(f'❌ 获取任务列表失败: {response.get("msg", "未知错误")}')
            return

        actions = response.get("data", {})
        task_keys = {
            "action4": ("sign", "签到任务"),
            "action12": ("view", "浏览文章任务"),
            "action39": ("question", "答题任务"),
        }
        for action_key, (task_key, label) in task_keys.items():
            if action_key in actions:
                user["task"][task_key] = actions[action_key].get("status") == 1
            else:
                self.log(f"❌ task list {action_key} {label} 不存在")

    # -- 签到 ---------------------------------------------------------------

    def get_sign_info(self) -> None:
        """执行签到任务：多次尝试取最高积分后提交"""
        max_attempts = 5
        best_score = 0
        best_params = None

        for attempt in range(max_attempts):
            response = self.make_request("GET", self.API_SIGN_LIST)
            print(f"get_sign_info (attempt {attempt + 1}) API response ——> {response}")

            if response.get("code") != 0:
                self.log(f'❌ 获取签到列表失败: {response.get("msg", "未知错误")}')
                break

            data = response.get("data", {})
            hid = data.get("hid", "")
            reward_hash = data.get("rewardHash", "")

            for item in data.get("list", []):
                if item.get("hid") == hid:
                    current_score = item.get("score", 0)
                    self.log(
                        f"第{attempt + 1}次获取签到列表: score={current_score} "
                        f"hid={hid} rewardHash={reward_hash}"
                    )
                    if current_score > best_score:
                        best_score = current_score
                        best_params = (hid, reward_hash, current_score)
                    self.log(f"当前可获得签到积分: {best_score}")
                    break

            if attempt < max_attempts - 1:
                self.log("继续尝试获取更高积分, 延时5-10s")
                time.sleep(random.randint(5, 10))
            else:
                self.log("即将提交签到, 延时3-4s")
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
        response = self.make_request("POST", self.API_SIGN_SUBMIT, json=json_data)
        print(f"submit_sign API response ——> {response}")

        if response.get("code") == 0:
            self.log(f"✅ 签到成功 | 积分 +{score}")
        else:
            self.log(f'❌ 签到失败: {response.get("msg", "未知错误")}')

    # -- 文章浏览 -----------------------------------------------------------

    def get_article_list(self) -> List[str]:
        """获取文章列表，随机挑最多 3 篇"""
        params = {"page_no": "1", "page_size": "20", "type_hid": ""}
        response = self.make_request("GET", self.API_ARTICLE_LIST, params=params)
        print(f"get_article_list API response ——> {response}")

        if response.get("code") == 0:
            data = response.get("data", {})
            article_list = [
                item.get("data_id", "")
                for item in data.get("list", [])
                if item.get("data_id")
            ]
            return random.sample(article_list, min(3, len(article_list)))

        self.log(f'❌ 获取文章列表失败: {response.get("msg", "未知错误")}')
        return []

    def get_article_detail(self, article_id: str) -> None:
        """浏览文章"""
        self.log(f"浏览文章 article_id: {article_id}")
        endpoint = self.API_ARTICLE_DETAIL.format(article_id)
        try:
            response = self.make_request("GET", endpoint)
            if response.get("code") == -1:
                self.log(f"⚠️ 文章浏览异常: {response.get('msg', '未知错误')}")
            else:
                self.log("✅ 文章浏览成功")
        except Exception as exc:
            self.log(f"❌ 文章浏览过程中发生异常: {exc}")

    def submit_article_score(self) -> None:
        """提交文章积分"""
        json_data = {"ctu_token": "", "action": 12}
        response = self.make_request(
            "POST", self.API_ARTICLE_SCORE_SUBMIT, json=json_data
        )
        print(f"submit_article_score API response ——> {response}")

        if response.get("code") == 0:
            score = response.get("data", {}).get("score", 0)
            self.log(f"✅ 浏览文章成功 | 积分 +{score}")
        else:
            self.log(f'❌ 浏览文章失败: {response.get("msg", "未知错误")}')

    # -- 答题 ---------------------------------------------------------------

    def get_question_info(self, share_user_hid: str) -> None:
        """执行答题任务"""
        params = {"date": datetime.now().strftime("%Y%m%d")}
        response = self.make_request("GET", self.API_QUESTION_INFO, params=params)
        print(f"get_question_info API response ——> {response}")
        if response.get("code") != 0:
            self.log(f'❌ 获取问题失败: {response.get("msg", "未知错误")}')
            return

        data = response.get("data", {})
        # state: 1=未答题 2=已答对 3=答错且无人帮忙 4=答错但有人帮忙
        if data.get("state") == 3:
            self.log("今日已答题但回答错误，当前无人帮助答题，跳过")
            return
        if data.get("state") != 1:
            answer = self._first_answer(data.get("answer"))
            if answer in ("A", "B", "C", "D"):
                self.correct_answer = answer
                self.log(f"今日已答题，跳过，答案：{answer}")
            else:
                self.log("今日已答题，但未获取到答案，跳过")
            return

        question_info = data.get("question_info", {})
        questions_hid = question_info.get("questions_hid", "")

        # 构建问题字符串，只包含未被标记为错误的选项
        question_str = f"{question_info.get('content', '')}\n"
        valid_options = []
        for option in question_info.get("option", []):
            if option.get("option") not in self.wrong_answers:
                valid_options.append(option)
                question_str += f'{option.get("option", "")}. {option.get("option_content", "")}\n'
            else:
                self.log(
                    f"跳过错误选项 {option.get('option', '')}. {option.get('option_content', '')}"
                )

        self.log(f"\n问题详情:\n{question_str}")

        if len(valid_options) == 1:
            answer = valid_options[0]["option"]
            self.log(f"仅剩一个选项，使用答案: {answer}")
            time.sleep(random.randint(3, 5))
            self.submit_question_answer(questions_hid, answer, share_user_hid)
            return

        answer = self.get_question_answer(question_str)
        time.sleep(random.randint(3, 5))
        self.submit_question_answer(questions_hid, answer, share_user_hid)

    @staticmethod
    def _first_answer(value: Any) -> str:
        """从已答题接口取答案字母，兼容 'D.功率103kw...' 这类带解析的返回"""
        raw = str(value[0]).strip() if isinstance(value, (list, tuple)) and value else str(value or "").strip()
        if not raw:
            return ""
        if raw in ("A", "B", "C", "D"):
            return raw
        # 形如 "D.功率103kw，扭矩211N·m" -> 取首位字母
        match = re.match(r"^([A-D])[\.．、:：\s]", raw)
        return match.group(1) if match else raw

    # -- JWT 解析（与 Android 版 User.parseJwt* 行为一致）--------------------

    @staticmethod
    def _parse_jwt_payload(token: str) -> Dict[str, Any]:
        """解析 JWT payload（URL-safe base64，无 padding）；解析失败返回空字典"""
        try:
            parts = token.split(".")
            if len(parts) < 2:
                return {}
            padded = parts[1] + "=" * (-len(parts[1]) % 4)
            payload = base64.urlsafe_b64decode(padded.encode("ascii"))
            data = json.loads(payload.decode("utf-8"))
            return data if isinstance(data, dict) else {}
        except Exception:
            return {}

    @staticmethod
    def _jwt_exp(token: str) -> int:
        """JWT 中的 exp（秒级 Unix 时间戳），解析失败返回 0"""
        value = BeiJingHyundai._parse_jwt_payload(token).get("exp", 0)
        try:
            return int(value)
        except (TypeError, ValueError):
            return 0

    @staticmethod
    def _jwt_hid(token: str) -> str:
        """JWT 中的 user_hid，解析失败返回空串"""
        return str(BeiJingHyundai._parse_jwt_payload(token).get("user_hid") or "")

    @staticmethod
    def _format_expire(exp: int) -> str:
        """把秒级时间戳格式化为可读时间；0 返回未知"""
        if not exp:
            return "未知"
        return datetime.fromtimestamp(exp).strftime("%Y-%m-%d %H:%M:%S")

    @staticmethod
    def _build_search_query(question: str) -> str:
        """用题干（去掉选项）作为搜索关键词"""
        for line in question.splitlines():
            stem = line.strip()
            if stem:
                return stem[:120]
        return ""

    def get_ai_answer(self, question: str) -> str:
        """获取通用AI答案：先联网搜资料，再把资料 + 题目一起提交给 AI"""
        if not (self.ai_api_key and self.ai_request_url and self.ai_model):
            return ""

        # 1. 联网搜索资料（失败时自动降级为空）
        material = ""
        if self.searcher and self.searcher.enabled:
            query = self._build_search_query(question)
            self.log(f"🔍 联网搜索题干: {query}")
            material = self.searcher.search(query)

        # 2. 拼装 prompt
        if material:
            user_content = (
                "以下是联网搜索到的资料，可能包含本题相关信息，仅供参考；"
                "若与题目无关请忽略，必要时结合你自己的知识判断。\n"
                "===== 资料开始 =====\n"
                f"{material}\n"
                "===== 资料结束 =====\n\n"
                f"题目与选项：\n{question}\n\n"
                "请只输出正确答案对应的那个英文字母。"
            )
        else:
            user_content = question

        json_data: Dict[str, Any] = {
            "model": self.ai_model,
            "messages": [
                {"role": "system", "content": self.SYSTEM_PROMPT},
                {"role": "user", "content": user_content},
            ],
        }

        # 额外请求参数（如 hunyuan 的搜索增强开关）并入请求体
        if self.ai_request_params:
            try:
                json_data.update(json.loads(self.ai_request_params))
            except json.JSONDecodeError as exc:
                self.log(f"❌ AI 请求参数解析失败: {exc}")

        headers = {
            "Authorization": f"Bearer {self.ai_api_key}",
            "Content-Type": "application/json",
        }

        try:
            self.log(
                f"通用 AI API request ——> model={self.ai_model}, "
                f"messages={len(json_data['messages'])}, user_chars={len(user_content)}"
            )
            response = requests.post(
                self.ai_request_url,
                headers=headers,
                json=json_data,
                timeout=60,
            )
            print(f"通用 AI API response status ——> {response.status_code}")
            response.raise_for_status()
            response_json = response.json()

            choices = response_json.get("choices", [])
            if not choices:
                self.log("❌ AI 未返回有效 choices")
                return ""

            message = choices[0].get("message", {})
            content = message.get("content") or message.get("reasoning_content") or ""
            return self._extract_answer(str(content))
        except Exception as exc:
            self.log(f"通用 AI API 请求失败: {exc}")
            return ""

    def _extract_answer(self, raw: str) -> str:
        """从 AI 回复中提炼 A/B/C/D，并排除已知错误答案"""
        normalized = (
            raw.upper()
            .replace("Ａ", "A")
            .replace("Ｂ", "B")
            .replace("Ｃ", "C")
            .replace("Ｄ", "D")
            .strip()
        )
        valid_answers = {"A", "B", "C", "D"} - self.wrong_answers
        if not valid_answers:
            return ""

        # 优先：回复本身就是一个字母
        if normalized in valid_answers:
            return normalized

        # 其次：独立的单个字母（如 "正确答案是 C。"）
        match = re.search(r"(?<![A-Z])[ABCD](?![A-Z])", normalized)
        if match and match.group() in valid_answers:
            return match.group()

        # 兜底：回复里出现过的所有字母交集
        found = set(normalized) & valid_answers
        if found:
            return sorted(found)[0]

        self.log("❌ 没有找到符合的 AI 答案")
        return ""

    def get_question_answer(self, question: str) -> str:
        """获取答题答案，优先级：历史正确答案 > 预设答案 > AI > 随机"""
        if self.correct_answer:
            self.log(f"使用历史正确答案: {self.correct_answer}")
            return self.correct_answer

        if self.preset_answer:
            self.log(f"使用预设答案: {self.preset_answer}")
            return self.preset_answer

        if self.ai_api_key and self.ai_request_url and self.ai_model:
            ai_answer = self.get_ai_answer(question)
            if ai_answer:
                self.log(f"使用 AI 答案: {ai_answer}")
                return ai_answer

        answer = self.get_random_answer()
        self.log(f"随机答题，答案: {answer}")
        return answer

    def get_random_answer(self) -> str:
        """获取随机答案，排除已知错误答案"""
        available = {"A", "B", "C", "D"} - self.wrong_answers
        if not available:
            self.wrong_answers.clear()
            available = {"A", "B", "C", "D"}
        return random.choice(sorted(available))

    def get_answered_question(self) -> None:
        """从已答题账号获取答案"""
        params = {"date": datetime.now().strftime("%Y%m%d")}
        response = self.make_request("GET", self.API_QUESTION_INFO, params=params)
        print(f"get_answered_question API response ——> {response}")
        if response.get("code") != 0:
            self.log(f'❌ 从已答题账号获取问题失败: {response.get("msg", "未知错误")}')
            return

        answer = self._first_answer(response.get("data", {}).get("answer"))
        if answer in ("A", "B", "C", "D"):
            self.correct_answer = answer
            self.log(f"从已答题账号获取到答案：{answer}")
            return
        self.log("从已答题账号获取答案失败")

    def submit_question_answer(
        self, question_id: str, answer: str, share_user_hid: str
    ) -> None:
        """提交答题答案"""
        json_data: Dict[str, Any] = {
            "answer": answer,
            "questions_hid": question_id,
            "ctu_token": "",
        }
        if share_user_hid:
            json_data["date"] = datetime.now().strftime("%Y%m%d")
            json_data["share_user_hid"] = share_user_hid

        response = self.make_request("POST", self.API_QUESTION_SUBMIT, json=json_data)
        print(f"submit_question_answer API response ——> {response}")

        if response.get("code") == 0:
            data = response.get("data", {})
            if data.get("state") == 3:  # 答错
                self.wrong_answers.add(answer)
                if self.correct_answer == answer:
                    self.correct_answer = ""
                if self.preset_answer == answer:
                    self.preset_answer = ""
                self.log("❌ 答题错误")
            elif data.get("state") == 2:  # 答对
                if self.correct_answer != answer:
                    self.correct_answer = answer
                self.log(f"✅ 答题正确 | 积分 +{data.get('answer_score', 0)}")
        else:
            self.log(f'❌ 答题失败: {response.get("msg", "未知错误")}')

    def get_backup_share_hid(self, user_hid: str) -> str:
        """从备用 hid 列表中获取一个不同于用户自身的 hid"""
        available = [hid for hid in self.BACKUP_HIDS if hid != user_hid]
        return random.choice(available) if available else ""

    # -- 配置加载 -----------------------------------------------------------

    @staticmethod
    def _env_int(name: str, default: int) -> int:
        try:
            return int(os.getenv(name, "") or default)
        except ValueError:
            return default

    def _load_search_config(self) -> None:
        """读取百度 AI 搜索配置（SEARCH_API_KEY 即百度的 bce-v3 API Key）"""
        api_key = os.getenv("SEARCH_API_KEY", "").strip()
        self.searcher = WebSearcher(
            log=self.log,
            api_key=api_key,
            result_count=self._env_int("SEARCH_RESULT_COUNT", 5),
            timeout=self._env_int("SEARCH_TIMEOUT", 30),
        )
        if self.searcher.enabled:
            self.log("🔎 联网搜索已启用: 百度 AI 搜索（千帆，每月免费 1500 次）")
        else:
            self.log("🔎 未设置 SEARCH_API_KEY，联网搜索未启用，AI 将只依据题目分析")

    def _load_ai_config(self) -> None:
        """读取 AI 配置，兼容旧版 HUNYUAN / GLM 环境变量"""
        self.ai_api_key = os.getenv("AI_API_KEY", "")
        self.ai_request_url = os.getenv("AI_REQUEST_URL", "")
        self.ai_model = os.getenv("AI_MODEL", "")
        self.ai_request_params = os.getenv("AI_REQUEST_PARAMS", "")

        if self.ai_api_key and self.ai_request_url and self.ai_model:
            self.log("💯 已获取到通用 AI 配置, 使用通用 AI 答题")
            return

        self.ai_hunyuan_api_key = os.getenv("HUNYUAN_API_KEY", "")
        if self.ai_hunyuan_api_key:
            self.ai_api_key = self.ai_hunyuan_api_key
            self.ai_request_url = "https://api.hunyuan.cloud.tencent.com/v1/chat/completions"
            self.ai_model = "hunyuan-turbo"
            self.ai_request_params = json.dumps(
                {
                    "enable_enhancement": True,
                    "force_search_enhancement": True,
                    "enable_instruction_search": True,
                }
            )
            self.log("💯 已获取到腾讯混元 AI 配置, 使用腾讯混元 AI 答题")
            return

        self.ai_glm_api_key = os.getenv("GLM_API_KEY", "")
        if self.ai_glm_api_key:
            self.ai_api_key = self.ai_glm_api_key
            self.ai_request_url = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
            self.ai_model = "glm-4.5-flash"
            self.ai_request_params = json.dumps({"do_sample": False})
            self.log("💯 已获取到智谱 GLM AI 配置, 使用智谱 GLM AI 答题")
            return

        self.log("⚠️ 未设置 AI_API_KEY/AI_REQUEST_URL/AI_MODEL，答题将走随机或历史答案")

    @staticmethod
    def _load_tokens() -> Tuple[List[Tuple[str, int, str]], List[Tuple[str, int, str]]]:
        """读取并去重 token，保持环境变量顺序。

        返回 (有效列表, 过期列表)，元素为 (token, exp秒, hid)。
        解析 JWT 可提前排除已过期 token；解析失败（exp=0）保留，走接口实测。
        """
        tokens: List[str] = []
        seen = set()

        inline = os.getenv("BJXD", "")
        if inline:
            for token in inline.split(","):
                token = token.strip()
                if token and token not in seen:
                    tokens.append(token)
                    seen.add(token)

        index = 1
        empty_count = 0
        while empty_count < 5:
            token = (os.getenv(f"BJXD{index}") or "").strip()
            if not token:
                empty_count += 1
            elif token not in seen:
                empty_count = 0
                tokens.append(token)
                seen.add(token)
            index += 1

        valid: List[Tuple[str, int, str]] = []
        expired: List[Tuple[str, int, str]] = []
        now = int(time.time())
        for token in tokens:
            exp = BeiJingHyundai._jwt_exp(token)
            hid = BeiJingHyundai._jwt_hid(token)
            if exp and now > exp:
                expired.append((token, exp, hid))
            else:
                valid.append((token, exp, hid))
        return valid, expired

    # -- 主流程 -------------------------------------------------------------

    def run(self) -> None:
        """运行主程序"""
        try:
            from dotenv import load_dotenv

            load_dotenv()
            self.log("✅ dotenv 成功加载 .env 文件", print_to_console=True)
        except ImportError:
            self.log("⚠️ 缺少 dotenv 库, 青龙环境请忽略, 本地运行请安装此库")

        tokens, expired_tokens = self._load_tokens()
        for _token, exp, hid in expired_tokens:
            self.log(
                f"⏰ 账号已过期，跳过（JWT hid={hid or '未知'}, "
                f"exp={self._format_expire(exp)}）"
            )
        if not tokens:
            self.log("⛔️ 未获取到有效 token, 请检查环境变量 BJXD 或 BJXD1/BJXD2/...（或 token 已全部过期）")
            self.push_notification()
            return

        self.log(f"👻 共获取到用户 token {len(tokens)} 个（已排除 JWT 中已过期的）")
        self._load_ai_config()
        self._load_search_config()

        self.preset_answer = os.getenv("BJXD_ANSWER", "").upper()
        if self.preset_answer:
            if self.preset_answer in ("A", "B", "C", "D"):
                self.log(f"📝 已获取预设答案: {self.preset_answer}")
            else:
                self.preset_answer = ""
                self.log("❌ 预设答案格式错误，仅支持 A/B/C/D")

        self.log("获取用户信息")
        for token, exp, jwt_hid in tokens:
            self.token = token
            # JWT 解析出的 hid 先存着：即使接口失败也能确认 token 对应谁；
            # 仍调用接口获取昵称/手机号，同时验证 token 在有效期内确实可用。
            user = self.get_user_info()
            if user:
                user["token_expire"] = exp
                if not user.get("hid") and jwt_hid:
                    user["hid"] = jwt_hid
                self.users.append(user)
                self.log(
                    f"✅ 账号有效: {user.get('nickname', '未知')} "
                    f"({user.get('phone', '未知')}, hid={user.get('hid', '')}), "
                    f"Token过期: {self._format_expire(exp)}"
                )
            else:
                self.log(f"⚠️ token 获取账号信息失败（JWT hid={jwt_hid or '未知'}, exp={self._format_expire(exp)}），已跳过")
            time.sleep(random.randint(3, 5))

        if not self.users:
            self.log("❌ 未获取到有效用户")
            self.push_notification()
            return

        for index, user in enumerate(self.users):
            prev_index = index - 1 if index > 0 else len(self.users) - 1
            if len(self.users) > 1 and self.users[prev_index].get("hid") != user.get(
                "hid"
            ):
                user["share_user_hid"] = self.users[prev_index].get("hid", "")
            else:
                user["share_user_hid"] = self.get_backup_share_hid(user.get("hid", ""))

        self.log("\n============ 执行任务 ============")
        for index, user in enumerate(self.users, 1):
            self.token = user["token"]
            self.user = user

            if index > 1:
                self.log("\n进行下一个账号, 等待 5-10 秒...")
                time.sleep(random.randint(5, 10))

            self.log(f"\n======== ▷ 第 {index} 个账号 ◁ ========")
            self.log(
                f"👻 用户名: {user.get('nickname', '未知')} | "
                f"手机号: {user.get('phone', '未知')} | "
                f"积分: {user.get('score_value', 0)}\n"
                f"🆔 用户hid: {user.get('hid', '')}\n"
                f"🆔 分享hid: {user.get('share_user_hid', '')}\n"
                f"⏰ Token过期: {self._format_expire(user.get('token_expire', 0))}"
            )

            self.check_task_status(user)
            self.log(f"任务状态: {user['task']}")

            user_task = user.get("task", {})

            if not user_task.get("question"):
                self.get_question_info(user.get("share_user_hid", ""))
            else:
                self.log("✅ 答题任务 已完成，跳过")
                if not self.correct_answer:
                    self.get_answered_question()

            if not user_task.get("sign"):
                self.get_sign_info()
                time.sleep(random.randint(5, 10))
            else:
                self.log("✅ 签到任务 已完成，跳过")

            if not user_task.get("view"):
                article_ids = self.get_article_list()
                for article_index, article_id in enumerate(article_ids, 1):
                    self.log(f"🔄 开始处理第 {article_index}/{len(article_ids)} 篇文章")
                    try:
                        self.get_article_detail(article_id)
                    except Exception as exc:
                        self.log(f"❌ 第 {article_index} 篇文章处理失败: {exc}")
                    time.sleep(random.randint(10, 15))
                if article_ids:
                    try:
                        self.submit_article_score()
                    except Exception as exc:
                        self.log(f"❌ 提交文章积分失败: {exc}")
            else:
                self.log("✅ 浏览文章任务 已完成，跳过")

        self.log("\n============ 积分详情 ============")
        for index, user in enumerate(self.users, 1):
            if index > 1:
                self.log("\n进行下一个账号, 等待 5-10 秒...")
                time.sleep(random.randint(5, 10))
            self.token = user["token"]
            self.user = user
            self.log(f"\n======== ▷ 第 {index} 个账号 ◁ ========")
            self.log(
                f"👻 用户名: {user.get('nickname', '未知')} | "
                f"手机号: {user.get('phone', '未知')} | "
                f"⏰ Token过期: {self._format_expire(user.get('token_expire', 0))}"
            )
            self.get_score_details()

        self.push_notification()


def main() -> None:
    """入口：供 `python bjxd.py` 与 `uv run bjxd` 共用"""
    BeiJingHyundai().run()


if __name__ == "__main__":
    main()
