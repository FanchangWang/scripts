# ningway

ningway.com 音视频资源下载工具。

## 安装

```bash
uv sync
```

## 使用

```bash
# 更新视频目录（从 ningway.com API 拉取）
uv run update-data

# 下载视频
uv run download
```

## 项目结构

```
src/ningway/
├── config.py       # 路径、常量、映射表
├── encode.py       # nn_encode 编码逻辑
├── video.py        # 视频分类、域名路由
├── url.py          # URL 生成与编码
├── logger.py       # 控制台日志 + 失败日志
├── display.py      # 终端 UI 进度显示
├── downloader.py   # 下载逻辑、重试、状态管理
├── processor.py    # data.json 解析、任务生成
├── updater.py      # API 拉取、数据对比
└── cli.py          # 入口点

data/
└── data.json       # 视频/系列目录（由 update-data 更新）

out/
├── 日期/{date}/    # 按日期分类的视频
├── 列表/{分类}/    # 按编号前缀分类的视频
└── 分类/{系列名}/  # 系列视频
```

## 开发

```bash
uv run python check.py   # lint + format + type check
```

## 忽略列表

`config.py` 中的 `IGNORE_NOS` 用于跳过已知缺失的视频编号，避免重复下载和生成失败日志。
