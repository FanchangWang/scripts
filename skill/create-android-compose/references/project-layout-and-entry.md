# 项目骨架：模板、SDK、入口、主题、导航、测试

## 1. 从官方模板起手

优先用 Android CLI（事实来源永远是模板本身）：

```bash
android create --list                        # 先列出可用模板
android create empty-activity \
  --name="MyApp" \
  --application-id="com.example.myapp" \
  --namespace="com.example.myapp" \
  --min-sdk=24 \
  --output=./my-app
```

Android Studio 里对应 **Empty Activity**（Compose 版本）。官方明确：新项目用 Empty Activity；其他模板只用于维护 / 迁移旧代码库。

**禁止**：手写 Gradle 起项目、非 Compose 模板、`--force` 覆盖已有项目目录。若目标目录已有内容，先确认而不是覆盖。

## 2. SDK 与平台基线

- minSdk ≥ 24（Compose 当前默认下界；Android 4.4 / API 19 不适用）。
- compileSdk / targetSdk 取模板与当前 Play 要求的值，不要凭记忆写死；targetSdk 35+ 起 edge-to-edge 是强制行为，Android 16（API 36）起 predictive back 默认启用。
- JDK 17+。
- manifest 给包含输入框的 Activity 设置 `android:windowSoftInputMode="adjustResize"`；不要 `android:configChanges` / `screenOrientation`。

## 3. 包结构与模块拆分

默认**单 `:app` 模块 + 包边界**，不要一上来就拆一堆模块。推荐目录：

```text
app/src/main/java/<pkg>/
├── MainActivity.kt
├── ui/
│   ├── theme/{Color,Type,Shape,Theme}.kt
│   ├── navigation/
│   └── <feature>/…Screen.kt + …ViewModel.kt
└── data/
    ├── repository/
    ├── model/
    └── remote/            # 需要网络时才有
```

拆分时机：增量构建超过约 60 秒、不同模块需要不同 minSdk / 依赖、或要发布独立库时，才拆成 `app/` + `core/{design-system,database,network,common}` + `feature/<name>/`。feature 依赖 core/data，不依赖其他 feature。

## 4. 入口 Activity

`enableEdgeToEdge()` 必须在 `setContent` 之前调用；insets 只消费一次：

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()               // 必须在 super.onCreate 之前/尽早，且早于 setContent
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Scaffold { innerPadding ->
                    AppNavHost(Modifier.padding(innerPadding))
                }
            }
        }
    }
}
```

- 交互式内容用 `Modifier.safeDrawingPadding()` / `WindowInsets.safeDrawing`；不要硬编码 `statusBarsPadding()`。
- IME：优先 `fitInside(WindowInsetsRulers.Ime.current)`；用 `imePadding()` 时必须在 `verticalScroll()` 之前，且不要与 Scaffold `contentPadding` 重复叠加。

## 5. 主题骨架

建立 `ui/theme/`：

- `Color.kt`：`lightColorScheme(...)` / `darkColorScheme(...)`，M3 角色填齐，优先 `surfaceContainer*`。
- `Type.kt`：`Typography(...)`，M3 type scale，字体族显式声明。
- `Shape.kt`：`Shapes(...)` 五档。
- `Theme.kt`：`AppTheme { }` 包装，处理 `isSystemInDarkTheme()`、动态配色 opt-in、`MotionScheme.expressive()`（按稳定版 API 可用性决定）。

主题文件之外禁止裸 `Color(0xFF...)` / `.sp` / `RoundedCornerShape(<字面量>.dp)`；字符串一律 `stringResource`。

## 6. 导航与自适应

- Navigation 3：`@Serializable` 类型化路由 / key，参数只放 ID；导航图放 app 模块。
- `currentWindowAdaptiveInfo().windowSizeClass` 判断 compact/medium/expanded；用 `NavigationSuiteScaffold` 自动切换 nav bar / rail。
- 多窗格用 Navigation 3 `SceneStrategy`；不要用旧 `ListDetailPaneScaffold` / `SupportingPaneScaffold` 作为新项目基线。
- 每个 public Composable 加 `@Preview`（Light/Dark + 多尺寸）。

## 7. 测试、性能与忽略文件

- 单元 + Compose UI 测试依赖（`createComposeRule`）；按需要加 Turbine / fake repository。
- Baseline Profile / Startup Profile 模块；R8 保持最小规则。
- `.gitignore` 排除：`/build`、`.gradle`、`local.properties`、`*.jks`、`keystore.properties`。

## 8. 验收

运行项目实际支持的命令（`./gradlew build`、`assembleDebug`、`test`，或 `android build ':app@debug'`），记录命令与退出码。只报"文件已生成"不算完成。然后逐项确认：无 XML 布局 / Java / Material 2、无硬编码版本与文案、edge-to-edge 已接入、深浅色可用、测试基线存在。最后把 UI 设计交给 `ui-android-compose`，把审计交给 `audit-android-compose`。
