# 构建骨架与依赖选型

## 总原则

**先跑模板 / CLI，再增量修改。** 不要在 skill 里硬编码一串"当前版本号"当作事实；版本以模板生成结果、`libs.versions.toml`、官方 release notes 为准。禁止 `+` 和区间版本，禁止在模块 build 文件里重复写版本。

## 文件形态

- `settings.gradle.kts`、`build.gradle.kts`、`app/build.gradle.kts`：Kotlin DSL（`.kts`），不用 Groovy DSL。
- `gradle/libs.versions.toml`：所有依赖与插件版本的唯一来源。
- `build-logic/`（included build）：convention plugin 共享构建逻辑，`includeBuild("build-logic")`。

```toml
# gradle/libs.versions.toml —— 结构示意，值由模板 / 当前版本决定
[versions]
agp = "<由模板/CLI 解析>"
kotlin = "<由模板/CLI 解析>"
ksp = "<由模板/CLI 解析>"
compose-bom = "<由模板/CLI 解析>"

[libraries]
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material3-window-size-class = { module = "androidx.compose.material3:material3-window-size-class" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

## 插件与工具链

- Compose 编译器用 `org.jetbrains.kotlin.plugin.compose`；Kotlin 2.0+ 不需要 `kotlinCompilerExtensionVersion`。
- 用 **KSP**，不用 kapt。AGP 9.x 内置 Kotlin 支持时，不要额外套用过时的 `org.jetbrains.kotlin.android` 写法——以模板生成结果为准。
- 启用配置缓存 / 构建缓存（若模板支持）；不要引入已废弃插件（如旧 screenshot 插件）。
- 升级 / 校验用 `./gradlew help`、`./gradlew build --dry-run`、`./gradlew build`，以实际构建结果为准，而不是只看 CLI 退出码。

## 依赖选型

**必选**：Compose BOM（用 BOM 管理 compose 子库版本，不要逐个写版本）、`androidx.compose.material3:material3`、`material3-window-size-class`、`androidx.lifecycle:lifecycle-runtime-compose`、Navigation 3、activity-compose。

**按需**：Room 3（数据库）、Hilt（DI）、Coil（图片）、`material3-adaptive-navigation-suite`、`material3-adaptive`。

**禁止选择**：
- `androidx.compose.material`（Material 2 已废弃且不再维护）；
- Java 互操作主导的库组合；
- 旧 Navigation 组件 / 旧 pane scaffold 作为新项目基线；
- 任何要求 XML 布局的依赖或模板；
- 版本未定、`+` 或区间依赖。

## 验证清单

- [ ] 所有 build 文件是 `.kts`；
- [ ] 版本只出现在 `libs.versions.toml`；
- [ ] 没有 `kapt`、Material 2、硬编码版本号；
- [ ] 依赖由 BOM 管理 compose 版本；
- [ ] `./gradlew help` 与 `./gradlew build` 实际通过（或记录具体失败原因）。
