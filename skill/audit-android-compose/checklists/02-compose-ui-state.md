# Jetpack Compose UI and state

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** State ownership: state hoisted to the lowest common owner; UI state immutable where practical; single source of truth.
- [ ] **02.** State reads/writes: no backwards writes during composition; no side effects directly in composable bodies.
- [ ] **03.** `remember` / `rememberSaveable`: correct keys, saveability, stale captures, over-retention, and process recreation expectations.
- [ ] **04.** Effect APIs: `LaunchedEffect` keys and cancellation; `DisposableEffect` cleanup; `SideEffect` use; `rememberCoroutineScope` lifetime.
- [ ] **05.** Lifecycle-aware collection: `collectAsStateWithLifecycle` or justified alternative; avoid collecting UI flows beyond screen lifecycle.
- [ ] **06.** Events: one-off events vs durable state; duplicate event delivery; event loss during configuration/process recreation.
- [ ] **07.** Stability and recomposition: inspect expensive work in composition, unstable parameters, mutable collections, lambda captures; do not equate every recomposition with a defect.
- [ ] **08.** State restoration: rotation, process death, navigation back stack, saved state size and sensitive state.
- [ ] **09.** Composable contracts: stateless UI where appropriate, previews/testability, stable keys, clear state/event APIs.
- [ ] **10.** CompositionLocal: appropriate scope, hidden dependencies, default values, and unintended global coupling.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
