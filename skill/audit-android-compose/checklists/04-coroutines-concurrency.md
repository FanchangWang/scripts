# Coroutines, Flow, and concurrency

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** Structured concurrency: child jobs belong to intended scope; no detached GlobalScope; jobs cancelled with owner.
- [ ] **02.** Dispatcher correctness: blocking IO not on Main; CPU work appropriately dispatched; no redundant dispatcher switches.
- [ ] **03.** Cancellation: CancellationException is not swallowed; finally/NonCancellable used narrowly; cancellation reaches network/database work.
- [ ] **04.** Flow: cold/hot semantics understood; `stateIn`/`shareIn` scope and SharingStarted policy appropriate; no accidental duplicate upstream work.
- [ ] **05.** Flow operators: `flowOn`, `buffer`, `conflate`, `collectLatest`, `flatMapLatest`, debounce and retry semantics correct for use case.
- [ ] **06.** Race conditions: rapid taps, concurrent refreshes, stale response overwrites, duplicate writes, mutex/atomic strategy.
- [ ] **07.** Error handling: errors modeled and surfaced; retry bounded/backoff; cancellation distinguished from failure.
- [ ] **08.** Deadlocks/starvation: blocking calls, locks around suspension, limited dispatcher misuse, synchronous waits.
- [ ] **09.** Scope lifetime: viewModelScope, lifecycleScope, application scope, worker scope chosen deliberately.
- [ ] **10.** Tests: deterministic coroutine tests, injected dispatchers/clocks, no real delays where avoidable.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
