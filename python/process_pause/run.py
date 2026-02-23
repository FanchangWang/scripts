import psutil
import ctypes
import tkinter as tk
from tkinter import messagebox

def get_wechat_process():
    """获取内存使用最大的WeChatAppEx.exe进程"""
    wechat_processes = []
    for proc in psutil.process_iter(['pid', 'name', 'memory_info']):
        try:
            if proc.info['name'] == 'WeChatAppEx.exe':
                wechat_processes.append(proc)
        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            pass

    if not wechat_processes:
        return None

    # 按内存使用量排序，返回最大的那个
    wechat_processes.sort(key=lambda p: p.info['memory_info'].rss, reverse=True)
    return wechat_processes[0]

def pause_process(pid):
    """暂停进程"""
    try:
        process = psutil.Process(pid)
        process.suspend()
        return True, ""
    except Exception as e:
        return False, f"{type(e).__name__}: {str(e)}"

def resume_process(pid):
    """恢复进程"""
    try:
        process = psutil.Process(pid)
        process.resume()
        return True, ""
    except Exception as e:
        return False, f"{type(e).__name__}: {str(e)}"

# 高 DPI 感知
ctypes.windll.shcore.SetProcessDpiAwareness(1)

class WeChatProcessManager:
    def __init__(self, root):
        self.root = root
        self.root.title("微信小程序暂停工具")
        self.root.geometry("320x120")
        self.root.resizable(False, False)
        self.root.attributes("-topmost", True)

        # 设置窗口样式
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

        # 进程信息
        self.current_proc = None
        self.is_paused = False

        # 创建UI组件
        self.create_widgets()

    def create_widgets(self):
        # 第一行：PID显示和查询按钮
        row1_frame = tk.Frame(self.root)
        row1_frame.pack(pady=10, padx=10, fill=tk.X)

        self.pid_label = tk.Label(row1_frame, text="进程 PID：暂未查询", width=15, anchor=tk.W)
        self.pid_label.pack(side=tk.LEFT)

        self.query_btn = tk.Button(row1_frame, text="查询进程", command=self.query_process, padx=15, pady=3)
        self.query_btn.pack(side=tk.RIGHT)

        # 第二行：状态显示和暂停/恢复按钮
        row2_frame = tk.Frame(self.root)
        row2_frame.pack(pady=5, padx=10, fill=tk.X)

        self.status_label = tk.Label(row2_frame, text="进程状态：运行中", width=15, anchor=tk.W)
        self.status_label.pack(side=tk.LEFT)

        self.toggle_btn = tk.Button(row2_frame, text="暂停进程", command=self.toggle_process, state=tk.DISABLED, padx=15, pady=3)
        self.toggle_btn.pack(side=tk.RIGHT)

    def query_process(self):
        """查询进程"""
        self.current_proc = get_wechat_process()
        if self.current_proc:
            pid = self.current_proc.info['pid']
            self.pid_label.config(text=f"进程 PID：{pid}")
            self.toggle_btn.config(state=tk.NORMAL, text="暂停进程")
            self.is_paused = False
            self.status_label.config(text="进程状态：运行中")
        else:
            messagebox.showinfo("提示", "未找到运行中的 WeChatAppEx.exe 进程")
            self.pid_label.config(text="进程 PID：查询失败")
            self.toggle_btn.config(state=tk.DISABLED)
            self.status_label.config(text="进程状态：运行中")

    def toggle_process(self):
        """切换暂停/恢复状态"""
        if not self.current_proc:
            return

        pid = self.current_proc.info['pid']

        if not self.is_paused:
            # 暂停进程
            success, error_msg = pause_process(pid)
            if success:
                self.is_paused = True
                self.toggle_btn.config(text="恢复进程")
                self.status_label.config(text="进程状态：暂停中")
            else:
                messagebox.showerror("错误", f"无法暂停进程 {pid}\n{error_msg}\n提示: 请尝试以管理员身份运行此脚本")
        else:
            # 恢复进程
            success, error_msg = resume_process(pid)
            if success:
                self.is_paused = False
                self.toggle_btn.config(text="暂停进程")
                self.status_label.config(text="进程状态：运行中")
            else:
                messagebox.showerror("错误", f"无法恢复进程 {pid}\n{error_msg}\n提示: 请尝试以管理员身份运行此脚本")

    def on_close(self):
        """关闭窗口"""
        self.root.destroy()

def main():
    root = tk.Tk()
    app = WeChatProcessManager(root)
    root.mainloop()

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except Exception as e:
        messagebox.showerror("错误", f"发生错误: {e}")
