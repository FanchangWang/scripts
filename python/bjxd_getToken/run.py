import sys
from PyQt6.QtWidgets import (
    QApplication,
    QMainWindow,
    QVBoxLayout,
    QWidget,
    QPushButton,
    QTextEdit,
    QMessageBox,
)
from PyQt6.QtWebEngineWidgets import QWebEngineView
from PyQt6.QtCore import QUrl
from PyQt6.QtGui import QGuiApplication
from PyQt6.QtCore import Qt


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()

        self.setWindowTitle("获取 北京现代 APP api token")
        # 设置窗口大小
        self.resize(600, 800)
        # 计算窗口的位置，使其居中
        screen = QGuiApplication.primaryScreen()
        screen_geometry = screen.availableGeometry()
        x = (screen_geometry.width() - self.width()) // 2
        y = (screen_geometry.height() - self.height()) // 2
        self.move(x, y)

        self.browser = QWebEngineView()
        self.browser.setUrl(QUrl("https://bm2-wx.bluemembers.com.cn/browser/login"))

        # 创建按钮
        self.button = QPushButton("先在上面登录后，再点我获取并复制 Token", self)
        self.button.setStyleSheet("height: 60px;")  # 增加按钮高度
        self.button.clicked.connect(self.get_token)

        # 创建只读文本编辑框
        self.token_edit = QTextEdit(self)  # 使用 QTextEdit
        self.token_edit.setReadOnly(True)
        self.token_edit.setFixedHeight(180)  # 设置固定高度，可以根据需要调整

        # 设置布局
        layout = QVBoxLayout()
        layout.addWidget(self.browser)
        layout.addWidget(self.button)
        layout.addWidget(self.token_edit)

        container = QWidget()
        container.setLayout(layout)
        self.setCentralWidget(container)

    def get_token(self):
        # 使用 JavaScript 获取 localStorage 中的 token
        self.browser.page().runJavaScript(
            "localStorage.getItem('token');", self.handle_result
        )

    def handle_result(self, result):
        if not result:
            QMessageBox.information(
                self, "错误", "Token 获取失败！请先登录然后再获取 Token"
            )
            return
        # 处理结果
        self.token_edit.setPlainText(result)  # 将 token 显示在文本编辑框中
        # 复制 token 到剪贴板
        clipboard = QApplication.clipboard()
        clipboard.setText(result)
        QMessageBox.information(
            self,
            "提示",
            f"Token 获取成功！并且已经复制到剪贴板！",
        )


if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    sys.exit(app.exec())
