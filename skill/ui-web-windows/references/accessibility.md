# 无障碍与验证（WCAG 2.2 AA）

数值来自 WCAG 2.2：1.4.3（对比度，最低 AA）、1.4.11（非文本对比度）、2.4.7 / 2.4.13（焦点可见 / 焦点外观）。这是**硬约束**，不是建议。

## 对比度

| 对象 | 最低比值 |
|---|---|
| 正文文本 | 4.5:1 |
| 大字（≥24px，或 ≥18.66px 且 bold/600+） | 3:1 |
| UI 组件边界、图标、选中态、焦点指示器 | 3:1 |
| 纯装饰元素 | 无要求，但要 `aria-hidden` / 确保不被误读 |

焦点指示器要求：≥2px 周长，焦点前后颜色对比 ≥3:1。

```css
:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}
/* 永远不要只写 outline: none 而不给替代方案 */
```

不要只用颜色传达状态：错误 / 成功 / 选中必须同时有文字、图标或形状变化。

## 键盘可达

- 所有交互元素用原生 `button` / `a` / `input` 或带 `role` + `tabindex` 的等价实现。
- Tab 顺序与视觉顺序一致；不要用正数 `tabindex`。
- 模态对话框：打开时焦点移入、关闭时归还焦点、`Escape` 关闭、焦点被困在内部。
- 自定义控件（下拉、菜单、标签页）实现对应 WAI-ARIA pattern 的键盘操作。

## 语义与文案

- icon-only 按钮必须有 `aria-label`。
- 装饰图片 `alt=""`，有含义图片写具体描述。
- 表单控件有关联的 `<label>`；错误提示用 `aria-describedby` 关联并 `aria-invalid="true"`。
- 标题层级 `h1 → h2 → h3` 不跳级；不要靠字号模拟标题。

## 强制颜色 / 高对比模式

```css
@media (forced-colors: active) {
  .card { border: 1px solid CanvasText; }
  :focus-visible { outline: 2px solid Highlight; }
}
```

在 `forced-colors: active` 下：文字可读、焦点可见、状态不只靠颜色区分。`color-scheme` 声明过的页面在 Windows 高对比模式下表现更好。

## 动效

- `prefers-reduced-motion: reduce` 时移除位移 / 缩放 / 视差，保留必要的状态变化（可用透明度）。
- 不要自动播放闪烁内容；闪烁频率不得高于 3Hz。

## 验证清单（必须逐项执行并输出结果）

| # | 检查 | 方法 | 通过标准 |
|---|---|---|---|
| A1 | 浅色正文对比 | 计算或工具扫描 | ≥ 4.5:1（大字 ≥ 3:1） |
| A2 | 深色正文对比 | 同上 | 同上 |
| A3 | UI 边界 / 图标 / 选中态 | 同上 | ≥ 3:1 |
| A4 | 焦点环 | 两套主题下 Tab 走查 | ≥2px、可见、前后 ≥3:1 |
| A5 | 键盘全流程 | 不用鼠标完成所有任务 | 无死路、顺序一致 |
| A6 | 模态焦点管理 | 打开/关闭/Escape | 焦点进出正确 |
| A7 | 语义标签 | 审查 DOM | icon 按钮有 label，图片 alt 正确 |
| A8 | 强制颜色模式 | Windows 高对比主题 | 可读、焦点可见 |
| A9 | reduced motion | 系统开启减少动画 | 无位移动效 |
| A10 | FOUC | 深色模式下强刷 | 首屏不闪白 |

任何一项 FAIL 都必须修复后才能声称完成。报告里列出 FAIL 项、文件位置和修复结果。
