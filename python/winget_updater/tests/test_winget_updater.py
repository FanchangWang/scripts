"""winget_updater 单元测试（stdlib unittest，零第三方依赖）。"""

import json
import subprocess
import sys
import unittest
import unittest.mock
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from winget_updater import config as config_mod
from winget_updater.conpty import ConPtyError
from winget_updater.gui import _WIDTHS
from winget_updater.models import Action, PackageUpdate
from winget_updater.winget import (
    WingetError,
    _FrameFeeder,
    _parse_installer_url,
    check_upgrades,
    fetch_installer_url,
    parse_progress,
    parse_upgrade_table,
    upgrade_package,
)

# 真实样本：本机 winget v1.29.380 经伪控制台抓取的下载输出字节流
# （含 VT 转义序列、spinner 帧、17.8 MB 下载进度条）
PTY_FIXTURE = Path(__file__).parent / "fixtures" / "winget_pty_download.bin"

# 真实样本：本机 winget v1.29.380 中文表头输出（含单空格分隔的 FlClash 行、
# 版本含空格的 Ubisoft 行、以及两行汇总信息）
REAL_SAMPLE = """名称                                             ID                                 版本              可用             源
-----------------------------------------------------------------------------------------------------------------------------
Docker Desktop                                   Docker.DockerDesktop               4.94.0            4.95.0           winget
FlClash 版本 0.8.99+2026100301                   chen08209.FlClash                  0.8.99+2026100301 0.9.0+2026101401 winget
FluxDown                                         zerx-lab.FluxDown                  0.5.6-rc.1        0.5.6            winget
Microsoft Visual Studio Code (User)              Microsoft.VisualStudioCode         1.141.0           1.141.1          winget
Microsoft Windows Desktop Runtime - 8.0.31 (x64) Microsoft.DotNet.DesktopRuntime.8  8.0.31            8.0.32           winget
Microsoft Windows Desktop Runtime 10.0.12 (x64)  Microsoft.DotNet.DesktopRuntime.10 10.0.12           10.0.13          winget
Oh My Posh                                       JanDeDobbeleer.OhMyPosh            31.7.0.0          31.8.0           winget
PowerShell 7-x64                                 Microsoft.PowerShell               7.6.6.0           7.6.7.0          winget
ProxyBridge 4.0.0                                InterceptSuite.ProxyBridge         4.0.0             4.1.1            winget
Reqable version 3.2.23                           Reqable.Reqable                    3.2.23            3.3.1            winget
Snow Shot                                        mg-chao.snow-shot                  1.2.6             1.2.9            winget
Ubisoft Connect                                  Ubisoft.Connect                    < 174.2.0.13389   174.2.0.13389    winget
Visual Studio Community 2026                     Microsoft.VisualStudio.Community   18.10.3           18.11.0          winget
微信                                             Tencent.WeChat.Universal           4.1.15.50         4.1.15.53        winget
微信输入法                                       Tencent.WeType                     3.0.0.22          3.0.0.32        winget
15 升级可用。
3 程序包的版本号无法确定。使用 --include-unknown 查看所有结果。
"""

ENGLISH_SAMPLE = """Name            Id          Version  Available  Source
-----------------------------------------------
Foo App         Foo.Bar     1.0      2.0        winget
"""


def _pkg(package_id: str, available: str = "1.0.0") -> PackageUpdate:
    return PackageUpdate(
        name=package_id,
        package_id=package_id,
        version="0.9.0",
        available=available,
        source="winget",
    )


class _FakeStdout:
    """subprocess.Popen stdout 的测试替身：仅需 fileno()（os.read 被 mock）。"""

    def fileno(self) -> int:
        return 3


class _FakePopen:
    """subprocess.Popen 的测试替身：stdout + wait/kill。"""

    def __init__(self, returncode: int = 0) -> None:
        self.stdout = _FakeStdout()
        self.returncode = returncode
        self.killed = False

    def wait(self, timeout: float | None = None) -> int:
        return self.returncode

    def kill(self) -> None:
        self.killed = True


class TestParseUpgradeTable(unittest.TestCase):
    """表格解析：语言无关定宽切列。"""

    def test_real_sample(self) -> None:
        updates = parse_upgrade_table(REAL_SAMPLE)
        self.assertEqual(len(updates), 15)

    def test_real_sample_first_row(self) -> None:
        first = parse_upgrade_table(REAL_SAMPLE)[0]
        self.assertEqual(first.name, "Docker Desktop")
        self.assertEqual(first.package_id, "Docker.DockerDesktop")
        self.assertEqual(first.version, "4.94.0")
        self.assertEqual(first.available, "4.95.0")
        self.assertEqual(first.source, "winget")

    def test_single_space_separated_columns(self) -> None:
        """FlClash 行版本/可用两个值只隔 1 个空格，定宽切列必须正确。"""
        flclash = next(
            p for p in parse_upgrade_table(REAL_SAMPLE) if p.package_id == "chen08209.FlClash"
        )
        self.assertEqual(flclash.version, "0.8.99+2026100301")
        self.assertEqual(flclash.available, "0.9.0+2026101401")

    def test_version_containing_space(self) -> None:
        """Ubisoft 行版本列为 "< 174.2.0.13389"（含空格）。"""
        ubisoft = next(
            p for p in parse_upgrade_table(REAL_SAMPLE) if p.package_id == "Ubisoft.Connect"
        )
        self.assertEqual(ubisoft.version, "< 174.2.0.13389")
        self.assertEqual(ubisoft.available, "174.2.0.13389")

    def test_english_header(self) -> None:
        updates = parse_upgrade_table(ENGLISH_SAMPLE)
        self.assertEqual(len(updates), 1)
        self.assertEqual(updates[0].package_id, "Foo.Bar")
        self.assertEqual(updates[0].available, "2.0")

    def test_empty_table(self) -> None:
        text = "名称  ID  版本  可用  源\n--------------------\n"
        self.assertEqual(parse_upgrade_table(text), [])

    def test_no_separator_means_no_updates(self) -> None:
        """winget 无可用更新时不输出表格（无分隔线），应返回空列表。"""
        self.assertEqual(parse_upgrade_table("没有可用升级\n"), [])

    def test_wrong_column_count_raises(self) -> None:
        text = "只有两列\n----------\nA  B\n"
        with self.assertRaises(WingetError):
            parse_upgrade_table(text)


