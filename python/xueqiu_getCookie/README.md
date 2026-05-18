# 雪球 Cookie 获取工具

通过 PyQt6 WebEngine 获取雪球网站登录 Cookie。

## 安装依赖

```bash
uv sync
```

## 使用方法

1. 运行程序：`python main.py`
2. 点击「登录雪球」按钮
3. 在弹出的浏览器窗口中登录雪球
4. 登录完成后关闭该窗口
5. 点击「复制 Cookie」按钮，Cookie 将复制到剪贴板

## 获取的 Cookie

- `xq_a_token`
- `u`

## 注意事项

- 需要 Windows 系统（使用 pywin32 解密 Cookie）
- 登录窗口关闭后 Cookie 才会写入本地数据库
- 如复制失败，请确保已关闭登录窗口
