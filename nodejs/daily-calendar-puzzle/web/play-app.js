// 游玩视图：拖拽拼板上手拼，调用 core 做规则校验与答案对照
"use strict";
const {
  ROWS, COLS, LABELS, BANNED, PIECES, SIZES, COLORS, PIECE_NAMES,
  key, buildAllowed, solveFixed, normalize, rotate, rotateCCW, flip, flipV,
} = DCPCore;

const boardEl = document.getElementById("board");
const trayEl = document.getElementById("tray");
const dateEl = document.getElementById("date");
const msgEl = document.getElementById("msg");

let allowedSet = new Set();
let allowedCount = 0;
let currentTargets = [];
let currentDate = null;
let placed = new Map();        // id -> [[r,c],...]
let orient = new Map();        // id -> 归一化朝向 [[x,y],...]
let selectedId = null;
let dragState = null;       // 当前拖拽中的拼板 {from, id}
let hintCells = null;       // 提示推荐区域 [[r,c],...]
let answerSnapshot = null;   // 铺完整答案前的用户形态快照
let showingAnswer = false;   // 当前是否处于“完整答案”展示态

function resetOrient() {
  orient.clear();
  PIECES.forEach(p => orient.set(p.id, p.cells.map(q => [...q])));
}

function occupiedSet(exceptId) {
  const s = new Set();
  for (const [id, cells] of placed) {
    if (id === exceptId) continue;
    cells.forEach(([r, c]) => s.add(key(r, c)));
  }
  return s;
}

function cellsForAnchor(id, ar, ac) {
  const oc = orient.get(id);
  const minX = Math.min(...oc.map(p => p[0]));
  const minY = Math.min(...oc.map(p => p[1]));
  return oc.map(([x, y]) => [ar + (y - minY), ac + (x - minX)]);
}

function canPlace(id, cells, exceptId) {
  const occ = occupiedSet(exceptId);
  return cells.every(([r, c]) => allowedSet.has(key(r, c)) && !occ.has(key(r, c)));
}

function clearPreview() {
  boardEl.querySelectorAll(".preview-ok,.preview-bad")
    .forEach(el => el.classList.remove("preview-ok", "preview-bad"));
}

// 拖拽过程中预览整块目标占位：合法蓝、非法红
function renderPreview(cells, valid) {
  clearPreview();
  for (const [r, c] of cells) {
    if (r < 0 || r >= ROWS || c < 0 || c >= COLS) continue;
    boardEl.children[r * COLS + c].classList.add(valid ? "preview-ok" : "preview-bad");
  }
}

function setMsg(text, win) {
  msgEl.textContent = text;
  msgEl.className = "msg" + (win ? " win" : "");
}