class TestConfig(unittest.TestCase):
    """配置读写与跳过过滤。"""

    def setUp(self) -> None:
        self._tmp = TemporaryDirectory()
        self.path = Path(self._tmp.name) / "config.json"
        self.records: dict[str, dict] = {}

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_load_missing_file(self) -> None:
        self.assertEqual(config_mod.load_records(self.path), {})

    def test_load_corrupt_file(self) -> None:
        self.path.write_text("{broken", encoding="utf-8")
        self.assertEqual(config_mod.load_records(self.path), {})

    def test_save_and_load_roundtrip(self) -> None:
        self.records["A.B"] = {"action": "skip_version", "version": "2.0"}
        config_mod.save_records(self.path, self.records)
        loaded = config_mod.load_records(self.path)
        self.assertEqual(loaded["A.B"]["action"], "skip_version")

    def test_record_skip_this_time_removes_record(self) -> None:
        """本次不更新 = 不记录，且清除旧记录。"""
        self.records["A.B"] = {"action": "permanent_skip"}
        pkg = _pkg("A.B", "2.0")
        pkg.action = Action.SKIP_THIS_TIME
        config_mod.record_action(self.records, pkg)
        self.assertEqual(self.records, {})

    def test_record_skip_version_stores_version(self) -> None:
        pkg = _pkg("A.B", "2.0")
        pkg.action = Action.SKIP_VERSION
        config_mod.record_action(self.records, pkg)
        self.assertEqual(self.records["A.B"]["action"], "skip_version")
        self.assertEqual(self.records["A.B"]["version"], "2.0")

    def test_record_update_action_has_no_version(self) -> None:
        pkg = _pkg("A.B", "2.0")
        pkg.action = Action.UPDATE_NO_PROXY
        config_mod.record_action(self.records, pkg)
        self.assertEqual(self.records["A.B"]["action"], "update_no_proxy")
        self.assertNotIn("version", self.records["A.B"])

    def test_forget_package(self) -> None:
        self.records["A.B"] = {"action": "update_with_proxy"}
        config_mod.forget_package(self.records, "A.B")
        self.assertEqual(self.records, {})

    def test_default_action_uses_recorded_update_action(self) -> None:
        self.records["A.B"] = {"action": "update_no_proxy"}
        pkg = _pkg("A.B")
        self.assertEqual(config_mod.default_action(pkg, self.records), Action.UPDATE_NO_PROXY)

    def test_default_action_skip_record_falls_back_to_proxy(self) -> None:
        self.records["A.B"] = {"action": "permanent_skip"}
        pkg = _pkg("A.B")
        self.assertEqual(config_mod.default_action(pkg, self.records), Action.UPDATE_WITH_PROXY)

    def test_default_action_no_record(self) -> None:
        pkg = _pkg("A.B")
        self.assertEqual(config_mod.default_action(pkg, {}), Action.UPDATE_WITH_PROXY)

    def test_visible_permanent_skip_hidden(self) -> None:
        self.records["A.B"] = {"action": "permanent_skip"}
        updates = [_pkg("A.B"), _pkg("C.D")]
        visible = config_mod.visible_updates(updates, self.records, show_skipped=False)
        self.assertEqual([p.package_id for p in visible], ["C.D"])

    def test_visible_skip_version_same_version_hidden(self) -> None:
        self.records["A.B"] = {"action": "skip_version", "version": "1.0.0"}
        visible = config_mod.visible_updates(
            [_pkg("A.B", "1.0.0")], self.records, show_skipped=False
        )
        self.assertEqual(visible, [])

    def test_visible_skip_version_new_version_reshown(self) -> None:
        self.records["A.B"] = {"action": "skip_version", "version": "1.0.0"}
        visible = config_mod.visible_updates(
            [_pkg("A.B", "2.0.0")], self.records, show_skipped=False
        )
        self.assertEqual(len(visible), 1)

    def test_visible_show_skipped_shows_all(self) -> None:
        self.records["A.B"] = {"action": "permanent_skip"}
        updates = [_pkg("A.B"), _pkg("C.D")]
        visible = config_mod.visible_updates(updates, self.records, show_skipped=True)
        self.assertEqual(len(visible), 2)


