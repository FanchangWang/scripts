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

## Kotlin + Compose 配套约束（UI 侧）

本 skill 只管 UI，但下列 Kotlin 约定直接决定界面能否正确工作，写 composable 时必须遵守：

1. **状态归属与不可变 UI state**：Screen 无状态化，状态提升到 ViewModel / state holder；对外暴露 `StateFlow<UiState>` / `State<UiState>`，`UiState` 用 `data class` + `val`。不要在 composable 里直接写回 ViewModel 的可变状态。
2. **生命周期感知收集**：UI 层用 `collectAsStateWithLifecycle()` 收集 Flow；确需 `collectAsState()` 要写明理由。
3. **`remember` / `rememberSaveable` / `derivedStateOf`**：配置变化后要恢复的小状态用 `rememberSaveable`；由其他状态推导的值用 `derivedStateOf`，避免整屏重算；纯计算缓存用 `remember`。
4. **列表稳定 key**：`items(list, key = { it.id })`，不要用 index 当 key；key 必须跨重组 / 滚动稳定。
5. **不稳定参数与 lambda**：传给重组件的 lambda 尽量稳定（`remember` 包装或直接引用函数），不要在 composition 中新建集合 / 对象当参数。稳定性问题先 profile，不要凭直觉判定。
6. **一次性事件**：导航 / snackbar / toast 用 ViewModel 的事件通道（`SharedFlow` / channel / 事件队列），在 `LaunchedEffect` 中消费并去重；不要塞进持久 `UiState`。
7. **Effect 与协程**：`LaunchedEffect` 的 key 只放真正影响任务的输入；`DisposableEffect` 必须 dispose；`rememberCoroutineScope` 只用于用户手势启动的任务；不要在 composition 中直接 `launch`。
8. **`CompositionLocal`**：只用于主题 token 这类低频稳定依赖（如 `LocalSpacing`），不要传业务状态。
9. **用类型表达状态**：加载 / 空 / 错误用 `sealed interface` / `data object` 等完整建模，不要用一堆可空字段 + `!!` 充当状态机。
10. **可测试性**：`@Preview` 传示例 UiState；composable 参数只暴露 state + 事件 lambda，便于 `createComposeRule` 测试。
