# Workflow: Regression audit after fixes

Prompt:
> Use the audit-android-compose skill in regression mode. Read CODE_AUDIT_TRACKER.md and the prior reports, but independently verify every finding marked fixed. Inspect the actual diff and all affected callers/callees/shared state. Reproduce or test the original failure condition where feasible. Run relevant tests and static checks. Search for new defects introduced by the patch. Mark each item VERIFIED_FIXED, REOPENED, FIXED_UNVERIFIED, or NOT_REPRODUCIBLE with evidence. Do not silently delete or overwrite old findings. Produce a regression report and update the final status.

Required status meanings:
- VERIFIED_FIXED: behavior validated by a test or reproducible runtime check.
- FIXED_UNVERIFIED: code appears corrected but behavior could not be validated.
- REOPENED: original issue remains or has returned.
- NOT_REPRODUCIBLE: evidence is insufficient to confirm the original report; explain why.
