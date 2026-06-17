# tinypng

批量压缩 PNG 图片，基于 TinyPNG API，递归处理子目录并保持目录结构。

## 功能

- 递归扫描源目录下所有 `.png` 文件
- 调用 TinyPNG API 压缩，输出到指定目录
- 输出目录自动保持与源目录一致的结构
- 压缩前后的目录结构、文件名完全对应

## 前置要求

- Python >= 3.12
- [uv](https://docs.astral.sh/uv/) 包管理器
- [TinyPNG API Key](https://tinypng.com/developers)

## 安装

```bash
# 克隆项目后进入目录，同步依赖
uv sync
```

## 配置

在项目根目录创建 `.env` 文件（已提供模板），填入你的 API Key：

```env
TINIFY_API_KEY=你的API_KEY
```

## 使用

两种运行方式等价：

```bash
# 方式一：console script
uv run tinypng

# 方式二：模块运行
uv run python -m tinypng
```

按提示依次输入源 PNG 目录和输出目录即可。

### 示例

```
Enter source PNG directory: D:\photos\raw
Enter output directory:    D:\photos\compressed
Found 12 PNG file(s). Starting compression...
  Compressed: vacation/sunset.png
  Compressed: vacation/beach.png
  Compressed: family/portrait.png
Done.
```

输出后 `D:\photos\compressed` 的目录结构与 `D:\photos\raw` 完全一致。

## 项目结构

```
tinypng/
├── .env                  # API Key（已 gitignore）
├── .gitignore
├── .python-version       # Python 版本锁定
├── pyproject.toml        # 项目配置与依赖
├── uv.lock               # 依赖锁文件
└── src/
    └── tinypng/
        ├── __init__.py
        └── __main__.py   # 入口
```

## 开发

```bash
# 同步环境（含 dev 依赖）
uv sync --group dev

# 代码检查
uv run ruff check src/

# 类型检查
uv run ty check src/
```
