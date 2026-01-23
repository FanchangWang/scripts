import sys
import subprocess
from PyQt6.QtWidgets import (QApplication, QDialog, QMessageBox,
                             QListWidget, QVBoxLayout, QPushButton, QWidget, QAbstractItemView)
from PyQt6.QtCore import Qt
from PyQt6.QtGui import QFont

class DeviceSelector(QDialog):
    """设备选择对话框"""
    def __init__(self, devices, parent=None):
        super().__init__(parent)
        self.setWindowTitle("选择设备")
        self.setGeometry(300, 300, 1000, 400)

        # 设置中文字体
        font = QFont()
        font.setFamily("SimHei")
        self.setFont(font)

        # 窗口居中显示
        self.center()

        self.selected_device = None

        layout = QVBoxLayout()

        self.list_widget = QListWidget()
        for device in devices:
            self.list_widget.addItem(device)
        # 在PyQt6中，使用QAbstractItemView中的SelectionMode枚举
        self.list_widget.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        if devices:
            self.list_widget.setCurrentRow(0)

        layout.addWidget(self.list_widget)

        select_btn = QPushButton("选择")
        # 加大按钮尺寸
        select_btn.setMinimumHeight(40)
        select_btn.clicked.connect(self.on_select)
        layout.addWidget(select_btn)

        self.setLayout(layout)

    def center(self):
        """将窗口居中显示"""
        qr = self.frameGeometry()
        # 在PyQt6中，QDesktopWidget被移除，使用QWidget的screen()方法
        screen = self.screen()
        if screen:
            cp = screen.availableGeometry().center()
            qr.moveCenter(cp)
            self.move(qr.topLeft())

    def on_select(self):
        if self.list_widget.selectedItems():
            self.selected_device = self.list_widget.selectedItems()[0].text().split()[0]
            self.accept()

def get_connected_devices():
    """获取已连接的Android设备列表"""
    try:
        result = subprocess.run(
            ["adb", "devices", "-l"],
            capture_output=True,
            text=True,
            check=True
        )
        output = result.stdout.strip()
        devices = []
        # 跳过第一行"List of devices attached"
        for line in output.split('\n')[1:]:
            if line.strip():
                # 分割行，检查状态是否为"device"（排除offline状态）
                parts = line.strip().split()
                if len(parts) > 1 and parts[1] == "device":
                    devices.append(line.strip())
        return devices
    except subprocess.CalledProcessError:
        return []
    except FileNotFoundError:
        QMessageBox.critical(None, "错误", "未找到adb，请确保adb已添加到系统PATH中")
        sys.exit(1)

def connect_to_device(ip_port):
    """连接到指定IP和端口的设备"""
    try:
        result = subprocess.run(
            ["adb", "connect", ip_port],
            capture_output=True,
            text=True
        )
        return "connected to" in result.stdout or "already connected" in result.stdout
    except Exception:
        return False

def start_scrcpy(device_id=None, extra_args=None):
    """启动scrcpy"""
    cmd = ["scrcpy.exe","--stay-awake"]
    # 询问用户是否保持屏幕关闭
    reply = QMessageBox.question(
        None,
        "屏幕设置",
        "是否保持屏幕关闭？",
        QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
    )
    if reply == QMessageBox.StandardButton.Yes:
        cmd.append("--turn-screen-off")
    if device_id:
        cmd.extend(["-s", device_id])
    if extra_args:
        cmd.extend(extra_args)

    try:
        # 启动scrcpy，不显示控制台窗口
        subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            stdin=subprocess.PIPE,
            text=True,
            encoding='utf-8',
            creationflags=subprocess.CREATE_NO_WINDOW  # Windows特定：不创建窗口
        )
        return True
    except FileNotFoundError:
        QMessageBox.critical(None, "错误", "未找到scrcpy.exe，请确保其已添加到系统PATH中")
        return False
    except Exception as e:
        QMessageBox.critical(None, "错误", f"启动scrcpy失败: {str(e)}")
        return False

def main():
    # 确保中文显示正常
    app = QApplication(sys.argv)
    font = QFont("SimHei")
    app.setFont(font)

    # 处理透传参数（跳过脚本名）
    extra_args = sys.argv[1:] if len(sys.argv) > 1 else []

    # 检查已连接设备
    devices = get_connected_devices()

    if len(devices) == 1:
        # 只有一台设备，直接连接
        device_id = devices[0].split()[0]
        start_scrcpy(device_id, extra_args)
    elif len(devices) > 1:
        # 多台设备，显示选择对话框
        selector = DeviceSelector(devices)
        if selector.exec() == QDialog.DialogCode.Accepted and selector.selected_device:
            start_scrcpy(selector.selected_device, extra_args)
    else:
        # 无设备，询问是否连接指定IP
        reply = QMessageBox.question(
            None,
            "无设备已连接",
            "是否尝试连接 192.168.31.60:5555 ?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )

        if reply == QMessageBox.StandardButton.Yes:
            if connect_to_device("192.168.31.60:5555"):
                # 连接成功，再次检查设备并启动scrcpy
                new_devices = get_connected_devices()
                if new_devices:
                    device_id = new_devices[0].split()[0]
                    start_scrcpy(device_id, extra_args)
                else:
                    QMessageBox.warning(None, "警告", "连接成功，但未检测到设备")
            else:
                QMessageBox.warning(None, "警告", "连接失败")
        # 用户选择No或连接失败，退出

if __name__ == "__main__":
    main()
