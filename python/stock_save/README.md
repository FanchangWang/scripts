# 股票分钟线数据保存项目

## 项目简介

本项目用于从雪球API获取股票分钟线数据，保存到本地JSON文件，并通过WxPusher发送通知。同时提供一个简单的Web界面查看历史数据。

## 功能说明

- 从雪球API获取股票分钟线数据
- 保存数据到本地JSON文件
- 通过WxPusher发送操作通知
- 提供Web界面查看历史数据
- 支持Docker容器化部署

## 环境要求

- Python 3.12+
- uv（Python包管理器）
- Docker（可选，用于容器化部署）

## 安装与配置

### 1. 克隆项目

```bash
git clone <repository-url>
cd stock_save
```

### 2. 安装依赖

使用uv安装项目依赖：

```bash
python -m uv sync
```

### 3. 配置环境变量

复制 `.env.example` 文件为 `.env` 并填写相应配置：

```bash
cp .env.example .env
```

编辑 `.env` 文件，填写以下配置：

- `STOCK_SYMBOL`：股票代码（默认为SH603533）
- `XUEQIU_COOKIE`：雪球API所需的Cookie（包含xq_a_token和u字段）
- `WXPUSHER_APP_TOKEN`：WxPusher应用令牌
- `WXPUSHER_UIDS`：接收通知的用户UID

## 运行方式

### 方式1：直接运行Python脚本

```bash
# 运行股票分钟线数据保存脚本
python -m uv run stock_save_minute.py

# 运行Web服务器（查看历史数据）
python -m uv run server.py
```

### 方式2：使用Docker运行

```bash
# 启动服务
./start.sh

# 查看服务状态
docker compose ps

# 查看服务日志
docker compose logs -f
```

## 配置说明

### 环境变量

| 环境变量 | 说明 | 默认值 |
|---------|------|--------|
| STOCK_SYMBOL | 股票代码 | SH603533 |
| XUEQIU_COOKIE | 雪球API所需的Cookie | 无 |
| WXPUSHER_APP_TOKEN | WxPusher应用令牌 | 无 |
| WXPUSHER_UIDS | 接收通知的用户UID | 无 |


### 雪球Cookie获取方法

1. 打开浏览器，访问 [雪球网站](https://xueqiu.com)
2. 登录账号
3. 打开浏览器开发者工具（F12）
4. 复制对应的Cookie值到 `.env` 文件的 `XUEQIU_COOKIE` 字段

### WxPusher配置

1. 访问 [WxPusher官网](https://wxpusher.zjiecode.com)
2. 注册并登录
3. 创建应用，获取AppToken
4. 关注WxPusher公众号，获取用户UID
5. 将AppToken和UID填写到 `.env` 文件中对应的字段

## 注意事项

1. 雪球Cookie有效期约为28天，过期后需要重新获取
2. 建议使用Docker部署，便于长期运行和管理
3. 数据默认保存在 `data` 目录，按日期命名为 `YYYY-MM-DD.json`
4. 日志默认保存在 `log` 目录，使用按天滚动的方式
5. Web服务器默认运行在 `http://localhost:8000`

## 故障排查

- **无法获取数据**：检查雪球Cookie是否过期或格式正确
- **通知发送失败**：检查WxPusher配置是否正确
- **Docker运行失败**：检查 `.env` 文件是否正确配置，以及Docker是否正常运行
