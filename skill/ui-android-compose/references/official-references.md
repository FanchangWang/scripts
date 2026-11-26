# 官方与高质量参考

遇到规范争议时按此顺序裁决：**Google 官方文档 > Fluent/Material 官方规范 > 本 skill 的 anchors/规则 > 社区资料**。

## Google / Android 官方

| 主题 | URL |
|---|---|
| Agent skills 格式说明 | https://developer.android.com/tools/agents/android-skills |
| Google 官方 skills 仓库（edge-to-edge、adaptive、wear 等） | https://github.com/android/skills |
| Compose + Material 3 设计系统 | https://developer.android.com/develop/ui/compose/designsystems/material3 |
| Material 3 在 Compose 的 release notes | https://developer.android.com/jetpack/androidx/releases/compose-material3 |
| Edge-to-edge 设置 | https://developer.android.com/develop/ui/compose/system/setup-e2e |
| WindowInsets 与 insets UI | https://developer.android.com/develop/ui/compose/system/insets-ui |
| Material insets | https://developer.android.com/develop/ui/compose/system/material-insets |
| Compose 语义 / 无障碍 | https://developer.android.com/develop/ui/compose/accessibility/semantics |
| Material 3 设计首页 | https://m3.material.io |
| M3 Expressive 构建指南 | https://m3.material.io/blog/building-with-m3-expressive |
| Type scale tokens | https://m3.material.io/styles/typography/type-scale-tokens |
| 官方实战：Androidify + Compose | https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html |

## 社区（结构 / 思路参考，不作权威）

| 主题 | URL |
|---|---|
| Android agent skills 集合 | https://github.com/new-silvermoon/awesome-android-agent-skills |
| Figma → Compose 映射 | https://github.com/aldefy/compose-skill |
| Compose 性能 skills | https://github.com/skydoves/compose-performance-skills |

## 已知过时 / 避免

- `androidx.compose.material`（Material 2）：维护模式，新代码不用。
- `surfaceVariant`：尽量换成 `surfaceContainer*` 层次。
- `SOFT_INPUT_ADJUST_RESIZE`：废弃，用 manifest `adjustResize`。
- `shadow()`：改用 `dropShadow()` / `innerShadow()`。
- small / surface FAB：官方不再推荐。
- `ListDetailPaneScaffold` / `SupportingPaneScaffold`：优先 Navigation3 `SceneStrategy`。

> 网络受限时可用 `gh api` 读取 GitHub 仓库内容；`developer.android.com` / `m3.material.io` 抓取失败时以 web search 摘要为准，并在报告中注明未直接核实。
