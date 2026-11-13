// 把 core/ 源码打包成浏览器可用的 web/core.bundle.js（挂全局 DCPCore）
// 同时生成 web/worker.js：core 裸代码 + Worker 枚举壳，供求解视图惰性逐解回传
// 同源：core 改后重新打包即可，CLI 直接 import core，网页加载 bundle
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const coreDir = path.join(__dirname, "..", "core");
const outBundle = path.join(__dirname, "..", "web", "core.bundle.js");
const outWorker = path.join(__dirname, "..", "web", "worker.js");

const files = ["board.js", "geometry.js", "date.js", "solver.js"];

const EXPORTS = [
  // board
  "ROWS", "COLS", "LABELS", "BANNED", "PIECES", "SIZES", "COLORS", "PIECE_NAMES", "key",
  // geometry
  "normalize", "rotate", "rotateCCW", "flip", "flipV", "orientations",
  // date
  "targetCells", "isValidDate",
  // solver
  "buildAllowed", "solve", "solveFixed", "enumerate", "verify", "ORIENTS",
];

let body = "";
for (const f of files) {
  let src = fs.readFileSync(path.join(coreDir, f), "utf8");
  src = src.replace(/^\s*import .*$/gm, "");        // 去掉 import 行
  src = src.replace(/^\s*export\s*\{[^}]*\}\s*;?/gm, ""); // 去掉 export {...}
  src = src.replace(/^export\s+/gm, "");            // 去掉 export 前缀
  body += "\n// ===== " + f + " =====\n" + src;
}

// 主线程 bundle：IIFE 挂全局，不污染全局词法环境
const bundle = `/* 自动生成，请勿手改；源码在 core/，由 scripts/build-bundle.js 生成 */
(function (global) {
${body}

global.DCPCore = { ${EXPORTS.join(", ")} };
})(globalThis);
`;
fs.writeFileSync(outBundle, bundle, "utf8");

// Worker 脚本：裸 core 代码 + 枚举壳，逐解 postMessage
const workerShell = `
// ===== Worker 枚举壳 =====
self.onmessage = function (e) {
  const { y, m, d, limit } = e.data;
  const L = (limit === undefined || limit === null) ? Infinity : limit;
  const count = enumerate(y, m, d, function (sol) {
    self.postMessage({ type: "sol", sol: sol });
  }, L);
  self.postMessage({ type: "done", total: count });
};
`;
fs.writeFileSync(outWorker, body + workerShell, "utf8");

console.log("已生成 web/core.bundle.js 与 web/worker.js");
