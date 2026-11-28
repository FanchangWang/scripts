# create-android-compose

新建 Android 项目的 skill（给 AI agent 用）。只负责从零创建符合 Google 当前推荐技术栈的 Compose 项目：Kotlin、Jetpack Compose、Material 3 Expressive、Navigation 3、Kotlin DSL + version catalog、edge-to-edge。**不适用于旧项目**（Android 4.4 / XML 布局 / Java / Material 2 / 迁移改造）。

日常 UI 设计交接给 `ui-android-compose`；代码审计交接给 `audit-android-compose`。

## 结构

```text
SKILL.md                                  入口：适用性闸门、创建流程、硬规则、交接
references/anti-legacy-gate.md             什么情况不适用、如何拒绝、XML 资源边界
references/project-layout-and-entry.md     模板、SDK、包结构、入口、主题、导航、测试
references/build-and-dependencies.md       Kotlin DSL、version catalog、convention plugin、依赖选型
```

## 发布

```powershell
pwsh.exe -NoProfile -File ..\publish.ps1 -Skill create-android-compose
```