// ---------- 棋盘渲染 ----------
function renderBoard() {
  boardEl.innerHTML = "";
  const tset = new Set(currentTargets.map(p => key(p[0], p[1])));
  const grid = [];
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
    div.dataset.r = r; div.dataset.c = c;
    boardEl.appendChild(div);
    grid.push(div);
  }
  // 铺已放置块
  const RAD = 8;
  for (const [id, cells] of placed) {
    const color = COLORS[id - 1];
    for (const [r, c] of cells) {
      const div = grid[r * COLS + c];
      div.className = "cell" + (selectedId === id ? " selected-cell" : "");
      div.style.background = color;
      div.dataset.pid = id;
      div.draggable = true;
    }
  }
  const idAt = (r, c) => {
    if (r < 0 || r >= ROWS || c < 0 || c >= COLS) return -1;
    return grid[r * COLS + c].dataset.pid ? Number(grid[r * COLS + c].dataset.pid) : -1;
  };
  for (const [id, cells] of placed) {
    for (const [r, c] of cells) {
      const div = grid[r * COLS + c];
      const up = idAt(r - 1, c), dn = idAt(r + 1, c), lf = idAt(r, c - 1), rt = idAt(r, c + 1);
      const sUp = up === id, sDn = dn === id, sLf = lf === id, sRt = rt === id;
      div.style.borderTopLeftRadius = (!sUp && !sLf) ? RAD + "px" : "0";
      div.style.borderTopRightRadius = (!sUp && !sRt) ? RAD + "px" : "0";
      div.style.borderBottomLeftRadius = (!sDn && !sLf) ? RAD + "px" : "0";
      div.style.borderBottomRightRadius = (!sDn && !sRt) ? RAD + "px" : "0";
      div.style.borderTop = sUp ? "1px dashed rgba(0,0,0,.15)" : "1px solid var(--card)";
      div.style.borderLeft = sLf ? "1px dashed rgba(0,0,0,.15)" : "1px solid var(--card)";
    }
    const [r, c] = cells[0];
    grid[r * COLS + c].innerHTML = `<div class="plabel"><div class="pid">${id} 号</div><div class="psz">共 ${SIZES[id - 1]} 格</div></div>`;
  }
  // 提示推荐区域高亮
  if (hintCells) {
    const hset = new Set(hintCells.map(([r, c]) => key(r, c)));
    grid.forEach(div => {
      if (hset.has(key(Number(div.dataset.r), Number(div.dataset.c)))) div.classList.add("hint");
    });
  }
  // 点击选中
  grid.forEach(div => {
    div.addEventListener("click", () => {
      const pid = div.dataset.pid ? Number(div.dataset.pid) : null;
      selectedId = pid;
      renderBoard(); renderTray();
    });
  });
  checkWin();
}

// ---------- 托盘渲染 ----------
function renderTray() {
  trayEl.innerHTML = "";
  PIECES.forEach(p => {
    const inPlay = placed.has(p.id);
    const card = document.createElement("div");
    card.className = "piece" + (inPlay ? " used" : "") + (selectedId === p.id ? " selected" : "");
    card.draggable = !inPlay;
    card.dataset.id = p.id;

    const oc = orient.get(p.id);
    const xs = oc.map(q => q[0]), ys = oc.map(q => q[1]);
    const minX = Math.min(...xs), maxX = Math.max(...xs);
    const minY = Math.min(...ys), maxY = Math.max(...ys);
    const w = maxX - minX + 1, h = maxY - minY + 1;
    // 统一 4×4 固定网格，当前朝向居中填入，旋转不改变卡片外框尺寸
    const G = 4;
    const ox = Math.floor((G - w) / 2), oy = Math.floor((G - h) / 2);
    const filled = new Set(oc.map(([qx, qy]) => key(qx, qy)));
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

    card.addEventListener("dragstart", e => {
      // 卡片本身是 draggable 元素，e.target 不一定是内部小格，按鼠标坐标在 4×4 网格里定位被抓格
      let offset = [0, 0];
      const mr = mini.getBoundingClientRect();
      const mx = Math.floor((e.clientX - mr.left) / (mr.width / G));
      const my = Math.floor((e.clientY - mr.top) / (mr.height / G));
      let qx = mx - ox, qy = my - oy;
      if (!filled.has(key(qx, qy))) {
        // 落在透明格时，取离指针最近的填色格
        let best = null, bd = Infinity;
        filled.forEach(k => {
          const [x, y] = k.split(",").map(Number);
          const d = Math.abs(x - qx) + Math.abs(y - qy);
          if (d < bd) { bd = d; best = [x, y]; }
        });
        if (best) { qx = best[0]; qy = best[1]; }
      }
      if (filled.has(key(qx, qy))) offset = [qy - minY, qx - minX];
      dragState = { from: "tray", id: p.id, offset };
      e.dataTransfer.setData("text/plain", JSON.stringify(dragState));
    });
    card.addEventListener("dragend", () => { dragState = null; clearPreview(); });
    card.addEventListener("click", () => { selectedId = p.id; renderBoard(); renderTray(); });
    trayEl.appendChild(card);
  });
}

