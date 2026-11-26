# Reference Anchors (Android — Compose + Material 3)

These are the **only** acceptable starting points for aesthetic decisions. Show this file to the user (one-line summary per anchor) during Step 1 intake. The user picks 1–3. If 2–3, the user designates which anchor contributes type, color, and motion separately.

**Hard rule:** every value the user sees in `Color.kt` / `Type.kt` / `Shape.kt` / `Spacing.kt` must trace to the recipe of an anchor below. Mixing values from different anchors without an explicit blend rule is forbidden.

**Native pick:** Material You is the most native to Android — recommend it as the default unless the user has reason to deviate. Apple-leaning anchors (Apple Music, Arc) are allowed but call out that they will fight platform conventions (predictive back, navigation bar, FAB).

---

## 1. Linear / Vercel / Geist
**One-line:** Tools-for-hackers. Monochrome stone, sharp edges, instant motion. Hostile to Material expressiveness — use when brand demands it.

**Type (Compose Typography):**
- `displayLarge` / `displayMedium` / `displaySmall`: Inter (declared in Type.kt), `FontWeight.SemiBold`, letterSpacing -0.02em, lineHeight 1.1
- `headlineLarge..Small`: Inter SemiBold
- `bodyLarge`: 16.sp / 24.sp lineHeight, `FontWeight.Normal`
- `bodyMedium` (default): 14.sp / 20.sp
- Mono: JetBrains Mono via `FontFamily`
- Scale (sp): 12, 13, 14, 16, 18, 20, 24, 32, 48

**Color (`ColorScheme`):**
- Neutrals: stone-50…stone-950 mapped to `surface`, `surfaceVariant`, `surfaceContainerLowest..Highest`
- `primary` = stone-900 (light) / stone-50 (dark), `onPrimary` = inverse
- `secondary` and `tertiary` collapse to stone variants — no brand secondary
- `error` = red-600 only. No gradient. No tint.

**Shapes (`RoundedCornerShape`):**
- `extraSmall`: 4.dp, `small`: 6.dp, `medium`: 8.dp, `large`: 10.dp, `extraLarge`: 12.dp
- Buttons never above 8.dp.

**Elevation:** flat. Use 1.dp `outline` borders, not shadows. Override `CardDefaults.cardElevation()` to 0.dp.

**Spacing:** xs 4, sm 8, md 12, lg 16, xl 20, 2xl 24, 3xl 32, 4xl 40, 5xl 48 (all .dp).
> 换算到 `SKILL.md` 的规范尺度：本锚点的 12 → `sm + xs`（或扩充 `Spacing.kt` 的 `compact`），
> 16 → `md`，24 → `lg`，32 → `xl`，48 → `xxl`。生成代码时只准引用 `MaterialTheme.spacing.*`。

**Motion:**
- short2 100ms (hover/state), short4 200ms (layout)
- Easing: `EaseOutCubic`
- No springs. No entrance animations on screen enter.

**Platform fights:** suppresses Material expressiveness, FAB, dynamic color. Disable dynamic color when this anchor is chosen.

**Don't pair with:** Material You, Apple Music, soft pastels, large radii.

---

## 2. Stripe / Plaid
**One-line:** Financial-grade trust. Calm blue/indigo, restrained color, generous reading whitespace.

**Type:**
- Display: Inter (Söhne if licensed), `FontWeight.Medium`, letterSpacing -0.01em, lineHeight 1.15
- Body: Inter Regular, lineHeight 1.6
- Mono: IBM Plex Mono
- Scale (sp): 14, 16, 18, 20, 24, 32, 40, 56, 72
- `bodyLarge` default: 16.sp / 26.sp

**Color:**
- Neutrals: slate-50…slate-900
- `primary` = indigo-600, `primaryContainer` = indigo-100, `onPrimaryContainer` = indigo-900
- Sober brand gradient (hero card ONLY, opt-in): `Brush.linearGradient(listOf(indigo-500, indigo-700))` — never multi-stop
- `error` = red-600

