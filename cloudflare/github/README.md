# github

GitHub 生态资源的加速代理，对应线上 Worker `github`。已用 `cf` + Vite+ 完全重写为 TypeScript ES Module 并部署上线。

## 线上信息

| 项             | 值                                      |
| -------------- | --------------------------------------- |
| Worker ID      | `cb78109f05f34b128914ee732e720b81`      |
| 自定义域名     | `github.allproxy.dpdns.org`             |
| workers.dev    | `https://github.2651919937.workers.dev` |
| 绑定 / Secrets | 无                                      |

## 目录结构

| 路径                   | 说明                                      |
| ---------------------- | ----------------------------------------- |
| `src/index.ts`         | Worker 主逻辑：路由、代理、深度模式       |
| `src/formats.ts`       | 简写格式解析、GitHub 域名白名单、安全校验 |
| `src/deep.ts`          | 文本 / JSON 中的 GitHub 绝对链接改写      |
| `src/page.ts`          | 根路径链接生成器页面（普通 / 深度两区）   |
| `test/index.test.ts`   | Worker 运行时单测（15 项）                |
| `cloudflare.config.ts` | Worker 名、域名、可观测性                 |

## 行为

- **仅代理 GitHub 生态域名**，其他域名一律 403：`github.com`、`*.github.com`、`raw.githubusercontent.com`、`raw.github.com`、`gist.github.com`、`gist.githubusercontent.com`、`objects.githubusercontent.com`、`codeload.github.com`、`github.githubassets.com`、`opengraph.githubassets.com`、`avatars.githubusercontent.com`、`desktop.githubusercontent.com`、`gh.io`、`github.io`、`*.github.io`。
- 支持完整 URL、去协议 `/github.com/...`，以及 `owner/repo`、Raw、Blob、Release、Archive、API、Gist、Git clone 等简写（见根目录页面生成链接）。
- **普通模式原样转发**：不修正 MIME、不把 `.html` 强制成网页；只删 CSP / `Set-Cookie`，补 `nosniff` 与 `Referrer-Policy`。
- 深度模式 `/deep/`：改写文本 / JSON 中的 GitHub 绝对链接（含 API 响应里的 `browser_download_url`），仅限文本类型、上限 2 MB；跳过时返回 `X-Deep-Convert: skipped`。
- 安全：仅 http(s)、禁 userinfo、禁非 80/443 端口、禁私网 / 环回地址；POST 仅允许 `github.com`。
- GitHub GET 响应按状态码边缘缓存 5 分钟，错误不缓存。
- `?q=<url>` 兼容旧格式，301 跳转到对应代理路径。

## 与 ghview 的分工

- `ghview`：Raw / Pages 只读查看，强制 HTML MIME、修 CSS/JS MIME、提供链接生成器。
- `github`：GitHub 生态代理 + 深度转换，原样转发 Content-Type。两者都配 `*.github.io`，处理方式不同。

## 开发与验证

```bash
npm run dev    # cf dev
npm test       # 15 项 Worker 运行时单测
npm run check  # 格式 / lint / 类型检查
npm run build  # cf build
```

`compatibilityDate` 为 `2026-11-23`（当前 workerd 支持的最新日期），工具链升级后再前移。

## 部署

已通过 `cf deploy` 部署到 `https://github.2651919937.workers.dev` 与 `github.allproxy.dpdns.org`，当前 Version ID `6c936bb9-45ea-480f-a4f8-774a1ba1f887`。

线上验证：根路径 200；Raw 简写 200；非 GitHub 域名返回 403；深度模式下 `install.ps1` 中 14 处 GitHub 链接已改写到代理域名。
