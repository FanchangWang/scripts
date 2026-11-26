# Testing, build, compatibility, and release

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** Unit tests: business rules, ViewModels/Flows, repositories/data sources, edge and failure cases.
- [ ] **02.** UI tests: critical user journeys, navigation/back behavior, loading/error/empty/success states, semantics-based selectors.
- [ ] **03.** Integration tests: database migrations, serialization, API contract, DI graph and WorkManager where applicable.
- [ ] **04.** Regression tests: each confirmed defect has a test or a documented reason a test is infeasible.
- [ ] **05.** Test quality: deterministic fakes, injected clock/dispatcher, no flaky sleeps, isolated data and cleanup.
- [ ] **06.** Coverage: assess risk-weighted critical paths, not line coverage alone; identify untested high-risk paths.
- [ ] **07.** Build: all intended variants/flavors compile; manifest/resource merge; minified release build where safe.
- [ ] **08.** Compatibility: minSdk, target SDK behavior changes, permissions, edge-to-edge, background limits, notification changes and API-specific branches.
- [ ] **09.** Release config: debuggable false, endpoints, logging, signing secrets externalized, R8/ProGuard correctness.
- [ ] **10.** CI: repeatable lint/test/build; dependency caching does not hide stale artifacts; failures visible.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
