"""Windows 深浅色主题：检测、ttk 主题应用、标题栏着色、配色定义。

Tk 8.6 的 ttk 内置主题（vista/clam 等）不跟随 Windows 深色模式，
系统切换到深色后界面仍是浅色；标题栏更是完全不受 Tk 控制。
本模块：

1. 读取注册表 Personalize\\AppsUseLightTheme 判断当前系统主题；
2. 基于 clam 主题创建 light / dark 两套配色（VS Code 风格），
   覆盖按钮、Treeview（含 heading/选中色）、滚动条、LabelFrame
   等控件，Treeview 行高由调用方按 DPI 系数传入；
3. 调用 DwmSetWindowAttribute 把标题栏切到沉浸式深色并按主题
   着色（caption/border/text color），非 Windows 为空操作；
4. 返回 Palette，供 tk 原生控件（详情面板 Text）同步着色。

主题切换由 GUI 侧每秒轮询注册表实现（tkinter 收不到
WM_SETTINGCHANGE 消息），见 gui.UpdaterApp._poll_theme。
"""

import ctypes
import ctypes.wintypes
import sys
import tkinter as tk
from dataclasses import dataclass
from tkinter import ttk
from weakref import WeakKeyDictionary

_THEME_LIGHT = "winget-light"
_THEME_DARK = "winget-dark"

# 各个 Tcl 解释器（root）已创建的主题名。ttk 主题注册在解释器里，
# 同一进程可以有多个 Tk() 实例（如测试），不能用模块级全局记录，
# 否则第二个解释器里 theme_use 会找不到主题。
_created: "WeakKeyDictionary[tk.Misc, set[str]]" = WeakKeyDictionary()


@dataclass(frozen=True)
class Palette:
    """一套界面配色。"""

    bg: str  # 主背景
    bg_alt: str  # 表格/按钮背景
    button_bg: str  # 按钮背景（须与主背景明显区分，否则深色下看不清边界）
    fg: str  # 主前景（文字）
    fg_dim: str  # 禁用文字
    border: str  # 边框/滚动条 thumb
    select_bg: str  # 选中背景
    select_fg: str  # 选中前景
    heading_bg: str  # 表头背景
    heading_fg: str  # 表头前景
    error: str  # 错误行
    progress: str  # 下载进度帧
    log_bg: str  # 详情面板背景
    log_fg: str  # 详情面板前景


LIGHT = Palette(
    bg="#f3f3f3",
    bg_alt="#ffffff",
    button_bg="#ffffff",
    fg="#1b1b1b",
    fg_dim="#8a8a8a",
    border="#c8c8c8",
    select_bg="#add6ff",
    select_fg="#000000",
    heading_bg="#ececec",
    heading_fg="#1b1b1b",
    error="#c72e2e",
    progress="#0e7c6b",
    log_bg="#ffffff",
    log_fg="#1f1f1f",
)

# 深色下窗口背景 #1f1f1f、表格 #252526，按钮取更亮一档的 #3f3f42
# （VS Code 次级按钮色系），否则按钮与背景糊成一片、边界不可见
DARK = Palette(
    bg="#1f1f1f",
    bg_alt="#252526",
    button_bg="#3f3f42",
    fg="#cccccc",
    fg_dim="#6e6e6e",
    border="#3c3c3c",
    select_bg="#264f78",
    select_fg="#ffffff",
    heading_bg="#2d2d30",
    heading_fg="#cccccc",
    error="#f14c4c",
    progress="#4ec9b0",
    log_bg="#1e1e1e",
    log_fg="#d4d4d4",
)


def is_system_dark() -> bool:
    """系统当前是否为深色模式（非 Windows 恒为浅色）。

    读取 HKCU\\...\\Themes\\Personalize\\AppsUseLightTheme：
    0 = 深色，1 = 浅色，键不存在（Win10 早期版本）= 浅色。
    """
    if sys.platform != "win32":
        return False
    try:
        import winreg
    except ImportError:
        return False
    try:
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
        ) as key:
            value, _ = winreg.QueryValueEx(key, "AppsUseLightTheme")
            return value == 0
    except OSError:
        return False


