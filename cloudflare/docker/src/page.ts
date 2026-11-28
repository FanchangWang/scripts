export const PAGE_HTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Docker 镜像加速代理</title>
<style>
:root{color-scheme:light dark;--bg:#f6f8fa;--panel:#fff;--text:#1f2328;--muted:#59636e;--line:#d1d9e0;--accent:#0969da}
@media(prefers-color-scheme:dark){:root{--bg:#0d1117;--panel:#161b22;--text:#e6edf3;--muted:#9198a1;--line:#30363d;--accent:#2f81f7}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}
main{max-width:760px;margin:0 auto;padding:44px 20px}
h1{margin:0 0 10px;font-size:26px}
h2{font-size:18px;margin-top:30px}
pre{background:var(--panel);border:1px solid var(--line);border-radius:6px;padding:14px;overflow:auto;font-size:13px}
code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace}
.muted{color:var(--muted)}
</style>
</head>
<body>
<main>
<h1>Docker 镜像加速代理</h1>
<p class="muted">基于 Cloudflare Workers 的纯转发代理。/token 使用你的 Docker Hub PAT 换取账号 token，目的请求计入账号额度而非 CF 共享出口 IP 的匿名配额。</p>

<h2>方式一：daemon.json（拉取命令不用改）</h2>
<pre><code>{
  "registry-mirrors": [
    "https://docker.allproxy.dpdns.org"
  ]
}</code></pre>
<p class="muted">改完重启 Docker，之后照常 <code>docker pull nginx</code>。</p>

<h2>方式二：把域名当 registry（冷启动更稳）</h2>
<pre><code>docker pull docker.allproxy.dpdns.org/library/nginx:latest
docker pull docker.allproxy.dpdns.org/owner/repo:tag</code></pre>

<h2>其他注册表</h2>
<p class="muted">按子域前缀路由：<code>quay.</code>、<code>gcr.</code>、<code>k8s.</code>、<code>ghcr.</code>、<code>nvcr.</code>、<code>cloudsmith.</code>；也可用 <code>?ns=quay.io</code> 覆盖。</p>
</main>
</body>
</html>`;
