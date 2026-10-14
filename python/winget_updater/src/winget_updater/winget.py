"""winget CLI 调用封装：检查更新、执行更新。

winget v1.29.380 起原生支持 --proxy / --no-proxy 全局参数，
代理直接透传给 winget 子进程，无需环境变量。

表格解析：winget upgrade 无 JSON 输出且表头随系统语言变化，
因此解析不依赖表头文字，改用 ID 锚定（见 parse_upgrade_table）。

输出采集：winget 1.29.280+ 在没有控制台附着时（stdout 是管道）
会抑制进度条与 spinner，只留干净日志行。upgrade_package 因此
优先用伪控制台（ConPTY，见 conpty.py）运行 winget，让下载进度
以 \\r 覆盖帧流出，经 _FrameFeeder 转成帧流；ConPTY 不可用时
降级普通管道（二进制读取——text=True 的 universal newlines 会把
\\r 翻译成 \\n，进度帧边界会丢失）。
"""

import codecs
import os
import re
import subprocess
import threading
from collections.abc import Callable

from winget_updater.conpty import ConPtyError, run_in_pty
from winget_updater.models import PackageUpdate

PROXY_URL = "http://192.168.31.50:7890"

# winget upgrade 表格固定 5 列：名称 / ID / 版本 / 可用 / 源
_EXPECTED_COLUMNS = 5

# 包 ID：字母开头的点分串（Publisher.Product），行内无空白
_ID_RE = re.compile(r"[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)+")

# 管道降级路径的读取块大小
_READ_CHUNK = 65536


class WingetError(RuntimeError):
    """winget 调用失败。"""


def check_upgrades(use_proxy: bool = True) -> list[PackageUpdate]:
    """执行 winget upgrade，返回可升级包列表（无更新时为空列表）。"""
    cmd = ["winget", "upgrade"]
    cmd += ["--proxy", PROXY_URL] if use_proxy else ["--no-proxy"]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    except FileNotFoundError as exc:
        raise WingetError("未找到 winget 命令，请确认已安装 App Installer") from exc
    if proc.returncode != 0:
        raise WingetError(last_error_line(proc) or f"winget upgrade 退出码 {proc.returncode}")
    return parse_upgrade_table(proc.stdout)


# winget show 输出中的安装包扩展名（安装程序 URL 的判据：URL 以这些
# 扩展名结尾，而主页/许可证/发行说明等页面 URL 不匹配——不依赖
# 「安装程序 URL」标签文字，各语言 winget 输出均可用）
_INSTALLER_EXT = (
    ".exe",
    ".msi",
    ".msp",
    ".msix",
    ".appx",
    ".appxbundle",
    ".msixbundle",
    ".zip",
    ".7z",
    ".nupkg",
    ".cab",
)

_URL_RE = re.compile(r"https?://\S+")
_SHA256_RE = re.compile(r"\b[0-9a-fA-F]{64}\b")


def fetch_installer_url(package_id: str, use_proxy: bool) -> tuple[str, str] | None:
    """从 winget 源提取安装包下载地址（升级失败时供手动下载安装）。

    返回 (安装程序 URL, SHA256)；查询失败或无安装包链接时返回 None。
    winget show 查源（非本机已安装版本），URL 即源内最新可用版本。
    """
    cmd = ["winget", "show", "--id", package_id, "--exact", "--accept-source-agreements"]
    cmd += ["--proxy", PROXY_URL] if use_proxy else ["--no-proxy"]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    except (FileNotFoundError, OSError):
        return None
    if proc.returncode != 0:
        return None
    return _parse_installer_url(proc.stdout)


def _parse_installer_url(show_output: str) -> tuple[str, str] | None:
    """从 winget show 输出解析 (安装程序 URL, SHA256)，无则 None。

    取第一个以安装包扩展名结尾的 URL（多架构时通常是默认/x64）；
    SHA256 取输出中第一个 64 位十六进制串（show 里唯一出现处）。
    """
    for line in show_output.splitlines():
        match = _URL_RE.search(line)
        if match is None:
            continue
        url = match.group(0).rstrip(").,，。）]】\"'")
        if url.split("?", 1)[0].lower().endswith(_INSTALLER_EXT):
            sha_match = _SHA256_RE.search(show_output)
            return url, sha_match.group(0) if sha_match else ""
    return None