def apply_theme(root: tk.Misc, dark: bool, rowheight: int) -> Palette:
    """应用浅色/深色主题，返回对应 Palette。

    dark=True 用深色配色，否则浅色；rowheight 为 Treeview 行高
    （像素，由调用方按 DPI 系数计算）。重复调用安全。

    注意：ttk 主题注册在各个 Tcl 解释器里（同一进程可以有多个
    Tk() 实例），「已创建」记录按 root 保存在 WeakKeyDictionary
    中而不是模块级全局，否则第二个解释器里 theme_use 会找不到
    主题。
    """
    palette = DARK if dark else LIGHT
    name = _THEME_DARK if dark else _THEME_LIGHT
    style = ttk.Style(root)
    created = _created.setdefault(root, set())
    if name not in created:
        _build_theme(style, name, palette, rowheight)
        created.add(name)
    style.theme_use(name)
    # 保险：主题已创建但行高系数变化时同步（单次运行内不会发生）
    style.configure("Treeview", rowheight=rowheight)
    return palette


# DWM 窗口属性（dwmapi.DwmSetWindowAttribute 的 dwAttribute）
_DWMWA_USE_IMMERSIVE_DARK_MODE = 20  # Win11；Win10 早期 build 用 19
_DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY = 19
_DWMWA_BORDER_COLOR = 34  # Win11 22000+
_DWMWA_CAPTION_COLOR = 35  # Win11 22000+
_DWMWA_TEXT_COLOR = 36  # Win11 22000+


def apply_titlebar(root: tk.Misc, dark: bool, palette: Palette) -> None:
    """把标题栏切到沉浸式深色并按主题着色（非 Windows 为空操作）。

    Tk 完全不控制标题栏，系统深色下默认仍是浅色标题栏，需要手动
    调用 DwmSetWindowAttribute：

    · DWMWA_USE_IMMERSIVE_DARK_MODE(20) 打开/关闭深色标题栏，
      Win10 早期 build（19041 之前 undocumented 时期）只认 19，
      失败时回退；
    · CAPTION / BORDER / TEXT_COLOR(34/35/36，Win11 22000+)
      把标题栏背景/边框/文字色设为主题配色，与内容区一致。

    任何一步失败都静默降级（标题栏保持系统默认），不抛异常。

    HWND 陷阱：Tk 在 update_idletasks/update 之后会给 toplevel 套一层
    wrapper 窗口，此时 winfo_id() 返回的是 wrapper 而非真实顶层，
    DWM 只认顶层窗口——对 wrapper 调用返回 E_INVALIDARG。
    因此这里用 GetParent 向上取一层（wrapper 的 parent 即真实顶层；
    winfo_id 本身就是顶层时 GetParent 返回 0，用原值）。
    """
    if sys.platform != "win32":
        return
    try:
        hwnd = root.winfo_id()
        if not hwnd:
            root.update_idletasks()
            hwnd = root.winfo_id()
        if not hwnd:
            return
        user32 = ctypes.windll.user32
        user32.GetParent.argtypes = [ctypes.wintypes.HWND]
        user32.GetParent.restype = ctypes.wintypes.HWND
        parent = user32.GetParent(hwnd)
        if parent:
            hwnd = parent
        dwmapi = ctypes.windll.dwmapi
        dwmapi.DwmSetWindowAttribute.argtypes = [
            ctypes.wintypes.HWND,
            ctypes.c_uint,
            ctypes.c_void_p,
            ctypes.c_uint,
        ]
        dwmapi.DwmSetWindowAttribute.restype = ctypes.c_long  # HRESULT

        dark_flag = ctypes.wintypes.BOOL(dark)
        if not _set_dwm_attr(dwmapi, hwnd, _DWMWA_USE_IMMERSIVE_DARK_MODE, dark_flag):
            _set_dwm_attr(dwmapi, hwnd, _DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY, dark_flag)
        # 标题栏配色（Win11 22000+，失败静默——旧系统没有这些属性）
        _set_dwm_attr(dwmapi, hwnd, _DWMWA_CAPTION_COLOR, _colorref(palette.bg))
        _set_dwm_attr(dwmapi, hwnd, _DWMWA_TEXT_COLOR, _colorref(palette.fg))
        _set_dwm_attr(dwmapi, hwnd, _DWMWA_BORDER_COLOR, _colorref(palette.border))
    except (AttributeError, OSError):
        pass


