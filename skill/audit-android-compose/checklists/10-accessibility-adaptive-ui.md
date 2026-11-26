# Accessibility, localization, and adaptive UI

For each item mark **PASS**, **FINDING**, **NEEDS-VALIDATION**, or **N/A**. Add evidence (paths/lines) and explain N/A decisions. A checklist item is not passed merely because a search found no matching keyword; inspect the implementation and relevant call paths.

- [ ] **01.** Semantics: meaningful labels/roles/state descriptions; avoid redundant or misleading announcements.
- [ ] **02.** Touch: adequate target size, gesture alternatives, focus order and keyboard/D-pad navigation where relevant.
- [ ] **03.** Visual: contrast, color not sole signal, font scaling, dynamic type, content clipping and motion preferences.
- [ ] **04.** Compose semantics: merge/clear semantics used correctly; custom components expose actions and state.
- [ ] **05.** Localization: string resources, pluralization, RTL, locale-aware formatting, no hard-coded user-facing strings.
- [ ] **06.** Adaptive: phones/tablets/foldables/resizable windows, orientation, split-screen and large screens as product scope requires.
- [ ] **07.** Text/input: IME actions, autofill, password semantics, selection, error announcements and focus restoration.
- [ ] **08.** Testing: accessibility checks and Compose UI tests for critical controls.

## Domain exit gate

- [ ] All relevant inventory files reviewed.
- [ ] Callers/callees and tests inspected where applicable.
- [ ] Findings have evidence, severity, confidence, impact, and verification plan.
- [ ] Unchecked and blocked areas explicitly recorded.
