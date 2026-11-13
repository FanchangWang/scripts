// 回溯求解器 + 解合法性校验（同源核心，CLI 与网页都调用这里）
import { PIECES, BANNED, ROWS, COLS, key } from "./board.js";
import { orientations } from "./geometry.js";
import { targetCells } from "./date.js";

const ORIENTS = PIECES.map(p => orientations(p.cells));

// 计算某天可被拼板覆盖的格子（排除禁放格与当天露出格）
export function buildAllowed(y, m, d) {
  const targets = targetCells(y, m, d);
  const tset = new Set(targets.map(p => key(p[0], p[1])));
  const allowed = [];
  for (let r = 0; r < ROWS; r++) {
    for (let c = 0; c < COLS; c++) {
      const k = key(r, c);
      if (!BANNED.has(k) && !tset.has(k)) allowed.push(k);
    }
  }
  return { targets, tset, allowed, allowedSet: new Set(allowed) };
}

// 共享回溯：在给定初始占用 / 已用块上继续铺满剩余空格，每找到一解回调 onSol
function backtrack(allowed, allowedSet, used, occupied, placement, onSol, limit, state) {
  if (state.count >= limit) return;
  if (occupied.size === allowed.length) {
    state.count++;
    onSol(placement.map(p => ({ id: p.id, cells: p.cells.map(q => [...q]) })));
    return;
  }
  // 最受限空格启发式：选可选落法最少的空格先填
  let bestOpts = null;
  for (const k of allowedSet) {
    if (occupied.has(k)) continue;
    const [r, c] = k.split(",").map(Number);
    const opts = [];
    for (let pi = 0; pi < PIECES.length; pi++) {
      if (used[pi]) continue;
      for (const ori of ORIENTS[pi]) {
        for (const [ax, ay] of ori) {
          const dr = r - ay, dc = c - ax;
          const cells = ori.map(([x, yy]) => [yy + dr, x + dc]);
          if (cells.every(([rr, cc]) => allowedSet.has(key(rr, cc)) && !occupied.has(key(rr, cc)))) {
            opts.push({ pi, cells });
          }
        }
      }
    }
    if (bestOpts === null || opts.length < bestOpts.length) {
      bestOpts = opts;
      if (opts.length === 0) break;
    }
  }
  if (!bestOpts || bestOpts.length === 0) return;
  for (const opt of bestOpts) {
    const { pi, cells } = opt;
    const ck = cells.map(([r, c]) => key(r, c));
    if (ck.some(k => occupied.has(k))) continue;
    used[pi] = true; ck.forEach(k => occupied.add(k));
    placement.push({ id: pi + 1, cells });
    backtrack(allowed, allowedSet, used, occupied, placement, onSol, limit, state);
    placement.pop(); used[pi] = false; ck.forEach(k => occupied.delete(k));
  }
}

/**
 * 惰性枚举某天的全部铺法：每找到一解回调 onSol(sol)，返回解总数。
 * 供 Worker 逐解回传，避免一次性阻塞主线程。
 */
export function enumerate(y, m, d, onSol, limit = Infinity) {
  const { allowed, allowedSet } = buildAllowed(y, m, d);
  const used = new Array(PIECES.length).fill(false);
  const occupied = new Set();
  const placement = [];
  const state = { count: 0 };
  backtrack(allowed, allowedSet, used, occupied, placement, onSol, limit, state);
  return state.count;
}

/**
 * 求解某天的铺法（从空棋盘开始，一次性收集到 limit 个）。
 * @param {number} y 年
 * @param {number} m 月 1-12
 * @param {number} d 日 1-31
 * @param {number} limit 最多返回解数；Infinity 表示全量
 * @returns {{solutions: Array, targets: Array, allowedCount: number}}
 *   每个解: [{id, cells:[[row,col],...]}]
 */
export function solve(y, m, d, limit = 1) {
  const solutions = [];
  const { allowed } = buildAllowed(y, m, d);
  enumerate(y, m, d, sol => solutions.push(sol), limit);
  return { solutions, targets: targetCells(y, m, d), allowedCount: allowed.length };
}

/**
 * 在固定若干已放置拼板的前提下，推算剩余拼板能否铺满。
 * @param {number} y 年
 * @param {number} m 月 1-12
 * @param {number} d 日 1-31
 * @param {Array<{id:number, cells:Array}>} fixed 已放置拼板
 * @param {number} limit 最多返回解数
 * @returns {{solutions: Array, allowedCount: number, error?: string}}
 *   solutions 为完整铺法（含 fixed 块）；无解或固定块非法时为空并附 error
 */
export function solveFixed(y, m, d, fixed, limit = 1) {
  const { allowed, allowedSet } = buildAllowed(y, m, d);
  const used = new Array(PIECES.length).fill(false);
  const occupied = new Set();
  const placement = [];

  for (const f of fixed) {
    const pi = f.id - 1;
    if (pi < 0 || pi >= PIECES.length) return { solutions: [], error: "拼板编号不存在" };
    if (used[pi]) return { solutions: [], error: "重复放置同一块" };
    for (const [r, c] of f.cells) {
      const k = key(r, c);
      if (!allowedSet.has(k)) return { solutions: [], error: "已放块落入禁放/露出格" };
      if (occupied.has(k)) return { solutions: [], error: "已放块相互重叠" };
      occupied.add(k);
    }
    used[pi] = true;
    placement.push({ id: f.id, cells: f.cells.map(q => [...q]) });
  }

  const solutions = [];
  const state = { count: 0 };
  backtrack(allowed, allowedSet, used, occupied, placement, sol => solutions.push(sol), limit, state);
  return { solutions, allowedCount: allowed.length };
}

// 校验一个解：格数正确、不越界/不落禁放或露出格、不重叠、覆盖完整
export function verify(sol, allowedSet) {
  const seen = new Set();
  for (const piece of sol) {
    const meta = PIECES[piece.id - 1];
    if (!meta) return "拼板编号不存在: " + piece.id;
    if (piece.cells.length !== meta.size) return `格数不符: ${piece.id} 号应为 ${meta.size} 格`;
    for (const [r, c] of piece.cells) {
      const k = key(r, c);
      if (!allowedSet.has(k)) return "越界/落入禁放或露出格: " + k;
      if (seen.has(k)) return "重叠: " + k;
      seen.add(k);
    }
  }
  if (seen.size !== allowedSet.size) return `覆盖不全 ${seen.size}/${allowedSet.size}`;
  return "ok";
}

export { ORIENTS };
