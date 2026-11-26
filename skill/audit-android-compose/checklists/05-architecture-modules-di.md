# Architecture, modules, and dependency injection

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** Layer boundaries: UI/domain/data responsibilities and dependency direction match the project's chosen architecture.
- [ ] **02.** Business rules: not duplicated across UI, ViewModel, use case, and repository; no domain logic hidden in composables.
- [ ] **03.** Repository contracts: abstractions meaningful; source-of-truth and cache/network precedence explicit.
- [ ] **04.** Dependency graph: no cycles, service locator leakage, hidden global mutable singletons, or unnecessary module coupling.
- [ ] **05.** DI scopes: singleton/activity/retained/viewModel scopes correct; no scoped object escaping its lifetime.
- [ ] **06.** Construction: expensive dependencies not repeatedly created; initialization order safe; startup work bounded.
- [ ] **07.** Module API: implementation details not unnecessarily exposed; public API changes and Gradle dependency direction reviewed.
- [ ] **08.** Build variants: debug/release/flavor configuration parity, BuildConfig secrets, manifest placeholders and resource overlays.
- [ ] **09.** Architecture is not dogma: do not flag lack of Clean Architecture/MVVM/Hilt by itself; evaluate actual maintainability and defects.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
