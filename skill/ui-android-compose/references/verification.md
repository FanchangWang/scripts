# Verification (Android — Compose + Material 3)

You MUST run the relevant audit and print the result table to the user before claiming any work done. No exceptions. If any check FAILS, fix and re-run before responding.

---

## Token Audit (after Phase 1)

Run after generating `Color.kt`, `Type.kt`, `Shape.kt`, `Spacing.kt`, `Theme.kt`, detekt config, and recording design rules in the agent instruction file.

```
TOKEN AUDIT
───────────
[T1] Spacing values all from SKILL.md canonical scale
     (xs=4, sm=8, md=16, lg=24, xl=32, xxl=48)?          PASS / FAIL  (offending: ...)
[T2] ColorScheme has WCAG-AA contrast (text 4.5:1,
     UI 3:1) on every role pair (onPrimary/primary,
     onSurface/surface, etc.)?                         PASS / FAIL
[T3] Typography uses the full M3 type scale roles
     (no custom .sp sizes outside Type.kt)?            PASS / FAIL
[T4] Shapes scale has exactly 5 entries
     (xs/sm/md/lg/xl)?                                 PASS / FAIL
[T5] Motion durations/easings all from M3 MotionTokens
     (no raw tween() literals anywhere)?               PASS / FAIL
[T6] FontFamily declared in Type.kt (not bare
     "Inter" / "Roboto" inline anywhere)?              PASS / FAIL
[T7] No banned patterns present in theme files?        PASS / FAIL
[T8] Design rules recorded in the agent's instruction file (AGENTS.md / CLAUDE.md)?        PASS / FAIL
[T9] detekt config patched + detektDesign Gradle
     task wired into check?                            PASS / FAIL
[T10] `detektDesign` task exits 0 (or N/A with reason)? PASS / FAIL  (run it if configured!)
[T11] `./gradlew lint` exits 0?                        PASS / FAIL  (run it!)
[T12] Both lightColorScheme and darkColorScheme are
      populated with all M3 roles?                     PASS / FAIL
```

How to run T10–T11:
```bash
./gradlew detekt detektDesign   # detektDesign only if wired in the project
./gradlew lint
```

If FAIL: list the violations, fix the tokens (or remove the offending arbitrary value), re-print the audit. Do not show the user a partial PASS.

Then **stop and ask for approval** before Phase 2:

> "Phase 1 audit passed. Tokens written to ui/theme/Color.kt, Type.kt, Shape.kt, Spacing.kt, Theme.kt. Design rules at the agent instruction file (AGENTS.md or CLAUDE.md). Approve to proceed with UI generation?"

---

## UI Audit (after Phase 2)

Run after generating any composable, screen, or feature.

```
UI AUDIT
────────
[U1] Every dp / sp / Color value comes from
     MaterialTheme.{spacing,shapes,colorScheme,
     typography} (no raw \d+\.dp, no Color(0xFF...)
     literals outside theme files)?                    PASS / FAIL
[U2] Touch targets ≥ 48.dp on every interactive
     element (Button, IconButton, clickable Modifier
     with minimumInteractiveComponentSize)?            PASS / FAIL
[U3] Edge-to-edge respected (Activity calls
     enableEdgeToEdge(); Scaffold + WindowInsets used;
     no fixed statusBarsPadding outside Scaffold-less
     screens)?                                          PASS / FAIL
[U4] TalkBack contentDescription present on every
     icon-only IconButton; null with parent label
     where decorative?                                  PASS / FAIL
[U5] Empty + loading + error states present for
     every async surface (Flow, suspend, ViewModel
     state)?                                            PASS / FAIL
[U6] No banned patterns (per active ban list)?         PASS / FAIL
[U7] Real copy — no lorem, no filler phrases, no
     generic CTAs, sentence case on labels?            PASS / FAIL
[U8] Component anatomy rules satisfied (Button
     variant picked, single Card variant per surface,
     ≤ 5 NavigationBarItems, one FAB per screen,
     ListItem heights from spec)?                      PASS / FAIL
[U9] Dark theme parity — every screen verified in
     both isSystemInDarkTheme() = true and false?      PASS / FAIL
[U10] Material Symbols icon family consistent — only
      ONE of Filled/Outlined/Rounded/Sharp/TwoTone
      across the project (selection convention is
      the only allowed exception)?                     PASS / FAIL
[U11] Predictive back honored — ModalBottomSheet,
      AlertDialog, custom screens use BackHandler /
      PredictiveBackHandler appropriately?             PASS / FAIL
[U12] `./gradlew detekt` exits 0?                      PASS / FAIL
[U13] `./gradlew lint` exits 0?                        PASS / FAIL
[U14] No buzzwords (clean / modern / professional /
      sleek / premium / beautiful / elegant) in code,
      comments, or copy?                               PASS / FAIL
```

## How to run each check (the model performs these literally)

| Check | Command / action |
|---|---|
| U1 | Grep generated `.kt` files for `\b\d+\.dp\b`, `\b\d+\.sp\b`, `Color\(0x` outside `ui/theme/**`. Detekt rule will catch these but the model verifies first. |
| U2 | Read every `clickable` / `IconButton` / `Button` — verify either Material 3 component (handles min size automatically) or `Modifier.minimumInteractiveComponentSize()`. |
| U3 | Verify the Activity calls `enableEdgeToEdge()` in `onCreate`; verify screens use `Scaffold` with no manual `statusBarsPadding`/`navigationBarsPadding` overrides. |
| U4 | Grep for `IconButton(` and `Icon(` — verify each has `contentDescription = "..."` (specific) or `null` (decorative with parent label). |
| U5 | For every `collectAsState()` / `collectAsStateWithLifecycle()` / `produceState`, verify the rendered output has loading + empty + error branches. |
| U6 | Grep for the active ban patterns (see ban-patterns.md detection regexes). |
| U7 | Grep for the banned copy strings (`Lorem ipsum`, `Lightning fast`, `Powered by AI`, ...). Verify button labels are sentence case. |
| U8 | Re-read `component-anatomy.md` and walk through each composable you generated. |
| U9 | Use `@Preview(name = "Dark", uiMode = UI_MODE_NIGHT_YES)` previews on every screen file; verify both render correctly. |
| U10 | Grep all generated files for `androidx.compose.material.icons.` — verify only ONE of `filled.` / `outlined.` / `rounded.` / `sharp.` / `twotone.` appears (or two if selection convention documented). |
| U11 | For every screen and sheet, verify `BackHandler` or built-in M3 dismiss handles back. |
| U12 | Run `./gradlew detekt` |
| U13 | Run `./gradlew lint` |
| U14 | Grep all generated files for the buzzwords. Detekt rule will catch but the model verifies first. |

## Output format the model MUST print

Print the table verbatim. For each FAIL, on the next line, list `→ <file:line> <what went wrong>`. After fixing, re-run and re-print.

After all PASS:

> "UI audit passed. <N> files generated: <list>. detekt: 0 issues. lint: 0 issues. Ready for review."

## When you cannot make a check PASS

If a check is impossible to pass (e.g., the user explicitly asked for a banned pattern), document the exception inline with `WAIVED: <reason>` and require the user to confirm. NEVER silently skip a check.
