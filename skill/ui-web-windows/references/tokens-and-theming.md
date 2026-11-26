# Token 架构与深浅色主题

## 两层 token 模型

**第一层：全局原始值（raw / global）** —— 描述"有哪些值"，不带语义。

```css
:root {
  /* 中性色阶（示意，按品牌调整） */
  --neutral-0:  #ffffff;
  --neutral-1:  #f5f5f5;
  --neutral-2:  #e5e5e5;
  --neutral-3:  #c8c8c8;
  --neutral-4:  #8a8a8a;
  --neutral-5:  #5a5a5a;
  --neutral-6:  #303030;
  --neutral-7:  #1b1b1b;

  --brand-60: #0f6cbd;   /* Windows 蓝，可替换为品牌基色 */
  --brand-70: #115ea3;
  --brand-80: #0c3b5e;

  /* 字号 / 字阶 */
  --font-12: 0.75rem;
  --font-14: 0.875rem;
  --font-18: 1.125rem;
  --font-20: 1.25rem;
  --font-28: 1.75rem;
  --font-40: 2.5rem;
  --font-68: 4.25rem;

  /* 间距：4px 基准栅格，只有这套值 */
  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-6: 24px;
  --space-8: 32px;

  --radius-control: 4px;
  --radius-card: 8px;
  --duration-fast: 100ms;
  --duration-normal: 200ms;
}
```

**第二层：语义别名（semantic alias）** —— 组件只引用这一层。

```css
:root {
  --bg-base:        light-dark(var(--neutral-0), var(--neutral-7));
  --bg-subtle:      light-dark(var(--neutral-1), #242424);
  --bg-raised:      light-dark(var(--neutral-0), #2b2b2b);
  --fg-primary:     light-dark(#242424, #f5f5f5);
  --fg-muted:       light-dark(#616161, #b5b5b5);
  --border-subtle:  light-dark(var(--neutral-2), #3a3a3a);
  --border-strong:  light-dark(var(--neutral-3), #5a5a5a);
  --accent:         light-dark(var(--brand-60), #60cdff);
  --accent-fg:      light-dark(#ffffff, #003b5e);
  --focus-ring:     light-dark(#0f6cbd, #ffffff);
  --success:        light-dark(#107c10, #6ecc6e);
}
```

> 注意：`light-dark()` 的两个参数必须是合法的 CSS 值。复杂值请用括号包好：
> `--success: light-dark(#107c10, #6ecc6e);`

## 深浅色实现主线

### 1. 声明颜色模式（防 FOUC）

```html
<head>
  <meta name="color-scheme" content="light dark">
</head>
```

```css
:root {
  color-scheme: light dark;  /* 必须设在 :root，不要只写 body */
}
:root[data-scheme="light"] { color-scheme: light; }
:root[data-scheme="dark"]  { color-scheme: dark; }
```

`color-scheme` 会影响浏览器默认控件、滚动条、表单元素；只写 `body` 是不够的。

### 2. 用 `light-dark()` 定义语义 token

`light-dark(lightValue, darkValue)` 根据元素**计算后的** `color-scheme` 取值。它依赖祖先链上的 `color-scheme`，因此 `:root` 必须声明。

渐进增强（旧浏览器不支持 `light-dark()` 时）：

```css
:root { --bg-base: #ffffff; }
:root[data-scheme="dark"] { --bg-base: #1b1b1b; }
@supports (color: light-dark(red, blue)) {
  :root { --bg-base: light-dark(#ffffff, #1b1b1b); }
}
```

或用 `prefers-color-scheme` 媒体查询兜底。

### 3. JS 只负责切换选择器，不负责写颜色

