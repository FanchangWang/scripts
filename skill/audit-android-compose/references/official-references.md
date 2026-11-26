# Official references and control catalogs

Use these as starting points, not as substitutes for reading the current project code. Re-check current guidance for the project's actual SDK/library versions.

## Jetpack Compose

- Compose documentation: https://developer.android.com/develop/ui/compose/documentation
- Compose performance: https://developer.android.com/develop/ui/compose/performance
- Compose performance codelab / measurement approach: https://developer.android.com/codelabs/jetpack-compose-performance
- Compose state and lifecycle topics are linked from the Compose documentation.

Key principle: measure before optimizing. Recomposition count alone is not proof of a user-visible performance problem.

## Android architecture and testing

- Architecture recommendations: https://developer.android.com/topic/architecture/recommendations
- Testing in Android: https://developer.android.com/training/testing
- App quality: https://developer.android.com/docs/quality-guidelines

Use the recommendations as guidance. Do not impose a specific architecture or DI library when the project has a sound alternative.

## Security

- OWASP MASVS: https://mas.owasp.org/MASVS/
- OWASP MASTG: https://mas.owasp.org/MASTG/
- Android security best practices: https://developer.android.com/privacy-and-security/security-best-practices
- Network security configuration: https://developer.android.com/privacy-and-security/security-config
- App manifest / component security: https://developer.android.com/guide/topics/manifest/manifest-intro

MASVS is a control catalog. A source-code review is not a full penetration test or formal MASVS verification.

## Static analysis

- Android lint: https://developer.android.com/studio/write/lint
- Detekt: https://detekt.dev/
- KtLint: https://pinterest.github.io/ktlint/

Only run tasks actually configured in the repository. Record unavailable tasks rather than silently skipping them.

## Suggested measurement tools

- Macrobenchmark: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- Baseline Profiles: https://developer.android.com/topic/performance/baselineprofiles/overview
- Android Studio profiling: https://developer.android.com/studio/profile

## Version-awareness rule

Before flagging API usage as deprecated, unsafe, or incompatible, verify against the project's resolved dependency and SDK versions. Prefer version-matched official documentation and release notes.

## 2025–2026 additions (latest Google guidance)

- Agent skills format: https://developer.android.com/tools/agents/android-skills
- Google official skills repository (edge-to-edge / adaptive / wear): https://github.com/android/skills
- Material 3 in Compose: https://developer.android.com/develop/ui/compose/designsystems/material3
- Compose Material 3 release notes (M3 Expressive APIs): https://developer.android.com/jetpack/androidx/releases/compose-material3
- Material 3 Expressive: https://m3.material.io/blog/building-with-m3-expressive
- Edge-to-edge setup: https://developer.android.com/develop/ui/compose/system/setup-e2e
- WindowInsets: https://developer.android.com/develop/ui/compose/system/insets-ui
- Compose semantics / accessibility: https://developer.android.com/develop/ui/compose/accessibility/semantics

## Known deprecated / replaced patterns (verify version before flagging)

- `androidx.compose.material` (Material 2) is in maintenance; new code should use `material3`.
- `surfaceVariant` → prefer `surfaceContainer*` roles in M3.
- `SOFT_INPUT_ADJUST_RESIZE` constant → manifest `adjustResize`.
- `shadow()` → `dropShadow()` / `innerShadow()`.
- small / surface FABs are no longer recommended.
- `ListDetailPaneScaffold` / `SupportingPaneScaffold` → prefer Navigation3 `SceneStrategy`.
- Double padding: `imePadding()` plus `Scaffold` `contentPadding` at once; prefer `fitInside(WindowInsetsRulers.Ime.current)`.
