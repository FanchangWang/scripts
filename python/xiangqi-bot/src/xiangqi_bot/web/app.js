"use strict";

// ---------- 基础状态 ----------
const state = {
  connected: false,
  board: null,
  mySide: null,
  turn: null,
  phase: null,
  status: "idle",
  gameOver: false,
  highlight: [],
  lastMove: null,
  autoNext: true,
};

const STATUS_CN = {
  idle: "未开始",
  red: "红方走棋",
  black: "黑方走棋",
  over: "绝杀（棋局结束）",
  stopped: "中断",
  auto_next: "自动下一局",
};

const PIECE_CHARS = {
  b_r: "車", b_n: "馬", b_b: "象", b_a: "士", b_k: "將", b_c: "砲", b_p: "卒",
  r_R: "俥", r_N: "傌", r_B: "相", r_A: "仕", r_K: "帥", r_C: "炮", r_P: "兵",
};

const canvas = document.getElementById("board");
const ctx = canvas.getContext("2d");

// 画布尺寸：矫正棋盘 900x1000 + 四周 90px 标注边距（上/下各两行坐标 + 左/右行号）
const CELL = 100;
const MARGIN = 90;
const W = 900 + MARGIN * 2;
const H = 1000 + MARGIN * 2;

// 命令忙状态：null / "sync" / "flow"（命令执行期间禁用对应按钮）
let busy = null;

// ---------- WebSocket ----------
function connectWs() {
  const ws = new WebSocket(`ws://${location.host}/ws`);
  ws.onmessage = (ev) => handleEvent(JSON.parse(ev.data));
  ws.onclose = () => {
    setTimeout(connectWs, 2000);
  };
}

function handleEvent(msg) {
  switch (msg.type) {
    case "log":
      appendLog(msg.kind, msg.msg);
      break;
    case "state": {
      const s = msg.state;
      state.board = s.board;
      state.mySide = s.my_side;
      state.turn = s.turn;
      state.phase = s.phase;
      state.status = s.status;
      state.gameOver = s.game_over;
      state.highlight = s.highlight;
      state.lastMove = s.last_move;
      if (typeof s.auto_next === "boolean") {
        state.autoNext = s.auto_next;
        document.getElementById("toggle-auto-next").checked = state.autoNext;
      }
      busy = null; // 命令执行完毕，按逻辑恢复按钮
      renderStatus();
      drawBoard();
      break;
    }
    case "connected":
      state.connected = true;
      document.getElementById("device-info").textContent = `设备：${msg.serial}`;
      document.getElementById("connect-screen").hidden = true;
      document.getElementById("main-screen").hidden = false;
      break;
    case "disconnected":
      state.connected = false;
      Object.assign(state, {
        board: null,
        mySide: null,
        turn: null,
        phase: null,
        status: "idle",
        gameOver: false,
        highlight: [],
        lastMove: null,
      });
      busy = null;
      document.getElementById("device-info").textContent = "设备：-";
      document.getElementById("connect-screen").hidden = false;
      document.getElementById("main-screen").hidden = true;
      document.getElementById("prompt-mask").hidden = true;
      break;
    case "prompt_turn":
      document.getElementById("prompt-mask").hidden = false;
      break;
  }
}

// ---------- 日志 ----------
function appendLog(kind, msg) {
  const log = document.getElementById("log");
  const div = document.createElement("div");
  div.className = `entry kind-${kind}`;
  div.textContent = msg;
  log.appendChild(div);
  while (log.childElementCount > 500) {
    log.removeChild(log.firstChild);
  }
  log.scrollTop = log.scrollHeight;
}

// ---------- 状态栏与按钮 ----------
function renderStatus() {
  document.getElementById("phase").textContent = state.phase || "-";
  const sideCn =
    state.mySide === "red" ? "红方" : state.mySide === "black" ? "黑方" : "-";
  document.getElementById("side").textContent = sideCn;
  document.getElementById("flow-status").textContent = STATUS_CN[state.status] || state.status;
  applyButtons();
}