// ---------- 拖拽放置 ----------
boardEl.addEventListener("dragover", e => {
  const cell = e.target.closest(".cell");
  if (!cell) return;
  e.preventDefault();
  if (!dragState) return;
  const r = Number(cell.dataset.r), c = Number(cell.dataset.c);
  const [or, oc] = dragState.offset || [0, 0];
  const cells = cellsForAnchor(dragState.id, r - or, c - oc);
  const valid = canPlace(dragState.id, cells, dragState.from === "board" ? dragState.id : null);
  renderPreview(cells, valid);
});

boardEl.addEventListener("drop", e => {
  e.preventDefault();
  clearPreview();
  const cell = e.target.closest(".cell");
  if (!cell) { dragState = null; return; }
  let data = dragState;
  try {
    const parsed = JSON.parse(e.dataTransfer.getData("text/plain"));
    if (parsed && parsed.id) data = parsed;
  } catch { /* 兜底用 dragState */ }
  if (!data) { dragState = null; return; }
  const r = Number(cell.dataset.r), c = Number(cell.dataset.c);
  const [or, oc] = data.offset || [0, 0];
  const cells = cellsForAnchor(data.id, r - or, c - oc);
  if (!canPlace(data.id, cells, data.from === "board" ? data.id : null)) {
    setMsg("此处放不下：越界、压到禁放/露出格或与其他拼板重叠");
    dragState = null;
    return;
  }
  if (data.from === "board") placed.delete(data.id);
  placed.set(data.id, cells);
  selectedId = data.id;
  dragState = null;
  exitAnswerMode();
  setMsg("");
  renderBoard(); renderTray();
});

// 棋盘上已放块也可拖拽
boardEl.addEventListener("dragstart", e => {
  const cell = e.target.closest(".cell");
  if (cell && cell.dataset.pid) {
    const id = Number(cell.dataset.pid);
    const cells = placed.get(id);
    const ar = Math.min(...cells.map(q => q[0])), ac = Math.min(...cells.map(q => q[1]));
    const pr = Number(cell.dataset.r), pc = Number(cell.dataset.c);
    dragState = { from: "board", id, offset: [pr - ar, pc - ac] };
    e.dataTransfer.setData("text/plain", JSON.stringify(dragState));
  }
});
boardEl.addEventListener("dragend", () => { dragState = null; clearPreview(); });

// ---------- 工具 ----------
function transformSelected(fn) {
  if (!selectedId) { setMsg("请先选中一块拼板"); return; }
  const oc = orient.get(selectedId);
  const next = normalize(fn(oc));
  orient.set(selectedId, next);
  if (placed.has(selectedId)) {
    // 已放置：保持包围盒左上锚点重排，非法则还原
    const old = placed.get(selectedId);
    const ar = Math.min(...old.map(q => q[0])), ac = Math.min(...old.map(q => q[1]));
    const cells = cellsForAnchor(selectedId, ar, ac);
    if (!canPlace(selectedId, cells, selectedId)) {
      setMsg("当前朝向无法在原位放置");
      orient.set(selectedId, oc);
      return;
    }
    placed.set(selectedId, cells);
  }
  setMsg("");
  renderBoard(); renderTray();
}

// ---------- 答案 / 提示辅助 ----------
function currentFixed() {
  return [...placed.entries()].map(([id, cells]) => ({ id, cells: cells.map(q => [...q]) }));
}
function takeSnapshot() {
  return {
    placed: new Map([...placed].map(([id, cells]) => [id, cells.map(q => [...q])])),
    orient: new Map([...orient].map(([id, cells]) => [id, cells.map(q => [...q])])),
    selectedId,
  };
}
function restoreSnapshot(snap) {
  placed = new Map([...snap.placed].map(([id, cells]) => [id, cells.map(q => [...q])]));
  orient = new Map([...snap.orient].map(([id, cells]) => [id, cells.map(q => [...q])]));
  selectedId = snap.selectedId;
}
function exitAnswerMode() {
  answerSnapshot = null; showingAnswer = false; hintCells = null;
  document.getElementById("answer").hidden = false;
  document.getElementById("undoAnswer").hidden = true;
}
function solveFromCurrent() {
  return solveFixed(currentDate.y, currentDate.m, currentDate.d, currentFixed(), 1);
}

