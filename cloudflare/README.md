# cloudflare

Cloudflare Workers / Pages 的 JS/TS 项目集合（按平台聚合）。

## 项目索引

| 目录 | 技术栈 | 一句话用途 | 文档 |
|---|---|---|---|
| [docker](docker/) | Worker / ES Module | Docker Hub 等多注册表镜像代理（PAT + 原样转发） | [README](docker/README.md) |
| [ghview](ghview/) | Worker / ES Module | GitHub Raw / Pages 查看代理 + 链接生成器 | [README](ghview/README.md) |
| [github](github/) | Worker / ES Module | GitHub 生态代理 + `/deep/` 深度转换 | [README](github/README.md) |
| [proxy-ex](proxy-ex/) | Worker / ES Module | 通用 URL 代理（原样转发） | [README](proxy-ex/README.md) |

## 共性说明

- 4 个子项目对应线上账号中现有的 4 个 Worker，通过 `cf workers scripts search` / `cf workers versions get --include modules` 拉取。
- 4 个 Worker 均已重写并部署；其中 `docker` 绑定了 Secrets（`DOCKERHUB_USERNAME`、`DOCKERHUB_PAT`），其余无绑定；域名均托管在 Cloudflare。
- 统一管理使用 `cf` CLI（本机 `cf` 1.0.0-rc.6）：`cf auth whoami`、`cf workers scripts search`、`cf deploy`。
- 新代码统一使用 `cloudflare.config.ts` + `cf deploy`，不再新增 Wrangler 配置文件。
- 域名 / Token / 密码等敏感信息放 `.env` 或 Worker Secrets，按根 `README.md` 约定不入库。
