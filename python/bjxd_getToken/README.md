# bjxd_getToken

内嵌 PyQt6 WebEngine 登录北京现代 H5，登录后从 `localStorage` 读取 token 并复制到剪贴板。

## 环境要求

- Python ≥ 3.10
- 依赖：`PyQt6>=6.11.0`、`PyQt6-WebEngine>=6.11.0`
- 使用 uv 管理虚拟环境

## 快速开始

```bash
uv sync
uv run main.py
```

## 用法 / 参数

1. 窗口内嵌浏览器打开北京现代登录页：`https://bm2-wx.bluemembers.com.cn/browser/login`。
2. 在页面中完成登录。
3. 点击「先在上面登录后，再点我获取并复制 Token」按钮。
4. 程序执行 `localStorage.getItem('token')`，把 token 显示在只读文本框并复制到剪贴板。

无命令行参数。

## 功能 / 行为说明

- 取不到 token（未登录 / localStorage 中无 `token`）时弹窗提示「Token 获取失败」。
- 成功后弹窗提示并已复制到剪贴板，可直接粘贴到其他脚本 / 接口调试使用。

## 开发

- 入口：`main.py`；窗口标题「获取 北京现代 APP api token」，初始 600×800 居中。
- 登录地址如需调整，修改 `MainWindow.__init__` 中的 `setUrl`。