**Shapes:**
- `extraSmall`: 4.dp, `small`: 8.dp, `medium`: 12.dp, `large`: 16.dp, `extraLarge`: 20.dp

**Elevation:** Material 3 elevation tokens, paired with a 1.dp `outline` border on cards.

**Spacing:** xs 4, sm 8, md 12, lg 16, xl 24, 2xl 32, 3xl 48, 4xl 64.

**Motion:**
- short3 150ms (state), medium2 300ms (layout), long1 450ms (sheet)
- Easing: `MotionTokens.EasingStandardCubicBezier`
- One 200ms `AnimatedVisibility` fade on first composition; nothing else on enter.

**Don't pair with:** brutalist mono, neon, decorative serifs.

---

## 3. Notion / Craft
**One-line:** Document-feel productivity. Warm neutrals, serif accents in display, generous reading column.

**Type:**
- Display: Lora or Source Serif (declared in Type.kt), `FontWeight.Normal`, letterSpacing -0.005em, lineHeight 1.2
- Body: Inter or `FontFamily.Default`, lineHeight 1.6
- Mono: JetBrains Mono
- Scale (sp): 14, 16, 18, 21, 28, 36, 48, 60
- `bodyLarge` default: 16.sp / 26.sp
- Reading column max 680.dp via `Modifier.widthIn(max = 680.dp)`

**Color:**
- Warm neutrals (NOT cool slate): stone-50, stone-100… mapped to surface roles
- `primary` = stone-900, `tertiary` = amber-600 (links / highlights)

**Shapes:**
- `extraSmall`: 4.dp, `small`: 4.dp, `medium`: 6.dp, `large`: 10.dp, `extraLarge`: 12.dp
- Subtle.

**Elevation:** flat. Borders only (1.dp `outline`).

**Spacing:** xs 4, sm 8, md 12, lg 16, xl 24, 2xl 32, 3xl 48, 4xl 64.

**Motion:**
- short2 100ms (hover/state), short4 200ms (layout)
- Easing: `EaseOutCubic`
- Hover/state = background color tints, never transforms.

**Don't pair with:** neon, brutalist mono, sharp 0.dp shapes, hard shadows.

---

## 4. Editorial / NYT / Are.na
**One-line:** Text-first publication. Serif headlines, restrained chrome, baseline grid discipline.

**Type:**
- Display: Source Serif or PT Serif, `FontWeight.Normal` or `Bold`, lineHeight 1.05
- Body: `FontFamily.Default` (system) or Inter, lineHeight 1.55
- Mono: JetBrains Mono
- Scale (sp): 13, 15, 17, 21, 28, 40, 56, 80, 112
- `bodyLarge` default: 17.sp / 26.sp
- Baseline grid: 8.dp.

**Color:**
- True neutrals: zinc-50…zinc-950
- `primary` = red-700 (or user-provided brand) used sparingly
- Background light: surface = white. Dark: surface = zinc-950.

**Shapes:** `RoundedCornerShape(0.dp)` everywhere except `AsyncImage` corners (4.dp optional).

**Elevation:** none.

**Spacing:** xs 8, sm 16, md 24, lg 32, xl 48, 2xl 64, 3xl 96, 4xl 128. Column gutters 32–48.dp.

**Motion:**
- short1 50ms (state), short3 150ms (image fade)
- Easing: `LinearEasing`
- No transforms. No layout animation.

**Don't pair with:** Material You expressive shapes, Apple Music, large radii, drop shadows.

---

## 5. Arc / Raycast / Things 3
**One-line:** Apple-native polish on Android — rare, requires justification. Tinted scrims, vivid accent on neutral, soft radii, spring motion. **Will fight Material navigation patterns.**

**Type:**
- Display: `FontFamily.Default` (Roboto Flex), `FontWeight.SemiBold`, letterSpacing -0.022em, lineHeight 1.1
- Body: `FontFamily.Default`, lineHeight 1.45
- Mono: JetBrains Mono
- Scale (sp): 13, 15, 17, 20, 24, 32, 40, 56
- `bodyLarge` default: 15.sp / 22.sp

