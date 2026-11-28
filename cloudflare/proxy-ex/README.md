# proxy-ex

通用 URL 代理，对应线上 Worker `proxy-ex`。已用 `cf` + Vite+ 完全重写为 TypeScript ES Module 并部署上线。

## 线上信息

| 项             | 值                                        |
| -------------- | ----------------------------------------- |
| Worker ID      | `b8be1d9f169148ee90e9dfcecc09f85a`        |
| 自定义域名     | `proxy.allproxy.dpdns.org`                |
| workers.dev    | `https://proxy-ex.2651919937.workers.dev` |
| 绑定 / Secrets | 无                                        |

## 目录结构

| 路径                   | 说明                                  |
| ---------------------- | ------------------------------------- |
| `src/index.ts`         | Worker 主逻辑：路由、代理、响应头处理 |
| `src/formats.ts`       | URL 解析、安全校验、Referer 还原      |
| `src/page.ts`          | 根路径链接生成器                      |
| `test/index.test.ts`   | Worker 运行时单测（9 项）             |
| `cloudflare.config.ts` | Worker 名、域名、可观测性             |

## 行为

- 格式：`/https://<任意 URL>`、`<域名>/<host>/<path>`（去协议），兼容 `?q=`。
- 仅 `GET` / `HEAD`；无 Token、无密码。
- 原样转发：不改正文、不注入脚本、不强制 HTML、不修 MIME，上游 `Content-Type` 原样返回。
- 安全：仅 http(s)、端口 80/443、禁 userinfo、禁私网 / 环回 / 元数据地址；非 GitHub 域名也允许（这是它与 `github` Worker 的区别）。
- 相对资源与 JS 运行时拼出的根相对路径：通过 `Referer` 还原上游根目录，继续走代理。
- 响应头：只删 `Content-Security-Policy`、`Content-Security-Policy-Report-Only`、`Set-Cookie`；不额外加 `nosniff`，不改其他头。
- 不做边缘缓存。

## 与另外两个 Worker 的分工

| Worker     | 用途                                                                    |
| ---------- | ----------------------------------------------------------------------- |
| `ghview`   | GitHub Raw / Pages 只读查看，强制 HTML MIME、修 CSS/JS MIME、链接生成器 |
| `github`   | GitHub 生态代理 + `/deep/` 深度转换，原样 Content-Type，仅 GitHub 域名  |
| `proxy-ex` | 通用 URL 代理，任意域名、原样转发、无正文改写                           |

## 开发与验证

```bash
npm run dev    # cf dev
npm test       # 9 项 Worker 运行时单测
npm run check  # 格式 / lint / 类型检查
npm run build  # cf build
```

`compatibilityDate` 为 `2026-11-23`（当前 workerd 支持的最新日期）。

## 部署

已通过 `cf deploy` 部署到 `https://proxy-ex.2651919937.workers.dev` 与 `proxy.allproxy.dpdns.org`，当前 Version ID `eb0e4c41-6470-4426-8019-ad03c14a6b3e`。配置只保留 `proxy.allproxy.dpdns.org`。

线上验证：根路径 200；任意域名 `https://example.com/` 代理 200；私网地址 `https://127.0.0.1/a` 返回 403。
