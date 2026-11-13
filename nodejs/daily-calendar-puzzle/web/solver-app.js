// 求解视图：选日期 → 调用 core.solve 出方案，可翻解、统计全量总数
"use strict";
const {
  ROWS, COLS, LABELS, BANNED, PIECES, SIZES, COLORS, PIECE_NAMES,
  key, buildAllowed, normalize,
} = DCPCore;

const boardEl = document.getElementById("board");
const dateEl = document.getElementById("date");
const statEl = document.getElementById("stat");
const countEl = document.getElementById("count");
const prevBtn = document.getElementById("prev");
const nextBtn = document.getElementById("next");
const solveAllBtn = document.getElementById("solveAll");
const trayEl = document.getElementById("tray");

let solutions = [];
let idx = 0;
let total = null;
let mode = "first";      // first | all
let allDone = false;
let currentTargets = [];
let currentDate = null;

// 托盘缩略图：与棋盘上各拼板朝向一致，4×4 固定网格居中
function renderTray(sol) {
  trayEl.innerHTML = "";
  const byId = new Map((sol || []).map(p => [p.id, p]));
  PIECES.forEach(p => {
    const piece = byId.get(p.id);
    const oc = piece
      ? normalize(piece.cells.map(([r, c]) => [c, r]))
      : p.cells.map(q => [...q]);
    const xs = oc.map(q => q[0]), ys = oc.map(q => q[1]);
    const minX = Math.min(...xs), maxX = Math.max(...xs);
    const minY = Math.min(...ys), maxY = Math.max(...ys);
    const w = maxX - minX + 1, h = maxY - minY + 1;
    const G = 4;
    const ox = Math.floor((G - w) / 2), oy = Math.floor((G - h) / 2);
    const filled = new Set(oc.map(([qx, qy]) => key(qx, qy)));

    const card = document.createElement("div");
    card.className = "piece";
    const mini = document.createElement("div");
    mini.className = "mini";
    mini.style.gridTemplateColumns = `repeat(${G},14px)`;
    mini.style.gridTemplateRows = `repeat(${G},14px)`;
    for (let y = 0; y < G; y++) for (let x = 0; x < G; x++) {
      const m = document.createElement("div");
      m.className = "m";
      m.style.background = filled.has(key(x - ox, y - oy)) ? COLORS[p.id - 1] : "transparent";
      mini.appendChild(m);
    }
    const meta = document.createElement("div");
    meta.className = "meta";
    meta.textContent = `${p.id} 号 · ${p.size} 格`;
    card.appendChild(mini);
    card.appendChild(meta);
    trayEl.appendChild(card);
  });
}

function renderEmpty() {
  boardEl.innerHTML = "";
  const tset = new Set(currentTargets.map(p => key(p[0], p[1])));
  for (let r = 0; r < ROWS; r++) for (let c = 0; c < COLS; c++) {
    const div = document.createElement("div");
    const k = key(r, c);
    if (BANNED.has(k)) {
      div.className = "cell banned";
      div.textContent = LABELS[r][c] || "";
    } else if (tset.has(k)) {
      div.className = "cell target";
      div.textContent = LABELS[r][c] || "";
    } else {
      div.className = "cell empty";
    }
    boardEl.appendChild(div);
  }
}

function render() {
  renderEmpty();
  if (!solutions.length) { renderTray(null); return; }
  const sol = solutions[idx];
  const RAD = 8;
  const grid = [...boardEl.children];
  const idAt = (r, c) => {
    if (r < 0 || r >= ROWS || c < 0 || c >= COLS) return -1;
    return grid[r * COLS + c].dataset.pid ? Number(grid[r * COLS + c].dataset.pid) : -1;
  };

  for (const piece of sol) {
    const color = COLORS[piece.id - 1];
    for (const [r, c] of piece.cells) {
      const div = grid[r * COLS + c];
      div.className = "cell";
      div.style.background = color;
      div.dataset.pid = piece.id;
    }
  }
  for (const piece of sol) {
    for (const [r, c] of piece.cells) {
      const div = grid[r * COLS + c];
      const id = piece.id;
      const up = idAt(r - 1, c), dn = idAt(r + 1, c), lf = idAt(r, c - 1), rt = idAt(r, c + 1);
      const sUp = up === id, sDn = dn === id, sLf = lf === id, sRt = rt === id;
      div.style.borderTopLeftRadius = (!sUp && !sLf) ? RAD + "px" : "0";
      div.style.borderTopRightRadius = (!sUp && !sRt) ? RAD + "px" : "0";
      div.style.borderBottomLeftRadius = (!sDn && !sLf) ? RAD + "px" : "0";
      div.style.borderBottomRightRadius = (!sDn && !sRt) ? RAD + "px" : "0";
      div.style.borderTop = sUp ? "1px dashed rgba(0,0,0,.15)" : "1px solid var(--card)";
      div.style.borderLeft = sLf ? "1px dashed rgba(0,0,0,.15)" : "1px solid var(--card)";
    }
  }
  // 编号标注：每块左上角格放标签
  for (const piece of sol) {
    const [r, c] = piece.cells[0];
    const div = grid[r * COLS + c];
    div.innerHTML = `<div class="plabel"><div class="pid">${piece.id} 号</div><div class="psz">共 ${SIZES[piece.id - 1]} 格</div></div>`;
  }
  renderTray(sol);
  updateStat();
}

