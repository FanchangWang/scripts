# docker

Docker Hub 镜像加速代理，对应线上 Worker `docker`。已用 `cf` + Vite+ 完全重写为 TypeScript ES Module 并部署上线。

## 线上信息

| 项          | 值                                      |
| ----------- | --------------------------------------- |
| Worker ID   | `965ff88f4f614585bbda25959368a22b`      |
| 自定义域名  | `docker.allproxy.dpdns.org`             |
| workers.dev | `https://docker.2651919937.workers.dev` |
| Secrets     | `DOCKERHUB_USERNAME`、`DOCKERHUB_PAT`   |

## 目录结构

| 路径                   | 说明                                      |
| ---------------------- | ----------------------------------------- |
| `src/index.ts`         | 主逻辑：`/token` 注入 PAT、请求纯转发     |
| `src/registry.ts`      | 多注册表路由、`library/` 补全、realm 改写 |
| `src/page.ts`          | 根路径用法说明页                          |
| `test/index.test.ts`   | Worker 运行时单测（7 项）                 |
| `cloudflare.config.ts` | Worker 名、域名、Secrets、可观测性        |

## 行为

- 兼容 `daemon.json` 的 `registry-mirrors`：照常 `docker pull nginx`，命令不用改。
- 也可把我们的域名当 registry 用：`docker pull docker.allproxy.dpdns.org/library/nginx:latest`。
- `/token` 用 Worker Secret 里的 Docker Hub 用户名 + PAT 去 `auth.docker.io` 换账号 token；`WWW-Authenticate` 的 realm 改写到本站。这样目的请求计入**账号额度**（200 次/6 小时），不再撞 CF 共享出口 IP 的匿名配额。
- **纯转发，不做缓存**（账户未开通 R2）：每次 manifest / blob 都回源 Docker Hub；代价是每次都消耗账号额度，自用通常够。
- 多注册表：按子域前缀 `quay` / `gcr` / `k8s-gcr` / `k8s` / `ghcr` / `cloudsmith` / `nvcr` 路由，或 `?ns=` 覆盖。

## 开发与验证

```bash
npm run dev    # cf dev（读取 .dev.vars 占位值）
npm test       # 7 项单测
npm run check  # 格式 / lint / 类型检查
npm run build  # cf build
```

真实凭据走 Worker Secrets，`.dev.vars` 只是本地占位（已 git 忽略）。

## 部署

已通过 `cf deploy` 部署到 `https://docker.2651919937.workers.dev` 与 `docker.allproxy.dpdns.org`，当前 Version ID `acc5542e-e967-48a5-92cb-46194735e105`。临时 secrets 文件已删除。

线上验证：从本站 `/token` 拿账号 token 后请求 `library/alpine` manifest 返回 **200**，响应头 `x-ratelimit-limit: 200;w=3600`、`x-ratelimit-remaining: 200`，`docker-ratelimit-source` 为你的 Docker Hub 账号——确认走的是账号额度，不再是 CF 共享 IP 的匿名配额。

由于没有 R2，所有 manifest / blob 每次都回源；账号额度为 200 次/6 小时，自用足够，但短时间内大量拉取仍可能耗尽。
