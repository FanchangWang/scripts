"""tkinter GUI：列出可更新条目，五选项列点选，工作线程执行更新。

布局：Treeview 表格，左侧为包的名称/ID/版本/可用，右侧为 5 个选项列
（点击单元格即把该行切换为对应动作）、当前操作与状态列；
表格下方为「安装详情」面板，实时显示 winget 输出（终端风格，
自动换行，下载进度帧按 \\r 覆盖语义原地刷新）。
默认全部选中「更新(使用代理)」。

高 DPI：Windows 下在创建 Tk 前设置进程级 Per-Monitor V2 awareness，
创建后按 dpi/96 显式设置 tk scaling、同步放大命名字体与 Treeview
行高，窗口 geometry 与 Treeview 列宽按同一系数放大——控件、文字、
窗口三者等比，高分屏下清晰且大小与标准 Win11 应用一致。

系统主题：启动时读取注册表判断 Windows 深浅色并应用对应 ttk 主题
（clam 定制的 light/dark 两套配色），标题栏经 DwmSetWindowAttribute
切到沉浸式深色并按主题着色，之后每秒轮询注册表，系统切换主题后
GUI 自动跟随。
"""

import ctypes
import ctypes.wintypes
import queue
import sys
import threading
import tkinter as tk
import tkinter.font as tkfont
from pathlib import Path
from tkinter import messagebox, ttk

from winget_updater import config as config_mod
from winget_updater import theme as theme_mod
from winget_updater.models import Action, PackageUpdate
from winget_updater.winget import (
    PROXY_URL,
    fetch_installer_url,
    parse_progress,
    upgrade_package,
)

_SELECTED = "●"
_UNSELECTED = "○"

_UPDATE_ACTIONS = frozenset({Action.UPDATE_WITH_PROXY, Action.UPDATE_NO_PROXY})
_DONE_STATUS = frozenset({"成功", "失败", "已取消"})

# 队列消息：(kind, pkg, text)
#   ("row", pkg, "")    刷新该行状态列
#   ("log", pkg, line)  向详情面板追加一行输出
#   ("prog", pkg, frame) 进度帧（同位置覆盖上一帧）
#   None                全部完成（结束哨兵）
_MSG_ROW = "row"
_MSG_LOG = "log"
_MSG_PROG = "prog"

_COLUMNS = (
    "name",
    "id",
    "version",
    "available",
    "opt_proxy",
    "opt_direct",
    "opt_once",
    "opt_ver",
    "opt_perm",
    "action",
    "status",
)

_HEADINGS = {
    "name": "名称",
    "id": "ID",
    "version": "版本",
    "available": "可用",
    "opt_proxy": "更新(使用代理)",
    "opt_direct": "更新(不用代理)",
    "opt_once": "本次不更新",
    "opt_ver": "跳过本版本",
    "opt_perm": "永久跳过",
    "action": "当前操作",
    "status": "状态",
}

_WIDTHS = {
    "name": 260,
    "id": 250,
    "version": 120,
    "available": 120,
    "opt_proxy": 115,
    "opt_direct": 115,
    "opt_once": 95,
    "opt_ver": 95,
    "opt_perm": 95,
    "action": 125,
    "status": 110,
}

# 选项列列号（#5 ~ #9）→ Action
_OPTION_COLUMNS = {
    "#5": Action.UPDATE_WITH_PROXY,
    "#6": Action.UPDATE_NO_PROXY,
    "#7": Action.SKIP_THIS_TIME,
    "#8": Action.SKIP_VERSION,
    "#9": Action.PERMANENT_SKIP,
}

_ACTION_LABELS = {
    Action.UPDATE_WITH_PROXY: "更新(使用代理)",
    Action.UPDATE_NO_PROXY: "更新(不用代理)",
    Action.SKIP_THIS_TIME: "本次不更新",
    Action.SKIP_VERSION: "跳过本版本",
    Action.PERMANENT_SKIP: "永久跳过",
}

