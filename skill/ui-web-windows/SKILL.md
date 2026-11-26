---
name: ui-web-windows
description: Design and build Windows desktop web app UIs (HTML/CSS/JS or any web framework) with first-class light/dark theming, Fluent 2 / Windows 11 conventions, WCAG 2.2 contrast, and no generic "AI slop" aesthetics. Use whenever the user asks to build, design, restyle, scaffold, or improve a web interface that runs on Windows — including light/dark mode, theme tokens, Fluent styling, custom title bars, or accessibility checks.
---

# ui-web-windows — Windows Web 应用 UI 设计规范

为运行在 **Windows 桌面**上的 Web 应用（浏览器 / PWA / WebView / Electron）生成生产级 UI。目标不是"好看的网页"，而是**像原生 Windows 应用一样克制、可访问、深浅色一致**的界面。

核心原则：**先用 token 锁定设计决策，再写组件**。禁止用 *clean / modern / premium / beautiful* 之类形容词代替具体约束。每一个颜色、间距、圆角、字体都必须来自具名 token。

参考基线：Fluent 2 设计体系（2024+）、Windows 11 设计原则、MDN 颜色模式规范、WCAG 2.2 AA。

---

## 工作流（按顺序执行）

### Step 0 — 项目扫描

在动手前检查：

```text
AGENTS.md / CLAUDE.md / DESIGN.md   # 项目已记录的设计规则
tokens.css / theme.css / *.css      # 是否已有主题 token
package.json / index.html / ...     # 技术栈（原生 / React / Vue / Electron / PWA）
manifest.webmanifest                # PWA + window-controls-overlay
```

- **已有 token 文件**：读取并沿用；只复用已有 token，新增 token 前必须征得用户同意。跳到 Step 3。
- **无 token 且无设计规则**：进入 bootstrap 模式，继续 Step 1。
- 不要把设计规则硬塞进一个新文件；优先写进项目已有的 agent 指令文件（`AGENTS.md` / `CLAUDE.md`）。若项目确实没有，询问用户后再创建 `DESIGN.md`。

### Step 1 — 收集决策（仅 bootstrap）

用提问收集，不要问"你想让它感觉如何"这类空问题：

1. **主题模式**：自动跟随系统（默认）/ 仅浅色 / 仅深色 / 用户可切换（三态：system / light / dark）。
2. **强调色**：给出 1 个品牌基色（hex）；无品牌时用 Windows 蓝，且必须声明。
3. **密度**：舒适（默认，控件高度 32px）/ 紧凑（24px）/ 宽松（40px）。
4. **窗口形态**：普通浏览器页 / PWA 自绘标题栏（Window Controls Overlay）/ WebView2 / Electron。
5. **信息密度与布局**：内容型（长文/列表）/ 工具型（面板/表格）/ 仪表盘。

### Step 2 — 建立 token（两阶段，需用户确认）

动手前先做**两遍法**：第一遍出紧凑方案（4–6 个具名 hex、字体角色、一句话布局概念 + ASCII 线框、对齐方式、原则）并写进设计记录；第二遍自问"换一个通用 prompt 会不会得到几乎相同的方案？"，若会则针对主题 / 行业 / 受众 / 具体任务修订后再动手。需求缺这些信息时先提出并请用户确认。

生成 `tokens.css`（或项目等价文件），结构为**两层**：

1. **全局原始值**（raw）：调色板、字号、间距、圆角、描边、动效时长。
2. **语义别名**（semantic alias）：组件只准引用 alias，例如 `--bg-base`、`--fg-primary`、`--fg-muted`、`--border-subtle`、`--accent`、`--focus-ring`。

主题实现主线：

```css
:root {
  color-scheme: light dark;               /* 必须设在 :root，不要只写 body */
  --bg-base: light-dark(#ffffff, #1b1b1b);
  --fg-primary: light-dark(#242424, #f5f5f5);
  --border-subtle: light-dark(#e5e5e5, #3a3a3a);
}
:root[data-scheme="light"] { color-scheme: light; }
:root[data-scheme="dark"]  { color-scheme: dark; }
```

同时：

