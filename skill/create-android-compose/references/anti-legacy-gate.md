# 适用性闸门：什么项目不适用本 skill

本 skill **只服务于全新项目**。先判断，再动手。判断错了会产出无法运行或违背规范的骨架。

## 必须同时满足

- 全新项目（不是已有仓库的维护 / 迁移 / 改造）；
- Kotlin + Jetpack Compose + Material 3 / M3 Expressive；
- minSdk ≥ 24，目标设备为手机 / 平板 / 折叠屏；
- 接受官方 Empty Activity 模板与当前工具链。

## 出现以下任一信号 → 停止并说明不适用

| 信号 | 具体表现 |
|---|---|
| 旧平台 | Android 4.4 / API 19 或任何 minSdk < 24；用户要求兼容旧设备 |
| XML 布局 | 要求 `res/layout/*.xml`、`setContentView(R.layout...)`、`<include>` / `<merge>` 布局 |
| Java | 要求 `.java` 源码、Java 互操作为主、拒绝 Kotlin |
| 旧 UI 体系 | Material 2（`androidx.compose.material`）、`findViewById`、`AsyncTask`、传统 View 体系 |
| 旧导航 | 要求旧 Navigation 组件、`ListDetailPaneScaffold` / `SupportingPaneScaffold` 作为新项目基线 |
| 手写构建 | 要求手写 Gradle、指定非 Compose 模板、固定过时 AGP/Kotlin/Compose 版本 |
| 其他形态 | Wear OS / TV / Automotive / XR 项目 |

## 响应方式

1. 直接说明"本 skill 不适用"，给出命中的具体条目。
2. 不要"部分套用"：不要在一个旧项目里只改主题、只改导航，却保留 XML/Java 外壳。
3. 如果想要迁移，说明这是另一类任务（本仓库暂无迁移 skill）；不要拿创建 skill 冒充迁移 skill。
4. 如果用户只是表述含糊（例如"以前用 Java"），先确认这是新项目还是旧项目，再决定。

## 重要边界：不是所有 XML 都禁止

禁止的是 **XML 布局**。以下 XML 资源是正常且必要的，不要误判：

- `AndroidManifest.xml`
- `res/values/strings.xml`、`themes.xml`、`colors.xml`
- `res/mipmap-*` 自适应图标、`res/drawable` 矢量图
- `gradle/libs.versions.toml`（TOML，不是 XML）

## 判断顺序

先看"是不是新项目" → 再看平台 / minSdk → 再看语言 / UI 体系 → 最后看构建方式。前面任一步失败就直接停止，不要继续收集细节。
