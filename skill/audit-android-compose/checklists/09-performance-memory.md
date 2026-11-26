# Performance, startup, memory, and battery

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** Main thread: disk/network/blocking work, expensive parsing, large allocations, lock contention and long synchronous initialization.
- [ ] **02.** Compose phases: identify expensive composition/layout/draw work; state read phase; defer rapidly changing reads where useful.
- [ ] **03.** Recomposition: establish whether work is costly; stable keys/contentType for lazy lists; avoid repeated expensive calculations.
- [ ] **04.** Lazy layouts: stable unique keys, contentType, paging/backpressure, item size, nested scrolling and large list behavior.
- [ ] **05.** Images: appropriate sizing/caching, decode off main, avoid unbounded bitmap retention, lifecycle-aware loading.
- [ ] **06.** Memory: retained Activity/View/Context, listeners, callbacks, coroutine jobs, caches, bitmaps, WebView and static references.
- [ ] **07.** Startup: eager initialization, dependency graph, disk IO, content providers, baseline/startup profiles where justified.
- [ ] **08.** Battery/background: unnecessary polling, wake locks, frequent jobs, location/sensor use, foreground service justification.
- [ ] **09.** ANR/crash: binder/main-thread work, synchronous IPC, lock contention, input handling, StrictMode evidence.
- [ ] **10.** Measure before optimizing: baseline, repeatable device/build, traces/benchmark, before-after comparison. Static suspicion is not a measured regression.
- [ ] **11.** Release behavior: evaluate optimized release/R8 build; debug performance is not representative.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
