// 本地静态服务：托管 web/ 下的求解视图与游玩视图
// 运行: node server/server.js  (或 npm start)
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const webDir = path.join(__dirname, "..", "web");
const PORT = Number(process.env.PORT || 5173);

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
};

const ROUTES = {
  "/": "index.html",
  "/index": "index.html",
  "/solve": "index.html",
  "/play": "play.html",
};

const server = http.createServer((req, res) => {
  const url = decodeURIComponent(req.url.split("?")[0]);
  let file = ROUTES[url];
  if (!file) {
    file = path.normalize(url).replace(/^(\.\.[/\\])+/, "");
  }
  const full = path.join(webDir, file);
  if (!full.startsWith(webDir)) {
    res.writeHead(403); res.end("Forbidden"); return;
  }
  fs.readFile(full, (err, data) => {
    if (err) {
      res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("404 Not Found: " + url);
      return;
    }
    const ext = path.extname(full).toLowerCase();
    res.writeHead(200, { "Content-Type": MIME[ext] || "application/octet-stream" });
    res.end(data);
  });
});

server.on("error", (err) => {
  if (err.code === "EADDRINUSE") {
    console.error(`\n端口 ${PORT} 已被占用。可先关闭占用该端口的旧服务，或换一个端口启动：`);
    console.error(`  PowerShell:  $env:PORT=5174; npm start`);
    console.error(`  cmd:         set PORT=5174 && npm start\n`);
    process.exit(1);
  }
  throw err;
});

server.listen(PORT, () => {
  console.log(`日历拼版已启动:`);
  console.log(`  求解视图: http://localhost:${PORT}/`);
  console.log(`  游玩视图: http://localhost:${PORT}/play`);
});