**Color:**
- Neutrals: zinc-50…zinc-950
- `primary` = blue-500 (Apple system blue) by default; user can override hue
- Scrims: tinted surface tokens via `surfaceColorAtElevation`
- `error` = red-500, `tertiary` = green-500

**Shapes:**
- `extraSmall`: 8.dp, `small`: 12.dp, `medium`: 16.dp, `large`: 20.dp, `extraLarge`: 24.dp
- `ModalBottomSheet` top corners use `extraLarge`.

**Elevation:** Material 3 e1/e2 only on cards. Never on Buttons (use tonal variants).

**Spacing:** xs 4, sm 8, md 12, lg 16, xl 20, 2xl 24, 3xl 32, 4xl 40, 5xl 56.

**Motion:**
- short4 200ms (state), medium3 350ms (layout), long2 500ms (sheet)
- Easing: `CubicBezierEasing(0.32f, 0.72f, 0f, 1f)` — Apple-feel
- Press: `Modifier.scale(0.97f)` over 100ms.
- ModalBottomSheet slides up with `Spring(stiffness = StiffnessMediumLow)`.

**Platform fights:** wants iOS sheet behavior; on Android use `ModalBottomSheet` and accept the difference. Predictive back works.

**Don't pair with:** brutalist, hard shadows, square shapes.

---

## 6. Apple Music / Apple Podcasts (cross-platform — Apple-leaning)
**One-line:** Edge-to-edge artwork. Large titles. Translucent chrome. Card-based content. **Apple-leaning — only when brand demands cross-platform consistency with iOS.**

**Type:**
- Display: `FontFamily.Default` (Roboto Flex), `FontWeight.Bold`, letterSpacing -0.025em, lineHeight 1.05
- Body: `FontFamily.Default`, lineHeight 1.4
- Scale (sp): 13, 15, 17, 22, 28, 34, 44, 60
- `bodyLarge` default: 17.sp / 24.sp

**Color:**
- Album-art-driven dynamic accent (extract dominant color via Coil + Palette, fall back to red-500 / `primary` from seed)
- Background dark default: surface = zinc-950
- Translucent chrome: `surfaceColorAtElevation` + `Modifier.blur()` ONLY on bottom sheet scrim (one allowed exception to the blur ban)

**Shapes:**
- `extraSmall`: 6.dp (artwork sm), `small`: 12.dp (artwork md), `medium`: 16.dp, `large`: 20.dp, `extraLarge`: 28.dp (sheets)

**Elevation:** none at rest. e3 only on hover/press lift cards.

**Spacing:** xs 4, sm 8, md 16, lg 20, xl 24, 2xl 32, 3xl 48, 4xl 64.

**Motion:**
- short4 200ms (state), medium3 350ms (layout), long2 500ms (sheet)
- Easing: `CubicBezierEasing(0.32f, 0.72f, 0f, 1f)`
- Card press: `Modifier.scale(0.98f)`.
- Velocity-based scroll inertia (LazyColumn natural).

**Platform fights:** clashes with Material 3 navigation conventions. Document the deliberate decision in the agent instruction file (AGENTS.md or CLAUDE.md).

**Don't pair with:** brutalist, editorial serif, no-radius.

---

## 7. Material You (Android Expressive — DEFAULT, most native)
**One-line:** Dynamic color from brand seed (or wallpaper). Expressive shapes. Bold motion physics. **The native Android pick.**

**Type:**
- Display: Roboto Flex (variable font, declared explicitly), `FontWeight.Normal` for display, `Medium` for body, lineHeight 1.15 display / 1.5 body
- Body: Roboto Flex
- Mono: Roboto Mono
- Scale (sp) — Material 3 type scale: 11, 12, 14, 16, 22, 28, 32, 36, 45, 57
- `bodyMedium` (default): 14.sp / 20.sp

