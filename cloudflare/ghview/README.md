# ghview

GitHub Raw / GitHub Pages 的只读查看代理，对应线上 Worker `ghview`。已按 Cloudflare 官方 `cf` 工作流完全重写为 TypeScript ES Module，并于 2026-11-27 部署上线（最新 Version ID `0ec2f737-ebfd-4e84-b871-86f117dce53b`）。

## 线上信息

| 项             | 值                                      |
| -------------- | --------------------------------------- |
| Worker ID      | `0e60f991dff343b7bbba4f375bc3943f`      |
| 自定义域名     | `ghview.allproxy.dpdns.org`             |
| workers.dev    | `https://ghview.2651919937.workers.dev` |
| 绑定 / Secrets | 无                                      |

## 目录结构

| 路径                   | 说明                                       |
| ---------------------- | ------------------------------------------ |
| `src/index.ts`         | Worker 主逻辑                              |
| `src/page.ts`          | 根路径链接生成器页面（内联 HTML/CSS/JS）   |
| `test/index.test.ts`   | Worker 运行时单测（11 项）                 |
| `cloudflare.config.ts` | `cf` 项目配置（Worker 名、域名、可观测性） |
| `vite.config.ts`       | Cloudflare Vite 插件 + Vite+ 格式/检查配置 |

## 行为

- 仅允许 `GET` / `HEAD`，其他方法返回 405。
- 根路径 `/` 返回**链接生成器页面**（自包含 HTML，无外部资源）：输入 raw / GitHub Pages / `github.com/…/blob/…` / 本站已有代理链接，自动生成对应的简写与完整 URL 两种格式；每条都提供「复制」和「打开」（新标签页），支持回车触发生成。
- 支持三种路径：
  - `/gh/{owner}/{repo}/{branch}/{path}` → `raw.githubusercontent.com`
  - `/ghpages/{owner}/{repo}/{path}` → `{owner}.github.io/{repo}/...`
  - `/https://raw.githubusercontent.com/...` 或 `/https://{owner}.github.io/...`
- 中转页面里的**路径相对资源**（`styles/index.css`、`./assets/app.js`）会随代理前缀一起回源。
- **根相对资源**（`/styles/index.css`）和 JS 运行时拼出的根路径：利用浏览器子资源请求携带的 `Referer` 还原上游根目录，再拼回目标 URL（无状态、不串标签页）。外部绝对 URL 不代理。
- Raw 模式按扩展名修正 MIME（`text/css`、`text/javascript`、`image/*`、字体等），避免 `nosniff` 下浏览器拒绝加载 CSS/JS。
- 安全：仅 HTTPS、禁端口 / userinfo、host 白名单、拒绝路径穿越；`%2e%2e` 由 WHATWG URL 规范化后也不会越界（有单测）。
- 响应：删除 CSP / `Content-Disposition` / `Set-Cookie`，补 `nosniff` 与 `Referrer-Policy`；raw 的 `.html` 强制 `text/html`；错误 `no-store`。
- 成功响应按状态码在边缘缓存 5 分钟（`cf.cacheTtlByStatus`），错误不缓存。
- 流式透传响应体，不做 `response.text()` 缓冲。

## 开发与验证

```bash
npm run dev        # cf dev，本地开发
npm test           # Worker 运行时单测
npm run check      # 格式 / lint / 类型检查
npm run build      # cf build，仅构建
```

`compatibilityDate` 固定为 `2026-11-23`（当前已安装 workerd 支持的最新日期）；工具链升级后再前移。

## 部署

已通过 `cf deploy` 部署：同时发布到 `https://ghview.2651919937.workers.dev` 与 `ghview.allproxy.dpdns.org`，当前 Version ID `0ec2f737-ebfd-4e84-b871-86f117dce53b`。

线上验证：

- `/` 返回 200（链接生成器页面）
- `/gh/octocat/Hello-World/master/README` 返回 200
- `/gh/twbs/bootstrap/main/dist/css/bootstrap.css` 返回 200 且 `Content-Type: text/css; charset=utf-8`
- 带 `Referer: /gh/twbs/bootstrap/main/README.md` 请求 `/dist/css/bootstrap.css` 返回 200（根相对路径还原生效）

`cf deploy` 会按 `cloudflare.config.ts` 上传并应用域名配置；`cf deploy --dry-run` 可先预览构建产物而不发请求。注意：因启用了 `workersDev` 且未显式设置 `previewUrls`，Preview URLs 默认开启，如需关闭可在配置中加 `previewUrls: false`。
