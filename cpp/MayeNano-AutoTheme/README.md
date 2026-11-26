# Maye Nano Auto Theme

最新版本请通过 [mayenano-autotheme-latest](https://github.com/FanchangWang/scripts/releases/tag/mayenano-autotheme-latest) 获取；版本号定义在 `src/p_AutoTheme.cpp` 的 `PLUGIN_VERSION`，文档不写死版本号。

Maye Nano 快速启动器插件：跟随 Windows 浅色 / 深色模式**自动切换主题**。

直接调用软件自身的主题应用函数，**不重启应用**，因此不会重新注册热键、不会打断使用。设置界面切换主题时走的也是同一个函数（入口通过逆向定位，见 [逆向笔记](docs/reverse-engineering.md)）。

## 功能

- 系统深浅色变化时立即切换主题（监听注册表 `AppsUseLightTheme`）。
- 软件设置窗口打开时自动弹出插件设置窗口；软件设置窗口关闭时自动隐藏（可手动关闭，也可用 `OpenWithSettings` 关掉自动弹出）。应用设置窗口本身不会暂停自动切换。
- 首次安装后第一次启动自动弹一次设置窗口（之后仅在主题丢失或打开软件设置时弹出）。
- 窗口标题「Auto Theme 插件设置」，定位在软件设置窗口附近，支持高 DPI，顶部有用途说明、版本匹配结果与提示文案；窗口自身跟随 Windows 深浅色换肤（深色标题栏、深色背景与控件）。
- 插件设置窗口：
  - 显示支持版本 / 当前版本，右下角显示插件自身版本（版本号由源码 `PLUGIN_VERSION` 单一配置源决定）；
  - Light / Dark 下拉框从 `theme\*.json` 动态读取，含 `Default` 项；
  - **主题预览**：窗口打开时暂停自动切换，点下拉右侧「预览主题」即可临时套用该主题，按钮变「取消预览」；两个按钮互斥，取消或关闭窗口后恢复按系统模式自动切换；
  - 可启用 / 禁用自动切换并保存，保存确认后窗口自动关闭；
  - 窗口置顶跟随软件 `cfg.json` 的 `IsTopMost`；下拉展开期间暂停周期性置顶，避免遮挡列表；
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
│   ├── install.md
│   └── release.md           GitHub 发版流程（版本、tag、zip、旧 release 处理）
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

## 发版

版本号只在 `src/p_AutoTheme.cpp` 的 `PLUGIN_VERSION` 一处配置；发新版需要同步文档、重新编译 DLL、打
`mayenano-autotheme-vX.Y.Z` 与 `mayenano-autotheme-latest` 两个 tag、上传 zip，并把旧 release 正文改成过期提示
（GitHub 不支持旧 tag 链接 HTTP 跳转，旧 release 不能删）。

完整命令与检查清单见 [docs/release.md](docs/release.md)。

## 兼容性

已内置以下版本的主题函数 RVA（无需分析）：

| 版本 | RVA |
|---|---|
| 7.0.1.261107 | `0x1819D0` |
| 6.2.0.260501 | `0x17D970` |
| 6.1.0.260422 | `0x17D920` |
| 6.0.0.260417 | `0x17B150` |

未知版本会尝试自动定位主题函数；失败则静默降级并在设置窗口提示。详见 [docs/install.md](docs/install.md) 与 [docs/reverse-engineering.md](docs/reverse-engineering.md)。
