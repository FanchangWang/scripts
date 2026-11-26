# Workflow: Full project audit

Use with `SKILL.md` in `full` mode.

## Run 1 — inventory only
Prompt:
> Use the audit-android-compose skill in full mode. Perform Phase 0 and Phase 1 only. Do not modify source files. Inspect the repository, enumerate modules/source sets/files, identify tooling and safe commands, and create/update PROJECT_INVENTORY.md plus a coverage matrix in CODE_AUDIT_TRACKER.md. Report any scope you could not inspect. Do not claim the audit is complete.

Acceptance:
- Every module and source set listed.
- Source/config/test/manifest/resource/build files included.
- Exclusions justified.
- No source code modified.

## Run 2 — audit only
Prompt:
> Continue the existing full audit. Do not modify application source code. Complete all applicable domain checklists and cross-cutting flow tracing. Run only safe, available checks. Create/update all audit reports and tracker. Continue after finding issues; do not stop at a small sample. For each domain report files reviewed and files not reviewed. If the context limit is reached, persist exact progress and mark the audit INCOMPLETE.

Acceptance:
- All applicable domains covered.
- All findings evidence-based.
- Coverage and limitations explicit.
- No source code modified.

## Run 3 — remediation
Prompt:
> Fix only the findings I explicitly approve. Work in priority order and one root cause at a time. Add regression tests. Do not perform unrelated refactoring. After each fix, inspect the full diff and run relevant tests. Update the tracker with VERIFIED_FIXED or FIXED_UNVERIFIED; do not equate code edits with verification.

## Run 4 — independent closeout
Prompt:
> Perform an independent regression audit. Do not trust prior fix summaries. Re-open each changed implementation, verify the original failure condition, inspect callers/callees/shared state and adjacent flows, run available tests/lint/build checks, and search for regressions. Also inspect any previously unreviewed files. Update REGRESSION_TEST_REPORT.md and FINAL_AUDIT_REPORT.md. State COMPLETE, COMPLETE_WITH_LIMITATIONS, or INCOMPLETE accurately.