class TestWingetCommands(unittest.TestCase):
    """winget 子进程命令构造。"""

    @patch("winget_updater.winget.subprocess.run")
    def test_check_upgrades_with_proxy(self, mock_run: unittest.mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess([], 0, REAL_SAMPLE, "")
        updates = check_upgrades(use_proxy=True)
        cmd = mock_run.call_args[0][0]
        self.assertIn("--proxy", cmd)
        self.assertEqual(len(updates), 15)

    @patch("winget_updater.winget.subprocess.run")
    def test_check_upgrades_no_proxy(self, mock_run: unittest.mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess([], 0, ENGLISH_SAMPLE, "")
        check_upgrades(use_proxy=False)
        cmd = mock_run.call_args[0][0]
        self.assertIn("--no-proxy", cmd)
        self.assertNotIn("--proxy", cmd)

    @patch("winget_updater.winget.subprocess.run")
    def test_check_upgrades_nonzero_raises(self, mock_run: unittest.mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess([], 1, "", "network error")
        with self.assertRaises(WingetError):
            check_upgrades()

    @patch("winget_updater.winget.run_in_pty")
    def test_upgrade_package_with_proxy(self, mock_pty: unittest.mock.Mock) -> None:
        mock_pty.return_value = 0
        proc = upgrade_package(
            "Docker.DockerDesktop",
            use_proxy=True,
            on_line=lambda line, carriage_return: None,
        )
        cmd = mock_pty.call_args[0][0]
        self.assertEqual(cmd[:3], ["winget", "upgrade", "--id"])
        self.assertIn("Docker.DockerDesktop", cmd)
        self.assertIn("--silent", cmd)
        self.assertIn("--accept-package-agreements", cmd)
        self.assertIn("--accept-source-agreements", cmd)
        self.assertIn("--disable-interactivity", cmd)
        self.assertIn("--proxy", cmd)
        self.assertEqual(proc.returncode, 0)

    @patch("winget_updater.winget.run_in_pty")
    def test_upgrade_package_no_proxy(self, mock_pty: unittest.mock.Mock) -> None:
        mock_pty.return_value = 0
        upgrade_package(
            "Docker.DockerDesktop",
            use_proxy=False,
            on_line=lambda line, carriage_return: None,
        )
        cmd = mock_pty.call_args[0][0]
        self.assertIn("--no-proxy", cmd)
        self.assertNotIn("--proxy", cmd)

    @patch("winget_updater.winget.run_in_pty")
    def test_upgrade_package_streams_lines(self, mock_pty: unittest.mock.Mock) -> None:
        """输出通过 on_line 回调逐行流出，returncode 透传。"""

        def fake_pty(cmd, on_chunk, timeout=3600):
            on_chunk(b"Found Docker Desktop [Docker.DockerDesktop]\n")
            on_chunk(b"Version: 4.95.0\n")
            on_chunk(b"Success!\n")
            return 0

        mock_pty.side_effect = fake_pty
        events: list[tuple[str, bool]] = []
        proc = upgrade_package(
            "Docker.DockerDesktop",
            use_proxy=True,
            on_line=lambda line, carriage_return: events.append((line, carriage_return)),
        )
        self.assertEqual(
            events,
            [
                ("Found Docker Desktop [Docker.DockerDesktop]", False),
                ("Version: 4.95.0", False),
                ("Success!", False),
            ],
        )
        self.assertEqual(proc.returncode, 0)

    @patch("winget_updater.winget.run_in_pty")
    def test_upgrade_package_progress_frames(self, mock_pty: unittest.mock.Mock) -> None:
        """下载进度用 \\r 覆盖：进度帧标记 carriage_return=True。"""

        def fake_pty(cmd, on_chunk, timeout=3600):
            on_chunk(
                b"  \xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x91\xe2\x96\x91"
                b" 12.5 MB / 100.0 MB\r"
                b"  \xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88"
                b"\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88 100.0 MB / 100.0 MB\r\n"
            )
            on_chunk(b"Successfully installed\n")
            return 0

        mock_pty.side_effect = fake_pty
        events: list[tuple[str, bool]] = []
        upgrade_package(
            "Docker.DockerDesktop",
            use_proxy=True,
            on_line=lambda line, carriage_return: events.append((line, carriage_return)),
        )
        self.assertEqual(
            events,
            [
                ("  ████░░ 12.5 MB / 100.0 MB", True),
                ("  ██████████ 100.0 MB / 100.0 MB", True),
                ("Successfully installed", False),
            ],
        )

    @patch("winget_updater.winget.run_in_pty")
    def test_upgrade_package_crlf_only_line(self, mock_pty: unittest.mock.Mock) -> None:
        """winget 以 \\r\\n 结束进度行：不产生多余空帧。"""

        def fake_pty(cmd, on_chunk, timeout=3600):
            on_chunk(b"  50.0 MB / 100.0 MB\r\nDone\n")
            return 0

        mock_pty.side_effect = fake_pty
        events: list[tuple[str, bool]] = []
        upgrade_package(
            "Docker.DockerDesktop",
            use_proxy=True,
            on_line=lambda line, carriage_return: events.append((line, carriage_return)),
        )
        self.assertEqual(
            events,
            [("  50.0 MB / 100.0 MB", True), ("Done", False)],
        )

    @patch("winget_updater.winget.run_in_pty")
    def test_upgrade_package_failure_returncode(self, mock_pty: unittest.mock.Mock) -> None:
        mock_pty.return_value = 1
        proc = upgrade_package(
            "Microsoft.VisualStudio.Community",
            use_proxy=False,
            on_line=lambda line, carriage_return: None,
        )
        self.assertEqual(proc.returncode, 1)

    @patch("winget_updater.winget.os.read")
    @patch("winget_updater.winget.subprocess.Popen")
    @patch("winget_updater.winget.run_in_pty")
    def test_upgrade_package_falls_back_to_pipe(
        self,
        mock_pty: unittest.mock.Mock,
        mock_popen: unittest.mock.Mock,
        mock_read: unittest.mock.Mock,
    ) -> None:
        """ConPTY 不可用且无输出时降级普通管道，输出照样分帧。"""
        mock_pty.side_effect = ConPtyError("系统过旧")
        mock_popen.return_value = _FakePopen(returncode=0)
        mock_read.side_effect = [b"Found Docker Desktop\n", b""]
        events: list[tuple[str, bool]] = []
        proc = upgrade_package(
            "Docker.DockerDesktop",
            use_proxy=True,
            on_line=lambda line, carriage_return: events.append((line, carriage_return)),
        )
        self.assertEqual(events, [("Found Docker Desktop", False)])
        self.assertEqual(proc.returncode, 0)
        mock_popen.assert_called_once()

    @patch("winget_updater.winget.run_in_pty")
    def test_upgrade_package_midstream_failure_reraises(self, mock_pty: unittest.mock.Mock) -> None:
        """已收到输出后的失败不降级重跑（避免重复安装）。"""

        def fake_pty(cmd, on_chunk, timeout=3600):
            on_chunk(b"Found Docker Desktop\n")
            raise OSError(109, "ReadFile 失败")

        mock_pty.side_effect = fake_pty
        with self.assertRaises(OSError):
            upgrade_package(
                "Docker.DockerDesktop",
                use_proxy=True,
                on_line=lambda line, carriage_return: None,
            )


class TestFrameFeeder(unittest.TestCase):
    """帧解析器：VT 剥离、\\r 分帧、内容分类、跨块 UTF-8。"""

    def _feed(self, data: bytes, chunk_size: int | None = None) -> list[tuple[str, bool]]:
        events: list[tuple[str, bool]] = []
        feeder = _FrameFeeder(lambda line, cr: events.append((line, cr)))
        if chunk_size is None:
            feeder.feed(data)
        else:
            for start in range(0, len(data), chunk_size):
                feeder.feed(data[start : start + chunk_size])
        feeder.flush()
        return events

    def test_real_pty_capture_replay(self) -> None:
        """回放真实抓取：日志行全保留、URL 行不丢、进度条/ spinner 为进度帧。"""
        events = self._feed(PTY_FIXTURE.read_bytes())
        lines = [line for line, carriage_return in events if not carriage_return]
        progress = [line for line, carriage_return in events if carriage_return]

        self.assertTrue(any(line.startswith("已找到 Microsoft Visual C++") for line in lines))
        self.assertTrue(any(line.startswith("此应用程序由其所有者授权") for line in lines))
        self.assertTrue(any(line.startswith("正在下载 https://") for line in lines))
        self.assertTrue(any(line == "已成功验证安装程序哈希" for line in lines))

        # 下载进度条：方块字符 + MB 计量，且是进度帧（GUI 原地覆盖/终态保留）
        self.assertTrue(any("17.8 MB / 17.8 MB" in frame and "█" in frame for frame in progress))
        # spinner 帧
        self.assertTrue(any(frame.strip() == "-" for frame in progress))
        self.assertTrue(any(frame.strip() == "/" for frame in progress))
        # 擦行空帧（winget 用 \x1b[K 擦掉 spinner 行）
        self.assertIn("", progress)
        # VT 序列不泄漏进帧文本
        self.assertFalse(any("\x1b" in line for line, _ in events))

    def test_byte_by_byte_matches_whole_feed(self) -> None:
        """逐字节喂入与整块喂入结果一致（跨块 UTF-8 与 VT 序列截断）。"""
        data = PTY_FIXTURE.read_bytes()
        self.assertEqual(self._feed(data, chunk_size=1), self._feed(data))

    def test_plain_lines(self) -> None:
        self.assertEqual(
            self._feed(b"Found 1 upgrades\nSuccess!\n"),
            [("Found 1 upgrades", False), ("Success!", False)],
        )

    def test_crlf_line_no_empty_frame(self) -> None:
        """\\r\\n 是一个边界，不产生多余空帧。"""
        self.assertEqual(
            self._feed(b"  50.0 MB / 100.0 MB\r\nDone\n"),
            [("  50.0 MB / 100.0 MB", True), ("Done", False)],
        )

    def test_cursor_home_is_frame_boundary(self) -> None:
        """\\x1b[H（光标归位）与 \\r 一样是行首重绘边界。"""
        self.assertEqual(
            self._feed(b"line1\r\n\x1b[H   - \x1b[m\x1b[H   \\ \x1b[m\r\nnext\n"),
            [
                ("line1", False),
                ("   - ", True),
                ("   \\ ", True),
                ("next", False),
            ],
        )

    def test_erase_line_emits_empty_frame(self) -> None:
        """\\x1b[K（擦到行尾）映射为 \\r：空帧让 GUI 删除进度行。"""
        self.assertEqual(
            self._feed(b"   / \x1b[m\r\x1b[K\x1b[500Cnext\n"),
            [("   / ", True), ("", True), ("next", False)],
        )

    def test_osc_and_sgr_stripped(self) -> None:
        """OSC（BEL 与 ST 两种结尾）与 SGR 着色被剥离。"""
        self.assertEqual(
            self._feed(b"\x1b]0;title\x07\x1b[96mname\x1b[m\x1b]9;4;1;100\x1b\\\r\n"),
            [("name", False)],
        )

    def test_size_without_blocks_is_progress(self) -> None:
        """无方块字符的纯计量行（如部分 winget 版本）也算进度帧。"""
        self.assertEqual(
            self._feed(b"  12.5 MB / 100.0 MB\r\n"),
            [("  12.5 MB / 100.0 MB", True)],
        )

    def test_log_lines_are_not_progress(self) -> None:
        """常见日志行不被误判为进度帧。"""
        for line in (
            "已找到 Microsoft Visual C++ 版本 14.51.36247.0\r\n",
            "正在启动程序包安装...\r\n",
            "已成功安装\r\n",
            "版本 10.0.13 / 10.0.12\r\n",
        ):
            events = self._feed(line.encode())
            self.assertEqual(len(events), 1)
            self.assertFalse(events[0][1], f"误判为进度帧: {events[0][0]!r}")

    def test_unterminated_frame_flushed(self) -> None:
        """流结束时的未完成帧按内容分类发出。"""
        self.assertEqual(self._feed(b"partial line"), [("partial line", False)])


class TestParseProgress(unittest.TestCase):
    """下载进度帧的计量提取（供安装详情 label 显示）。"""

    def test_mb_pair(self) -> None:
        self.assertEqual(parse_progress("  ██████▏  17.0 MB / 29.6 MB"), (17.0, 29.6))

    def test_kb_first_frame(self) -> None:
        """首帧单位可能是 KB，统一折算为 MB。"""
        self.assertEqual(parse_progress("  █  1024 KB / 29.6 MB"), (1.0, 29.6))

    def test_gb(self) -> None:
        self.assertEqual(parse_progress("  1.5 GB / 2.0 GB"), (1536.0, 2048.0))

    def test_complete_bar(self) -> None:
        self.assertEqual(parse_progress("  ██████████  29.6 MB / 29.6 MB"), (29.6, 29.6))

    def test_spinner_frame(self) -> None:
        self.assertIsNone(parse_progress("   \\ "))

    def test_log_line(self) -> None:
        self.assertIsNone(parse_progress("正在下载 https://github.com/reqable/reqable-app"))

    def test_empty(self) -> None:
        self.assertIsNone(parse_progress(""))


class TestInstallerUrl(unittest.TestCase):
    """winget show 输出的安装包链接提取（升级失败手动兜底）。"""

    FIXTURE = Path(__file__).parent / "fixtures" / "winget_show_wetype.txt"

    def test_real_show_output(self) -> None:
        """真实输出：安装程序 URL 被提取，SHA256 一并带出。"""
        result = _parse_installer_url(self.FIXTURE.read_text(encoding="utf-8"))
        self.assertIsNotNone(result)
        assert result is not None  # ty: 解包收窄
        url, sha = result
        self.assertEqual(url, "https://download.z.weixin.qq.com/app/win/WeTypeSetup_3.0.0.32_3.exe")
        self.assertEqual(sha, "80326881e6c6d3b1a1a3fc2ab2bdbd61fd2085b9f2cfb11099eafbe909073797")

    def test_page_urls_not_matched(self) -> None:
        """主页/许可证/发行说明等页面 URL 不被误判为安装包链接。"""
        text = (
            "发布服务器 URL: https://z.weixin.qq.com/\n"
            "许可证 URL: https://wetype.wxqcloud.qq.com/page/article/index/14.html\n"
            "发行说明 URL: https://z.weixin.qq.com/web/change-log/171\n"
        )
        self.assertIsNone(_parse_installer_url(text))

    def test_query_string_and_trailing_punct(self) -> None:
        """URL 带查询参数 / 行尾标点时按扩展名正确识别。"""
        text = "Installer Url: https://example.com/app-setup.exe?from=winget。\n"
        result = _parse_installer_url(text)
        self.assertIsNotNone(result)
        assert result is not None  # ty: 解包收窄
        url, _ = result
        self.assertEqual(url, "https://example.com/app-setup.exe?from=winget")

    def test_no_installer(self) -> None:
        """msix/store 包等无安装程序 URL 的输出返回 None。"""
        self.assertIsNone(_parse_installer_url("已找到 某包 [Some.App]\n版本: 1.0.0\n"))

    @patch("winget_updater.winget.subprocess.run")
    def test_fetch_installer_url_command(self, mock_run: unittest.mock.Mock) -> None:
        """fetch_installer_url 走 winget show --exact 并解析其输出。"""
        mock_run.return_value = subprocess.CompletedProcess(
            [], 0, self.FIXTURE.read_text(encoding="utf-8"), ""
        )
        result = fetch_installer_url("Tencent.WeType", use_proxy=True)
        self.assertIsNotNone(result)
        assert result is not None  # ty: 解包收窄
        url, sha = result
        self.assertEqual(url, "https://download.z.weixin.qq.com/app/win/WeTypeSetup_3.0.0.32_3.exe")
        self.assertTrue(sha)
        cmd = mock_run.call_args[0][0]
        self.assertEqual(cmd[:3], ["winget", "show", "--id"])
        self.assertIn("--exact", cmd)
        self.assertIn("--proxy", cmd)

    @patch("winget_updater.winget.subprocess.run")
    def test_fetch_installer_url_no_proxy(self, mock_run: unittest.mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess([], 1, "", "")
        self.assertIsNone(fetch_installer_url("Tencent.WeType", use_proxy=False))
        self.assertIn("--no-proxy", mock_run.call_args[0][0])


class TestCliFlow(unittest.TestCase):
    """CLI 主流程分支（GUI 以 mock 替代）。"""

    @patch("winget_updater.cli.run_gui", return_value=0)
    @patch("winget_updater.winget.subprocess.run")
    def test_no_updates_skips_gui(
        self, mock_run: unittest.mock.Mock, mock_gui: unittest.mock.Mock
    ) -> None:
        mock_run.return_value = subprocess.CompletedProcess([], 0, "没有可用升级\n", "")
        from winget_updater.cli import main

        self.assertEqual(main([]), 0)
        mock_gui.assert_not_called()

    @patch("winget_updater.cli.run_gui", return_value=0)
    @patch("winget_updater.winget.subprocess.run")
    def test_permanent_skip_filtered(
        self, mock_run: unittest.mock.Mock, mock_gui: unittest.mock.Mock
    ) -> None:
        mock_run.return_value = subprocess.CompletedProcess([], 0, REAL_SAMPLE, "")
        from winget_updater.cli import main

        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            path.write_text(
                json.dumps(
                    {"version": 1, "packages": {"Tencent.WeType": {"action": "permanent_skip"}}}
                ),
                encoding="utf-8",
            )
            self.assertEqual(main(["--config", str(path)]), 0)
            visible = mock_gui.call_args[0][0]
            self.assertEqual(len(visible), 14)
            self.assertNotIn("Tencent.WeType", [pkg.package_id for pkg in visible])

    @patch("winget_updater.cli.run_gui", return_value=0)
    @patch("winget_updater.winget.subprocess.run")
    def test_show_skipped_shows_all(
        self, mock_run: unittest.mock.Mock, mock_gui: unittest.mock.Mock
    ) -> None:
        mock_run.return_value = subprocess.CompletedProcess([], 0, REAL_SAMPLE, "")
        from winget_updater.cli import main

        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            path.write_text(
                json.dumps(
                    {"version": 1, "packages": {"Tencent.WeType": {"action": "permanent_skip"}}}
                ),
                encoding="utf-8",
            )
            self.assertEqual(main(["--config", str(path), "--show-skipped"]), 0)
            visible = mock_gui.call_args[0][0]
            self.assertEqual(len(visible), 15)

    @patch("winget_updater.cli.run_gui", return_value=0)
    @patch("winget_updater.winget.subprocess.run")
    def test_recorded_update_action_becomes_default(
        self, mock_run: unittest.mock.Mock, mock_gui: unittest.mock.Mock
    ) -> None:
        mock_run.return_value = subprocess.CompletedProcess([], 0, REAL_SAMPLE, "")
        from winget_updater.cli import main

        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            path.write_text(
                json.dumps(
                    {
                        "version": 1,
                        "packages": {"Microsoft.PowerShell": {"action": "update_no_proxy"}},
                    }
                ),
                encoding="utf-8",
            )
            main(["--config", str(path)])
            visible = mock_gui.call_args[0][0]
            powershell = next(pkg for pkg in visible if pkg.package_id == "Microsoft.PowerShell")
            self.assertEqual(powershell.action, Action.UPDATE_NO_PROXY)
            others = [pkg for pkg in visible if pkg.package_id != "Microsoft.PowerShell"]
            self.assertTrue(all(pkg.action is Action.UPDATE_WITH_PROXY for pkg in others))

    @patch("winget_updater.cli.run_gui", return_value=0)
    @patch("winget_updater.winget.subprocess.run")
    def test_winget_failure_returns_1(
        self, mock_run: unittest.mock.Mock, mock_gui: unittest.mock.Mock
    ) -> None:
        mock_run.return_value = subprocess.CompletedProcess([], 1, "", "network unreachable")
        from winget_updater.cli import main

        self.assertEqual(main([]), 1)
        mock_gui.assert_not_called()


class TestGuiSmoke(unittest.TestCase):
    """GUI 冒烟：隐藏窗口构建 + 行刷新断言（无显示环境时跳过）。"""

    def test_build_and_refresh(self) -> None:
        try:
            import tkinter as tk

            from winget_updater.gui import UpdaterApp

            root = tk.Tk()
        except Exception:  # 无显示环境（TclError 等）直接跳过
            self.skipTest("当前环境无法创建 Tk 窗口")

        try:
            root.withdraw()
            pkgs = [
                PackageUpdate(
                    name="Docker Desktop",
                    package_id="Docker.DockerDesktop",
                    version="4.94.0",
                    available="4.95.0",
                    source="winget",
                )
            ]
            app = UpdaterApp(root, pkgs, Path("config.json"), {})
            root.update()

            # 列宽随 DPI 系数放大（Treeview 列宽是物理像素，不随 scaling 转换；
            # 未放大会出现「窗口变大但列宽不变、右侧空白、文本被截断」）
            name_width = app.tree.column("name", "width")
            self.assertEqual(name_width, round(_WIDTHS["name"] * app.scale))
            total_width = sum(app.tree.column(col, "width") for col in _WIDTHS)
            tree_width = app.tree.winfo_width()
            self.assertGreaterEqual(
                total_width, tree_width - 120, f"列总宽 {total_width} 远小于表格宽 {tree_width}"
            )

            # 列顺序：name/id/version/available/opt_proxy/opt_direct/opt_once/opt_ver/opt_perm/action/status
            values = tuple(app.tree.item("Docker.DockerDesktop", "values"))
            self.assertEqual(values[4], "●")  # 默认：更新(使用代理)
            self.assertEqual(values[5], "○")

            pkgs[0].action = Action.PERMANENT_SKIP
            app._refresh_row(pkgs[0])
            values = tuple(app.tree.item("Docker.DockerDesktop", "values"))
            self.assertEqual(values[4], "○")
            self.assertEqual(values[8], "●")  # 永久跳过
            self.assertEqual(values[9], "永久跳过")

            # 安装详情面板：追加 / 清空
            app._append_log(pkgs[0], ">>> Docker Desktop <<<")
            app._append_log(pkgs[0], "Found 1 upgrades available")
            content = app.log_text.get("1.0", "end-1c")
            self.assertIn(">>> Docker Desktop <<<", content)
            self.assertIn("Found 1 upgrades available", content)

            # 自动换行（长行按字符断行，无水平滚动条）
            self.assertEqual(app.log_text.cget("wrap"), "char")

            # 完成统计写入详情面板：汇总行 + 失败明细（红色 error 标签）
            failed_pkg = PackageUpdate(
                name="微信输入法",
                package_id="Tencent.WeType",
                version="3.0.0.22",
                available="3.0.0.32",
                source="winget",
            )
            failed_pkg.status = "失败"
            failed_pkg.error = "无法使用 WinGet 升级包。请使用发布者提供的方法来升级此包。"
            failed_pkg.installer_url = (
                "https://download.z.weixin.qq.com/app/win/WeTypeSetup_3.0.0.32_3.exe"
            )
            failed_pkg.installer_sha = (
                "80326881e6c6d3b1a1a3fc2ab2bdbd61fd2085b9f2cfb11099eafbe909073797"
            )
            app._append_summary(["成功 1 个，失败 1 个"], [failed_pkg])
            content = app.log_text.get("1.0", "end-1c")
            self.assertIn("成功 1 个，失败 1 个", content)
            self.assertIn("· 微信输入法：无法使用 WinGet 升级包", content)
            # 汇总区附安装包链接与 SHA256（批量时不必向上翻日志）
            self.assertIn(
                "https://download.z.weixin.qq.com/app/win/WeTypeSetup_3.0.0.32_3.exe", content
            )
            self.assertIn(
                "SHA256: 80326881e6c6d3b1a1a3fc2ab2bdbd61fd2085b9f2cfb11099eafbe909073797", content
            )
            self.assertIn("error", app.log_text.tag_names("end-4l"))

            # 下载进度帧：不入日志面板（防刷屏），仅刷新「安装详情」label
            app._clear_log()
            self.assertEqual(app._log_panel.cget("text"), "安装详情")
            app._append_log(pkgs[0], "  ██░░░░░░░░ 12.5 MB / 100.0 MB", carriage_return=True)
            app._append_log(pkgs[0], "  ██████████ 100.0 MB / 100.0 MB", carriage_return=True)
            label = app._log_panel.cget("text")
            self.assertIn("当前：Docker Desktop", label)
            self.assertIn("100.0 MB / 100.0 MB", label)
            self.assertIn("100.0%", label)
            self.assertEqual(app.log_text.get("1.0", "end-1c"), "")

            # spinner 帧（无计量）：不写日志，label 保留上一次进度
            app._append_log(pkgs[0], "   \\ ", carriage_return=True)
            self.assertEqual(app.log_text.get("1.0", "end-1c"), "")
            self.assertIn("100.0 MB / 100.0 MB", app._log_panel.cget("text"))

            # 普通行照常入日志；label 保留当前包名（无进度时）
            app._append_log(pkgs[0], "Install completed")
            content = app.log_text.get("1.0", "end-1c")
            self.assertIn("Install completed", content)
            self.assertIn("当前：Docker Desktop", app._log_panel.cget("text"))

            app._clear_log()
            self.assertEqual(app.log_text.get("1.0", "end-1c"), "")
            self.assertEqual(app._log_panel.cget("text"), "安装详情")

            # 错误行命中 error 标签（红色）
            app._append_log(pkgs[0], "error 0x80070005")
            tags = app.log_text.tag_names("end-2l")
            self.assertIn("error", tags)

            # 主题跟随：mock 系统主题取反，_poll_theme 应切换主题并重配详情面板
            before = (app._dark, app.log_text.cget("background"))
            with patch("winget_updater.theme.is_system_dark", return_value=not before[0]):
                app._poll_theme()
            after = (app._dark, app.log_text.cget("background"))
            self.assertNotEqual(after[0], before[0], "系统主题切换后未跟随")
            self.assertNotEqual(after[1], before[1], "详情面板配色未跟随主题")
            with patch("winget_updater.theme.is_system_dark", return_value=before[0]):
                app._poll_theme()
            self.assertEqual((app._dark, app.log_text.cget("background")), before)
        finally:
            app._cancel_polling()
            if root.winfo_exists():
                root.destroy()


class TestTheme(unittest.TestCase):
    """系统主题检测与 ttk light/dark 主题（无显示环境时跳过）。"""

    def test_dark_button_bg_distinct_from_window_bg(self) -> None:
        """深色主题按钮背景必须与窗口背景明显区分（否则按钮边界不可见）。"""
        from winget_updater.theme import DARK

        def luminance(hex_color: str) -> float:
            rgb = hex_color.lstrip("#")
            return sum(int(rgb[i : i + 2], 16) for i in (0, 2, 4)) / 3

        self.assertGreater(
            luminance(DARK.button_bg) - luminance(DARK.bg),
            15,
            "深色主题按钮背景与窗口背景差异过小，按钮边界不可见",
        )

    @unittest.skipUnless(sys.platform == "win32", "非 Windows 平台")
    def test_is_system_dark_returns_bool(self) -> None:
        from winget_updater.theme import is_system_dark

        self.assertIsInstance(is_system_dark(), bool)

    @unittest.skipUnless(sys.platform == "win32", "非 Windows 平台")
    def test_apply_titlebar_sets_immersive_dark_mode(self) -> None:
        """标题栏深色开关：DwmSetWindowAttribute 后读回生效。"""
        try:
            import tkinter as tk

            from winget_updater import theme as theme_mod

            root = tk.Tk()
        except Exception:  # 无显示环境（TclError 等）直接跳过
            self.skipTest("当前环境无法创建 Tk 窗口")

        try:
            root.withdraw()
            hwnd = root.winfo_id()
            if not hwnd:
                self.skipTest("无法获取窗口句柄")

            import ctypes
            import ctypes.wintypes

            dwmapi = ctypes.windll.dwmapi
            dwmapi.DwmGetWindowAttribute.argtypes = [
                ctypes.wintypes.HWND,
                ctypes.c_uint,
                ctypes.c_void_p,
                ctypes.c_uint,
            ]
            dwmapi.DwmGetWindowAttribute.restype = ctypes.c_long
            user32 = ctypes.windll.user32
            user32.GetParent.argtypes = [ctypes.wintypes.HWND]
            user32.GetParent.restype = ctypes.wintypes.HWND

            def read_dark() -> bool:
                top = root.winfo_id()
                parent = user32.GetParent(top)
                if parent:  # winfo_id 是 wrapper 时 parent 才是真实顶层
                    top = parent
                value = ctypes.wintypes.BOOL()
                hr = dwmapi.DwmGetWindowAttribute(
                    top,
                    theme_mod._DWMWA_USE_IMMERSIVE_DARK_MODE,
                    ctypes.byref(value),
                    ctypes.sizeof(value),
                )
                if hr != 0:
                    return False
                return bool(value.value)

            theme_mod.apply_titlebar(root, True, theme_mod.DARK)
            self.assertTrue(read_dark(), "深色标题栏未生效")
            theme_mod.apply_titlebar(root, False, theme_mod.LIGHT)
            self.assertFalse(read_dark(), "浅色标题栏未生效")
        finally:
            if root.winfo_exists():
                root.destroy()

    def test_apply_theme_light_and_dark(self) -> None:
        try:
            import tkinter as tk
            from tkinter import ttk

            from winget_updater import theme as theme_mod

            root = tk.Tk()
        except Exception:  # 无显示环境（TclError 等）直接跳过
            self.skipTest("当前环境无法创建 Tk 窗口")

        try:
            root.withdraw()
            style = ttk.Style(root)

            palette_light = theme_mod.apply_theme(root, dark=False, rowheight=36)
            self.assertEqual(style.theme_use(), theme_mod._THEME_LIGHT)
            self.assertEqual(style.lookup("Treeview", "background"), palette_light.bg_alt)
            self.assertEqual(style.lookup("Treeview", "fieldbackground"), palette_light.bg_alt)
            self.assertEqual(style.lookup("Treeview", "rowheight"), 36)

            palette_dark = theme_mod.apply_theme(root, dark=True, rowheight=36)
            self.assertEqual(style.theme_use(), theme_mod._THEME_DARK)
            self.assertEqual(style.lookup("Treeview", "background"), palette_dark.bg_alt)
            self.assertEqual(style.lookup("Treeview", "fieldbackground"), palette_dark.bg_alt)
            self.assertEqual(style.lookup("Treeview", "rowheight"), 36)
            self.assertNotEqual(palette_dark.bg, palette_light.bg)

            # 重复应用同一主题不报错（主题只创建一次）
            theme_mod.apply_theme(root, dark=True, rowheight=36)
            self.assertEqual(style.theme_use(), theme_mod._THEME_DARK)
        finally:
            if root.winfo_exists():
                root.destroy()

    def test_apply_theme_rowheight_passthrough(self) -> None:
        try:
            import tkinter as tk
            from tkinter import ttk

            from winget_updater import theme as theme_mod

            root = tk.Tk()
        except Exception:
            self.skipTest("当前环境无法创建 Tk 窗口")

        try:
            root.withdraw()
            style = ttk.Style(root)
            theme_mod.apply_theme(root, dark=False, rowheight=48)
            self.assertEqual(style.lookup("Treeview", "rowheight"), 48)
        finally:
            if root.winfo_exists():
                root.destroy()


class TestDpi(unittest.TestCase):
    """DPI awareness 与 tk scaling（仅 Windows 实测）。"""

    @unittest.skipUnless(sys.platform == "win32", "非 Windows 平台")
    def test_dpi_functions_callable(self) -> None:
        from winget_updater.gui import _dpi_per_monitor, _dpi_per_monitor_v2, _dpi_system

        self.assertIsInstance(_dpi_per_monitor_v2(), bool)
        self.assertIsInstance(_dpi_per_monitor(), bool)
        self.assertIsInstance(_dpi_system(), bool)

    @unittest.skipUnless(sys.platform == "win32", "非 Windows 平台")
    def test_apply_tk_scaling(self) -> None:
        """unaware 进程里 _display_scale≈1.0，不得破坏 Tk 默认 scaling。"""
        import tkinter as tk

        from winget_updater.gui import _apply_tk_scaling, _display_scale

        try:
            root = tk.Tk()
        except Exception:  # 无显示环境
            self.skipTest("当前环境无法创建 Tk 窗口")
        try:
            before = root.tk.call("tk", "scaling")
            _apply_tk_scaling(root)
            after = root.tk.call("tk", "scaling")
            scale = _display_scale(root)
            self.assertGreater(scale, 0)
            if abs(scale - 1.0) > 0.01:
                self.assertAlmostEqual(after, scale, places=2)
            else:
                self.assertAlmostEqual(after, before, places=2)
        finally:
            root.destroy()

    @unittest.skipUnless(sys.platform == "win32", "非 Windows 平台")
    def test_apply_tk_scaling_aware(self) -> None:
        """独立进程验证：aware 进程里 scaling 被显式设为 dpi/96。

        DPI awareness 是进程级设置，且创建首个窗口后不可再更改，
        因此必须在全新子进程中验证（顺带覆盖 run_gui 的真实链路：
        awareness → Tk → scaling → UI）。
        """
        import subprocess
        import textwrap

        script = textwrap.dedent(
            """
            import tkinter
            import tkinter.font

            from winget_updater.gui import (
                _apply_tk_scaling,
                _dpi_per_monitor_v2,
                _display_scale,
            )

            assert _dpi_per_monitor_v2(), "DPI awareness 设置失败"
            root = tkinter.Tk()
            default_scaling = root.tk.call("tk", "scaling")  # Tk 默认 dpi/72
            font_before = tkinter.font.nametofont("TkDefaultFont")["size"]
            scale = _display_scale(root)
            if scale <= 1.0:
                print("SKIP")  # 100% 缩放的显示器无修正空间
            else:
                _apply_tk_scaling(root)
                corrected = root.tk.call("tk", "scaling")
                assert abs(corrected - scale) < 0.01, f"corrected={corrected} scale={scale}"
                assert corrected < default_scaling, f"corrected={corrected} default={default_scaling}"
                # 命名字体必须同步放大（point 单位不随 scaling 变化）
                font_after = tkinter.font.nametofont("TkDefaultFont")["size"]
                assert font_after == round(font_before * scale), (
                    f"font {font_before} -> {font_after}, scale={scale}"
                )
                print("OK")
            root.destroy()
            """
        )
        proc = subprocess.run(
            [sys.executable, "-c", script], capture_output=True, text=True, timeout=60
        )
        if "TclError" in proc.stderr or "no display" in proc.stderr.lower():
            self.skipTest("当前环境无法创建 Tk 窗口")
        self.assertIn("OK", proc.stdout, proc.stderr)


if __name__ == "__main__":
    unittest.main()
