# Workflow: Security-focused audit

Prompt:
> Use the audit-android-compose skill in security mode. Build a lightweight threat model, identify assets and trust boundaries, inspect manifests/exported components/permissions/network security config/storage/WebView/deep links/authentication/cryptography/logging/backups/dependencies/release configuration, and trace sensitive data from input to storage/network/logging. Use the security checklist and OWASP MASVS as a control catalog. Do not claim a penetration test unless dynamic testing was actually performed. Do not print secrets. Do not modify files. Report attacker prerequisites, impact, evidence, confidence, and recommended validation.

Do not:
- expose token/key values in the report;
- assume client-side authorization is sufficient;
- claim encryption is secure merely because a class is named Crypto;
- claim a library is vulnerable without version/advisory evidence.
