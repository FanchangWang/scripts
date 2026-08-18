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

// 画布尺寸：10 横线 × 9 竖线交叉点棋盘 800x900
const CELL = 100;
const MT = 105;
const ML = 75;
const W = 800 + ML * 2;
const H = 900 + MT * 2;

// 命令忙状态：null / "sync" / "flow"（命令执行期间禁用对应按钮）
let busy = null;

// ---------- WebSocket ----------
const WS_RECONNECT_MAX = 5;
const WS_RECONNECT_INTERVAL_MS = 1000;
let wsReconnectTimer = null;
let wsReconnectCount = 0;

function connectWs() {
  clearTimeout(wsReconnectTimer);
  const ws = new WebSocket(`ws://${location.host}/ws`);
  ws.onopen = () => {
    wsReconnectCount = 0;
  };
  ws.onmessage = (ev) => handleEvent(JSON.parse(ev.data));
  ws.onclose = () => {
    wsReconnectCount++;
    if (wsReconnectCount >= WS_RECONNECT_MAX) {
      document.getElementById("disconnect-mask").hidden = false;
      return;
    }
    wsReconnectTimer = setTimeout(connectWs, WS_RECONNECT_INTERVAL_MS);
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
      applyButtons();
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
  const flowBtn = document.getElementById("btn-flow");
  let flowDisabled = false;
  let flowText = "开始棋局";
  if (
    state.status === "red" ||
    state.status === "black" ||
    state.status === "auto_next"
  ) {
    flowText = "中断棋局";
  }
  if (busy === "flow") flowDisabled = true;
  flowBtn.disabled = flowDisabled;
  flowBtn.textContent = flowText;
}

// ---------- 棋盘绘制 ----------
function drawBoard() {
  ctx.clearRect(0, 0, W, H);
  drawFrame();
  drawGrid();
  drawStarPoints();
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
  ctx.strokeRect(ML - 6, MT - 6, 800 + 12, 900 + 12);
}

function drawGrid() {
  ctx.strokeStyle = "#5a3a17";
  ctx.lineWidth = 1.5;

  // 横向线：10 条
  for (let i = 0; i <= 9; i++) {
    const y = MT + i * CELL;
    ctx.beginPath();
    ctx.moveTo(ML, y);
    ctx.lineTo(ML + 800, y);
    ctx.stroke();
  }

  // 竖向线：9 条，最外侧画满，中间楚河汉界处断开
  for (let j = 0; j <= 8; j++) {
    const x = ML + j * CELL;
    if (j === 0 || j === 8) {
      ctx.beginPath();
      ctx.moveTo(x, MT);
      ctx.lineTo(x, MT + 9 * CELL);
      ctx.stroke();
    } else {
      ctx.beginPath();
      ctx.moveTo(x, MT);
      ctx.lineTo(x, MT + 4 * CELL);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(x, MT + 5 * CELL);
      ctx.lineTo(x, MT + 9 * CELL);
      ctx.stroke();
    }
  }

  // 九宫 X（交叉点）
  const ix0 = ML + 3 * CELL;
  const ix1 = ML + 5 * CELL;
  const iy0 = MT + 0 * CELL;
  const iy2 = MT + 2 * CELL;
  const iy7 = MT + 7 * CELL;
  const iy9 = MT + 9 * CELL;
  ctx.beginPath();
  ctx.moveTo(ix0, iy0);
  ctx.lineTo(ix1, iy2);
  ctx.moveTo(ix1, iy0);
  ctx.lineTo(ix0, iy2);
  ctx.moveTo(ix0, iy7);
  ctx.lineTo(ix1, iy9);
  ctx.moveTo(ix1, iy7);
  ctx.lineTo(ix0, iy9);
  ctx.stroke();

  // 楚河汉界
  const riverY = MT + 4.5 * CELL;
  ctx.fillStyle = "#5a3a17";
  ctx.font = "bold 36px 'KaiTi', 'STKaiti', '楷体', 'Microsoft YaHei', serif";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillText("楚  河", ML + 2 * CELL, riverY);
  ctx.fillText("汉  界", ML + 6 * CELL, riverY);
}

function drawStarPoints() {
  const points = [
    [2, 1], [2, 7],
    [3, 0], [3, 2], [3, 4], [3, 6], [3, 8],
    [7, 1], [7, 7],
    [6, 0], [6, 2], [6, 4], [6, 6], [6, 8],
  ];
  ctx.strokeStyle = "#5a3a17";
  ctx.lineWidth = 1.5;
  const gap = 5;
  const len = 12;
  for (const [r, c] of points) {
    const x = ML + c * CELL;
    const y = MT + r * CELL;
    ctx.beginPath();
    if (c > 0) {
      ctx.moveTo(x - gap - len, y - gap);
      ctx.lineTo(x - gap, y - gap);
      ctx.lineTo(x - gap, y - gap - len);
      ctx.moveTo(x - gap - len, y + gap);
      ctx.lineTo(x - gap, y + gap);
      ctx.lineTo(x - gap, y + gap + len);
    }
    if (c < 8) {
      ctx.moveTo(x + gap + len, y - gap);
      ctx.lineTo(x + gap, y - gap);
      ctx.lineTo(x + gap, y - gap - len);
      ctx.moveTo(x + gap + len, y + gap);
      ctx.lineTo(x + gap, y + gap);
      ctx.lineTo(x + gap, y + gap + len);
    }
    ctx.stroke();
  }
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
    const x = ML + c * CELL;
    ctx.fillText(cols[c], x, 25);
    ctx.fillText(topNums[c], x, 55);
    ctx.fillText(bottomNums[c], x, H - 55);
    ctx.fillText(cols[c], x, H - 25);
  }

  for (let r = 0; r < 10; r++) {
    const y = MT + r * CELL;
    ctx.fillText(sideNums[r], 25, y);
    ctx.fillText(sideNums[r], W - 25, y);
  }
}

function drawHighlight() {
  ctx.strokeStyle = "#ffffff";
  ctx.lineWidth = 3;
  state.highlight.forEach((cell, i) => {
    const r = cell[0];
    const c = cell[1];
    const x = ML + c * CELL;
    const y = MT + r * CELL;
    if (i === 1) {
      const gap = 35;
      const len = 15;
      ctx.beginPath();
      // 左上
      ctx.moveTo(x - gap - len, y - gap);
      ctx.lineTo(x - gap, y - gap);
      ctx.lineTo(x - gap, y - gap - len);
      // 右上
      ctx.moveTo(x + gap + len, y - gap);
      ctx.lineTo(x + gap, y - gap);
      ctx.lineTo(x + gap, y - gap - len);
      // 左下
      ctx.moveTo(x - gap - len, y + gap);
      ctx.lineTo(x - gap, y + gap);
      ctx.lineTo(x - gap, y + gap + len);
      // 右下
      ctx.moveTo(x + gap + len, y + gap);
      ctx.lineTo(x + gap, y + gap);
      ctx.lineTo(x + gap, y + gap + len);
      ctx.stroke();
    } else {
      ctx.beginPath();
      ctx.arc(x, y, 9, 0, Math.PI * 2);
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
      const cx = ML + c * CELL;
      const cy = MT + r * CELL;
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
  document.getElementById("btn-flow").addEventListener("click", () => {
    if (
      state.status === "idle" ||
      state.status === "over" ||
      state.status === "stopped"
    ) {
      cmd("start", "flow", "同步并开始棋局...");
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

  document.getElementById("btn-reload").addEventListener("click", () => {
    location.reload();
  });
  document.getElementById("btn-close-page").addEventListener("click", () => {
    window.close();
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