def upgrade_package(
    package_id: str,
    use_proxy: bool,
    on_line: Callable[[str, bool], None] | None = None,
    timeout: int = 3600,
) -> subprocess.CompletedProcess[str]:
    """升级指定包；use_proxy 决定是否走代理。

    输出为流式：winget 输出经 _FrameFeeder 分帧后通过
    on_line(line, carriage_return) 实时流出，供 GUI 详情面板展示。
    下载进度帧（spinner / 进度条）以 carriage_return=True 发出，
    GUI 解析其大小刷新「安装详情」label，不写入日志面板；
    普通日志行 carriage_return=False。超时由 Timer 强杀子进程兜底。

    优先用伪控制台运行（winget 无控制台附着时抑制进度条）；ConPTY
    不可用或创建失败时降级普通管道，日志行不受影响、仅无进度帧。
    """
    cmd = [
        "winget",
        "upgrade",
        "--id",
        package_id,
        "--silent",
        "--accept-package-agreements",
        "--accept-source-agreements",
        "--disable-interactivity",
    ]
    cmd += ["--proxy", PROXY_URL] if use_proxy else ["--no-proxy"]

    if on_line is None:
        return _run_pipe(cmd, None, timeout)

    feeder = _FrameFeeder(on_line)
    try:
        code = run_in_pty(cmd, feeder.feed, timeout=timeout)
    except (ConPtyError, OSError):
        if feeder.received:
            raise  # 已收到输出：子进程可能已在运行，不能降级重跑
        return _run_pipe(cmd, feeder, timeout)
    feeder.flush()
    return subprocess.CompletedProcess(cmd, code, "", "")


def _run_pipe(
    cmd: list[str],
    feeder: "_FrameFeeder | None",
    timeout: int,
) -> subprocess.CompletedProcess[str]:
    """普通管道运行 winget（ConPTY 不可用时的降级路径）。

    二进制读取（bufsize=0 + os.read）：text=True 的 universal newlines
    会把 \\r 翻译成 \\n，进度帧边界会丢失；os.read 有数据即返回，
    不缓冲整行，保证实时性。
    """
    try:
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=0,
        )
    except FileNotFoundError as exc:
        raise WingetError("未找到 winget 命令，请确认已安装 App Installer") from exc
    killer = threading.Timer(timeout, proc.kill)
    killer.start()
    try:
        stdout = proc.stdout
        if stdout is None:
            raise WingetError("无法捕获 winget 输出")
        fd = stdout.fileno()
        while True:
            chunk = os.read(fd, _READ_CHUNK)
            if not chunk:
                break
            if feeder is not None:
                feeder.feed(chunk)
    finally:
        killer.cancel()
    proc.wait()
    if feeder is not None:
        feeder.flush()
    return subprocess.CompletedProcess(cmd, proc.returncode, "", "")


# spinner：整帧只有行首空白 + 单个旋转字符
_SPINNER_CHARS = "-\\|/"
# 进度条方块字符（全块到八分之一块）
_BLOCK_CHARS = "█▉▊▋▌▍▎▏"
# 下载计量："17.8 MB / 17.8 MB" 中的 "MB /" 部分
_SIZE_RE = re.compile(r"\d+(?:\.\d+)?\s*[KMGT]?B\s*/")


