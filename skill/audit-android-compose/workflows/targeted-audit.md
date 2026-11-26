# Workflow: Targeted feature audit

Prompt:
> Use the audit-android-compose skill in targeted mode for: [FEATURE / FILES / DOMAIN]. First identify entry points, state owners, dependencies, callers/callees, data sources, lifecycle owners, and tests. Review the target plus all materially affected files, not just the named file. Apply relevant domain checklists. Do not modify source code. Report exact scope, reviewed paths, exclusions, findings, and validation gaps. Do not imply a full-project audit.

Exit gate:
- Feature entry-to-render path traced.
- Failure, cancellation, retry, navigation and persistence paths reviewed as applicable.
- Related tests inspected.
- Scope limitations stated.