def _set_dwm_attr(
    dwmapi: ctypes.WinDLL, hwnd: int, attr: int, value: ctypes.c_long | ctypes.c_ulong
) -> bool:
    """DwmSetWindowAttribute 薄封装：成功（S_OK）返回 True。"""
    return dwmapi.DwmSetWindowAttribute(hwnd, attr, ctypes.byref(value), ctypes.sizeof(value)) == 0


def _colorref(hex_color: str) -> ctypes.wintypes.DWORD:
    """ "#rrggbb" -> COLORREF（0x00BBGGRR）。"""
    rgb = hex_color.lstrip("#")
    return ctypes.wintypes.DWORD(
        int(rgb[4:6], 16) | (int(rgb[2:4], 16) << 8) | (int(rgb[0:2], 16) << 16)
    )


def _build_theme(style: ttk.Style, name: str, p: Palette, rowheight: int) -> None:
    """基于 clam 创建一套完整配色的 ttk 主题。"""
    style.theme_create(
        name,
        parent="clam",
        settings={
            ".": {
                "configure": {
                    "background": p.bg,
                    "foreground": p.fg,
                    "troughcolor": p.bg,
                    "focuscolor": p.select_bg,
                    "selectbackground": p.select_bg,
                    "selectforeground": p.select_fg,
                    "insertwidth": 1,
                    "bordercolor": p.border,
                    "darkcolor": p.bg,
                    "lightcolor": p.bg,
                }
            },
            "TFrame": {"configure": {"background": p.bg}},
            "TLabel": {"configure": {"background": p.bg, "foreground": p.fg}},
            "TLabelframe": {
                "configure": {
                    "background": p.bg,
                    "foreground": p.fg,
                    "bordercolor": p.border,
                    "lightcolor": p.bg,
                    "darkcolor": p.bg,
                }
            },
            "TLabelframe.Label": {"configure": {"background": p.bg, "foreground": p.fg}},
            "TButton": {
                "configure": {
                    "background": p.button_bg,
                    "foreground": p.fg,
                    "bordercolor": p.border,
                    "lightcolor": p.button_bg,
                    "darkcolor": p.button_bg,
                    "focuscolor": p.select_bg,
                },
                "map": {
                    "background": [("pressed", p.heading_bg), ("active", p.heading_bg)],
                    "foreground": [("disabled", p.fg_dim)],
                },
            },
            "Treeview": {
                "configure": {
                    "background": p.bg_alt,
                    "foreground": p.fg,
                    "fieldbackground": p.bg_alt,
                    "rowheight": rowheight,
                    "bordercolor": p.border,
                },
                "map": {
                    "background": [("selected", p.select_bg)],
                    "foreground": [("selected", p.select_fg)],
                },
            },
            "Treeview.Heading": {
                "configure": {
                    "background": p.heading_bg,
                    "foreground": p.heading_fg,
                    "bordercolor": p.border,
                    "lightcolor": p.heading_bg,
                    "darkcolor": p.heading_bg,
                },
                "map": {"background": [("pressed", p.border), ("active", p.heading_bg)]},
            },
            "Vertical.TScrollbar": {
                "configure": {
                    "background": p.border,
                    "troughcolor": p.bg,
                    "bordercolor": p.bg,
                    "arrowcolor": p.fg,
                },
                "map": {"background": [("active", p.fg_dim), ("pressed", p.fg_dim)]},
            },
            "Horizontal.TScrollbar": {
                "configure": {
                    "background": p.border,
                    "troughcolor": p.bg,
                    "bordercolor": p.bg,
                    "arrowcolor": p.fg,
                },
                "map": {"background": [("active", p.fg_dim), ("pressed", p.fg_dim)]},
            },
            "TEntry": {
                "configure": {
                    "background": p.bg_alt,
                    "foreground": p.fg,
                    "fieldbackground": p.bg_alt,
                    "bordercolor": p.border,
                    "lightcolor": p.bg_alt,
                    "darkcolor": p.bg_alt,
                }
            },
        },
    )
