// 编译静态单文件 HTML：把 CSS / JS / core 全部内联进 dist/*.html，可 file:// 直接打开
// 运行: node scripts/build-static.js
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, "..");
const webDir = path.join(root, "web");
const distDir = path.join(root, "dist");

// 先确保 bundle 新鲜
execSync("node scripts/build-bundle.js", { cwd: root, stdio: "inherit" });

fs.mkdirSync(distDir, { recursive: true });

const css = fs.readFileSync(path.join(webDir, "style.css"), "utf8");
const workerJs = fs.readFileSync(path.join(webDir, "worker.js"), "utf8");

function inline(html) {
  // 内联 CSS
  html = html.replace(/<link[^>]*href="style\.css"[^>]*>/, () => `<style>\n${css}\n</style>`);
  // 内联 Worker 源码（file:// 下外部 Worker 不可用，需内联成 Blob）
  html = html.replace(/<script[^>]*id="worker-src"[^>]*><\/script>/,
    () => `<script type="text/plain" id="worker-src">\n${workerJs}\n</script>`);
  // 内联 JS（按 src 读取）
  html = html.replace(/<script[^>]*src="([^"]+)"[^>]*><\/script>/g, (m, src) => {
    const code = fs.readFileSync(path.join(webDir, src), "utf8");
    return `<script>\n${code}\n</script>`;
  });
  return html;
}

for (const page of ["index.html", "play.html"]) {
  const src = fs.readFileSync(path.join(webDir, page), "utf8");
  const out = inline(src);
  fs.writeFileSync(path.join(distDir, page), out, "utf8");
  console.log("已生成 dist/" + page);
}
console.log("静态打包完成");
