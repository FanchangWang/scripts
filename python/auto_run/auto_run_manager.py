import ctypes
import logging
from logging.handlers import TimedRotatingFileHandler
import os
import shlex
import subprocess
import sys
import time
import winreg

# 配置日志，使用覆盖模式
log_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'log')
os.makedirs(log_dir, exist_ok=True)
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - [PID:%(process)d] - %(levelname)s - %(message)s',
    handlers=[
        TimedRotatingFileHandler(
            os.path.join(log_dir, 'run.log'),
            when='D',
            interval=1,
            backupCount=7,
            encoding='utf-8'
        ),  # 日志文件放在log目录下，保留7个文件
        logging.StreamHandler(sys.stdout)
    ]
)

class AutoRunManager:
    """自动运行管理器，用于启动不同权限的程序"""

    def __init__(self):
        """初始化自动运行管理器"""
        self._uwp_apps_cache = None  # 实例变量替代全局变量
        self._task_name = "AutoRunManager\\runAsNormalUser"

    @property
    def is_admin(self):
        """检查当前是否以管理员权限运行"""
        try:
            return ctypes.windll.shell32.IsUserAnAdmin()
        except:
            return False

    def load_uwp_apps(self):
        """获取并加载UWP应用信息（带缓存机制）"""
        # 如果缓存存在，直接返回
        if self._uwp_apps_cache is not None:
            logging.info(f"使用缓存的UWP应用列表，共 {len(self._uwp_apps_cache)} 个应用")
            return self._uwp_apps_cache

        logging.info("开始获取UWP应用列表...")

        # 使用PowerShell命令Get-StartApps获取所有应用（包括商店应用）
        powershell_cmd = '''
        Get-StartApps | Where-Object {$_.AppID -like "*!*"} | Select-Object Name, AppID
        '''

        try:
            # 先以字节模式运行，然后尝试不同编码解码
            result = subprocess.run(
                ['powershell', '-Command', powershell_cmd],
                capture_output=True,
                text=False  # 先获取原始字节
            )

            if result.returncode != 0:
                logging.error(f"PowerShell命令执行失败")
                self._uwp_apps_cache = []
                return self._uwp_apps_cache

            # 尝试不同编码解码
            output = None
            for encoding in ['utf-8', 'gbk', 'utf-16']:
                try:
                    output = result.stdout.decode(encoding)
                    break
                except UnicodeDecodeError:
                    continue

            if output is None:
                logging.error("无法解码PowerShell输出")
                self._uwp_apps_cache = []
                return self._uwp_apps_cache

            # 解析输出
            lines = output.strip().split('\n')
            apps = []

            # 跳过表头
            if lines:
                lines = lines[2:]  # 跳过前两行表头

            for line in lines:
                line = line.strip()
                if not line:
                    continue

                # 分割列（假设Name和AppID之间有多个空格）
                parts = line.split()
                if len(parts) < 2:
                    continue

                # 重新组合Name（可能包含空格），最后一个是AppID
                app_id = parts[-1]
                app_name = ' '.join(parts[:-1])

                # 只处理UWP应用（AppID包含!）
                if '!' in app_id:
                    # 构建UWP应用启动路径
                    launch_path = f"shell:appsFolder\\{app_id}"

                    # 提取package_family_name和app_id
                    if '!' in app_id:
                        package_family_name, app_id_part = app_id.split('!', 1)
                    else:
                        package_family_name = app_id
                        app_id_part = ""

                    apps.append({
                        "app_name": app_name,
                        "launch_path": launch_path,
                        "package_family_name": package_family_name,
                        "app_id": app_id_part
                    })
                    logging.debug(f"找到UWP应用: {app_name} -> {launch_path}")

            logging.info(f"共找到 {len(apps)} 个UWP应用，已缓存")
            self._uwp_apps_cache = apps  # 缓存结果
            return self._uwp_apps_cache

        except Exception as e:
            logging.error(f"获取UWP应用时发生错误: {str(e)}")
            self._uwp_apps_cache = []
            return self._uwp_apps_cache

    def _find_uwp_app(self, package_name, app_id=""):
        """查找匹配的UWP应用（内部辅助方法）

        Args:
            package_name: 包族名
            app_id: 应用ID（可空）

        Returns:
            dict or None: 找到的UWP应用信息，找不到返回None
        """
        # 直接使用已缓存的UWP应用列表
        if self._uwp_apps_cache is None:
            return None

        # 查找匹配的UWP应用
        for app in self._uwp_apps_cache:
            # 通过package_family_name与app_id联合匹配
            if package_name.lower() in app['package_family_name'].lower():
                # 如果提供了app_id，则需要精确匹配；否则只匹配包名
                if not app_id or app_id == app['app_id']:
                    return app

        return None

    def start_exe(self, desc, path, args=""):
        """启动EXE程序

        Args:
            desc: 程序描述
            path: 程序路径
            args: 程序参数
        """
        logging.info(f"启动 exe: {desc} -> {path} {args}")
        try:
            is_shell = False
            split_args = shlex.split(args) if args.strip() else []
            if len(split_args) > 1: # 多个参数时必须 shell=True 防止解析错误
                full_cmd = f'"{path}" {args}'
                is_shell = True
            else:
                full_cmd = [path] + split_args
            subprocess.Popen(
                full_cmd,
                shell=is_shell,
                cwd=os.path.dirname(path),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=subprocess.CREATE_NO_WINDOW
            )
            time.sleep(2) # 等待程序启动完成
            return True
        except Exception as e:
            logging.error(f"启动失败 ErrMsg: {str(e)}")
            return False

    def start_uwp(self, desc, package_name, app_id=""):
        """启动UWP应用

        Args:
            desc: 应用描述
            package_name: 包族名
            app_id: 应用 id
        """
        logging.info(f"启动 UWP: {desc} -> {package_name} {app_id}")
        # 查找匹配的UWP应用
        target_app = self._find_uwp_app(package_name, app_id)
        if not target_app:
            logging.error(f"未找到应用")
            return False

        try:
            logging.debug(f"UWP name: {target_app['app_name']} path: {target_app['launch_path']}")
            # 使用explorer.exe启动UWP应用，更可靠
            full_cmd = [
                "explorer.exe",
                target_app['launch_path']
            ]
            subprocess.Popen(
                full_cmd,
                shell=False,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=subprocess.CREATE_NO_WINDOW
            )
            time.sleep(2)  # 等待程序启动完成
            return True
        except Exception as e:
            logging.error(f"启动失败 ErrMsg: {str(e)}")
            return False

    def _get_environment_variable(self, is_system=False):
        """读取系统或用户环境变量"""
        try:
            if is_system:
                key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r'SYSTEM\CurrentControlSet\Control\Session Manager\Environment', 0, winreg.KEY_READ)
            else:
                key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Environment', 0, winreg.KEY_READ)
            value, _ = winreg.QueryValueEx(key, 'Path')
            winreg.CloseKey(key)
            return value
        except Exception as e:
            logging.error(f"读取环境变量失败: {str(e)}")
            return ""

    def _set_environment_variable(self, value, is_system=False):
        """设置系统或用户环境变量"""
        try:
            if is_system and not self.is_admin:
                logging.error("修改系统环境变量需要管理员权限")
                return False

            access = winreg.KEY_WRITE | (winreg.KEY_WOW64_64KEY if is_system else 0)
            if is_system:
                key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r'SYSTEM\CurrentControlSet\Control\Session Manager\Environment', 0, access)
            else:
                key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Environment', 0, winreg.KEY_WRITE)

            # 写入环境变量，使用REG_EXPAND_SZ类型
            winreg.SetValueEx(key, 'Path', 0, winreg.REG_EXPAND_SZ, value)
            winreg.CloseKey(key)
            return True
        except Exception as e:
            logging.error(f"写入环境变量失败: {str(e)}")
            return False

    def _broadcast_environment_change(self):
        """通知系统环境变量已更改"""
        HWND_BROADCAST = 0xFFFF
        WM_SETTINGCHANGE = 0x001A
        SMTO_ABORTIFHUNG = 0x0002

        ctypes.windll.user32.SendMessageTimeoutW(
            HWND_BROADCAST,
            WM_SETTINGCHANGE,
            0,
            "Environment",
            SMTO_ABORTIFHUNG,
            5000,
            None
        )

    def _process_path(self, path_str):
        """处理Path字符串：去重并排序"""
        # 分割路径，处理空字符串
        paths = [p for p in path_str.split(';') if p.strip()]
        # 去重，保留顺序
        seen = set()
        unique_paths = []
        for p in paths:
            p = os.path.expandvars(p)
            normalized = os.path.normpath(p)
            if not os.path.exists(normalized): # 去除不存在的目录
                continue
            if normalized not in seen:
                seen.add(normalized)
                unique_paths.append(normalized)
        # 排序
        unique_paths.sort()
        # 重新拼接
        return ';'.join(unique_paths)

    def clean_and_sort_path(self):
        """读取系统和用户Path，去重排序后写回"""
        logging.info("开始清理和排序环境变量Path")

        # 读取系统Path和用户Path
        system_path = self._get_environment_variable(is_system=True)
        if system_path:
            logging.info(f"原始系统Path: {system_path}")
            processed_system_path = self._process_path(system_path)
            logging.info(f"处理后的系统Path: {processed_system_path}")
            system_result = self._set_environment_variable(processed_system_path, is_system=True)
            if system_result:
                logging.info(f"系统Path已清理排序: {processed_system_path}")
            else:
                logging.error("写入系统Path失败")

        user_path = self._get_environment_variable(is_system=False)
        if user_path:
            logging.info(f"原始用户Path: {user_path}")
            processed_user_path = self._process_path(user_path)
            logging.info(f"处理后的用户Path: {processed_user_path}")
            user_result = self._set_environment_variable(processed_user_path, is_system=False)
            if user_result:
                logging.info(f"用户Path已清理排序: {processed_user_path}")
            else:
                logging.error("写入用户Path失败")

        # 通知系统环境变量已更改
        self._broadcast_environment_change()

    def run(self):
        """运行自动启动管理器"""
        logging.info("=== 自动运行管理器开始执行 ===")

        # 检查是否以管理员权限运行
        if self.is_admin:
            logging.info("当前以管理员权限运行")
            self.clean_and_sort_path() # 清理和排序环境变量Path
            self.load_uwp_apps() # 加载UWP应用列表
            self.start_uwp('SnipDo 复制工具', r'JohannesTscholl.Pantherbar')
            self.start_exe('Snow Shot 截图工具', r'C:\Program Files\Snow Shot\snowshot.exe', '--auto_start')
            self.start_exe("SwitchHosts", r'C:\Users\guyue\AppData\Local\Programs\SwitchHosts\SwitchHosts.exe')
            self.start_exe("PowerToys", r'C:\Program Files\PowerToys\PowerToys.exe')
        else:
            logging.info("当前以普通用户权限运行")
            self.start_exe('Maye Lite 启动器', r'C:\Users\guyue\AppData\Local\Programs\MayeLite\Maye Lite.exe', '--autoruns')
            self.start_exe('Twinkle Tray', r'C:\Users\guyue\AppData\Local\Programs\twinkle-tray\Twinkle Tray.exe')
            self.start_exe('EcoPaste 剪贴板', r'C:\Users\guyue\AppData\Local\Programs\EcoPaste\EcoPaste.exe', '--auto-launch')
            self.start_exe('pot 翻译工具', r'C:\Program Files\pot\pot.exe')

            self.start_exe("Microsoft OneNote", r'C:\Program Files\Microsoft Office\root\Office16\ONENOTEM.EXE', '/tsr')
            self.start_exe("JetBrains Toolbox", r'C:\Users\guyue\AppData\Local\JetBrains\Toolbox\bin\jetbrains-toolbox.exe', '--minimize')
            self.start_exe("FDM 下载器", r'C:\Users\guyue\AppData\Local\Softdeluxe\Free Download Manager\fdm.exe', '--hidden')
            self.start_exe("UniGetUI", r'C:\Program Files\UniGetUI\UniGetUI.exe', '--daemon')

            self.start_exe("微信", r'C:\Program Files\Tencent\Weixin\Weixin.exe', '-autorun')

            self.start_exe("Epson Event Manager", r'C:\Program Files (x86)\Epson Software\Event Manager\EEventManager.exe')
            self.start_exe("Epson Status", r'C:\WINDOWS\system32\spool\DRIVERS\x64\3\E_YATIYOE.EXE', r'/EPT "EPLTarget\P0000000000000001" /M "L4260 Series" /EF "HKCU"')

        logging.info("=== 自动运行管理器执行完成 ===")

if __name__ == "__main__":
    # 创建AutoRunManager实例并运行
    AutoRunManager().run()
