# Networking and API integration

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** TLS: HTTPS by default; no permissive trust manager/hostname verifier; cleartext policy justified and narrowly scoped.
- [ ] **02.** Authentication: token storage, refresh races, expiry, logout invalidation, account switching and revocation.
- [ ] **03.** Authorization: server enforces access; client-side hiding is not treated as authorization.
- [ ] **04.** Input/output: validate untrusted responses and user input; handle empty, malformed, oversized and unexpected payloads.
- [ ] **05.** Timeouts: connect/read/write/call timeouts appropriate; cancellation propagates.
- [ ] **06.** Retry: bounded exponential backoff with jitter where appropriate; retry only safe/idempotent operations; respect Retry-After.
- [ ] **07.** Error mapping: HTTP status and domain errors mapped consistently; no leaking server internals to UI.
- [ ] **08.** Concurrency: duplicate requests, request coalescing, stale responses, pagination races and rate limits.
- [ ] **09.** Logging/telemetry: redact headers, tokens, request bodies and personal data; avoid URL query secrets.
- [ ] **10.** Network security config, certificate pinning (if used), proxy/debug trust overrides, and release/debug separation reviewed.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
