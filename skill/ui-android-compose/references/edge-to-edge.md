# Edge-to-edge 与 WindowInsets

权威来源：Google Android Developers — `developer.android.com/develop/ui/compose/system/setup-e2e`、`insets-ui`、`material-insets`；官方 skill `github.com/android/skills/tree/main/system/edge-to-edge`。

## 必须做的

1. **每个 Activity 调 `enableEdgeToEdge()`**（在 `setContent` 之前，Activity 的 `onCreate`）。
2. AndroidManifest 里窗口软键盘模式用 `android:windowSoftInputMode="adjustResize"`，不要用已废弃的 `SOFT_INPUT_ADJUST_RESIZE` 常量。
3. 只用**一种** inset 消费方式，避免双重 padding：
   - 常规 `Scaffold`：把 `innerPadding` 传给 `LazyColumn(contentPadding = innerPadding)` 等内容视图；
   - 需要自己处理时：根布局 `.consumeWindowInsets(WindowInsets.systemBars)`；
   - 自适应 `Scaffold` 不传 `PaddingValues` 时，逐屏处理 inset。
4. IME：优先 `Modifier.fitInside(WindowInsetsRulers.Ime.current)`；退而求其次用 `imePadding()`，且**必须放在 `verticalScroll()` 之前**。
5. 全屏 Dialog 若需要自己的 inset 处理，设 `decorFitsSystemWindows = false`。
6. 底部导航栏可设 `window.isNavigationBarContrastEnforced = false`，让 Material 自己处理对比度。

## 常见反模式

- ❌ 硬编码 `statusBarsPadding()` 而不走 window insets API。
- ❌ `imePadding()` 同时 `Scaffold` 又传 `contentPadding`，输入框被顶高两次。
- ❌ `android:windowSoftInputMode="adjustNothing"` + 手动位移，键盘遮挡输入框。
- ❌ 只在一个 Activity 开启 edge-to-edge，其他页面上下留黑边。

## 验证

- 深色 / 浅色、手势导航 / 三键导航、横竖屏各看一遍。
- 打开键盘后输入区域完整可见，关闭后布局恢复。
- 内容不被状态栏 / 导航栏遮挡，也没有多余留白。
