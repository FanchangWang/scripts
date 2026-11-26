# Lifecycle, navigation, and effects

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** Activity/Fragment lifecycle: callbacks, configuration changes, finishing vs recreation, multi-window assumptions.
- [ ] **02.** ViewModel scope: correct owner, navigation graph scoping, accidental activity/context retention, cleanup expectations.
- [ ] **03.** Composable lifecycle: effects restart only when intended; leaving composition cancels work; disposal releases registrations.
- [ ] **04.** Navigation: typed/validated arguments, deep links, authentication gates, back-stack correctness, duplicate destinations.
- [ ] **05.** Back handling: predictive back compatibility where applicable, nested handlers, modal/dialog priority.
- [ ] **06.** State across navigation: shared state owner, stale screen state, result passing, saved-state restoration.
- [ ] **07.** External activities/intents: resolve failures, validate returned data, URI permission flags, exported component boundaries.
- [ ] **08.** Configuration: locale, theme, font scale, screen size, orientation, process recreation.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