function applyButtons() {
  const syncBtn = document.getElementById("btn-sync");
  const flowBtn = document.getElementById("btn-flow");
  let syncDisabled = false;
  let flowDisabled = false;
  let flowText = "开始棋局";
  if (
    state.status === "red" ||
    state.status === "black" ||
    state.status === "auto_next"
  ) {
    // 对弈进行中（含自动下一局中）：仅可中断，按钮状态保持不变
    syncDisabled = true;
    flowText = "中断棋局";
  } else if (state.status === "stopped") {
    // 已同步未开始（如残局加载完成/中断后）：可同步可开始
    flowDisabled = false;
  } else {
    // idle / over：未同步或已结束，仅可同步
    flowDisabled = true;
  }
  if (busy === "sync") syncDisabled = true;
  if (busy === "flow") flowDisabled = true;
  syncBtn.disabled = syncDisabled;
  flowBtn.disabled = flowDisabled;
  flowBtn.textContent = flowText;
}

// ---------- 棋盘绘制 ----------
function drawBoard() {
  ctx.clearRect(0, 0, W, H);
  drawFrame();
  drawGrid();
  drawCoordinates();
  drawHighlight();
  if (state.board) {
    drawPieces();
  }
}

function drawFrame() {
  ctx.fillStyle = "#c8a06b";
  ctx.fillRect(0, 0, W, H);
  ctx.strokeStyle = "#6b4a1f";
  ctx.lineWidth = 3;
  ctx.strokeRect(MARGIN - 6, MARGIN - 6, 900 + 12, 1000 + 12);
}

function drawGrid() {
  ctx.strokeStyle = "#5a3a17";
  ctx.lineWidth = 1.5;

  // 横向线：11 条，无楚河汉界，直接画满
  for (let i = 0; i <= 10; i++) {
    const y = MARGIN + i * CELL;
    ctx.beginPath();
    ctx.moveTo(MARGIN, y);
    ctx.lineTo(MARGIN + 900, y);
    ctx.stroke();
  }

  // 竖向线：9 列全部连续
  for (let j = 0; j <= 8; j++) {
    const x = MARGIN + j * CELL;
    ctx.beginPath();
    ctx.moveTo(x, MARGIN);
    ctx.lineTo(x, MARGIN + 1000);
    ctx.stroke();
  }

  const x0 = MARGIN + 3 * CELL;
  const x1 = MARGIN + 5 * CELL;
  ctx.beginPath();
  ctx.moveTo(x0, MARGIN);
  ctx.lineTo(x1, MARGIN + 2 * CELL);
  ctx.moveTo(x1, MARGIN);
  ctx.lineTo(x0, MARGIN + 2 * CELL);
  ctx.moveTo(x0, MARGIN + 1000);
  ctx.lineTo(x1, MARGIN + 800);
  ctx.moveTo(x1, MARGIN + 1000);
  ctx.lineTo(x0, MARGIN + 800);
  ctx.stroke();
}