**Color:**
- Generated from brand seed via `dynamicLightColorScheme` / `dynamicDarkColorScheme` (API 31+) OR a fixed seed via Material Color Utilities (`Hct`/`Scheme`)
- All M3 roles populated: `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `secondary`, `secondaryContainer`, `tertiary`, `tertiaryContainer`, `surface`, `surfaceVariant`, `surfaceContainerLow..Highest`, `outline`, `outlineVariant`, `error`, `errorContainer`
- 5 surface elevation tints via `surfaceColorAtElevation`

**Shapes:**
- `extraSmall`: 4.dp, `small`: 8.dp, `medium`: 12.dp, `large`: 16.dp, `extraLarge`: 28.dp
- Pill shapes via `RoundedCornerShape(50)` allowed on Buttons (Expressive default)
- Use varied shapes per component — Buttons pill, Cards `large`, Sheets `extraLarge` top corners.

**Elevation:** Material 3 tokens e0–e5. e1 default for `ElevatedCard`.

**Spacing:** xs 4, sm 8, md 12, lg 16, xl 20, 2xl 24, 3xl 32, 4xl 40, 5xl 48, 6xl 56.

**Motion:**
- short2 100ms (small state), medium2 300ms (medium layout), long1 450ms (large), extraLong2 800ms (extra large)
- Easing: `MotionTokens.EasingEmphasizedCubicBezier` (cubic-bezier(0.2, 0, 0, 1)) for emphasized layout
- Spring physics for state changes: `Spring(stiffness = StiffnessMedium, dampingRatio = DampingRatioMediumBouncy)`
- Generous, expressive — animations have personality, not sterile.
- `animateContentSize()` allowed on cards that resize.

**Platform native:** all Material 3 conventions honored. Predictive back via `BackHandler`. Dynamic color recommended on. FAB allowed.

**Don't pair with:** Linear/Vercel mono, editorial serif, brutalist.

---

## 8. Brutalist / mono / hacker
**One-line:** High-contrast, monospace, raw. Deliberately loud. Hostile to Material expressiveness.

**Type:**
- Display: JetBrains Mono or IBM Plex Mono (declared), `FontWeight.Bold`, lineHeight 1.1
- Body: same family, `FontWeight.Normal`, lineHeight 1.5
- Scale (sp): 12, 14, 16, 20, 28, 40, 56, 80, 112
- `bodyMedium` default: 14.sp

**Color:**
- Black + white only by default (zinc-950 + zinc-50). One accent at full saturation: chartreuse / hot pink / electric blue
- No mid-grays. `outline` = `onSurface` (1.dp solid current color borders)

**Shapes:** `RoundedCornerShape(0.dp)` everywhere. No exceptions.

**Elevation:** offset hard "shadow" via `Modifier.offset(4.dp, 4.dp)` + background — not blur. Compose has no native offset shadow; emulate with stacked Box.

**Spacing:** xs 8, sm 16, md 24, lg 32, xl 48, 2xl 64, 3xl 96, 4xl 128. Always multiples of 8.

**Motion:**
- short1 50ms only
- Easing: `LinearEasing`
- "Hover" = invert colors via state, never transform.

**Platform fights:** suppresses dynamic color, ripple, expressive shapes. Disable dynamic color. Override `RippleTheme` to a hard-edge alpha flip.

**Don't pair with:** anything soft. Exclusive — pick brutalist or pick something else.

---

## Blending rules

- User picks 1 anchor → use it whole.
- User picks 2 anchors → designate one as **primary** (provides ColorScheme, Spacing, Shapes, elevation) and one as **accent** (may override only the typography display family OR only the motion personality OR only the accent hue — never all three).
- User picks 3 anchors → primary + type-anchor + motion-anchor. Color always from primary.
- **Forbidden blends:** Brutalist + anything. Editorial + Apple Music. Material You + Linear. Apple Music + Material You (Apple chrome will fight M3 navigation). If the user requests one of these, ask which they want to drop.
