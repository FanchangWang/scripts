# 结构分解与 Modifier 顺序

## 结构分解（先于写代码）

1. **判根布局**：
   - 顶栏 + 内容 + 底栏 → `Scaffold`
   - 重叠内容 → `Box`
   - 标签 / chip 流 → `FlowRow`
2. **按语义命名 section**：`ProductHeader`、`CheckoutSummary` 等；不要镜像 Figma 图层名。
3. **尽量扁平**：减少不必要的嵌套层级。
4. **有现成 M3 组件就绝不用 `Box` + modifier 手搓**：手搓会丢掉暗色、动态配色、ripple、无障碍语义和状态动画。
5. **列表用 `LazyColumn` / `LazyRow` / `LazyVerticalGrid`**，长列表不要用 `Column` + `verticalScroll`。

## Modifier 顺序

固定顺序：`size → 外 padding → dropShadow（必须在 background 之前）→ background → clip → clickable → 内 padding`。

- `padding` 放在 `clickable` 之后会扩大点击区（正确）；放在之前会缩小可见点击区（错误）。
- 用 `Arrangement.spacedBy()` 而不是逐项 `Spacer`。
- 用 `start` / `end` 而不是 `left` / `right`（RTL 兼容）。
- 触控目标用 `Modifier.minimumInteractiveComponentSize()` 保证 ≥48dp。

## 状态与预览

- 列表滚动状态用 `rememberLazyListState()`；配置变化用 `rememberSaveable`。
- 不要靠 `configChanges` 规避重建。
- 每个 public Composable 加 `@Preview`：Light / Dark + Phone / Foldable / Tablet / Desktop。
