# android

Android（Kotlin / Gradle）工程集合。

## 项目索引

| 目录 | 一句话用途 | 文档 |
|---|---|---|
| [xueqiuCookie](xueqiuCookie/) | WebView 登录雪球后读取、复制并导出登录 Cookie 的 App | [README](xueqiuCookie/README.md) |

## 共性说明

- 构建：用 Android Studio 打开工程，或在工程目录执行 `./gradlew assembleDebug` 生成调试 APK。
- 签名：debug 签名配置在各工程 `app/build.gradle.kts`，密钥库见 `jks/` 目录及其 `readme.txt`。
- 最低 / 目标 SDK、依赖版本以各工程 `gradle/libs.versions.toml` 与 `app/build.gradle.kts` 为准。