_OPTION_KEYS = {
    Action.UPDATE_WITH_PROXY: "opt_proxy",
    Action.UPDATE_NO_PROXY: "opt_direct",
    Action.SKIP_THIS_TIME: "opt_once",
    Action.SKIP_VERSION: "opt_ver",
    Action.PERMANENT_SKIP: "opt_perm",
}

# 窗口尺寸：Treeview 列宽总和 1500 + padding/滚动条/余量
_WINDOW_WIDTH = 1560
_WINDOW_HEIGHT = 780
_WINDOW_MIN_WIDTH = 1200
_WINDOW_MIN_HEIGHT = 520


def _enable_dpi_awareness() -> None:
    """Windows 高 DPI 适配，按能力逐级降级，非 Windows 平台为空操作。

    必须在创建 Tk 实例之前调用；全部失败时静默降级
    （Tk 仍可按系统默认运行）。
    """
    if sys.platform != "win32":
        return
    _dpi_per_monitor_v2() or _dpi_per_monitor() or _dpi_system()


def _dpi_per_monitor_v2() -> bool:
    """Per-Monitor V2（Win10 1703+）。"""
    func = getattr(ctypes.windll.user32, "SetProcessDpiAwarenessContext", None)
    if func is None:
        return False
    func.argtypes = [ctypes.c_void_p]
    func.restype = ctypes.wintypes.BOOL
    return bool(func(ctypes.c_void_p(-4)))


def _dpi_per_monitor() -> bool:
    """Per-Monitor（Win8.1+，shcore）。"""
    try:
        shcore = ctypes.windll.shcore
    except (AttributeError, OSError):
        return False
    func = getattr(shcore, "SetProcessDpiAwareness", None)
    if func is None:
        return False
    func.argtypes = [ctypes.c_int]
    func.restype = ctypes.c_long  # HRESULT（LONG），>= 0 为成功
    return func(2) >= 0  # S_OK


def _dpi_system() -> bool:
    """System DPI（Vista+）。"""
    func = getattr(ctypes.windll.user32, "SetProcessDPIAware", None)
    if func is None:
        return False
    func.argtypes = []
    func.restype = ctypes.wintypes.BOOL
    return bool(func())


def _apply_tk_scaling(root: tk.Tk) -> None:
    """把 tk scaling 显式设为 dpi/96（Win11 标准缩放），须在 build UI 前调用。

    Tk 8.6.10+ 会自动把 scaling 设为 dpi/72（150% 缩放下为 2.0），
    导致 aware 进程的界面比标准 Win11 应用大 1/3；而 geometry 使用
    物理像素、不随 scaling 转换，窗口若不同步放大就会「小窗口塞
    大内容」。显式覆盖为 dpi/96 后，控件与窗口按同一系数等比变化。

    注意 scaling 只作用于「距离」类单位；命名字体是 point 单位、
    ttk 主题值也不完全走 scaling，必须另行放大（见 _apply_font_scaling），
    否则会出现「窗口变大但文字/按钮没变大」。
    """
    scale = _display_scale(root)
    if abs(scale - 1.0) <= 0.01:
        return
    root.tk.call("tk", "scaling", scale)
    _apply_font_scaling(scale)


def _apply_font_scaling(scale: float) -> None:
    """按缩放系数放大 Tk 命名字体（TkDefaultFont 等，point 单位）。

    aware 进程里 point 字体会按真实 DPI 渲染一次（9pt@144dpi=18px），
    但不随 tk scaling 变化；150% 缩放的 4K 屏上 9pt 明显偏小，
    需要显式乘以缩放系数（9pt -> 13.5pt ≈ 27px，与标准 Win11 应用接近）。
    """
    for name in (
        "TkDefaultFont",
        "TkTextFont",
        "TkMenuFont",
        "TkHeadingFont",
        "TkFixedFont",
        "TkCaptionFont",
        "TkSmallCaptionFont",
        "TkIconFont",
        "TkTooltipFont",
    ):
        try:
            font = tkfont.nametofont(name)
        except (tk.TclError, KeyError):
            continue
        size = font["size"]
        if size > 0:  # 正数 = point；负数 = 像素，不处理
            font.configure(size=round(size * scale))