class _FrameFeeder:
    """把 winget 输出的原始字节流转换成「帧」流。

    winget 在终端模式下用 \\r 覆盖同一行来画进度条（spinner / 下载条），
    行与行之间以 \\r\\n 结束；伪控制台吐出的字节流里还夹杂 VT 转义序列
    （SGR 着色、光标显隐、OSC 标题与任务栏进度等）。本类负责：

    1. 增量 UTF-8 解码（读取块边界可能切过多字节字符）；
    2. 剥离 VT 序列；光标归位（\\x1b[H）与擦行（\\x1b[K）映射为 \\r——
       它们与 \\r 一样表示「回到行首重绘」；
    3. 按 \\r / \\n 分帧：\\r\\n 算一个边界，单独的 \\r 是进度帧边界；
    4. 按内容分类：进度帧（spinner 字符 / 方块条 / "N MB / M MB" 计量）
       以 carriage_return=True 发出（GUI 原地覆盖），其余按完整行发出；
       空帧表示 winget 擦除了该行，也以进度帧发出（GUI 删除进度行）。
    """

    def __init__(self, on_line: Callable[[str, bool], None]) -> None:
        self._on_line = on_line
        self._decoder = codecs.getincrementaldecoder("utf-8")("replace")
        self._buffer = ""
        self._pending_cr = False
        self._pending_escape = ""
        # 上一个边界是否为行结束（\\n / \\r\\n）或流开始：行首处的定位
        # 序列（如 \\x1b[H 后接换行）是 no-op，不产生空帧
        self._last_was_line_end = True
        self.received = False

    def feed(self, chunk: bytes) -> None:
        """喂入一块原始字节（在读取方线程中同步调用）。"""
        if chunk:
            self.received = True
        text = self._pending_escape + self._decoder.decode(chunk)
        self._pending_escape = ""
        self._consume(text)

    def flush(self) -> None:
        """流结束：刷新解码器与未完成的帧（无内容则不发空帧）。"""
        text = self._decoder.decode(b"", True)
        if text:
            self._consume(text)
        if self._buffer:
            self._emit(self._buffer)
            self._buffer = ""
        self._pending_cr = False
        self._pending_escape = ""

    # ---------- 内部 ----------

    def _consume(self, text: str) -> None:
        index = 0
        length = len(text)
        while index < length:
            char = text[index]
            if char == "\x1b":
                index, line_restart = self._scan_escape(text, index)
                if line_restart:
                    self._boundary()
                continue
            if char == "\r":
                self._boundary()
                self._pending_cr = True
                index += 1
                continue
            if char == "\n":
                # \r\n 已在上一个 \r 处分帧；单独的 \n 是行结束
                if not self._pending_cr:
                    self._boundary()
                self._pending_cr = False
                self._last_was_line_end = True
                index += 1
                continue
            self._pending_cr = False
            self._buffer += char
            index += 1

    def _boundary(self) -> None:
        """帧边界：把已累积文本作为一帧发出。

        行首（上一边界是行结束/流开始）且无内容时跳过——\\x1b[H 等
        定位序列在换行后是 no-op；行中（上一边界是 \\r）的空帧保留，
        它表示 winget 擦除了该行（GUI 删除进度行）。
        """
        if not self._buffer and self._last_was_line_end:
            return
        self._emit(self._buffer)
        self._buffer = ""
        self._last_was_line_end = False

    def _emit(self, frame: str) -> None:
        self._on_line(frame, _is_progress_frame(frame))

    def _scan_escape(self, text: str, index: int) -> tuple[int, bool]:
        """解析 text[index] 处的 ESC 序列。

        返回 (序列后的下标, 是否等价于 \\r)。序列在块尾被截断时把残部
        存入 _pending_escape 并返回 (len(text), False)，等待下一块拼接。
        """
        start = index
        index += 1
        if index >= len(text):
            self._pending_escape = text[start:]
            return len(text), False
        char = text[index]
        if char == "[":
            # CSI：参数字节 0x30-0x3f + 中间字节 0x20-0x2f + 终结字节 0x40-0x7e
            index += 1
            while index < len(text) and "\x30" <= text[index] <= "\x3f":
                index += 1
            while index < len(text) and "\x20" <= text[index] <= "\x2f":
                index += 1
            if index >= len(text):
                self._pending_escape = text[start:]
                return len(text), False
            final = text[index]
            if not "\x40" <= final <= "\x7e":
                return index + 1, False  # 非法终结字节，丢弃到此处
            # H/f 光标归位、K 擦行：等价于 \r（行首重绘）；
            # 其余（m 着色、J 清屏、C 右移、h/l 模式开关等）直接丢弃
            return index + 1, final in ("H", "f", "K")
        if char == "]":
            # OSC：以 BEL 或 ST（ESC \\）结束
            index += 1
            while index < len(text):
                if text[index] == "\x07":
                    return index + 1, False
                if text[index] == "\x1b":
                    if index + 1 >= len(text):
                        break
                    if text[index + 1] == "\\":
                        return index + 2, False
                index += 1
            self._pending_escape = text[start:]
            return len(text), False
        if char in "()#%":
            # 字符集选择等双字节转义序列
            if index + 1 >= len(text):
                self._pending_escape = text[start:]
                return len(text), False
            return index + 2, False
        return index + 1, False


def _is_progress_frame(frame: str) -> bool:
    """判断帧是否为 winget 进度帧（spinner / 下载进度条）。

    空帧 = winget 擦除了该行，按进度帧处理（GUI 删除进度行）。
    内容判据（本机 winget 1.29.380 实测形态）：整帧仅 spinner 旋转
    字符、含方块条字符、或含 "N MB / M MB" 计量。日志行（已找到 /
    正在下载 URL / 已成功验证等）均不匹配，按完整行保留。
    """
    if not frame:
        return True
    stripped = frame.strip()
    if len(stripped) == 1 and stripped in _SPINNER_CHARS:
        return True
    return any(ch in _BLOCK_CHARS for ch in frame) or bool(_SIZE_RE.search(frame))


