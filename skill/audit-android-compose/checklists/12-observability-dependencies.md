# Observability, dependencies, and operations

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** Crash reporting: initialization, PII scrubbing, sampling, release mapping/symbols and consent.
- [ ] **02.** Logging: structured levels, redaction, no secrets, no sensitive payloads, production verbosity controlled.
- [ ] **03.** Analytics: event correctness, duplicate events, consent, data minimization and SDK behavior.
- [ ] **04.** Dependency inventory: direct/transitive versions, unused dependencies, duplicate libraries, licenses and known advisories.
- [ ] **05.** Dependency management: version catalog consistency, repositories restricted appropriately, dependency locking/verification where practical.
- [ ] **06.** Background operations: WorkManager constraints, unique work policy, retry/backoff, cancellation and idempotency.
- [ ] **07.** Notifications: runtime permission, channels, deep-link safety, sensitive lock-screen content and user controls.
- [ ] **08.** Operational failure: offline, server outage, expired credentials, clock skew, storage full and process restart behavior.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
