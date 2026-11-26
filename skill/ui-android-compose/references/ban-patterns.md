# Ban Patterns (Android — Compose)

Eight patterns. Three are **default-on** (must be presented to the user pre-checked). Five are opt-in. The user picks at the start of Step 1.

When a pattern is active, you MUST verify your output against it during the audit and remove violations before claiming done.

---

## DEFAULT-ON

### 1. Generic gradient slop
**Forbidden patterns:**
- `Brush.linearGradient(listOf(Color(0xFF...purple...), Color(0xFF...pink...)))`
- `Brush.linearGradient(listOf(Color.Magenta, Color.Cyan))`
- Any indigo→purple, purple→pink, violet→fuchsia, blue→purple two-color brush
- Any 3+ stop brush unless the chosen anchor explicitly defines one
- Gradient `Text` via `Modifier.graphicsLayer { shaderEffect = ... }` over a multi-color brush
- `Brush.sweepGradient` for decoration

**Allowed:** single-anchor gradients explicitly listed in the anchor recipe (e.g., Stripe's sober indigo-500→indigo-700 hero brush). Solid `MaterialTheme.colorScheme.*` always allowed.

**Detection:** grep your output for `Brush.linearGradient`, `Brush.sweepGradient`, `Brush.radialGradient`, `Color(0xFF` literals near `Brush`.

---

### 2. Lorem ipsum & filler copy
**Forbidden strings (case-insensitive):**
- "Lorem ipsum"
- "Lightning fast" / "Blazing fast"
- "Built for the modern X"
- "Powered by AI"
- "Unleash the power of"
- "Get started in seconds"
- "The future of X"
- "Beautiful and intuitive"
- "Seamlessly integrate"
- "Take your X to the next level"
- "Reimagined" / "Redesigned"
- "Effortlessly"
- Generic CTAs: "Get Started" alone (must be specific: "Start free trial", "Read docs"), "Learn More", "Click Here", "Submit"
- Emoji bullets (sparkle, rocket, lightning, fire) in serious productivity UI

**Required behavior:** when copy is needed and the user has not provided it, STOP and ask: "I need real copy for [section]. What does this product do, and what should the headline say specifically about it?" Do not invent.

**Detection:** grep `.kt` files for the strings above.

---

### 3. Arbitrary values
**Forbidden patterns:**
- `Modifier.padding(13.dp)`, `.padding(17.dp)` — any `\d+\.dp` literal in non-theme files where the value is not 0
- `Modifier.size(372.dp)`, `.height(57.dp)`, `.width(99.dp)`
- `RoundedCornerShape(7.dp)` — must come from `MaterialTheme.shapes`
- `Color(0xFF3A4F6B)` literals inside composables — must come from `MaterialTheme.colorScheme.*`
- `TextStyle(fontSize = 13.sp, ...)` inline — must come from `MaterialTheme.typography.*`
- `Dp` arithmetic with literals: `12.dp + 5.dp`

**Required behavior:** if you need a value that doesn't exist in `Spacing.kt` / `Shape.kt` / `Color.kt` / `Type.kt`, STOP and ask the user to extend the theme. Do not improvise.

**Detection:** grep non-theme files for `\b\d+\.dp\b`, `Color\(0x`, `\b\d+\.sp\b` outside of `Type.kt`. The detekt config catches these.

---

## OPT-IN (ask the user)

### 4. Default font tells
**Forbidden as primary `FontFamily` (unless explicitly declared in `Type.kt` and chosen by the anchor):**
- `FontFamily("Inter")` as the project default
- Bare `FontFamily.SansSerif` when the anchor specifies a deliberate display family
- Hardcoded `Font(R.font.roboto)` outside `Type.kt`
- Mixing `FontFamily.Default`, `FontFamily.Serif`, `FontFamily.Monospace` in one screen

**Allowed:** `FontFamily.Default` (Roboto Flex via system on Android 14+), Roboto Flex declared via downloadable fonts, anchor-specific fonts (Inter, Source Serif, JetBrains Mono, IBM Plex Mono).

**Why:** Inter and Roboto-everywhere are AI tells. Prefer `FontFamily.Default` (which IS Roboto Flex on Android 14+) and only override at the `Typography` level — never inline in a composable.

---

### 5. Glassmorphism / blur overuse
**Forbidden:**
- `Modifier.blur(...)` on `Card`, `ListItem`, `OutlinedCard`, body content
- `Modifier.blur()` as a decorative overlay
- `RenderEffect.createBlurEffect` on app chrome
- Frosted scrims behind body text

**Allowed:** `Modifier.blur()` on `ModalBottomSheet` scrim ONLY when the chosen anchor is Apple Music or Arc/Raycast/Things 3, and only one usage per screen.

---

### 6. Card-in-card nesting
**Forbidden:**
- `Card { Card { ... } }` more than 1 level deep
- `ElevatedCard` inside `OutlinedCard` (mixed elevation strategies in one tree)
- Mixing shape sizes within one component (outer `MaterialTheme.shapes.large`, inner `MaterialTheme.shapes.extraLarge` children)
- `border` + `elevation` on the same `Card` — pick one

**Detection:** read your composable tree — count nesting of `Card`/`ElevatedCard`/`FilledCard`/`OutlinedCard`.

---

### 7. Generic layout patterns
**Forbidden as the default screen patterns:**
- "Hero" = centered headline + subhead + 2 buttons + screenshot below in a `Column(verticalArrangement = Center)`
- 3-icon-card "Features" `LazyVerticalGrid(GridCells.Fixed(3))` (icon + bold word + 2 lines)
- 3-quote testimonial row
- Pricing as 3 vertical cards with the middle one "popular"
- "How it works" 3-step horizontal arrow flow
- Tutorial-style centered loading spinner with no skeleton
- "Welcome to X" empty splash with a single button
- Bottom sheet that is just a list of buttons stacked vertically with no hierarchy

**Allowed:** any of the above ONLY if you have a specific reason rooted in the user's content and an anchor that explicitly uses the pattern.

**Required behavior:** when generating any list/feature/onboarding screen, propose ≥2 layout alternatives. Examples: dense `LazyColumn` with sticky headers, asymmetric two-pane with `NavigationRail` (tablet/foldable), single long-read with `TextField`-style input pinned bottom, dialogue-style alternating bubbles.

---

### 8. Decorative motion
**Forbidden:**
- `rememberInfiniteTransition` for decoration (looping pulses, glows, breathing)
- `LaunchedEffect(Unit) { while (true) ... }` animation loops
- Parallax backgrounds tied to scroll offset
- Auto-playing horizontal carousels (`HorizontalPager` with `LaunchedEffect` autoadvance)
- Hover-equivalent scale on every card / button (Compose has no hover on touch — but the press-scale equivalent everywhere)
- Animated gradient backgrounds via `infiniteRepeatable`
- Floating decorative `Canvas` shapes
- "Shimmer everywhere" loading placeholders that exceed 1 list at a time

**Allowed:** functional motion only — `AnimatedVisibility` for state changes, `animateContentSize()` on resizing cards, `AnimatedContent` for swaps, `Modifier.scale` press feedback, predictive back. Entrance animation only on a single hero element if anchor allows.

---

## Audit step

After generating any code, grep your output against the active patterns. Print:

```
BAN AUDIT
─────────
[1] Generic gradient slop:    PASS / FAIL (matches: ...)
[2] Lorem ipsum / filler:     PASS / FAIL
[3] Arbitrary values:         PASS / FAIL
[4] Default font tells:       PASS / FAIL / SKIP
...
```

If any FAIL: fix the violations, re-grep, re-print. Do not respond "done" until all PASS.
