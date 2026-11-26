# Kotlin correctness and code quality

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** Nullability: platform types, `!!`, unsafe casts, nullable API boundaries, default values masking missing data.
- [ ] **02.** Exceptions: swallowed exceptions, overly broad catches, incorrect exception translation, missing cancellation rethrow/propagation.
- [ ] **03.** Resource ownership: Closeable/use, streams, cursors, file handles, sockets, listeners, receivers, callbacks, and cleanup on every exit path.
- [ ] **04.** Collections and mutability: shared mutable collections, unsafe casts, mutation during iteration, defensive copies at API boundaries.
- [ ] **05.** Concurrency safety: shared mutable state, atomicity, synchronization, thread confinement, visibility, race conditions.
- [ ] **06.** API contracts: parameter validation, return invariants, sealed result modeling, exhaustive `when`, impossible states.
- [ ] **07.** Numeric/time handling: overflow, rounding, timezone/locale assumptions, wall clock vs monotonic clock, date parsing.
- [ ] **08.** Serialization: schema evolution, defaults, unknown enum values, malformed input, backward compatibility.
- [ ] **09.** Code complexity: deeply nested logic, duplicated business rules, hidden side effects, excessive coupling; report as Info unless it causes a defect.
- [ ] **10.** Logging: secrets, tokens, identifiers, personal data, overly verbose production logs.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
