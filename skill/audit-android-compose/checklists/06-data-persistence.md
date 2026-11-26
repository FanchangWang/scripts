# Data layer, persistence, and caching

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** Room/SQLite: transaction boundaries, indexes, query correctness, main-thread access, schema export and migration coverage.
- [ ] **02.** DataStore/preferences: atomic updates, corruption handling, migrations, key naming, default values.
- [ ] **03.** Migration: upgrade from every supported schema version; destructive fallback only when explicitly acceptable; data preservation.
- [ ] **04.** Cache consistency: invalidation, TTL, stale-while-revalidate policy, concurrent refresh, source-of-truth behavior.
- [ ] **05.** Serialization: malformed/old data, unknown fields/enums, nullable fields, version compatibility.
- [ ] **06.** Data integrity: partial writes, transaction rollback, idempotency, duplicate records, ordering and pagination.
- [ ] **07.** Sensitive data: encryption appropriate to threat model; keys in Android Keystore where appropriate; no secrets in plain preferences/files.
- [ ] **08.** Backup: Auto Backup / data extraction rules do not unintentionally export sensitive data.
- [ ] **09.** File storage: scoped storage, canonical paths, traversal, MIME/content validation, URI grants and cleanup.
- [ ] **10.** Offline behavior: connectivity loss, process death during write, retry duplication, conflict resolution.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
