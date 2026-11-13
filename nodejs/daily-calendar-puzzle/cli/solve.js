// 命令行求解工具（后台批量计算/校验用；结果查看以网页为准）
// 用法:
//   node cli/solve.js                         # 今天，求第 1 解
//   node cli/solve.js --date 2026-12-31
//   node cli/solve.js --limit 10
//   node cli/solve.js --all                   # 枚举全部，统计总数
//   node cli/solve.js --print                 # 打印首解棋盘
import { solve, verify, buildAllowed, LABELS, BANNED, PIECES, key, ROWS, COLS } from "../core/index.js";

function parseArgs(argv) {
  const opt = { date: null, limit: 1, all: false, print: false };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--date") opt.date = argv[++i];
    else if (a === "--limit") opt.limit = Number(argv[++i]);
    else if (a === "--all") opt.all = true;
    else if (a === "--print") opt.print = true;
  }
  return opt;
}

function todayStr() {
  const n = new Date();
  return `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, "0")}-${String(n.getDate()).padStart(2, "0")}`;
}

function renderBoard(sol, targets) {
  const tset = new Set(targets.map(p => key(p[0], p[1])));
  const cell = Array.from({ length: ROWS }, () => Array(COLS).fill("."));
  for (const p of sol) {
    for (const [r, c] of p.cells) cell[r][c] = String(p.id);
  }
  const lines = [];
  for (let r = 0; r < ROWS; r++) {
    let row = "";
    for (let c = 0; c < COLS; c++) {
      const k = key(r, c);
      if (BANNED.has(k)) row += " # ";
      else if (tset.has(k)) row += `[${(LABELS[r][c] || "?").padEnd(2)}]`;
      else row += " " + cell[r][c] + " ";
    }
    lines.push(row);
  }
  return lines.join("\n");
}

const opt = parseArgs(process.argv);
const str = opt.date || todayStr();
const [y, m, d] = str.split("-").map(Number);
const limit = opt.all ? Infinity : opt.limit;

const t0 = Date.now();
const { solutions, targets, allowedCount } = solve(y, m, d, limit);
const t1 = Date.now();

const { allowedSet } = buildAllowed(y, m, d);
let status = "ok";
let uniq = new Set();
for (const s of solutions) {
  const v = verify(s, allowedSet);
  if (v !== "ok") { status = "FAIL: " + v; break; }
  uniq.add(JSON.stringify(s.map(p => [p.id, ...p.cells.map(q => q.join(","))]).sort()));
}

const dow = ["日", "一", "二", "三", "四", "五", "六"][new Date(y, m - 1, d).getDay()];
console.log(`${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")} 周${dow}`);
console.log(`解数=${solutions.length}  唯一=${uniq.size}  覆盖=${allowedCount} 格  耗时=${t1 - t0}ms  ${status}`);
console.log(`拼板: ${PIECES.map(p => `${p.id}(${p.size})`).join(" ")}`);

if (opt.print && solutions.length > 0) {
  console.log("\n首解布局（数字=拼板编号，[]=露出格，#=禁放格）:");
  console.log(renderBoard(solutions[0], targets));
}
