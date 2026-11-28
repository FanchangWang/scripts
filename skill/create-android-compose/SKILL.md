---
name: create-android-compose
description: Create a brand-new Android project with Google's recommended modern stack — Kotlin, Jetpack Compose, Material 3 Expressive, Navigation 3, Kotlin DSL + version catalogs, edge-to-edge. Use ONLY for greenfield projects; not for maintaining legacy projects (Android 4.4 / XML layouts / Java / Material 2).
---

# create-android-compose — 新建 Android Compose 项目

只负责**从零创建一个新 Android 项目**，并把项目骨架落到 Google 当前推荐技术栈上。不负责日常 UI 设计、不负责审计、不负责改造旧项目。

## 适用性闸门（先判断，不满足就停下）

在动手前确认这是**全新项目**且满足：

- 语言 Kotlin；UI 用 Jetpack Compose；设计系统 Material 3 / M3 Expressive。
- minSdk ≥ 24；目标为手机 / 平板 / 折叠屏（Wear / TV / Automotive 不适用）。
- 允许使用官方 Empty Activity 模板与当前版本工具链。

出现以下任一情况，**明确告知用户本 skill 不适用并停止**，不要试图套用：

- 旧项目维护 / 迁移（尤其是 Android 4.4 / API 19 等低版本）；
- 要求 XML 布局、Java 源码、Material 2、`findViewById`、`AsyncTask`；
- 要求手写 Gradle 配置或指定非 Compose 模板。

详见 `references/anti-legacy-gate.md`。

## 工作流

### Step 0 — 收集最小输入

只问必要信息：应用名、`applicationId` / `namespace`、minSdk、目标设备形态、是否需要网络 / 数据库 / DI、品牌 seed 色（无则 M3 默认）。不要问"想要什么风格"这类空问题。

### Step 1 — 从官方模板起手

用 Android CLI 或 Android Studio 的 **Empty Activity (Compose)** 模板生成项目，不要手写 Gradle：`android create --list` 查看模板，`android create empty-activity --name=... --application-id=... --namespace=... --min-sdk=... --output=...`。模板/CLI 才是事实来源；不要在脚本里硬编码一串当前版本号。详见 `references/project-layout-and-entry.md`。

### Step 2 — 构建骨架

- Kotlin DSL：`settings.gradle.kts` / `build.gradle.kts` / `app/build.gradle.kts`；
- `gradle/libs.versions.toml` 作为依赖版本的唯一来源；
- `build-logic` included build 放 convention plugin 共享构建逻辑；
- KSP，不用 kapt；关闭已废弃插件；
- 版本按 CLI / 模板 / 官方 release notes 解析，禁止 `+` 或区间版本，禁止在模块文件里写死版本。

详见 `references/build-and-dependencies.md`。

### Step 3 — 依赖选型

Compose BOM + `androidx.compose.material3:material3` + `material3-window-size-class` + Navigation 3 + lifecycle/runtime-compose；按需要加 Room 3、Hilt、Coil。Material 2、旧 Navigation 组件、已废弃 API 一律不选。

### Step 4 — SDK 与平台基线

minSdk ≥ 24；compileSdk / targetSdk 按当前 Play 要求与官方模板取值（Android 16 / API 36 起 edge-to-edge 与 predictive back 为默认行为）；JDK 17+；manifest 设置 `android:windowSoftInputMode="adjustResize"`。

### Step 5 — 入口与主题骨架

`ComponentActivity`（或 `MainActivity`）在 `setContent` **之前**调 `enableEdgeToEdge()`；`setContent { AppTheme { Scaffold { ... } } }`，insets 只消费一次（Scaffold `innerPadding` 或 `consumeWindowInsets`），不要双重 padding。

建立 `ui/theme/{Color,Type,Shape,Theme}.kt`：M3 完整 colorScheme、`Typography`、`Shapes`，深浅色都填齐，动态配色 opt-in，不出现裸 `Color(0xFF...)` 在主题文件之外。

### Step 6 — 包结构与导航

默认单 `:app` 模块 + 包边界；仅当构建时间、minSdk 差异或发布库需求明确时才拆 `core/*`、`feature/*`。推荐 `ui/theme`、`ui/navigation`、`ui/<feature>`、`data/{repository,model,remote}`。导航用 Navigation 3 的 `@Serializable` 类型化路由；自适应用 `currentWindowAdaptiveInfo().windowSizeClass` + `NavigationSuiteScaffold`，多窗格用 `SceneStrategy`（不是旧 pane scaffold）。不要 `configChanges` / `screenOrientation`。

### Step 7 — 测试与发布基线

单元 + Compose UI 测试依赖与示例；Baseline Profile / Startup Profile；R8 与 `.gitignore`（排除 `/build`、`.gradle`、`local.properties`、`*.jks`、`keystore.properties`）。

### Step 8 — 验收与交接

运行项目实际可用的命令（`./gradlew build` / `assembleDebug` / `test` 或 `android build`），报告执行结果；逐项确认：无 XML 布局、无 Java 源码、无 Material 2、无硬编码版本 / 颜色 / 文案、有 edge-to-edge、深浅色可用、有测试基线。

然后交接：**UI 设计 → `ui-android-compose`**；**代码审计 → `audit-android-compose`**。本 skill 不负责这两件事。

## 硬规则

1. 不手写 Gradle；只用官方模板 / CLI 生成，再按项目需要增量修改。
2. 不生成 XML 布局、Java 源码、Material 2 依赖或已废弃 API。
3. 版本来自模板 / catalog / 官方 release notes，不臆造、不用 `+` 或区间。
4. 每个 Activity 调 `enableEdgeToEdge()`；insets 只消费一次。
5. minSdk ≥ 24；低于此值或明确要求旧平台时停止，不勉强适配。
6. 主题只用 `MaterialTheme.*`；颜色 / 字体 / 圆角 / 间距都走 token。
7. 字符串用 `stringResource`，不要硬编码文案。
8. 创建完成必须跑一次构建 / 测试验证，不能只报"文件已生成"。

## 文件索引

| 文件 | 何时读 |
|---|---|
| `references/anti-legacy-gate.md` | Step 0 之前：判断是否适用、如何拒绝旧项目 |
| `references/project-layout-and-entry.md` | Step 1、4–7：模板、SDK、入口、主题、包结构、导航、测试 |
| `references/build-and-dependencies.md` | Step 2–3：Gradle DSL、catalog、convention plugin、依赖选型 |

## 官方参考

- Android skills 概览：https://developer.android.com/tools/agents/android-skills
- `android create`：https://developer.android.com/tools/agents/android-cli/commands/create
- Compose 设置 / Empty Activity：https://developer.android.com/develop/ui/compose/setup
- 架构建议：https://developer.android.com/topic/architecture/recommendations
- Material 3：https://developer.android.com/develop/ui/compose/designsystems/material3
- Navigation 3：https://developer.android.com/guide/navigation/navigation-3
- Edge-to-edge：https://developer.android.com/develop/ui/compose/system/insets-ui