# 完整计量："17.0 MB / 29.6 MB"（首帧可能为 KB，如 "1024 KB / 29.6 MB"）
_PROGRESS_RE = re.compile(r"(\d+(?:\.\d+)?)\s*(KB|MB|GB)\s*/\s*(\d+(?:\.\d+)?)\s*(KB|MB|GB)")

# 各单位到 MB 的换算系数
_UNIT_TO_MB = {"KB": 1 / 1024, "MB": 1.0, "GB": 1024.0}


def parse_progress(frame: str) -> tuple[float, float] | None:
    """从 winget 下载进度帧提取 (已下载 MB, 总大小 MB)。

    典型帧："  ██████▏  17.0 MB / 29.6 MB"；首帧单位可能是 KB
    （"1024 KB / 29.6 MB"）。非进度帧（spinner / 日志行 / 空帧）
    返回 None。单位统一折算为 MB（浮点）。
    """
    match = _PROGRESS_RE.search(frame)
    if match is None:
        return None
    done = float(match.group(1)) * _UNIT_TO_MB[match.group(2)]
    total = float(match.group(3)) * _UNIT_TO_MB[match.group(4)]
    return done, total


def parse_upgrade_table(text: str) -> list[PackageUpdate]:
    """解析 winget upgrade 的表格输出。

    1. 定位分隔线（连续 - 组成的行），其前一行必须是 5 列表头；
       winget 无可用更新时不输出表格（无分隔线），此时返回空列表；
    2. 数据行逐行用 ID 锚定解析（见 _parse_row）；
    3. 汇总行（如 "15 升级可用。"）不含 ID，自然被跳过。
    """
    lines = text.splitlines()
    sep_index = next(
        (i for i, line in enumerate(lines) if line.strip() and set(line.strip()) == {"-"}),
        None,
    )
    if sep_index is None:
        return []
    if sep_index == 0:
        raise WingetError("无法解析 winget upgrade 输出（缺少表头）")

    starts = _column_starts(lines[sep_index - 1])
    if len(starts) != _EXPECTED_COLUMNS:
        raise WingetError(f"无法解析 winget upgrade 输出（预期 {_EXPECTED_COLUMNS} 列）")

    updates: list[PackageUpdate] = []
    for line in lines[sep_index + 1 :]:
        pkg = _parse_row(line)
        if pkg is not None:
            updates.append(pkg)
    return updates


def _parse_row(line: str) -> PackageUpdate | None:
    """解析单行数据；无法识别时返回 None。

    winget 重定向到文件时 CJK 名称按显示宽度补垫，各列字符串偏移
    随行变化，无法用表头偏移定宽切列，因此改用 ID 锚定：
    - ID 无空白且为点分形式（Publisher.Product），字母开头；
    - name = ID 左侧文本（strip 后必须非空）；
    - version / available / source = ID 右侧空白分词；
    - version 含内部空格仅出现在 "< 174.2.0.13389"（版本无法确定）
      这一种形态，此时右侧为 4 个 token 且首 token 为 "<"。
    """
    for match in _ID_RE.finditer(line):
        name = line[: match.start()].strip()
        if not name:
            continue
        tokens = line[match.end() :].split()
        if len(tokens) == 3:
            version, available, source = tokens
        elif len(tokens) == 4 and tokens[0] == "<":
            version = f"< {tokens[1]}"
            available, source = tokens[2], tokens[3]
        else:
            continue
        return PackageUpdate(
            name=name,
            package_id=match.group(),
            version=version,
            available=available,
            source=source,
        )
    return None


def last_error_line(proc: subprocess.CompletedProcess[str]) -> str:
    """取 winget 输出的最后一行非空文本作为错误摘要。"""
    text = (proc.stderr or "") + "\n" + (proc.stdout or "")
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return lines[-1] if lines else ""


def _column_starts(header: str) -> list[int]:
    """提取表头各列（连续非空白段）的起始字符偏移，用于校验列数。"""
    starts: list[int] = []
    in_word = False
    for index, char in enumerate(header):
        if char == " ":
            in_word = False
        elif not in_word:
            starts.append(index)
            in_word = True
    return starts
