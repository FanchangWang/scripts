# 审计阶段详解

来自 SKILL.md 的分阶段细则。按模式选择阶段；每个阶段的产出与判据见 SKILL.md。

## 阶段

### Phase 0 — 边界与基线
确认仓库根与分支 / worktree 状态；读 `settings.gradle(.kts)`、各模块构建文件、version catalog、convention plugins、Gradle wrapper、manifest、CI；识别模块、source set、flavor、build type、min/target/compile SDK、Kotlin/AGP/Compose 版本、DI/导航/数据库/网络库；排除生成 / vendor / 构建产物；识别已有测试、lint/static analysis、baseline profile、benchmark、崩溃上报与安全工具；记录**可用且安全**的命令（不要假设某个 Gradle task 存在）；记录基线 build/test/lint 结果。产出 `PROJECT_INVENTORY.md`（用 `templates/project-inventory.md`）。

### Phase 1 — 覆盖地图
建立文件清单：模块与 source set、相对路径、角色（UI / state holder / domain / repository / data source / model / DI / platform / test / build）、相关域、状态（未开始 / 进行中 / 已审 / 阻塞 / 不适用）、备注与 finding ID。覆盖 Kotlin/Java、XML 资源、manifest、Gradle/build logic、ProGuard/R8、network security config、数据库 schema/迁移、测试代码。大仓库按确定性分批并持久化进度；上下文 / 工具受限导致覆盖不全时不得声称完整。

### Phase 2 — 确定性检查
先检查项目实际配置了什么工具，再运行安全的任务，例如 assemble / lint / test / Detekt / KtLint / `check`。不要编造 task 名，也不要把跳过、不可用、超时或执行前就失败的 task 说成通过。记录精确命令、退出码与精简输出。静态检查干净不能替代人工审查。

### Phase 3 — 逐域审计
依次读 `checklists/01..12-*.md`：Kotlin 正确性、Compose UI/state、生命周期与 effect、协程与并发、架构/模块/DI、数据持久化、网络/API、安全隐私、性能内存、无障碍与自适应 UI、测试/构建/发布、可观测性与依赖。每域：声明范围与筛选标准 → 审查映射文件及调用者 / 测试 → 搜模式后看实现 → 关键链路至少跟一条完整路径 → 立即记录 finding → 标记每个检查项 PASS / FINDING / NEEDS-VALIDATION / N/A + 证据。发现一个问题不要停，继续做完当前域和其余适用域。

按最新 Google 规范额外关注：Material 2 残留（`androidx.compose.material`）、`surfaceVariant` 未换 `surfaceContainer*`、废弃 API（`SOFT_INPUT_ADJUST_RESIZE`、`shadow()`、small/surface FAB、旧 pane scaffold）、edge-to-edge / inset 双重 padding、`rememberInfiniteTransition` 装饰动画、硬编码 dp/颜色、不完整的深浅色 parity。判定前先核实项目版本。

### Phase 4 — 横切链路审计
独立追踪：启动与依赖初始化、认证 / 会话生命周期、主要用户旅程、导航与返回栈、状态恢复与进程死亡、网络请求 / 取消 / 重试 / 缓存、持久化写入 / 迁移 / 并发、后台任务与通知、权限与外部 intent、文件 / 媒体 / WebView / provider 边界。每条链路记录入口、状态持有者、数据路径、生命周期持有者、失败路径与测试。

### Phase 5 — finding 分级与报告
用 `templates/finding.md`。严重度：Critical（可利用安全问题、严重数据丢失 / 损坏、大范围沦陷）、High（重大用户可见失败、权限 / 数据暴露、常见路径崩溃/ANR）、Medium（有限但真实的功能 / 生命周期 / 可靠性 / 隐私 / 性能缺陷）、Low（影响小、触发窄）、Info（改进建议，不计入缺陷）。置信度：Confirmed（代码 / 测试直接证明）、Probable（证据强但运行时未完全验证）、Needs validation（需设备 / 后端 / 生产配置 / profiling）。按根因去重，不虚增严重度。

### Phase 6 — 修复计划（仅在用户要求时）
只读模式不编辑。若要求修复：确认用户批准的范围 → 按优先级一次修一个根因 → 改动最小且保持行为 → 补充 / 更新"修前失败、修后通过"的测试 → 先跑针对性测试再跑完整验证 → 审查 diff 中的无关改动、API/行为变化、秘密和生成文件噪音 → 用 commit/diff 与测试结果更新 finding 状态。代码改动不等于修复：只有行为被验证才是 `VERIFIED_FIXED`，否则用 `FIXED_UNVERIFIED`。

### Phase 7 — 独立回归复查
像审别人的补丁一样重开改动代码，不依赖之前的解释。复查原始失败条件、所有调用者 / 共享状态、生命周期与取消、错误与边界路径、测试与静态分析、相邻屏幕/模块的副作用、补丁引入的新缺陷。全审计还要对覆盖矩阵中所有未审 / 阻塞项做最后一轮，不能在范围内仍有未审源码时宣布完成。
