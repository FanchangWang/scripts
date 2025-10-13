import sys
from PyQt5.QtCore import QUrl, QCoreApplication, Qt
from PyQt5.QtGui import QGuiApplication
from PyQt5.QtWebEngineWidgets import QWebEngineView, QWebEngineProfile, QWebEnginePage
from PyQt5.QtWidgets import (
    QApplication,
    QMainWindow,
    QTextEdit,
    QVBoxLayout,
    QWidget,
    QPushButton,
    QMessageBox,
)


class CookieViewer(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("获取 嘉立创开源硬件平台 Cookie")
        # 计算窗口的位置，使其居中
        screen = QGuiApplication.primaryScreen()
        screen_geometry = screen.availableGeometry()
        scr_width = screen_geometry.width()
        scr_height = screen_geometry.height()
        # 设置窗口大小
        self.resize(int(scr_width * 0.7), int(scr_height * 0.8))
        x = (scr_width - self.width()) // 2
        y = (scr_height - self.height()) // 2
        self.move(x, y)

        self.cookies = {}
        self.required_cookie_names = ["oshwhub_session", "oshwhub_csrf"]

        # 创建界面组件
        self.copy_button = QPushButton("先在下面登录后，再点我获取并复制 Cookie")
        self.copy_button.setFixedHeight(60)
        self.cookie_text = QTextEdit()
        self.cookie_text.setFixedHeight(100)
        self.cookie_text.setReadOnly(True)
        self.browser = QWebEngineView()

        # 布局设置
        central_widget = QWidget()
        layout = QVBoxLayout()
        layout.addWidget(self.copy_button)
        layout.addWidget(self.cookie_text)
        layout.addWidget(self.browser)
        self.copy_button.clicked.connect(self.on_copy_button_click)
        central_widget.setLayout(layout)
        self.setCentralWidget(central_widget)

        # 创建自定义Profile和Page
        self.profile = QWebEngineProfile("custom_profile", self)
        self.page = QWebEnginePage(self.profile, self)  # 使用自定义profile创建page
        self.browser.setPage(self.page)  # 设置page到view

        # 连接Cookie添加信号
        self.profile.cookieStore().cookieAdded.connect(self.handle_cookie_added)

        # 加载测试网页 - 替换为你的目标网址
        self.browser.load(QUrl("https://oshwhub.com/sign_in"))

    def handle_cookie_added(self, cookie):
        """处理Cookie添加事件"""
        # 检查是否为HttpOnly Cookie
        try:
            name = cookie.name().data().decode("utf-8", errors="replace")
            value = cookie.value().data().decode("utf-8", errors="replace")
            if name in self.required_cookie_names:
                self.cookies[name] = value
                cookie_info = (
                    f"名称: {name} 域: {cookie.domain()}  {cookie.path()} {cookie.expirationDate().toString('yyyy-MM-dd HH:mm:ss')} httpOnly: {cookie.isHttpOnly()} 安全: {cookie.isSecure()}\n"
                    f"值: {value}\n"
                    "-----------------------------\n"
                )
                print(cookie_info)
        except Exception as e:
            print(f"处理Cookie时出错: {e}")

    def on_copy_button_click(self):
        cookie_str = "; ".join(
            [
                f"{k}={v}"
                for k, v in self.cookies.items()
                if k in self.required_cookie_names
            ]
        )
        self.cookie_text.clear()
        self.cookie_text.setText(cookie_str)
        clipboard = QApplication.clipboard()
        clipboard.setText(cookie_str)
        QMessageBox.information(
            self, "提示", f"Cookie 获取成功！并且已经复制到剪贴板！"
        )


if __name__ == "__main__":
    # 适应高DPI设备
    QCoreApplication.setAttribute(Qt.AA_EnableHighDpiScaling)
    # 适应Windows缩放
    QGuiApplication.setAttribute(Qt.HighDpiScaleFactorRoundingPolicy.PassThrough)
    app = QApplication(sys.argv)
    window = CookieViewer()
    window.show()
    sys.exit(app.exec_())