function updateStat() {
  if (mode === "all" && allDone) {
    statEl.innerHTML = `共 <b>${total}</b> 种铺法`;
    countEl.textContent = `第 ${idx + 1} / ${total} 种`;
    solveAllBtn.disabled = true;
    solveAllBtn.textContent = "已求出全部";
  } else if (mode === "all") {
    statEl.innerHTML = `枚举中，已找到 <b>${solutions.length}</b> 种`;
    countEl.textContent = solutions.length ? `第 ${idx + 1} / ${solutions.length}+ 种` : "";
    solveAllBtn.disabled = true;
    solveAllBtn.textContent = "求解中…";
  } else {
    statEl.innerHTML = `已找到 <b>${solutions.length}</b> 种铺法`;
    countEl.textContent = solutions.length ? `第 ${idx + 1} 种` : "";
    solveAllBtn.disabled = false;
    solveAllBtn.textContent = "求解全部解法";
  }
  prevBtn.disabled = solutions.length <= 1;
  nextBtn.disabled = solutions.length <= 1;
}

let worker = null;

function buildWorker() {
  const inline = document.getElementById("worker-src");
  if (inline && inline.textContent.trim()) {
    return new Worker(URL.createObjectURL(new Blob([inline.textContent], { type: "text/javascript" })));
  }
  return new Worker("worker.js");
}

function loadDate(y, m, d, limit) {
  if (worker) { worker.terminate(); worker = null; }
  solutions = []; idx = 0; total = null; allDone = false;
  mode = limit === 1 ? "first" : "all";
  currentDate = { y, m, d };
  currentTargets = buildAllowed(y, m, d).targets;
  statEl.textContent = "枚举中…";
  countEl.textContent = "";
  renderEmpty();

  worker = buildWorker();
  worker.onmessage = (e) => {
    if (e.data.type === "sol") {
      solutions.push(e.data.sol);
      if (solutions.length === 1) { idx = 0; render(); }
      else updateStat();
    } else if (e.data.type === "done") {
      total = e.data.total; allDone = true;
      updateStat();
    }
  };
  worker.postMessage({ y, m, d, limit });
}

function applyDateStr(str) {
  const [y, m, d] = str.split("-").map(Number);
  loadDate(y, m, d, 1);
}

dateEl.addEventListener("change", () => { if (dateEl.value) applyDateStr(dateEl.value); });
document.getElementById("today").addEventListener("click", () => {
  const n = new Date();
  const str = `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, "0")}-${String(n.getDate()).padStart(2, "0")}`;
  dateEl.value = str; applyDateStr(str);
});
solveAllBtn.addEventListener("click", () => {
  if (!currentDate) return;
  loadDate(currentDate.y, currentDate.m, currentDate.d, Infinity);
});
prevBtn.addEventListener("click", () => {
  if (!solutions.length) return;
  idx = (idx - 1 + solutions.length) % solutions.length; render();
});
nextBtn.addEventListener("click", () => {
  if (!solutions.length) return;
  idx = (idx + 1) % solutions.length; render();
});

function fitBoard() {
  const wrap = document.querySelector(".wrap");
  const side = document.querySelector(".side");
  const main = document.querySelector(".main");
  const pad = 32;
  const availW = wrap.clientWidth - side.offsetWidth - 18 - pad;
  const availH = main.clientHeight - pad;
  const cell = Math.max(28, Math.floor(Math.min(availW / COLS, availH / ROWS)));
  boardEl.style.width = (cell * COLS) + "px";
  boardEl.style.height = (cell * ROWS) + "px";
  boardEl.style.setProperty("--cell", cell + "px");
}
window.addEventListener("resize", fitBoard);

(function init() {
  const params = new URLSearchParams(location.search);
  let str = params.get("date");
  if (!str) {
    const n = new Date();
    str = `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, "0")}-${String(n.getDate()).padStart(2, "0")}`;
  }
  dateEl.value = str;
  applyDateStr(str);
  fitBoard();
})();