- `<head>` 加 `<meta name="color-scheme" content="light dark">`，防止首屏闪白（FOUC）。
- JS 只切换 `document.documentElement.dataset.scheme`，默认读 `prefers-color-scheme`，用户选择持久化到 `localStorage`。
- 深色**不是反转浅色**：降低高饱和色亮度 / 饱和度；用表面层级（`--bg-1` → `--bg-4`）表示层次；阴影在深色中减弱，改用描边。
- 派生色用 `color-mix(in oklab, …)` 或 OKLCH 相对色，避免纯改亮度导致溢出色域。
- 高对比 / `forced-colors: active` 模式必须可用。

可直接复制的样板见 `assets/tokens.example.css`。生成后打印 token 清单并请用户确认，**确认前不要进入 Step 3**。

### Step 3 — 实现 UI

只使用 Step 2 的 token 写组件。必须遵守：

- `references/fluent-windows.md` — Windows 11 字体栈、字阶、圆角、材质、标题栏与 PWA 特性。
- `references/accessibility.md` — WCAG 2.2 对比度、焦点、键盘、forced-colors。
- `references/anti-slop.md` — 禁用模式与真实文案要求。

状态完整性：每个可交互元素都要有 `hover` / `active` / `focus-visible` / `disabled` / `selected` 态；每个异步区域都要有 empty / loading / error 态。

### Step 4 — 验证（必须执行后才能说"完成"）

读 `references/accessibility.md` 的验证清单，逐项检查并输出结果表：

- 浅色 / 深色两套截图人工检查：无残留白块、无 FOUC、无低对比文本。
- 自动对比度检查：正文 ≥ 4.5:1；大字 ≥ 3:1；UI 边界 / 图标 / 选中态 ≥ 3:1；焦点指示器 ≥ 2px 且前后对比 ≥ 3:1。
- 只用键盘走查所有交互；focus 环在两套主题下都可见。
- 全文件搜索硬编码颜色 / 间距：组件中不应出现 `color: #...`、`padding: 13px` 之类。
- 窄窗口、系统 DPI 缩放、`forced-colors: active` 下不破版。
- 按 `references/anti-slop.md` 的 banned list 与 AI 默认审美指纹逐项自查，并用截图做一次"删掉一件配饰"的删减复查。

---

## 硬规则

1. **无硬编码**：组件里不允许出现裸 hex / `rgb()` / 任意 px 间距；颜色走 token，间距走 4px 栅格（4/8/12/16/24/32）。
2. **禁止 buzzword**：输出的代码与注释中禁止 *clean / modern / professional / sleek / premium / beautiful / elegant*（用户明确要求的文案除外）。
3. **双主题 parity**：任何新增颜色都必须同时给出浅色和深色值；深色不允许简单 `filter: invert()`。
4. **焦点可见**：所有可交互元素有 `:focus-visible` 描边（≥2px），不允许 `outline: none` 且不替代。
5. **语义色只传意**：success / warning / danger 只用于反馈，不做装饰。
6. **字体**：Windows 优先 `"Segoe UI Variable", "Segoe UI", system-ui`，中文回退 `Microsoft YaHei UI`；正文 14px/20px，标题用 600 而非 700；sentence case，不用全大写。
7. **不以渐变和阴影代替结构**：层级优先用背景微移 + 间距，其次才是 elevation；Windows 上用描边代替重阴影。
8. **真实文案**：禁止 lorem ipsum、"Lightning fast"、"Powered by AI"、"The future of X" 等占位文案。
9. **尊重系统设置**：`prefers-color-scheme`、`prefers-reduced-motion`、`forced-colors`、DPI 缩放都要处理。
10. **验证先行**：没跑完 Step 4 的验证表，不得声称完成。

## 文件索引

| 文件 | 何时读 |
|---|---|
| `references/tokens-and-theming.md` | Step 2：`light-dark()` / `color-scheme` / OKLCH / 深色不反转的完整范式 |
| `references/fluent-windows.md` | Step 3：Windows 11 字体、材质、标题栏、PWA Window Controls Overlay |
| `references/accessibility.md` | Step 3–4：WCAG 2.2 数值、focus、键盘、验证清单 |
| `references/anti-slop.md` | Step 1–4：禁用模式、文案约束、DESIGN 决策记录模板 |
| `assets/tokens.example.css` | Step 2：可复制的 token 样板 |

## 本 skill 不做什么

- 不是品牌设计工具；品牌色 / Logo 由用户决定，skill 只保证 token 化与可访问性。
- 不替代人工设计评审；验证表是底线，不是上限。
- 不针对 Android / iOS / 移动 Web；那些场景用对应的 `ui-android-*` / `ui-web-*` skill。
