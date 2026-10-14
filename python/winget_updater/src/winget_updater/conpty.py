"""Windows 伪控制台（ConPTY）运行器：让子进程「以为」自己挂在终端上。

winget 1.29.280+ 在没有控制台附着时（stdout 是管道或文件）会抑制
进度条与 spinner 输出，只留干净的日志行，下载进度因此不可见。
ConPTY（Windows 10 1809+）为子进程提供一个真实的伪控制台，winget
便按终端模式输出 \\r 覆盖式进度条；宿主侧从伪控制台输出管道读取
UTF-8 字节流（含 VT 转义序列），经 on_chunk 回调原样交给调用方
（VT 清洗与分帧逻辑见 winget._FrameFeeder）。

线程模型：调用方线程即读取线程（阻塞 ReadFile 循环）；另起看门狗
线程等待子进程退出后关闭伪控制台，读取循环随即以 ERROR_BROKEN_PIPE
收尾，不会死锁。超时由内部 Timer 强杀子进程，走同一收尾路径。

不可用时（系统过旧、DLL 缺导出、创建失败）抛出 ConPtyError，
调用方应降级到普通管道。
"""

import ctypes
import subprocess
import threading
import time
from collections.abc import Callable
from ctypes import wintypes
from typing import Any

_PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016
_EXTENDED_STARTUPINFO_PRESENT = 0x00080000
_INFINITE = 0xFFFFFFFF
_READ_CHUNK = 65536

# 伪控制台尺寸（字符数）：宽度要足够容纳 winget 的长 URL 行，避免
# 伪控制台把长行折行；高度远大于 winget 单次升级的输出行数，避免
# 滚动导致伪控制台发射滚动序列
_PTY_COLUMNS = 500
_PTY_ROWS = 100


class ConPtyError(RuntimeError):
    """ConPTY 不可用（系统过旧 / DLL 缺导出 / 创建失败），应降级普通管道。"""


class _Coord(ctypes.Structure):
    """COORD（ctypes.wintypes 未导出，按 Win32 布局自定义）。"""

    _fields_ = [
        ("X", wintypes.SHORT),
        ("Y", wintypes.SHORT),
    ]


