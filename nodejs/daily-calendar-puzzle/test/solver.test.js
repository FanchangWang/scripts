// 求解器基线测试：合法性、覆盖格数、解数
// 快速: node test/solver.test.js
// 全量基线(含 2026-11-13 = 2543 解，约 30s): FULL=1 node test/solver.test.js
import { solve, verify, buildAllowed } from "../core/index.js";

const cases = [
  [2026, 11, 13],
  [2026, 1, 1],
  [2026, 12, 31],
  [2026, 2, 28],
  [2026, 7, 17],
  [2026, 6, 30],
];

let failures = 0;

for (const [y, m, d] of cases) {
  const { solutions, allowedCount } = solve(y, m, d, 20);
  const { allowedSet } = buildAllowed(y, m, d);
  if (allowedCount !== 47) {
    console.error(`✗ ${y}-${m}-${d} 覆盖格数应为 47，实际 ${allowedCount}`);
    failures++;
  }
  solutions.forEach((sol, i) => {
    const v = verify(sol, allowedSet);
    if (v !== "ok") {
      console.error(`✗ ${y}-${m}-${d} 解#${i + 1}: ${v}`);
      failures++;
    }
    if (sol.length !== 10) {
      console.error(`✗ ${y}-${m}-${d} 解#${i + 1}: 拼板数应为 10，实际 ${sol.length}`);
      failures++;
    }
  });
  console.log(`✓ ${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")} 抽样 ${solutions.length} 解，覆盖 ${allowedCount} 格，校验通过`);
}

// 全量基线：2026-11-13 周五 = 2543 解
if (process.env.FULL === "1") {
  const t0 = Date.now();
  const { solutions } = solve(2026, 11, 13, Infinity);
  const t1 = Date.now();
  if (solutions.length !== 2543) {
    console.error(`✗ 基线解数应为 2543，实际 ${solutions.length}（${t1 - t0}ms）`);
    failures++;
  } else {
    console.log(`✓ 全量基线 2026-11-13 = ${solutions.length} 解（${t1 - t0}ms）`);
  }
}

if (failures > 0) {
  console.error(`\n${failures} 项失败`);
  process.exit(1);
}
console.log("\n全部测试通过");