function drawCoordinates() {
  const black = state.mySide === "black";
  const cols = [];
  for (let c = 0; c < 9; c++) {
    cols.push(black ? String.fromCharCode(105 - c) : String.fromCharCode(97 + c));
  }
  const topNums = ["1", "2", "3", "4", "5", "6", "7", "8", "9"];
  const bottomNums = ["九", "八", "七", "六", "五", "四", "三", "二", "一"];
  const sideNums = [];
  for (let r = 0; r < 10; r++) {
    sideNums.push(String(black ? r : 9 - r));
  }

  ctx.fillStyle = "#5a3a17";
  ctx.font = "bold 22px Consolas, 'Microsoft YaHei', monospace";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";

  for (let c = 0; c < 9; c++) {
    const x = MARGIN + c * CELL + CELL / 2;
    // 上方：字母行（随红黑翻转），其下固定 1-9
    ctx.fillText(cols[c], x, MARGIN - 36);
    ctx.fillText(topNums[c], x, MARGIN - 66);
    // 下方：固定 九到一（在上），其下字母行（随红黑翻转）
    ctx.fillText(bottomNums[c], x, MARGIN + 1000 + 66);
    ctx.fillText(cols[c], x, MARGIN + 1000 + 36);
  }

  // 左右两侧行号：红方 9-0（上到下），黑方 0-9，随红黑翻转
  for (let r = 0; r < 10; r++) {
    const y = MARGIN + r * CELL + CELL / 2;
    ctx.fillText(sideNums[r], MARGIN - 34, y);
    ctx.fillText(sideNums[r], MARGIN + 900 + 34, y);
  }
}

function drawHighlight() {
  ctx.strokeStyle = "#ffffff";
  ctx.lineWidth = 3;
  state.highlight.forEach((cell, i) => {
    const r = cell[0];
    const c = cell[1];
    const x = MARGIN + c * CELL;
    const y = MARGIN + r * CELL;
    if (i === 1) {
      // 落点：四角 90 度角标
      const len = 15;
      ctx.beginPath();
      ctx.moveTo(x, y + len);
      ctx.lineTo(x, y);
      ctx.lineTo(x + len, y);
      ctx.moveTo(x + CELL - len, y);
      ctx.lineTo(x + CELL, y);
      ctx.lineTo(x + CELL, y + len);
      ctx.moveTo(x, y + CELL - len);
      ctx.lineTo(x, y + CELL);
      ctx.lineTo(x + len, y + CELL);
      ctx.moveTo(x + CELL - len, y + CELL);
      ctx.lineTo(x + CELL, y + CELL);
      ctx.lineTo(x + CELL, y + CELL - len);
      ctx.stroke();
    } else {
      // 原位：中心白点
      ctx.beginPath();
      ctx.arc(x + CELL / 2, y + CELL / 2, 9, 0, Math.PI * 2);
      ctx.fillStyle = "#ffffff";
      ctx.fill();
    }
  });
}

function drawPieces() {
  for (let r = 0; r < 10; r++) {
    for (let c = 0; c < 9; c++) {
      const id = state.board[r][c];
      if (!id) continue;
      const cx = MARGIN + c * CELL + CELL / 2;
      const cy = MARGIN + r * CELL + CELL / 2;
      drawPiece(id, cx, cy);
    }
  }
}

function drawPiece(id, cx, cy) {
  const color = id.startsWith("r") ? "#b00000" : "#1c1c1c";
  ctx.fillStyle = "#e3cf9e";
  ctx.beginPath();
  ctx.arc(cx, cy, 30, 0, Math.PI * 2);
  ctx.fill();
  ctx.strokeStyle = color;
  ctx.lineWidth = 3;
  ctx.stroke();
  ctx.fillStyle = color;
  ctx.font = '42px "KaiTi", "STKaiti", "楷体", "Microsoft YaHei", serif';
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillText(PIECE_CHARS[id], cx, cy + 2);
}

// ---------- 设备 ----------
let lastSerials = null;

async function loadDevices() {
  const listEl = document.getElementById("device-list");
  const errEl = document.getElementById("device-error");
  let data;
  try {
    const res = await fetch("/api/devices");
    data = await res.json();
  } catch {
    errEl.className = "error";
    errEl.textContent = "无法访问服务端";
    errEl.hidden = false;
    return;
  }
  const devices = data.devices || [];
  const serials = devices.join("\n");
  if (lastSerials !== null && serials === lastSerials) {
    return;
  }
  lastSerials = serials;
  listEl.innerHTML = "";
  errEl.hidden = true;
  if (data.error) {
    errEl.className = "error";
    errEl.textContent = data.error;
    errEl.hidden = false;
  }
  if (!devices.length) {
    const li = document.createElement("li");
    li.className = "empty";
    li.textContent = "未检测到设备（请连接 USB 或先 `adb connect ip:port`）";
    listEl.appendChild(li);
    return;
  }
  for (const serial of devices) {
    const li = document.createElement("li");
    const span = document.createElement("span");
    span.className = "serial";
    span.textContent = serial;
    const btn = document.createElement("button");
    btn.textContent = "使用";
    btn.addEventListener("click", () => connect({ serial }));
    li.append(span, btn);
    listEl.appendChild(li);
  }
}

