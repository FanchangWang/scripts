import argparse
import binascii
import ctypes
import os
import platform
import sys
import tkinter as tk
from tkinter import filedialog
import winreg

# ====================== 可配置区域：预设多组特征码 ======================
# 格式：[(搜索十六进制字符串, 替换十六进制字符串), ...]
# 按顺序匹配，任意一组替换成功即停止
PATCH_SETS = [
    ("807805000f94c1", "c64005014885c9"),    # Build 4143
    ("807905000f94c2", "c6410501b20090"),    # Build 4192
    ("0fb6510583f201", "c6410501b20090"),    # Build 4200
]
# ======================================================================


def show_message(message, title="提示", icon=0x00):
    """统一的消息提示函数（命令行 + 弹窗）"""
    if getattr(sys, 'frozen', False):
        ctypes.windll.user32.MessageBoxW(None, message, title, icon)
    else:
        print(f"[{title}] {message}")

def get_sublime_path():
    """从注册表获取 Sublime Text 安装路径"""
    # 定义要检查的注册表路径
    registry_paths = [
        r"Software\Microsoft\Windows\CurrentVersion\Uninstall",
        r"Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"
    ]

    for reg_path in registry_paths:
        try:
            # 打开当前注册表路径
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, reg_path) as hkey:
                i = 0
                while True:
                    try:
                        # 枚举当前路径下的所有子键
                        subkey = winreg.EnumKey(hkey, i)
                        if "Sublime Text" in subkey:
                            # 构建完整的注册表键路径
                            full_key = os.path.join(reg_path, subkey)
                            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, full_key) as app_key:
                                try:
                                    # 获取安装位置
                                    install_location = winreg.QueryValueEx(app_key, "InstallLocation")[0]
                                    # 构建可执行文件路径
                                    exe_path = os.path.join(install_location, "sublime_text.exe")
                                    # 检查文件是否存在
                                    if os.path.exists(exe_path):
                                        return exe_path
                                except FileNotFoundError:
                                    pass
                                except OSError:
                                    pass
                        i += 1
                    except OSError:
                        # 枚举结束
                        break
        except OSError:
            # 当前注册表路径不存在，尝试下一个
            pass

    return None

def hex_search_replace(filename):
    """读取文件，按顺序替换多组特征码，任意一组成功即返回成功"""
    # 读取文件内容
    try:
        with open(filename, 'rb') as f:
            content = f.read()
    except PermissionError:
        show_message(f"无法读取文件：{filename}，请检查权限！", "错误", 0x10)
        return False

    new_content = content
    replaced = False

    # 遍历所有特征码组，按顺序尝试替换
    for search_hex, replace_hex in PATCH_SETS:
        try:
            search_bytes = binascii.unhexlify(search_hex)
            replace_bytes = binascii.unhexlify(replace_hex)
        except binascii.Error:
            show_message(f"特征码格式错误：{search_hex} -> {replace_hex}", "错误", 0x10)
            continue

        # 先检查是否存在匹配的特征码
        if search_bytes in content:
            # 执行替换
            new_content = new_content.replace(search_bytes, replace_bytes)
            replaced = True
            break  # 成功一组就退出，不再尝试后续组

    # 最终结果处理
    if replaced:
        # 备份原文件（仅备份一次）
        bak_file = filename + ".bak"

        try:
            # 先备份原文件
            if not os.path.exists(bak_file):
                os.rename(filename, bak_file)

            # 将新内容写回到原文件中
            with open(filename, 'wb') as f:
                f.write(new_content)

            show_message("替换完成！", "成功")
            return True
        except PermissionError:
            show_message(f"无法写入文件：{filename}，尝试使用管理员权限运行！", "错误", 0x10)
            return False
        except Exception as e:
            show_message(f"文件操作失败：{str(e)}", "错误", 0x10)
            return False
    else:
        show_message("未找到任何匹配的特征码，替换失败", "失败", 0x10)
        return False

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Sublime Text 注册机')
    parser.add_argument('filename', nargs='?', help='sublime_text.exe 文件路径')
    args = parser.parse_args()

    # 系统判断
    if platform.system() != 'Windows':
        show_message("此工具仅支持 Windows 系统", "错误", 0x10)
        sys.exit()

    # 文件选择
    filename = args.filename
    if not filename:
        # 尝试从注册表获取 Sublime 安装路径
        filename = get_sublime_path()
        if filename:
            show_message(f"自动检测到 Sublime Text 安装路径：{filename}", "提示")
        else:
            # GUI 选择文件
            root = tk.Tk()
            root.withdraw()
            filename = filedialog.askopenfilename(title="请选择 sublime_text.exe 文件")
            if not filename:
                show_message("未选择文件", "提示", 0x10)
                sys.exit()

    # 文件存在性检查
    if not os.path.isfile(filename):
        show_message(f"文件不存在：{filename}", "错误", 0x10)
        sys.exit()

    # 执行补丁
    hex_search_replace(filename)