```js
const root = document.documentElement;
const saved = localStorage.getItem('theme');
const systemDark = matchMedia('(prefers-color-scheme: dark)');

function apply(scheme) {
  root.dataset.scheme = scheme;       // 'system' 时移除 data-scheme
  if (scheme === 'system') root.removeAttribute('data-scheme');
  else root.dataset.scheme = scheme;
}

apply(saved || 'system');
systemDark.addEventListener('change', () => {
  if ((localStorage.getItem('theme') || 'system') === 'system') apply('system');
});
```

不要在 JS 里维护另一套颜色表；那会造成两套主题漂移。

## 深色不是浅色的反转

| 浅色做法 | 深色正确做法 |
|---|---|
| 白色背景 + 黑色阴影 | 深灰背景 + 更浅表面层级 + 细描边 |
| 高饱和品牌色 | 降低亮度与饱和度，避免刺眼 |
| 重投影表达层级 | 减弱阴影，用 `--bg-1 → --bg-4` 和描边表达层级 |
| 纯黑 `#000` / 纯白 `#fff` 大面积 | 用深灰 / 近白，降低对比疲劳 |

## 派生色

优先使用现成 token。需要派生时：

```css
/* perceptual 混色，避免 sRGB 线性混合发灰 */
--accent-hover: color-mix(in oklab, var(--accent) 88%, white);
--bg-hover:     color-mix(in oklab, var(--bg-base) 94%, var(--fg-primary));

/* OKLCH 相对色语法：从基色改亮度/色度 */
--accent-strong: oklch(from var(--accent) calc(l - 0.08) c h);
```

避免只改 `lightness` 导致超出 sRGB 色域；优先 `oklab` / `oklch` 色彩空间。

## 高对比 / 强制颜色模式

```css
@media (forced-colors: active) {
  .card { border: 1px solid CanvasText; }
  .button:focus-visible { outline: 2px solid Highlight; }
}
```

确保 `forced-colors: active` 下：文字仍可读、焦点仍可见、状态不只靠颜色传达。

## 用户自定义色的自动对比文字（Lea Verou 技巧）

当颜色来自用户 / 标签 / 数据（无法预先写死 `onXxx` token）时，用 OKLCH 相对色语法按亮度自动选黑或白：

```css
--l: clamp(0, (l / var(--l-threshold) - 1) * -infinity, 1);
color: oklch(from var(--color) var(--l) 0 h);
```

`--l-threshold` 取 `0.623`（WCAG 安全）或 `0.70`（APCA 可读性更好）。要点：

- APCA 的 `Lc` **符号编码极性**：深底浅字为负值，浅底深字为正值——可用来快速判断可读性方向；**合规判定仍以 WCAG 2.2 为准**，APCA 只作补充。
- 这是兜底手段，优先仍是为已知颜色定义成对 token；对自定义色用完后要实测对比度。
- 色阶派生时，保持每个 tint 的 lightness / hue，只降低 chroma 直到落入目标色域（gamut mapping），不要简单压暗导致发灰或溢色。

## Windows 系统强调色与上下文适配

- 原生表单控件可用 `accent-color: var(--accent)` 跟随主题；`AccentColor` / `AccentColorText` 系统色可直接参与配色。
- **注意**：部分浏览器为降低指纹风险，只在特定受限场景下返回真实 `AccentColor`，否则给固定值。因此系统色只能作渐进增强，不能替代 `--accent` token。
- 嵌入式 / WebView2 场景可做上下文适配：系统为深色时用略柔和的浅色主题，系统为浅色时用对比更强的深色主题，避免页面压过周围 UI。
- `color-scheme` 是渐进增强，保留 `prefers-color-scheme` 兜底；**不要在单个组件上写媒体查询**，只在 `:root` / token 层做切换。

## 常见错误

- ❌ 只在 `body` 上写 `color-scheme`，表单控件和滚动条仍是浅色。
- ❌ 组件里直接写 `color: #333`，深色主题下变成黑底黑字。
- ❌ 深色主题用 `filter: invert(1)`，图标和品牌色全毁。
- ❌ 用 `light-dark()` 但没声明 `color-scheme`，取值永远走第一分支。
