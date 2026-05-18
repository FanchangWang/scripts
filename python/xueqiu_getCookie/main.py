import os
import shutil
import sqlite3
import sys

import win32crypt
from PyQt6.QtCore import Qt, QUrl
from PyQt6.QtGui import QCloseEvent
from PyQt6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile
from PyQt6.QtWebEngineWidgets import QWebEngineView
from PyQt6.QtWidgets import (
    QApplication,
    QDialog,
    QLabel,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

APP_DATA_PATH = os.path.join(os.path.expanduser("~"), "xueqiu_cookie_getter")
os.makedirs(APP_DATA_PATH, exist_ok=True)


def decrypt_windows_cookie(encrypted_blob):
    try:
        decrypted = win32crypt.CryptUnprotectData(encrypted_blob, None, None, None, 0)
        return decrypted[1].decode("utf-8", errors="ignore")
    except Exception:
        return ""


def get_cookie_from_db():
    possible_paths = [
        os.path.join(APP_DATA_PATH, "Network", "Cookies"),
        os.path.join(APP_DATA_PATH, "Cookies"),
    ]

    cookie_db_path = None
    for path in possible_paths:
        if os.path.exists(path):
            cookie_db_path = path
            break

    if not cookie_db_path:
        return "", ""

    temp_db_path = "xueqiu_temp_cookies.db"
    try:
        shutil.copy2(cookie_db_path, temp_db_path)
    except PermissionError:
        return "", ""

    xq_a_token = ""
    u_value = ""

    try:
        conn = sqlite3.connect(temp_db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT host_key, name, value, encrypted_value FROM cookies")
        for row in cursor.fetchall():
            domain, name, v_raw, v_encrypted = row[0], row[1], row[2], row[3]
            if "xueqiu" not in domain:
                continue
            if v_raw and len(v_raw) > 0:
                final_value = v_raw
            elif v_encrypted and len(v_encrypted) > 0:
                final_value = decrypt_windows_cookie(v_encrypted)
            else:
                final_value = ""
            if name == "xq_a_token":
                xq_a_token = final_value
            elif name == "u":
                u_value = final_value
        conn.close()
    except Exception:
        pass
    finally:
        if os.path.exists(temp_db_path):
            os.remove(temp_db_path)

    return xq_a_token, u_value


class LoginWindow(QDialog):
    def __init__(self):
        super().__init__()
        self.init_profile()
        self.init_ui()

    def init_profile(self):
        self.profile = QWebEngineProfile("xueqiu_login_profile", self)
        self.profile.setPersistentStoragePath(APP_DATA_PATH)
        self.profile.setPersistentCookiesPolicy(
            QWebEngineProfile.PersistentCookiesPolicy.AllowPersistentCookies
        )

    def init_ui(self):
        self.setWindowTitle("雪球登录")
        self.resize(1280, 800)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        self.web_view = QWebEngineView()
        page = QWebEnginePage(self.profile, self.web_view)
        self.web_view.setPage(page)
        self.web_view.load(QUrl("https://xueqiu.com/"))

        self.hint_label = QLabel("登录完成后关闭此窗口，然后点击「复制 Cookie」", self)
        self.hint_label.setStyleSheet(
            "background-color: #fef3c7; color: #92400e; padding: 8px; font-size: 14px;"
        )
        self.hint_label.setFixedHeight(30)
        layout.addWidget(self.hint_label, 0)
        layout.addWidget(self.web_view)

    def closeEvent(self, event: QCloseEvent):
        self.cleanup()
        event.accept()

    def cleanup(self):
        if self.web_view:
            self.web_view.close()
            self.web_view.deleteLater()
            self.web_view.setPage(None)
            self.web_view.deleteLater()
        self.profile.deleteLater()


class XueQiuCookieGetter(QMainWindow):
    def __init__(self):
        super().__init__()
        self.init_ui()

    def init_ui(self):
        self.setWindowTitle("雪球 Cookie 获取工具")
        self.setFixedSize(400, 200)

        central_widget = QWidget()
        self.setCentralWidget(central_widget)

        layout = QVBoxLayout(central_widget)
        layout.addStretch()

        self.login_btn = QPushButton("登录雪球", self)
        self.login_btn.setStyleSheet(
            "height: 48px; font-size: 16px; font-weight: bold; "
            "background-color: #3b82f6; color: white; border: none; border-radius: 8px;"
        )
        self.login_btn.clicked.connect(self.open_login_window)
        layout.addWidget(self.login_btn)

        self.copy_btn = QPushButton("复制 Cookie", self)
        self.copy_btn.setStyleSheet(
            "height: 48px; font-size: 16px; font-weight: bold; "
            "background-color: #10b981; color: white; border: none; border-radius: 8px;"
        )
        self.copy_btn.clicked.connect(self.copy_cookie)
        layout.addWidget(self.copy_btn)

        layout.addStretch()

    def open_login_window(self):
        login_window = LoginWindow()
        login_window.exec()

    def copy_cookie(self):
        xq_a_token, u_value = get_cookie_from_db()

        if xq_a_token and u_value:
            cookie_str = f'XUEQIU_COOKIE="xq_a_token={xq_a_token}; u={u_value}"'
            clipboard = QApplication.clipboard()
            clipboard.setText(cookie_str)
            QMessageBox.information(self, "成功", "Cookie 已复制到剪贴板！")
        else:
            QMessageBox.warning(
                self, "提示", "未找到完整的 Cookie 信息，请先登录雪球网站"
            )


if __name__ == "__main__":
    QApplication.setHighDpiScaleFactorRoundingPolicy(
        Qt.HighDpiScaleFactorRoundingPolicy.PassThrough
    )
    app = QApplication(sys.argv)
    window = XueQiuCookieGetter()
    window.show()
    sys.exit(app.exec())
