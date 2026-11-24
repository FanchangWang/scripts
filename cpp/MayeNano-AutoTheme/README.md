# Maye Nano Auto Theme

Maye Nano 快速启动器插件：跟随 Windows 浅色 / 深色模式**自动切换主题**。

直接调用软件自身的主题应用函数，**不重启应用**，因此不会重新注册热键、不会打断使用。设置界面切换主题时走的也是同一个函数（入口通过逆向定位，见 [逆向笔记](docs/reverse-engineering.md)）。

## 功能

- 系统深浅色变化时立即切换主题（监听注册表 `AppsUseLightTheme`）。
- 软件设置窗口打开时自动弹出插件设置窗口；软件设置窗口关闭时自动隐藏（可手动关闭，也可用 `OpenWithSettings` 关掉自动弹出）。
- 首次安装后第一次启动自动弹一次设置窗口（之后仅在主题丢失或打开软件设置时弹出）。
- 窗口标题「Auto Theme 插件设置」，定位在软件设置窗口附近，支持高 DPI，顶部有用途说明、版本匹配结果与提示文案。
- 插件设置窗口：
  - 显示支持版本 / 当前版本；
  - Light / Dark 下拉框从 `theme\*.json` 动态读取，含 `Default` 项；
  - 可启用 / 禁用自动切换并保存；
  - 窗口置顶跟随软件 `cfg.json` 的 `IsTopMost`；
  - 主题文件丢失时自动弹出并提示重新选择；
  - 版本不支持时提示更新插件或禁用插件，功能静默。
- 默认 Dark = `Black-1.json`，Light = `Default`。

## 目录结构

```
MayeNano-AutoTheme/
├── README.md
├── .gitignore
├── src/
│   ├── p_AutoTheme.cpp
│   └── SDK_MN.hpp
├── config/
│   └── autotheme.ini
├── scripts/
│   ├── build.bat
│   └── install.ps1
├── docs/
│   ├── reverse-engineering.md
│   └── install.md
├── build/                 中间产物（git 忽略）
└── dist/
    └── mn.cc.arae.autotheme.dll   交付物（入库）
```

## 快速开始

```bat
scripts\build.bat
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install.ps1
```

默认安装到 `C:\Users\guyue\AppData\Local\Programs\MayeNano`，可用 `-TargetDir` 覆盖。

## 兼容性

已内置以下版本的主题函数 RVA（无需分析）：

| 版本 | RVA |
|---|---|
| 7.0.1.261107 | `0x1819D0` |
| 6.2.0.260501 | `0x17D970` |
| 6.1.0.260422 | `0x17D920` |
| 6.0.0.260417 | `0x17B150` |

未知版本会尝试自动定位主题函数；失败则静默降级并在设置窗口提示。详见 [docs/install.md](docs/install.md) 与 [docs/reverse-engineering.md](docs/reverse-engineering.md)。
