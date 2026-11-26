# Fluent 2 / Windows 11 约定

来源基线：Fluent 2（fluent2.microsoft.design）、Windows 11 设计原则与 signature experiences（learn.microsoft.com/windows/apps/design）、Fluent UI Web Components。

## 设计原则（用于做取舍）

Windows 11 的五个原则：**Effortless**（毫不费力）、**Calm**（平静、不抢注意力）、**Personal**（个性化）、**Familiar**（熟悉）、**Coherent**（一致）。

当两个方案都能用时，选更**克制**的那个：少一点装饰、少一点圆角和阴影、少一点强调色。

## 字体

```css
:root {
  --font-family: "Segoe UI Variable", "Segoe UI Variable Text",
                 "Segoe UI", system-ui, "Microsoft YaHei UI", sans-serif;
  --font-family-display: "Segoe UI Variable Display", "Segoe UI Variable",
                         "Segoe UI", system-ui, "Microsoft YaHei UI", sans-serif;
}
```

- `Segoe UI Variable` 有 `wght` / `opsz` 两个轴；正文用 Text  optically sized，大标题用 Display。
- 中文回退 `Microsoft YaHei UI`，不要退到宋体 / SimSun。
- 字阶（px）：12 / 14 / 18 / 20 / 28 / 40 / 68。
  - 正文：14px / 行高 20px。
  - 标题：20/28/40，字重 **600 (Semibold)**，不要 700。
  - 最小字号：Regular 12px、Semibold 14px。
- **Sentence case**，左对齐；不要全大写标题，不要滥用斜体。
- 每行 50–60 字符，长文用 `max-width: 68ch`。

## 间距与尺寸

- 4px 基准栅格：`4 / 8 / 12 / 16 / 24 / 32`。
- 控件高度：紧凑 24px、舒适 32px、宽松 40px —— 三选一，全应用统一。
- 触摸目标 ≥ 44×44px。
- 圆角：控件 4px、卡片 8px 左右；不要所有元素同一圆角 + 同一阴影。

## 材质与层级

Fluent 有四种材质：Solid（实心）、Acrylic（亚克力）、Mica（云母）、Smoke。

- 常规界面用 Solid；Mica 仅用于应用主背景等大表面，并随活动窗口 / 桌面壁纸变化；Acrylic 用于浮层 / 侧边栏等短暂表面。
- Web 上可用 `backdrop-filter: blur(...)` 近似，但必须提供降级（不支持时用纯色）。
- 层级顺序：背景微移 → 间距 → 描边 → elevation。Windows 上**优先用描边代替重投影**。
- 阴影层级（Blur 半径）：2 / 4 / 8 / 16 / 28 / 64，按层级选择，不要随手加 `box-shadow`。

## 交互态

- `hover` / `active` / `focus-visible` / `selected` / `disabled` 必须齐全。
- Windows 桌面约定"越交互越亮"（pressed 比 hover 更亮）；Web Components 默认相反。二者选其一并在全应用保持一致，不要混用。
- 焦点用**加粗描边**（Fluent `colorStrokeFocus1` / `colorStrokeFocus2`），不要靠改色表达焦点。
- 语义色（success / warning / danger）只用于反馈，不用于装饰。

## 图标

优先 `Segoe Fluent Icons` 字体或 Fluent UI 图标集；单一家族，不要混用 Emoji 和不同风格图标。装饰图标要有 `aria-hidden="true"`；有含义的图标按钮必须有 `aria-label`。

## PWA 自定义标题栏（Window Controls Overlay）

适用：以 PWA 形式安装、希望标题栏融入页面。

1. `manifest.webmanifest`：

```json
{
  "display": "standalone",
  "display_override": ["window-controls-overlay"],
  "theme_color": "#1b1b1b"
}
```

2. CSS 安全区：

```css
@media (display-mode: window-controls-overlay) {
  .app-header {
    padding-left: env(titlebar-area-x, 0);
    padding-top: env(titlebar-area-y, 0);
    width: env(titlebar-area-width, 100%);
    -webkit-app-region: drag;
  }
  .app-header button { -webkit-app-region: no-drag; }
}
```

3. JS 能力检测：`navigator.windowControlsOverlay?.visible`；监听 `geometrychange` 更新布局。
4. Electron 等价方案：`app-region: drag` / `no-drag`。
5. 标题栏只放应用名和少量上下文；为窗口控制按钮预留安全边距，不要把交互元素放在 `titlebar-area-*` 之外。

## 其他 Windows 细节

- 尊重系统 DPI / 缩放：用 `rem` 和相对单位，不要写死 `width: 1200px` 作为布局基准。
- 窄窗口：桌面优先，但侧栏要能折叠为抽屉，表格横向可滚动。断点由内容决定，不要照搬手机断点。
- `prefers-reduced-motion: reduce` 时关闭位移 / 视差动效，保留必要的状态变化。
- 深浅色两套都要处理滚动条、选中态、tooltip 和原生表单控件（`color-scheme` 已声明时浏览器会自动配合）。

## Web 实现微规则（易漏但影响观感）

来自 Vercel `web-design-guidelines` 等实践：

- 标题用 `text-wrap: balance`；数字列用 `font-variant-numeric: tabular-nums`，避免跳动。
- 锚点目标加 `scroll-margin-top`，避免被固定标题栏遮住。
- flex 子项加 `min-width: 0`，否则长内容撑破布局；图片写显式 `width` / `height` 防布局偏移。
- 模态框 `overscroll-behavior: contain` 防止滚动穿透；按钮 `touch-action: manipulation` 消除移动端双击延迟。
- 日期 / 数字用 `Intl.DateTimeFormat` / `Intl.NumberFormat`，不要手写格式。
- 筛选、标签页、分页等状态要反映到 URL（可深链、可刷新恢复），不要只留在组件内存里。
- 破坏性操作要有确认或撤销；审查结果用 `file:line` 精确定位，不要只说"某处有问题"。

## 组件 API（框架通用）

- 避免布尔 prop 泛滥（`isThread` / `isEditing` / `isCompact` 组合爆炸）：用**复合组件**或**显式变体组件**表达语义。
- Context 接口拆成 `state` / `actions` / `meta` 三层，便于替换实现与测试。
- 优先传 `children`，而不是 `renderXxx` 回调 prop。
- 改样式前检查 CSS 选择器优先级：`.section` 与 `.cta` 的特异性冲突会悄悄抵消 padding / margin，看起来像 token 失效。
