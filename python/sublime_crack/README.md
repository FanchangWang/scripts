# Sublime Text 注册机

一个用于去除 Sublime Text 未注册弹窗的补丁工具，支持自动检测安装路径和自动提权。

## 功能特点

- 🎯 **自动检测**：从注册表自动获取 Sublime Text 安装路径
- 🔧 **智能补丁**：支持多组特征码，自动匹配适合的版本
- 💾 **安全备份**：自动备份原文件，防止操作失误
- 📱 **友好界面**：命令行与 GUI 结合，操作简单
- 🛡️ **错误处理**：完善的错误提示和异常处理

## 支持版本

- Sublime Text 旧版本
- Build 4143
- Build 4192
- Build 4200
- 可通过添加特征码支持更多版本

## 环境要求

- Python 3.12+
- Windows 系统
- uv 包管理器（可选，用于虚拟环境）

## 使用方法

### 方法一：使用 uv 运行

```bash
# 进入项目目录
cd scripts\python\sublime_crack

# 使用 uv 运行（自动创建虚拟环境）
python -m uv run main.py
```

### 方法二：直接运行

```bash
# 进入项目目录
cd scripts\python\sublime_crack

# 直接运行
python main.py
```

### 方法三：指定文件路径

```bash
# 指定 Sublime Text 可执行文件路径
python main.py "C:\Program Files\Sublime Text\sublime_text.exe"
```

## 工作原理

1. **自动检测**：程序从 Windows 注册表读取 Sublime Text 安装信息
2. **特征匹配**：搜索可执行文件中的特定十六进制特征码
3. **智能替换**：使用预设的补丁替换匹配到的特征码
4. **安全备份**：在替换前自动备份原文件

## 配置说明

在 `main.py` 文件中，可以修改 `PATCH_SETS` 列表添加更多特征码：

```python
PATCH_SETS = [
    ("搜索十六进制字符串", "替换十六进制字符串"),    # 版本说明
    # 可继续添加更多组...
]
```

## 注意事项

1. 运行前请关闭 Sublime Text
2. 程序会自动备份原文件（.bak 后缀）
3. 操作有风险，建议先手动备份 Sublime Text 文件夹
4. 仅用于学习和测试，请勿用于商业用途

## 技术细节

- **开发语言**：Python 3.12
- **依赖管理**：uv
- **GUI 库**：tkinter
- **系统 API**：winreg, ctypes

## 故障排除

### 无法检测到 Sublime Text
- 确保 Sublime Text 已正确安装
- 尝试手动指定文件路径

### 替换失败
- 确保 Sublime Text 版本在支持列表中
- 尝试添加新的特征码

## 许可证

仅供学习和研究使用。

## 更新日志

- **v1.0.0**：初始版本
  - 支持自动检测 Sublime Text 路径
  - 支持多组特征码
  - 完善的错误处理

---

**免责声明**：本工具仅用于学习和测试，请勿用于商业用途。使用本工具造成的任何后果，由使用者自行承担。
