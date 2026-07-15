# ningway_download 项目指南

## 概述

ningway.com 音视频资源下载工具，Python 3.12 + uv 包管理。

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
```

## 开发命令

```bash
uv sync                                    # 安装依赖
uv run update-data                         # 更新 data.json
uv run download                            # 下载视频
uv run python check.py                     # 代码检查与修复
uv run ruff check src/                     # lint 检查
uv run ruff format src/                    # 格式化
uv run ty check src/                       # 类型检查
```

## 关键逻辑

### 域名路由

视频编号前缀决定下载域名（`video.py:get_domain_for_video()`）：

| 编号模式 | 域名 |
|----------|------|
| `Mxxxx`（音频） | `r2.196212.xyz` |
| `Sxxxx`（系列） | `list.ningway.com` |
| `A/B/C/E/F/G/Wxxxx`、`KCxxx`、`4066x` | `r2.ningway.com` |
| 22000–49999 | `sa.ningway.com` |
| 其他 | `b2.ningway.com` |

### 下载输出目录

```
out/
├── 日期/{date}/      — 有 date 字段的视频
├── 列表/{分类}/      — videos 按编号前缀分类
└── 分类/{系列名}/    — series 按系列嵌套目录
```

## 注意事项

- `data.json` 由 `update_data` 命令更新，不要手动编辑
- 下载前会清理 `.tmp` 文件，下载中使用 `.tmp` 后缀原子替换
- 默认最大并发数为 `min(域名数, 8)`
