---
name: ui-android-compose
description: Generate production-grade Android UI in Jetpack Compose + Material 3 Expressive, following the latest Google Android UI guidelines (2025-2026). Use whenever the user asks to build, design, scaffold, restyle, or improve an Android screen, component, Activity, Fragment, or any Compose interface. Two phases — theme tokens first (with approval gate), then UI — both gated by a mandatory self-audit. Anti-AI-slop.
---

# ui-android-compose — Compose UI 设计规范

生成**生产级** Jetpack Compose UI（Material 3 / Expressive、Kotlin）：把 LLM 默认"AI slop"（紫粉渐变、满屏 16.dp、三卡 Features 区、空话文案、裸 `Color(0xFF...)`）替换为**可追溯的约束**。

非协商原则：**NEVER use a buzzword in place of a token**。每个视觉选择都要能追溯到 `references/anchors.md` 的具名锚点和 `ui/theme/` 下的 token。

规范基线：`androidx.compose.material3`（Material 2 已维护期，新代码不用）、M3 Expressive（`MaterialExpressiveTheme` / `MotionScheme.expressive()`，需 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`）、edge-to-edge、predictive back。minSdk / BOM 以项目实际配置为准，不要臆造版本。

---

## 工作流（按顺序）

### Step 0 — 项目扫描（决定 bootstrap / extend）

```text
AGENTS.md / CLAUDE.md              # 项目已记录的设计规则
app/src/main/**/ui/theme/Theme.kt  # MaterialTheme 包装
.../ui/theme/Color.kt / Type.kt / Shape.kt / Spacing.kt
app/build.gradle*                  # 确认 material3 / BOM 实际版本与 minSdk
config/detekt/detekt.yml           # 静态检查配置
```

- **已有 token 文件**：读取并沿用；只复用现有 token，新增 token 前先问用户；跳到 Step 3。
- **无 token 也无设计规则**：bootstrap 模式，继续 Step 1。
- 设计规则写进项目已有的 agent 指令文件，不要擅自新建 `DESIGN_RULES.md`。

### Step 1 — 收集决策（仅 bootstrap）

用 AskUserQuestion 按顺序收集，不要问"想让它感觉如何"：

1. **锚点**：展示 `anchors.md` 的 8 套配方摘要，让用户选 1–3 套；2–3 套时确认主锚点与贡献维度（type / color / motion）。Material You 最贴合 Android，建议默认。
2. **禁用模式**：展示 `ban-patterns.md` 的 8 类禁用项，确认默认开启的 3 项。
3. **项目上下文**：内容密度（airy / balanced / dense）、主 surface（phone / tablet / foldable；Wear 等请改问其他 skill）、深色主题（M3 默认必需 / 可选 / 无）、动态配色（on / off）、品牌 seed 色（关闭动态配色时必填）、minSdk（低于 34 时给出提示但按项目实际处理）。

### Step 2 — Phase 1：主题 token（需用户确认）

按顺序生成：

1. `ui/theme/Color.kt` — `lightColorScheme(...)` / `darkColorScheme(...)`，模板 `templates/Color.kt.tmpl`。所有 M3 角色填齐，优先 `surfaceContainer*` 而非旧的 `surfaceVariant`。
2. `ui/theme/Type.kt` — `Typography(...)`，M3 type scale（display/headline/title/body/label × L/M/S）；`FontFamily` 显式声明，默认 Roboto Flex 可回退字体。模板 `templates/Type.kt.tmpl`。
3. `ui/theme/Shape.kt` — `Shapes(...)` 五档 `RoundedCornerShape`。模板 `templates/Shape.kt.tmpl`。
4. `ui/theme/Spacing.kt` — `Spacing` data class + `LocalSpacing` CompositionLocal，模板 `templates/Spacing.kt.tmpl`。统一尺度见下方"规范尺度"。
5. `ui/theme/Theme.kt` — `AppTheme { }` 包装，处理 `isSystemInDarkTheme()`、动态配色 opt-in、`MotionScheme.expressive()`。参考 `references/material3-essentials.md`。
6. `config/detekt/detekt.yml` — 用 `templates/detekt-design.yml.tmpl` 的规则思路接入项目现有 detekt，任务名统一为 `detektDesign` 并挂到 `check`，具体版本按项目。
7. 设计决策写进项目 agent 指令文件：锚点、禁用项、token 路径、项目级选择、验证命令。模板 `templates/design-rules.md.tmpl` 只作内容大纲，不直接生成独立文件。

然后运行 `verification.md` 的 **Token Audit**，输出 PASS/FAIL 表。**用户确认前不得进入 Phase 2**。

### Step 3 — Phase 2：UI

只用主题 token 写界面：

- **结构分解**：按 `references/structure-and-modifiers.md` 判根布局、语义命名、优先用 M3 组件，不手搓。
- `references/component-anatomy.md` — Button / TextField / Card / ListItem / TopAppBar / NavigationBar / FAB / ModalBottomSheet / AlertDialog / Snackbar / 空/加载/错误态。
- `references/motion.md` — M3 motion token、spring 区分 spatial 与 effects、reduced motion。
- `references/copy-voice.md` — 真实文案、sentence case、无占位符。
- `references/icons-imagery.md` — 单一家族 Material Symbols、Coil `AsyncImage`、TalkBack。
- `references/edge-to-edge.md` — `enableEdgeToEdge()`、insets 只用一种方式、`imePadding()` 在 `verticalScroll()` 之前。
- `references/navigation-adaptive.md` — 类型安全路由、返回栈、`WindowSizeClass`、`NavigationSuiteScaffold`。

然后运行 **UI Audit** + 项目实际静态检查（如 `./gradlew detekt lint`），修复所有 FAIL 才能说完成。

---

## 规范尺度（冲突时以本节为准）

颜色只走 `MaterialTheme.colorScheme.*`（`Color(0xFF...)` 仅允许在 `Color.kt`）；字体只走 `MaterialTheme.typography.*`（`.sp` 仅允许在 `Type.kt`）；圆角只走 `MaterialTheme.shapes.*`；间距只走 `MaterialTheme.spacing.*`（xs=4, sm=8, md=16, lg=24, xl=32, xxl=48）；触控目标 ≥48dp。`references/` 中迁移来的旧示例可能带硬编码数值，**仅表意、禁止照搬**，按本节换算或先扩充 `Spacing.kt`。

## Modifier 与预览

固定顺序、spacedBy、RTL、预览矩阵见 `references/structure-and-modifiers.md`。每个 public Composable 要有 `@Preview`（Light/Dark + Phone/Foldable/Tablet/Desktop）。

## 硬规则

1. 非主题文件禁止裸 `\d+.dp` / `\d+.sp` / `Color(0x...)`；没有对应 token 就先问用户扩充主题。
2. 代码与注释禁止 buzzword（用户明确要求的文案除外）。
3. 禁止紫→粉 / 靛→紫渐变、卡片 `.blur()` 装饰（除非锚点明确指定且只用于 scrim）、未声明的 `FontFamily("Inter")`。
4. 禁止占位文案：lorem ipsum、"Get Started"、"Learn More"、"Powered by AI"、"Beautiful and intuitive"。
5. 交互目标 ≥ 48dp；edge-to-edge 常开；predictive back 可用时支持。
6. 深色 parity：`isSystemInDarkTheme()` + 两套完整 colorScheme + WCAG AA 对比。
7. TalkBack：icon-only `IconButton` 必须有 `contentDescription`；装饰图标用 `null`。
8. 每个异步界面有 empty / loading / error 三态。
9. Material 2、废弃 API（`SOFT_INPUT_ADJUST_RESIZE`、裸 `shadow()`、`rememberInfiniteTransition` 装饰动画）一律不用。
10. 必须跑 `verification.md` 的两张审计表并打印结果。

## 文件索引

| 文件 | 何时读 |
|---|---|
| `references/anchors.md` | Step 1：8 套锚点，必须完整读，不要凭记忆引用 |
| `references/ban-patterns.md` | Step 1 展示禁用项；Step 2/3 验证 |
| `references/structure-and-modifiers.md` | Step 3：根布局、Modifier 顺序、预览矩阵 |
| `references/component-anatomy.md` | Step 3：写任何 composable 之前 |
| `references/motion.md` | Step 3：任何过渡 / 动画 |
| `references/copy-voice.md` | Step 3：任何用户可见字符串 |
| `references/icons-imagery.md` | Step 3：图标 / 图片 |
| `references/edge-to-edge.md` | Step 3：Activity 包装、insets、IME |
| `references/navigation-adaptive.md` | Step 3：导航 / 多窗格 / 自适应布局 |
| `references/compose-components.md` | Step 3：组件目录（代码仅表意，须 token 化） |
| `references/material3-essentials.md` | Step 2/3：M3 Expressive、主题、CompositionLocal |
| `references/verification.md` | Step 2 后 Token Audit、Step 3 后 UI Audit（强制） |
| `references/official-references.md` | 需要查官方规范时 |
| `templates/*` | 生成主题文件时填占位符，不要原样交付 |
