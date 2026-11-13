# xueqiuCookie

Android 应用：内嵌 WebView 登录雪球，读取登录 Cookie（`xq_a_token`、`u`）并复制 / 推送到后端，附带雪球行情 JSON 拉取与本地保存。

## 环境要求

- Android Studio，Gradle（工程自带 `gradlew` / Wrapper）。
- JDK 11（`compileOptions` 配置 Java 11）。
- `minSdk 24`，`targetSdk / compileSdk 36`；包名 `com.xueqiu.cookie`。

## 快速开始

1. Android Studio 打开 `android/xueqiuCookie/` 工程，或命令行执行：

```bash
./gradlew assembleDebug
```

2. 安装到手机 / 模拟器后打开「雪球Cookie」。

## 用法 / 参数

界面按钮与对应行为：

| 按钮 | 行为 |
|---|---|
| 登录雪球 | 弹出内嵌 WebView 加载 `https://xueqiu.com/`（桌面 UA），完成登录后关闭对话框 |
| 获取 Cookie | 从 `CookieManager` 解析 `xq_a_token`、`u` 填入输入框 |
| 复制 Cookie | 整体复制为 `XUEQIU_COOKIE="xq_a_token=...; u=..."` 到剪贴板 |
| 复制 `xq_a_token` / 复制 `u` | 分别复制单个字段 |
| Cookie API test | 向 API 域名 `POST /api/cookie/test`，用 Cookie 校验有效性并返回日期 / 保存状态 |
| Cookie API save | 向 API 域名 `PUT /api/cookie`，把 Cookie 保存到服务器 |
| 获取 JSON 数据 | 带 Cookie 请求雪球当日分钟线，保存到 `Download/xueqiuCookie/<日期>.json`，并刷新最近文件列表 |

- API 域名在输入框填写，默认值见 `res/values/strings.xml`；尾部斜杠会自动去除。
- 行情股票代码 `SH603533` 在 `fetchAndSaveJson` 中硬编码（**待核对**，如需更换请改源码）。

## 功能 / 行为说明

- WebView 开启 JavaScript 与 DOM Storage；Cookie 变更后先 `flush()` 再读取。
- API 请求在子线程执行，结果回主线程弹窗提示；无 Cookie 时先提示「请先获取 Cookie」。
- 保存 JSON 适配 Android Q 以上的 MediaStore 下载目录；低版本申请外部存储写入权限。

## 开发

- 源码：`app/src/main/java/com/xueqiu/cookie/MainActivity.kt`；布局 `app/src/main/res/layout/activity_main.xml`。
- 签名：debug 签名配置在 `app/build.gradle.kts`，密钥库 `jks/xueqiu_key.jks`，别名 / 密码见 `jks/readme.txt`。
- 依赖版本集中在 `gradle/libs.versions.toml`。
