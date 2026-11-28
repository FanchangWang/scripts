# ui-android-compose

Android Compose UI 设计 skill（给 AI agent 用）。遵循 Google 最新 Material 3 / Material 3 Expressive 规范，两阶段流程（主题 token → UI）+ 强制自检，抑制 AI slop 审美。

由旧 `ui-android`（流程骨架、锚点、禁用模式、组件/动效/文案/图标/验证规则）与 `mobile-android-design`（导航、自适应布局、组件目录、主题概念）合并整理；迁移示例中的硬编码数值一律以 `SKILL.md` 的"规范尺度"为准。

## 结构

```text
SKILL.md                              入口：触发条件、工作流、硬规则、规范尺度
references/anchors.md                 8 套设计锚点配方
references/ban-patterns.md            8 类禁用模式与检测式
references/component-anatomy.md       逐组件尺寸/状态规则
references/motion.md                  M3 motion token、spring、reduced motion
references/copy-voice.md              真实文案、sentence case、本地化
references/icons-imagery.md           Material Symbols 单一家族、图片、TalkBack
references/structure-and-modifiers.md 根布局、Modifier 顺序、状态/Kotlin 配套约束、预览
references/verification.md            Token Audit / UI Audit（强制）
references/edge-to-edge.md            Activity、insets、IME（Google 官方规范）
references/navigation-adaptive.md     类型安全路由、返回栈、WindowSizeClass
references/compose-components.md      组件目录（代码仅表意）
references/material3-essentials.md    M3 Expressive、主题、CompositionLocal
references/official-references.md     官方链接与过时 API 清单
templates/Color.kt.tmpl / Type.kt.tmpl / Shape.kt.tmpl / Spacing.kt.tmpl
templates/detekt-design.yml.tmpl      反 slop 静态检查思路
templates/design-rules.md.tmpl        写入项目 agent 指令文件的大纲
```

## 发布

```powershell
pwsh.exe -NoProfile -File ..\publish.ps1 -Skill ui-android-compose
```