def _display_scale(root: tk.Tk) -> float:
    """当前显示器的缩放系数（96 DPI = 1.0）。

    不能用 winfo_fpixels('1i')：它返回 72*scaling，而 scaling 可能
    已被 _apply_tk_scaling 改过，会形成循环依赖。Windows 下直接向
    系统查询真实 DPI（单显示器场景；unaware 进程得到虚拟化的 96，
    恰好对应「不干预、交给系统拉伸」的降级行为）。
    """
    if sys.platform == "win32":
        try:
            hdc = ctypes.windll.user32.GetDC(None)
            try:
                dpi = ctypes.windll.gdi32.GetDeviceCaps(hdc, 88)  # LOGPIXELSY
            finally:
                ctypes.windll.user32.ReleaseDC(None, hdc)
            if dpi > 0:
                return dpi / 96.0
        except (AttributeError, OSError):
            pass
    dpi = root.winfo_fpixels("1i")
    return dpi / 96.0 if dpi > 0 else 1.0


class UpdaterApp:
    """winget 更新主窗口。"""

    def __init__(
        self,
        root: tk.Tk,
        packages: list[PackageUpdate],
        config_path: Path,
        records: dict[str, dict],
    ) -> None:
        self.root = root
        self.packages = packages
        self.config_path = config_path
        self.records = records
        self.stop_event = threading.Event()
        self.result_queue: queue.Queue[tuple[str, PackageUpdate, str] | None] = queue.Queue()
        self.worker: threading.Thread | None = None
        self.running = False
        # 安装详情 label 的当前状态：进行中的包 + 最近一次下载进度 (MB, MB)
        self._current_pkg: PackageUpdate | None = None
        self._progress: tuple[float, float] | None = None

        root.title(f"winget 更新 — 共 {len(packages)} 项（代理 {PROXY_URL}）")
        # geometry 使用物理像素、不随 tk scaling 转换，需按缩放系数手动放大，
        # 与内部控件（列宽等，同样不随 scaling 转换）保持一致
        self.scale = _display_scale(root)
        self._rowheight = round(24 * self.scale)
        width = round(_WINDOW_WIDTH * self.scale)
        height = round(_WINDOW_HEIGHT * self.scale)
        root.geometry(f"{width}x{height}")
        root.minsize(round(_WINDOW_MIN_WIDTH * self.scale), round(_WINDOW_MIN_HEIGHT * self.scale))
        _center_window(root)

        # 系统主题（深浅色）——须在构建界面前应用
        self._dark = theme_mod.is_system_dark()
        self._palette = theme_mod.apply_theme(root, self._dark, self._rowheight)
        # 标题栏深色：Tk 不控制标题栏，需 DWM 单独设置
        theme_mod.apply_titlebar(root, self._dark, self._palette)

        self._build_widgets()
        root.protocol("WM_DELETE_WINDOW", self._on_close)
        self._after_id: str | None = root.after(100, self._poll_queue)
        # 每秒轮询注册表，跟随系统深浅色切换（tkinter 收不到 WM_SETTINGCHANGE）
        self._theme_after_id: str | None = root.after(1000, self._poll_theme)

    # ---------- 界面构建 ----------

    def _build_widgets(self) -> None:
        frame = ttk.Frame(self.root, padding=8)
        frame.pack(fill="both", expand=True)
        frame.rowconfigure(0, weight=3)
        frame.rowconfigure(1, weight=2)
        frame.rowconfigure(2, weight=0)
        frame.columnconfigure(0, weight=1)

        self._build_table(frame)
        self._build_log(frame)
        self._build_button_bar(frame)

    def _build_table(self, parent: ttk.Frame) -> None:
        # Treeview 行高由主题管理（ttk 主题默认值偏小且不随 DPI 变化），
        # 已在 __init__ 随主题应用时按 self._rowheight 设定
        self.tree = ttk.Treeview(parent, columns=_COLUMNS, show="headings")
        for col in _COLUMNS:
            self.tree.heading(col, text=_HEADINGS[col])
            self.tree.column(
                col,
                # 列宽与 geometry 一样是物理像素、不随 tk scaling 转换，
                # 必须手动乘缩放系数，否则高 DPI 下列宽不变、右侧出现空白
                width=round(_WIDTHS[col] * self.scale),
                anchor="w" if col in ("name", "id") else "center",
                stretch=False,
            )
        scrollbar = ttk.Scrollbar(parent, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)
        self.tree.grid(row=0, column=0, columnspan=2, sticky="nsew")
        scrollbar.grid(row=0, column=1, sticky="ns")
        self.tree.bind("<Button-1>", self._on_click)

        self._pkg_by_row: dict[str, PackageUpdate] = {}
        for pkg in self.packages:
            self.tree.insert("", "end", iid=pkg.package_id, values=self._row_values(pkg))
            self._pkg_by_row[pkg.package_id] = pkg

    def _build_log(self, parent: ttk.Frame) -> None:
        """安装详情面板：终端风格的只读文本区，实时显示 winget 输出。

        下载进度不在此面板刷新（避免刷屏）——label 文本动态显示
        「当前：包名 ( 已下 / 总大小  百分比)」，见 _update_log_label。
        """
        panel = ttk.LabelFrame(parent, text="安装详情")
        panel.grid(row=1, column=0, columnspan=2, sticky="nsew", pady=(8, 0))
        panel.rowconfigure(0, weight=1)
        panel.columnconfigure(0, weight=1)
        self._log_panel = panel

        self.log_text = tk.Text(
            panel,
            wrap="char",  # 自动换行（长行按字符断行，无需水平滚动条）
            font=("Consolas", max(9, round(9 * self.scale))),
            state="disabled",
            height=10,
            background=self._palette.log_bg,
            foreground=self._palette.log_fg,
            insertbackground=self._palette.log_fg,
        )
        v_scroll = ttk.Scrollbar(panel, orient="vertical", command=self.log_text.yview)
        self.log_text.configure(yscrollcommand=v_scroll.set)
        self.log_text.grid(row=0, column=0, sticky="nsew")
        v_scroll.grid(row=0, column=1, sticky="ns")

        # 终端风格配色（随主题切换）：错误行红色
        self.log_text.tag_configure("error", foreground=self._palette.error)

    def _apply_palette(self, palette: theme_mod.Palette) -> None:
        """主题切换后重设 tk 原生控件（详情面板 Text 及其 tag）的配色。"""
        self._palette = palette
        self.log_text.configure(
            background=palette.log_bg,
            foreground=palette.log_fg,
            insertbackground=palette.log_fg,
        )
        self.log_text.tag_configure("error", foreground=palette.error)

    def _poll_theme(self) -> None:
        """每秒检查系统主题，切换到深浅色时重新应用主题与配色。"""
        dark = theme_mod.is_system_dark()
        if dark != self._dark:
            self._dark = dark
            self._apply_palette(theme_mod.apply_theme(self.root, dark, self._rowheight))
            # 标题栏同步切换（caption/border/text 色 + 沉浸式深色）
            theme_mod.apply_titlebar(self.root, dark, self._palette)
        self._theme_after_id = self.root.after(1000, self._poll_theme)

    def _build_button_bar(self, parent: ttk.Frame) -> None:
        bar = ttk.Frame(parent)
        bar.grid(row=2, column=0, columnspan=2, sticky="ew", pady=(8, 0))

        self.btn_default = ttk.Button(bar, text="全部默认(使用代理)", command=self._reset_all)
        self.btn_start = ttk.Button(bar, text="开始更新", command=self._start_updates)
        self.btn_stop = ttk.Button(bar, text="停止", command=self._stop, state="disabled")
        self.btn_cancel = ttk.Button(bar, text="取消", command=self._on_close)
        self.btn_default.pack(side="left")
        self.btn_start.pack(side="left", padx=(8, 0))
        self.btn_stop.pack(side="left", padx=(8, 0))
        self.btn_cancel.pack(side="left", padx=(8, 0))

        self.progress = ttk.Label(bar, text="")
        self.progress.pack(side="right")

    def _row_values(self, pkg: PackageUpdate) -> tuple[str, ...]:
        """生成 Treeview 一行的显示值。"""
        values: dict[str, str] = {
            "name": pkg.name,
            "id": pkg.package_id,
            "version": pkg.version,
            "available": pkg.available,
            "action": _ACTION_LABELS[pkg.action],
            "status": pkg.status,
        }
        for action, key in _OPTION_KEYS.items():
            values[key] = _SELECTED if action is pkg.action else _UNSELECTED
        return tuple(values[col] for col in _COLUMNS)

    def _refresh_row(self, pkg: PackageUpdate) -> None:
        if self.tree.exists(pkg.package_id):
            self.tree.item(pkg.package_id, values=self._row_values(pkg))

    def _append_log(self, pkg: PackageUpdate, line: str, carriage_return: bool = False) -> None:
        """向详情面板追加一行（只读 Text 临时解锁写入）。

        carriage_return=True 表示这是 winget 的进度帧（spinner / 下载
        进度条）：不写入日志面板（避免刷屏），仅解析出下载大小刷新
        「安装详情」label；解析不到（spinner 帧）时保留上一次进度显示。
        """
        self._current_pkg = pkg
        if carriage_return:
            progress = parse_progress(line)
            if progress is not None:
                self._progress = progress
            self._update_log_label()
            return
        self.log_text.configure(state="normal")
        try:
            is_error = any(word in line.lower() for word in ("error", "failed", "错误", "失败"))
            self.log_text.insert("end", f"[{pkg.name}] {line}\n", "error" if is_error else ())
        finally:
            self.log_text.configure(state="disabled")
        self.log_text.yview_moveto(1.0)
        self._update_log_label()

    def _update_log_label(self) -> None:
        """「安装详情」label：进行中显示 当前包名 + 下载进度，其余保持包名。"""
        if self._current_pkg is None:
            self._log_panel.configure(text="安装详情")
            return
        name = self._current_pkg.name
        if self._progress is None:
            self._log_panel.configure(text=f"安装详情  当前：{name}")
            return
        done, total = self._progress
        percent = done / total * 100 if total > 0 else 0.0
        self._log_panel.configure(
            text=f"安装详情  当前：{name} ( {done:.1f} MB / {total:.1f} MB  {percent:.1f}%)"
        )

    def _clear_log(self) -> None:
        self.log_text.configure(state="normal")
        self.log_text.delete("1.0", "end")
        self.log_text.configure(state="disabled")
        self._current_pkg = None
        self._progress = None
        self._update_log_label()

    # ---------- 交互 ----------

    def _on_click(self, event: tk.Event) -> None:
        """点击选项列单元格：把该行切换为对应动作。"""
        if self.running:
            return
        if self.tree.identify_region(event.x, event.y) != "cell":
            return
        action = _OPTION_COLUMNS.get(self.tree.identify_column(event.x))
        if action is None:
            return
        pkg = self._pkg_by_row.get(self.tree.identify_row(event.y))
        if pkg is None:
            return
        pkg.action = action
        self._refresh_row(pkg)

    def _reset_all(self) -> None:
        """全部恢复默认：更新(使用代理)。"""
        if self.running:
            return
        for pkg in self.packages:
            pkg.action = Action.UPDATE_WITH_PROXY
            self._refresh_row(pkg)

    def _start_updates(self) -> None:
        """写配置并启动工作线程执行更新。"""
        todo = [pkg for pkg in self.packages if pkg.action in _UPDATE_ACTIONS]

        # 5 个选项中 4 个落盘；「本次不更新」= 删除记录（下次仍提示）
        for pkg in self.packages:
            config_mod.record_action(self.records, pkg)
        config_mod.save_records(self.config_path, self.records)

        if not todo:
            messagebox.showinfo("winget 更新", "没有需要更新的条目，配置已保存。", parent=self.root)
            self.root.destroy()
            return

        self.running = True
        self._set_buttons_running(True)
        self._clear_log()
        self._update_progress()
        self.worker = threading.Thread(target=self._run_updates, args=(todo,), daemon=True)
        self.worker.start()

    def _stop(self) -> None:
        """请求停止：当前包更新完成后不再继续。"""
        self.stop_event.set()
        self.btn_stop.config(state="disabled")

    def _on_close(self) -> None:
        if self.running and not messagebox.askyesno(
            "确认", "更新正在进行，确定要中止吗？", parent=self.root
        ):
            return
        self.stop_event.set()
        self._cancel_polling()
        self.root.destroy()

    # ---------- 更新执行（工作线程 + 队列刷新） ----------

    def _run_updates(self, todo: list[PackageUpdate]) -> None:
        """工作线程：逐条升级，winget 输出实时流入详情面板。"""
        try:
            for pkg in todo:
                if self.stop_event.is_set():
                    pkg.status = "已取消"
                    self.result_queue.put((_MSG_ROW, pkg, ""))
                    continue
                pkg.status = "更新中..."
                self.result_queue.put((_MSG_ROW, pkg, ""))
                self.result_queue.put((_MSG_LOG, pkg, f">>> {pkg.name}（{pkg.package_id}）<<<"))

                log_lines: list[str] = []

                def on_line(
                    line: str,
                    carriage_return: bool = False,
                    _pkg: PackageUpdate = pkg,
                    _lines: list[str] = log_lines,
                ) -> None:
                    if carriage_return:
                        # 进度帧不参与错误摘要（取最后非空普通行）
                        self.result_queue.put((_MSG_PROG, _pkg, line))
                    else:
                        _lines.append(line)
                        self.result_queue.put((_MSG_LOG, _pkg, line))

                try:
                    proc = upgrade_package(
                        pkg.package_id,
                        pkg.action is Action.UPDATE_WITH_PROXY,
                        on_line=on_line,
                    )
                except Exception as exc:  # winget 缺失 / 启动失败等
                    pkg.status = "失败"
                    pkg.error = str(exc)
                    self.result_queue.put((_MSG_ROW, pkg, ""))
                    continue

                if proc.returncode == 0:
                    pkg.status = "成功"
                    config_mod.forget_package(self.records, pkg.package_id)
                else:
                    pkg.status = "失败"
                    pkg.error = _last_nonempty(log_lines) or f"退出码 {proc.returncode}"
                    # winget 升不了（如「无法使用 WinGet 升级包」）：从源提取
                    # 安装包链接投递到详情面板，供手动下载安装
                    self._queue_installer_fallback(pkg)
                self.result_queue.put((_MSG_ROW, pkg, ""))
        finally:
            # 无论正常结束还是线程异常，都必须投递结束哨兵，防止 GUI 卡死
            self.result_queue.put(None)

    def _queue_installer_fallback(self, pkg: PackageUpdate) -> None:
        """升级失败兜底：从 winget 源取安装包链接，投递到详情面板。

        覆盖 winget 自身无法升级的包（如「无法使用 WinGet 升级包」的
        输入法/便携包）。查询失败（网络/源问题）时静默跳过，不打扰主流程。
        """
        try:
            found = fetch_installer_url(pkg.package_id, pkg.action is Action.UPDATE_WITH_PROXY)
        except Exception:
            return
        if found is None:
            return
        url, sha = found
        pkg.installer_url = url
        pkg.installer_sha = sha
        self.result_queue.put((_MSG_LOG, pkg, "winget 无法升级此包，可手动下载安装："))
        self.result_queue.put((_MSG_LOG, pkg, url))
        if sha:
            self.result_queue.put((_MSG_LOG, pkg, f"SHA256: {sha}"))

    def _poll_queue(self) -> None:
        """GUI 线程轮询队列，刷新状态列、详情面板与进度。"""
        try:
            while True:
                item = self.result_queue.get_nowait()
                if item is None:
                    self._on_finished()
                    return
                kind, pkg, text = item
                if kind == _MSG_LOG:
                    self._append_log(pkg, text)
                elif kind == _MSG_PROG:
                    self._append_log(pkg, text, carriage_return=True)
                else:
                    self._refresh_row(pkg)
                self._update_progress()
        except queue.Empty:
            pass
        self._after_id = self.root.after(100, self._poll_queue)

    def _cancel_polling(self) -> None:
        """取消队列轮询（窗口关闭时调用，避免 destroy 后回调触发）。"""
        for attr in ("_after_id", "_theme_after_id"):
            after_id = getattr(self, attr, None)
            if after_id is not None:
                try:
                    self.root.after_cancel(after_id)
                except (tk.TclError, ValueError):
                    pass
                setattr(self, attr, None)

    def _on_finished(self) -> None:
        """全部完成：恢复按钮、重写配置、弹汇总。"""
        self.running = False
        self._set_buttons_running(False)
        config_mod.save_records(self.config_path, self.records)

        ok = [pkg for pkg in self.packages if pkg.status == "成功"]
        failed = [pkg for pkg in self.packages if pkg.status == "失败"]
        canceled = [pkg for pkg in self.packages if pkg.status == "已取消"]

        # label 收尾为汇总统计（与弹窗一致），进行中状态让位
        self._current_pkg = None
        self._progress = None
        self._log_panel.configure(
            text=f"安装详情  总{len(ok) + len(failed) + len(canceled)} 成功{len(ok)}"
        )

        summary = f"成功 {len(ok)} 个，失败 {len(failed)} 个"
        if canceled:
            summary += f"，取消 {len(canceled)} 个"
        lines = [summary]
        lines.extend(f"· {pkg.name}：{pkg.error}" for pkg in failed)
        # 统计同步写入详情面板一份（label 只有简版总数）
        self._append_summary(lines, failed)
        messagebox.showinfo("更新完成", "\n".join(lines), parent=self.root)

    def _append_summary(self, lines: list[str], failed: list[PackageUpdate]) -> None:
        """把完成统计写入详情面板：汇总行 + 失败明细（红色 error tag）。

        失败包附升级时取到的安装包链接与 SHA256（批量更新时不必向上
        翻日志找链接）；未取到链接的包只显示错误摘要。
        """
        self.log_text.configure(state="normal")
        try:
            self.log_text.insert("end", "\n")
            self.log_text.insert("end", f"{lines[0]}\n")
            for pkg in failed:
                self.log_text.insert("end", f"· {pkg.name}：{pkg.error}\n", "error")
                if pkg.installer_url:
                    self.log_text.insert("end", f"  {pkg.installer_url}\n")
                    if pkg.installer_sha:
                        self.log_text.insert("end", f"  SHA256: {pkg.installer_sha}\n")
        finally:
            self.log_text.configure(state="disabled")
        self.log_text.yview_moveto(1.0)

    # ---------- 辅助 ----------

    def _set_buttons_running(self, running: bool) -> None:
        state = "disabled" if running else "normal"
        self.btn_default.config(state=state)
        self.btn_start.config(state=state)
        self.btn_cancel.config(state=state)
        self.btn_stop.config(state="normal" if running else "disabled")

    def _update_progress(self) -> None:
        total = sum(1 for pkg in self.packages if pkg.action in _UPDATE_ACTIONS)
        done = sum(1 for pkg in self.packages if pkg.status in _DONE_STATUS)
        self.progress.config(text=f"进度 {done}/{total}")


def _last_nonempty(lines: list[str]) -> str:
    """取输出中最后一行非空文本作为错误摘要。"""
    nonempty = [line.strip() for line in lines if line.strip()]
    return nonempty[-1] if nonempty else ""


def _center_window(root: tk.Tk) -> None:
    """窗口在屏幕上居中（DPI aware 后 winfo_* 均为物理像素，自洽）。"""
    root.update_idletasks()
    width = root.winfo_width()
    height = root.winfo_height()
    screen_width = root.winfo_screenwidth()
    screen_height = root.winfo_screenheight()
    x = max((screen_width - width) // 2, 0)
    y = max((screen_height - height) // 2, 0)
    root.geometry(f"{width}x{height}+{x}+{y}")


def run_gui(
    packages: list[PackageUpdate],
    config_path: Path,
    records: dict[str, dict],
) -> int:
    """打开 GUI 主窗口，阻塞至关闭。"""
    _enable_dpi_awareness()
    root = tk.Tk()
    _apply_tk_scaling(root)  # 须在构建界面前：覆盖 Tk 默认的 dpi/72 scaling
    UpdaterApp(root, packages, config_path, records)
    root.mainloop()
    return 0
