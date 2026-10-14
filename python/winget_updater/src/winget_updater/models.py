"""数据模型：可更新包条目与用户动作。"""

from dataclasses import dataclass
from enum import Enum


class Action(Enum):
    """用户对单个更新条目可选的 5 种操作。"""

    UPDATE_WITH_PROXY = "update_with_proxy"
    UPDATE_NO_PROXY = "update_no_proxy"
    SKIP_THIS_TIME = "skip_this_time"
    SKIP_VERSION = "skip_version"
    PERMANENT_SKIP = "permanent_skip"


@dataclass(slots=True)
class PackageUpdate:
    """一个可升级的 winget 包。"""

    name: str
    package_id: str
    version: str
    available: str
    source: str
    action: Action = Action.UPDATE_WITH_PROXY
    status: str = "等待"
    error: str = ""
    # 升级失败时从 winget 源提取的安装包链接（手动下载兜底），空 = 未取到
    installer_url: str = ""
    installer_sha: str = ""
