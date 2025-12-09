import subprocess
import os
import ctypes



TASK_DIR = f"AutoRunManager"
TASK_NAME = f"{TASK_DIR}\\AutoRunManagerTask"

def is_admin():
    """检查是否以管理员权限运行"""
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except:
        return False

def run_as_admin():
    """以管理员权限运行当前脚本"""
    # 获取当前脚本路径
    script_path = os.path.abspath(__file__)
    # 使用ShellExecute提升权限
    ctypes.windll.shell32.ShellExecuteW(
        None, "runas", "python", f'"{script_path}"', None, 1
    )

def create_task():
    """创建计划任务"""
    # 任务计划命令
    script_name = "auto_run_manager.py"
    script_dir = os.path.dirname(os.path.abspath(__file__))
    task_run_cmd = f'python.exe "{script_dir}\\{script_name}"'
    try:
        # 获取当前Windows登录的用户（兼容域账户）
        # 优先使用域\用户名格式，不存在域则使用本地用户名
        domain = os.environ.get("USERDOMAIN")
        username = os.environ.get("USERNAME")
        login_user = f"{domain}\\{username}" if domain else username
        create_cmd = [
            "schtasks", "/create",
            "/tn", TASK_NAME,
            "/tr", task_run_cmd,
            "/sc", "onlogon", # 触发器 登录时
            "/ru", login_user, # 仅当前用户登录时触发
            "/it", # 交互方式运行任务（需要用户登录）
            "/delay", "0000:20", # 延迟20秒执行
            "/rl", "HIGHEST", # 以最高权限运行（管理员权限）
            "/f" # 强制创建，覆盖已存在任务
        ]
        result = subprocess.run(create_cmd, capture_output=True, text=True, encoding='gbk')
        if result.returncode != 0:
            print(f"创建任务失败: {result.stderr}")
            return False
        print(f"创建任务 {result.stdout}")
        return True
    except Exception as e:
            print(f"创建任务失败: {str(e)}")
            return False

def delete_task():
    """删除计划任务"""
    try:
        delete_cmd = [
            "schtasks", "/delete",
            "/tn", TASK_NAME,
            "/f" # 强制删除，不提示确认
        ]
        result = subprocess.run(delete_cmd, capture_output=True, text=True, encoding='gbk')
        if result.returncode != 0:
            print(f"删除任务失败: {result.stderr}")
            return False
        print(f"删除任务 {result.stdout}")
        return True
    except Exception as e:
        print(f"删除任务失败: {str(e)}")
        return False

def main():
    """主函数"""
    print("\n")
    print("=== 创建任务计划程序脚本开始执行 ====")
    print("\n请选择操作:")
    print("  Yy: 创建或更新任务计划")
    print("  Dd: 删除已存在任务计划")
    print("  其他: 取消操作")
    choice = input("请输入 (默认: Y): ").strip().lower()

    if choice == '' or choice == 'y':
        success = create_task()
        if success:
            print("你可以在任务计划程序中查看和管理这个任务")
    elif choice == 'd':
        delete_task()
    else:
        print("操作取消")
    input("\n脚本执行完毕，按任意键退出")

if __name__ == "__main__":
    if not is_admin():
        # 命令行提示需要管理员权限
        print("需要管理员权限才能创建任务计划，尝试申请管理员权限...")
        run_as_admin()
        exit()
    # 以管理员权限运行主函数
    main()