document.getElementById("rotateL").addEventListener("click", () => transformSelected(rotateCCW));
document.getElementById("rotateR").addEventListener("click", () => transformSelected(rotate));
document.getElementById("flip").addEventListener("click", () => transformSelected(flip));
document.getElementById("flipV").addEventListener("click", () => transformSelected(flipV));
document.getElementById("remove").addEventListener("click", () => {
  if (!selectedId || !placed.has(selectedId)) { setMsg("没有可取回的选中拼板"); return; }
  placed.delete(selectedId);
  exitAnswerMode();
  setMsg("");
  renderBoard(); renderTray();
});
document.getElementById("reset").addEventListener("click", () => {
  placed.clear(); selectedId = null; resetOrient();
  exitAnswerMode();
  setMsg("");
  renderBoard(); renderTray();
});

// 提示下一块：基于当前已放块推算，高亮剩余块的一个推荐落点
document.getElementById("hint").addEventListener("click", () => {
  if (showingAnswer) { setMsg("当前为答案展示态，请先撤销答案"); return; }
  const res = solveFromCurrent();
  if (res.error) { setMsg(res.error); return; }
  if (!res.solutions.length) {
    hintCells = null;
    setMsg("当前摆法无解，需要调整已放拼板");
    renderBoard();
    return;
  }
  const sol = res.solutions[0];
  const placedIds = new Set(placed.keys());
  const next = sol.find(p => !placedIds.has(p.id));
  if (!next) { setMsg("已经铺满了"); return; }
  hintCells = next.cells;
  // 托盘对应块同步转到推荐落点的朝向，便于直接拖放
  orient.set(next.id, normalize(next.cells.map(([r, c]) => [c, r])));
  setMsg(`建议把 ${next.id} 号放到绿色高亮区域`);
  renderBoard(); renderTray();
});

// 完整答案：保留用户已放块，铺出剩余块；可撤销恢复
document.getElementById("answer").addEventListener("click", () => {
  if (showingAnswer) return;
  const res = solveFromCurrent();
  if (res.error) { setMsg(res.error); return; }
  if (!res.solutions.length) { setMsg("当前摆法无解，需要调整已放拼板"); return; }
  answerSnapshot = takeSnapshot();
  const sol = res.solutions[0];
  placed.clear();
  for (const piece of sol) placed.set(piece.id, piece.cells.map(q => [...q]));
  showingAnswer = true; hintCells = null;
  document.getElementById("answer").hidden = true;
  document.getElementById("undoAnswer").hidden = false;
  setMsg("已铺出完整参考方案，可点“撤销答案”恢复你的摆法");
  renderBoard(); renderTray();
});

document.getElementById("undoAnswer").addEventListener("click", () => {
  if (answerSnapshot) restoreSnapshot(answerSnapshot);
  exitAnswerMode();
  setMsg("已恢复你的摆法");
  renderBoard(); renderTray();
});

function checkWin() {
  if (placed.size === PIECES.length && occupiedSet(-1).size === allowedCount) {
    setMsg("恭喜，全部铺满，胜利！", true);
  }
}

// ---------- 日期 ----------
function loadDate(y, m, d) {
  currentDate = { y, m, d };
  const { targets, allowedSet: as, allowedCount: ac } = buildAllowed(y, m, d);
  currentTargets = targets; allowedSet = as; allowedCount = ac;
  placed.clear(); selectedId = null; resetOrient();
  exitAnswerMode();
  setMsg("");
  renderBoard(); renderTray();
}

function applyDateStr(str) {
  const [y, m, d] = str.split("-").map(Number);
  loadDate(y, m, d);
}
dateEl.addEventListener("change", () => { if (dateEl.value) applyDateStr(dateEl.value); });
document.getElementById("today").addEventListener("click", () => {
  const n = new Date();
  const str = `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, "0")}-${String(n.getDate()).padStart(2, "0")}`;
  dateEl.value = str; applyDateStr(str);
});

function fitBoard() {
  const main = document.querySelector(".main");
  const pad = 32;
  const availW = main.clientWidth - pad;
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
