import datetime
import json
from pathlib import Path

from stock_save.config import get_cookie, get_settings
from stock_save.logger import setup_logging
from stock_save.models import AppSettings
from stock_save.notification import push_notification
from stock_save.xueqiu import XueqiuClient

logger = setup_logging()


def _save_json(file_path: Path, data: dict) -> None:
    file_path.parent.mkdir(parents=True, exist_ok=True)
    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def save_data_to_file(data: dict, settings: AppSettings) -> str:
    if not data or not (items := data.get("data", {}).get("items")):
        logger.error("无效的数据格式")
        return "保存失败"

    timestamp = items[0]["timestamp"]
    dt = datetime.datetime.fromtimestamp(timestamp / 1000)
    date_str = dt.strftime("%Y-%m-%d")
    file_path = settings.data_dir / f"{date_str}.json"

    exists_before = file_path.exists()
    if exists_before:
        try:
            with open(file_path, encoding="utf-8") as f:
                old_data = json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            old_data = None

        if old_data == data:
            logger.info(f"数据已存在且相同，跳过: {file_path}")
            push_notification(
                f"股票分钟:📂 {date_str}",
                f"股票 {settings.stock_symbol} 的分钟线数据 {date_str} 已存在，跳过保存",
                settings.wxpusher_app_token,
                settings.wxpusher_uids,
            )
            return "已存在"
        logger.info(f"数据已存在但内容不同，覆盖: {file_path}")

    _save_json(file_path, data)
    status = "🔄 覆盖" if exists_before else "✅"
    logger.info(f"数据已保存到: {file_path}")
    push_notification(
        f"股票分钟:{status} {date_str}",
        f"股票 {settings.stock_symbol} 的分钟线数据 {date_str} 保存成功",
        settings.wxpusher_app_token,
        settings.wxpusher_uids,
    )
    return "已覆盖" if exists_before else "保存成功"


def main() -> None:
    logger.info("股票分钟线数据保存任务开始")
    settings = get_settings()
    cookie = get_cookie(settings.cookie_config_path)

    if not cookie.xq_a_token or not cookie.u:
        logger.error("Cookie 未配置")
        push_notification(
            "股票分钟线数据获取失败",
            f"股票 {settings.stock_symbol} 的 Cookie 未配置",
            settings.wxpusher_app_token,
            settings.wxpusher_uids,
        )
        return

    client = XueqiuClient(cookie, settings.stock_symbol)
    data = client.get_minute_data()
    if not data:
        logger.error("获取数据失败")
        push_notification(
            "股票分钟线数据获取失败",
            f"股票 {settings.stock_symbol} 的分钟线数据获取失败",
            settings.wxpusher_app_token,
            settings.wxpusher_uids,
        )
        return

    save_data_to_file(data, settings)
    logger.info("股票分钟线数据保存任务结束")


if __name__ == "__main__":
    main()
