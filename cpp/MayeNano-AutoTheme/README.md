# Maye Nano Auto Theme

Maye Nano 快速启动器插件：跟随 Windows 浅色 / 深色模式**自动切换主题**。

关键点：直接调用软件自身的主题应用函数，**不重启应用**，因此不会重新注册热键、不会打断使用。插件 API 没有主题切换接口，这个入口是通过逆向定位的（见 [逆向笔记](docs/reverse-engineering.md)）。

## 目录结构

```
MayeNano-AutoTheme/
├── README.md                  本文件
├── .gitignore
├── src/                       插件源码
│   ├── p_AutoTheme.cpp
│   └── SDK_MN.hpp             Maye Nano 插件 SDK
├── config/
│   └── autotheme.ini          配置模板（Light / Dark / 回退开关）
├── scripts/
│   ├── build.bat              编译脚本（clang + MSVC/SDK）
│   ├── install.ps1            安装脚本（复制到 Maye Nano 并启用插件）
│   └── restart.ps1            运行时辅助脚本（仅回退方案用）
├── docs/
│   ├── reverse-engineering.md 逆向过程与内部函数地址
│   └── install.md             安装、配置、回退说明
├── build/                     编译中间产物（.exp / .lib，git 忽略）
└── dist/
    └── mn.cc.arae.autotheme.dll   ← 编译产物 / 交付物（入库）
```

## 快速开始

```bat
rem 1. 编译（产物在 dist\）
scripts\build.bat

rem 2. 退出 Maye Nano 后安装
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install.ps1
```

安装脚本默认写入 `C:\Users\guyue\AppData\Local\Programs\MayeNano`，可用 `-TargetDir` 指定其它目录。

## 配置要点

```ini
[AutoTheme]
Light=BlueWhite-1.json     ; 留空 = 默认主题
Dark=Black-1.json          ; 留空 = 默认主题
UseInternalCall=1          ; 1=进程内切换（无重启）；0=回退到重启
```

完整配置项见 [docs/install.md](docs/install.md)。

## 兼容性 / 风险

内部函数 RVA 是 `0x1819D0`，仅对应 **Maye Nano 7.0.1.261107**。软件更新后若失效，把 `UseInternalCall=0` 即可退回重启方案。细节见 [docs/reverse-engineering.md](docs/reverse-engineering.md)。
