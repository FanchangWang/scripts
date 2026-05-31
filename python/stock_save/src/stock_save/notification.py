import logging

import httpx

WXPUSHER_API_URL = "https://wxpusher.zjiecode.com/api/send/message"

logger = logging.getLogger(__name__)


def push_notification(title: str, message: str, app_token: str, uids: str) -> bool:
    if not app_token or not uids:
        logger.warning("WxPusher配置不完整, 无法发送通知")
        return False

    try:
        payload = {
            "appToken": app_token,
            "content": f'<h1>{title}</h1><br/><div style="white-space: pre-wrap;">{message}</div>',
            "summary": title,
            "contentType": 2,
            "uids": [uids],
        }
        with httpx.Client(timeout=10) as client:
            resp = client.post(WXPUSHER_API_URL, json=payload)
            resp.raise_for_status()
            result = resp.json()
            if result.get("success"):
                logger.info("通知发送成功")
                return True
            logger.error(f"通知发送失败: {result.get('msg')}")
            return False
    except Exception as e:
        logger.error(f"发送通知失败: {e}")
        return False