class _StartupInfo(ctypes.Structure):
    """STARTUPINFOW（ctypes.wintypes 未导出，按 Win32 布局自定义）。"""

    _fields_ = [
        ("cb", wintypes.DWORD),
        ("lpReserved", wintypes.LPWSTR),
        ("lpDesktop", wintypes.LPWSTR),
        ("lpTitle", wintypes.LPWSTR),
        ("dwX", wintypes.DWORD),
        ("dwY", wintypes.DWORD),
        ("dwXSize", wintypes.DWORD),
        ("dwYSize", wintypes.DWORD),
        ("dwXCountChars", wintypes.DWORD),
        ("dwYCountChars", wintypes.DWORD),
        ("dwFillAttribute", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("wShowWindow", wintypes.WORD),
        ("cbReserved2", wintypes.WORD),
        ("lpReserved2", ctypes.POINTER(wintypes.BYTE)),
        ("hStdInput", wintypes.HANDLE),
        ("hStdOutput", wintypes.HANDLE),
        ("hStdError", wintypes.HANDLE),
    ]


class _StartupInfoEx(ctypes.Structure):
    _fields_ = [
        ("StartupInfo", _StartupInfo),
        ("lpAttributeList", ctypes.c_void_p),
    ]


class _ProcessInformation(ctypes.Structure):
    _fields_ = [
        ("hProcess", wintypes.HANDLE),
        ("hThread", wintypes.HANDLE),
        ("dwProcessId", wintypes.DWORD),
        ("dwThreadId", wintypes.DWORD),
    ]


def _load_dll() -> ctypes.WinDLL:
    """加载导出 ConPTY API 的 DLL（kernel32，个别构建在 kernelbase）。

    use_last_error 必须在 DLL 级开启：函数级 func.use_last_error 赋值
    不生效（ctypes 只在创建函数对象时读取该标志），GetLastError 会
    恒为 0。
    """
    for name in ("kernel32", "kernelbase"):
        try:
            return ctypes.WinDLL(name, use_last_error=True)
        except (AttributeError, OSError):
            continue
    raise ConPtyError("找不到 kernel32 / kernelbase")


def _bind(dll: ctypes.WinDLL, name: str, argtypes: list, restype) -> Any:
    func: Any = getattr(dll, name, None)
    if func is None:
        raise ConPtyError(f"{dll._name} 缺少导出 {name}")
    func.argtypes = argtypes
    func.restype = restype
    return func


def run_in_pty(
    cmd: list[str],
    on_chunk: Callable[[bytes], None],
    timeout: int = 3600,
) -> int:
    """在伪控制台中运行 cmd，输出原始字节经 on_chunk 实时回调，返回退出码。

    on_chunk 在调用方线程中被同步调用；异常会向上传播（调用方负责
    清理语义：本函数退出前已回收全部内核对象）。
    """
    dll = _load_dll()
    create_pipe = _bind(
        dll,
        "CreatePipe",
        [
            ctypes.POINTER(wintypes.HANDLE),
            ctypes.POINTER(wintypes.HANDLE),
            ctypes.c_void_p,
            wintypes.DWORD,
        ],
        wintypes.BOOL,
    )
    create_pty = _bind(
        dll,
        "CreatePseudoConsole",
        [
            _Coord,
            wintypes.HANDLE,
            wintypes.HANDLE,
            wintypes.DWORD,
            ctypes.POINTER(wintypes.HANDLE),
        ],
        ctypes.c_long,  # HRESULT
    )
    close_pty = _bind(dll, "ClosePseudoConsole", [wintypes.HANDLE], None)
    init_attrs = _bind(
        dll,
        "InitializeProcThreadAttributeList",
        [
            ctypes.c_void_p,
            wintypes.DWORD,
            wintypes.DWORD,
            ctypes.POINTER(ctypes.c_size_t),
        ],
        wintypes.BOOL,
    )
    update_attr = _bind(
        dll,
        "UpdateProcThreadAttribute",
        [
            ctypes.c_void_p,
            wintypes.DWORD,
            ctypes.c_size_t,
            ctypes.c_void_p,
            ctypes.c_size_t,
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_size_t),
        ],
        wintypes.BOOL,
    )
    delete_attrs = _bind(dll, "DeleteProcThreadAttributeList", [ctypes.c_void_p], None)
    create_proc = _bind(
        dll,
        "CreateProcessW",
        [
            wintypes.LPCWSTR,
            ctypes.c_void_p,  # lpCommandLine（可变缓冲区）
            ctypes.c_void_p,
            ctypes.c_void_p,
            wintypes.BOOL,
            wintypes.DWORD,
            ctypes.c_void_p,
            wintypes.LPCWSTR,
            ctypes.c_void_p,
            ctypes.c_void_p,
        ],
        wintypes.BOOL,
    )
    read_file = _bind(
        dll,
        "ReadFile",
        [
            wintypes.HANDLE,
            ctypes.c_void_p,
            wintypes.DWORD,
            ctypes.POINTER(wintypes.DWORD),
            ctypes.c_void_p,
        ],
        wintypes.BOOL,
    )
    wait_single = _bind(
        dll, "WaitForSingleObject", [wintypes.HANDLE, wintypes.DWORD], wintypes.DWORD
    )
    get_exit_code = _bind(
        dll,
        "GetExitCodeProcess",
        [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)],
        wintypes.BOOL,
    )
    terminate = _bind(dll, "TerminateProcess", [wintypes.HANDLE, wintypes.DWORD], wintypes.BOOL)
    close_handle = _bind(dll, "CloseHandle", [wintypes.HANDLE], wintypes.BOOL)
    get_std_handle = _bind(dll, "GetStdHandle", [wintypes.DWORD], wintypes.HANDLE)
    set_std_handle = _bind(dll, "SetStdHandle", [wintypes.DWORD, wintypes.HANDLE], wintypes.BOOL)

    # 管道：pty 输入（宿主写 -> 子进程读）与输出（子进程写 -> 宿主读）
    in_read = wintypes.HANDLE()
    in_write = wintypes.HANDLE()
    out_read = wintypes.HANDLE()
    out_write = wintypes.HANDLE()
    if not create_pipe(ctypes.byref(in_read), ctypes.byref(in_write), None, 0) or not create_pipe(
        ctypes.byref(out_read), ctypes.byref(out_write), None, 0
    ):
        for handle in (in_read, in_write, out_read, out_write):
            if handle:
                close_handle(handle)
        raise OSError(ctypes.get_last_error(), "CreatePipe 失败")

    hpc = wintypes.HANDLE()
    hr = create_pty(
        _Coord(_PTY_COLUMNS, _PTY_ROWS),
        in_read,
        out_write,
        0,
        ctypes.byref(hpc),
    )
    if hr != 0:
        for handle in (in_read, in_write, out_read, out_write):
            if handle:
                close_handle(handle)
        raise ConPtyError(f"CreatePseudoConsole 失败 HRESULT {hr:#x}")
    # 伪控制台已持有这两端，本进程关闭自己的副本
    close_handle(in_read)
    close_handle(out_write)

    # 属性列表：把伪控制台挂到子进程上
    attr_size = ctypes.c_size_t(0)
    init_attrs(None, 1, 0, ctypes.byref(attr_size))
    attr_buf = ctypes.create_string_buffer(max(attr_size.value, 1))
    if not init_attrs(attr_buf, 1, 0, ctypes.byref(attr_size)) or not update_attr(
        attr_buf,
        0,
        _PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE,
        # lpValue 必须直接传 HPCON 值本身（官方文档传法）；传 byref(hpc)
        # 属性会静默失效——子进程挂到宿主控制台而非伪控制台
        ctypes.c_void_p(hpc.value),
        ctypes.sizeof(ctypes.c_void_p),
        None,
        None,
    ):
        delete_attrs(attr_buf)
        close_pty(hpc)
        close_handle(in_write)
        close_handle(out_read)
        raise ConPtyError("属性列表初始化失败")

    si = _StartupInfoEx()
    # cb 必须是 sizeof(STARTUPINFOEX)，这是启动信息扩展的关键
    si.StartupInfo.cb = ctypes.sizeof(_StartupInfoEx)
    si.lpAttributeList = ctypes.cast(attr_buf, ctypes.c_void_p)
    pi = _ProcessInformation()
    command_line = ctypes.create_unicode_buffer(subprocess.list2cmdline(cmd))
    # pty 属性只负责控制台；std 句柄默认继承父进程的管道。必须先把宿主
    # 三个标准句柄置 NULL，子进程才拿到伪控制台的 ConDrv 句柄，否则
    # 子进程输出会写进宿主的 stdout 管道，pty 侧一个字节都收不到。
    # CreateProcessW 在复制句柄时读取的是进程 PEB 的句柄表，置 NULL
    # 不影响本进程后续恢复。
    _saved_std = [get_std_handle(n) for n in (-10, -11, -12)]  # STD_INPUT/OUTPUT/ERROR
    for _n in (-10, -11, -12):
        set_std_handle(_n, None)
    try:
        created = create_proc(
            None,
            command_line,
            None,
            None,
            False,
            _EXTENDED_STARTUPINFO_PRESENT,
            None,
            None,
            # lpStartupInfo 传 STARTUPINFOEX 全结构的指针（STARTUPINFO*
            # 兼容指向其首字段）
            ctypes.cast(ctypes.pointer(si), ctypes.POINTER(_StartupInfo)),
            ctypes.byref(pi),
        )
    finally:
        for _n, _h in zip((-10, -11, -12), _saved_std, strict=False):
            set_std_handle(_n, _h)
    if not created:
        error = ctypes.get_last_error()
        delete_attrs(attr_buf)
        close_pty(hpc)
        close_handle(in_write)
        close_handle(out_read)
        raise OSError(error, "CreateProcessW 失败")
    delete_attrs(attr_buf)

    killer = threading.Timer(timeout, terminate, args=(pi.hProcess, 1))
    killer.daemon = True
    killer.start()

    def _watchdog() -> None:
        """子进程退出后关闭伪控制台，读取循环随之收到 ERROR_BROKEN_PIPE。"""
        wait_single(pi.hProcess, _INFINITE)
        # 给管道里在途的尾帧一点时间（随后仍可从管道读尽）
        time.sleep(0.15)
        close_pty(hpc)

    watcher = threading.Thread(target=_watchdog, daemon=True)
    watcher.start()

    buf = ctypes.create_string_buffer(_READ_CHUNK)
    try:
        while True:
            read = wintypes.DWORD()
            if not read_file(out_read, buf, _READ_CHUNK, ctypes.byref(read), None):
                error = ctypes.get_last_error()
                if error != 109:  # ERROR_BROKEN_PIPE 之外都是读取故障
                    raise OSError(error, "ReadFile 读取伪控制台输出失败")
                break  # ERROR_BROKEN_PIPE：伪控制台已关闭，输出结束
            if read.value:
                on_chunk(buf.raw[: read.value])
    finally:
        killer.cancel()
        watcher.join(timeout=5)
        # 确保子进程已退出并取退出码
        if wait_single(pi.hProcess, 5000) != 0:
            terminate(pi.hProcess, 1)
            wait_single(pi.hProcess, _INFINITE)
        code = wintypes.DWORD(0xFFFFFFFF)
        get_exit_code(pi.hProcess, ctypes.byref(code))
        returncode = int(code.value)
        for handle in (out_read, in_write, pi.hThread, pi.hProcess):
            if handle:
                close_handle(handle)
    return returncode