let connecting = false;

async function connect(payload) {
  if (connecting) return;
  connecting = true;
  const mask = document.getElementById("loading-mask");
  const errEl = document.getElementById("device-error");
  const btn = document.getElementById("btn-connect");
  mask.hidden = false;
  btn.disabled = true;
  errEl.hidden = true;
  try {
    const res = await fetch("/api/connect", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const data = await res.json().catch(() => ({}));
    if (data.ok) {
      setTimeout(loadDevices, 500);
    } else {
      errEl.className = "error";
      errEl.textContent = data.error || "连接失败，请检查设备后重试";
      errEl.hidden = false;
    }
  } catch {
    errEl.className = "error";
    errEl.textContent = "无法访问服务端";
    errEl.hidden = false;
  } finally {
    mask.hidden = true;
    btn.disabled = false;
    connecting = false;
  }
}

// ---------- 事件绑定 ----------
function post(path, body) {
  return fetch(`/api/${path}`, {
    method: "POST",
    headers: body ? { "Content-Type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
}

async function cmd(path, key, logMsg) {
  if (busy) return; // 防止重复点击
  busy = key;
  applyButtons();
  if (logMsg) appendLog("info", logMsg);
  try {
    await post(path);
  } catch {
    busy = null;
    applyButtons();
    appendLog("error", "命令发送失败");
  }
}

function bindEvents() {
  document.getElementById("btn-refresh").addEventListener("click", loadDevices);
  document.getElementById("connect-form").addEventListener("submit", (ev) => {
    ev.preventDefault();
    const ip = document.getElementById("ip").value.trim();
    const port = parseInt(document.getElementById("port").value, 10);
    if (!ip || !Number.isFinite(port)) return;
    connect({ ip, port });
  });

  document.getElementById("btn-disconnect").addEventListener("click", () => post("disconnect"));
  document.getElementById("toggle-auto-next").addEventListener("change", (ev) => {
    const enable = ev.target.checked;
    state.autoNext = enable;
    post("auto_next", { enable });
    appendLog(enable ? "info" : "warn", `自动下一局已${enable ? "开启" : "关闭"}（对局结束后生效）`);
  });
  document.getElementById("btn-sync").addEventListener("click", () => {
    cmd("sync", "sync", "同步棋局...");
  });
  document.getElementById("btn-flow").addEventListener("click", () => {
    if (state.status === "stopped") {
      cmd("start", "flow", "开始棋局...");
    } else if (
      state.status === "red" ||
      state.status === "black" ||
      state.status === "auto_next"
    ) {
      cmd("interrupt", "flow", "中断棋局...");
    }
  });

  document.getElementById("prompt-no").addEventListener("click", () => {
    document.getElementById("prompt-mask").hidden = true;
    post("answer_turn", { turn: "no" });
  });
  document.getElementById("prompt-start").addEventListener("click", () => {
    document.getElementById("prompt-mask").hidden = true;
    post("answer_turn", { turn: "start" });
  });
}

// ---------- 启动 ----------
canvas.width = W;
canvas.height = H;
drawBoard(); // 未同步时绘制空棋盘
bindEvents();
loadDevices();
setInterval(() => {
  if (!document.getElementById("connect-screen").hidden && !connecting) {
    loadDevices();
  }
}, 4000);
connectWs();
