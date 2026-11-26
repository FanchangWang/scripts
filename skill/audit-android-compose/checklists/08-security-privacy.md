# Security and privacy (OWASP MASVS-oriented)

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** Threat model: identify assets, trust boundaries, attackers, entry points, and consequences; scope conclusions.
- [ ] **02.** Platform components: exported activities/services/receivers/providers, intent filters, permissions, URI grants and caller validation.
- [ ] **03.** Authentication/session: secure token lifecycle, logout, biometric/device credential assumptions, session timeout and account isolation.
- [ ] **04.** Cryptography: platform/library primitives, authenticated encryption, nonce/IV uniqueness, key generation/storage/rotation; no custom crypto.
- [ ] **05.** Local data: sensitive data at rest, logs, clipboard, screenshots/recents, backups, caches, temporary files.
- [ ] **06.** Network: TLS validation, cleartext, trust overrides, sensitive query parameters, certificate handling.
- [ ] **07.** WebView: JavaScript/interface exposure, file/content access, URL allowlists, SSL errors, navigation and mixed content.
- [ ] **08.** Input/deep links: untrusted extras, URI parsing, path traversal, SQL injection, command injection, deserialization and open redirects.
- [ ] **09.** Permissions: least privilege, runtime request timing, rationale, denial/permanent denial, revocation and unused permissions.
- [ ] **10.** Dependencies/supply chain: outdated/vulnerable libraries, repository trust, dependency verification/locking, malicious transitive risk.
- [ ] **11.** Release hardening: debug flags, test endpoints, debuggable, backup rules, network config, R8 rules, signing config and secrets.
- [ ] **12.** Privacy: data minimization, consent, retention/deletion, analytics identifiers, third-party SDK collection and disclosure consistency.
- [ ] **13.** Findings must describe attacker prerequisites and realistic impact; do not claim exploitability from a pattern alone.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
