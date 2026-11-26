# Motion (Android — Compose + Material 3)

Motion has personality. Pick the personality from the chosen anchor; do not mix.

Material 3 motion tokens are the only allowed durations. Easings come from `androidx.compose.material3.tokens.MotionTokens` or explicit `CubicBezierEasing` defined in the anchor recipe.

## Duration tokens (Material 3)

| Token | Value | Use |
|---|---|---|
| `short1` | 50ms | Selection / state binary flips |
| `short2` | 100ms | Small component state changes |
| `short3` | 150ms | Hover-equivalent / focus |
| `short4` | 200ms | State changes, ripple completion |
| `medium1` | 250ms | Small layout shifts |
| `medium2` | 300ms | Default layout transitions |
| `medium3` | 350ms | Medium layout (cards expand, sheets) |
| `medium4` | 400ms | Larger layout |
| `long1` | 450ms | Modal sheet / dialog entrance |
| `long2` | 500ms | Large layout, predictive back |
| `long3` | 550ms | Larger transitions |
| `long4` | 600ms | Extra large transitions |
| `extraLong1..4` | 700–1000ms | Hero transitions only — sparingly |

Anchor mappings: Linear uses short2/short4. Stripe uses short3/medium2/long1. Apple Music + Arc use short4/medium3/long2. Material You uses medium2/long1 + spring physics. Brutalist uses short1 only. Editorial uses short1/short3.

## Easing tokens

| Token | Curve | Use |
|---|---|---|
| `MotionTokens.EasingStandardCubicBezier` | (0.2, 0, 0, 1) | Default, layout in/out |
| `MotionTokens.EasingEmphasizedCubicBezier` | (0.2, 0, 0, 1) emphasized | Material You expressive layout |
| `MotionTokens.EasingEmphasizedAccelerateCubicBezier` | exit | Outgoing emphasized |
| `MotionTokens.EasingEmphasizedDecelerateCubicBezier` | enter | Incoming emphasized |
| `EaseOutCubic` | (0.33, 1, 0.68, 1) | Linear/Notion default |
| `LinearEasing` | linear | Brutalist + progress bars only |
| `CubicBezierEasing(0.32f, 0.72f, 0f, 1f)` | Apple-feel | Arc / Apple Music anchors only |
| `Spring(stiffness, dampingRatio)` | spring | Material You state changes |

NEVER use bare `tween()` without specifying easing. Always: `tween(durationMillis = MotionTokens.DurationMedium2.toInt(), easing = MotionTokens.EasingEmphasizedCubicBezier)`.

## When motion is allowed

- State changes via `animate*AsState` (`animateColorAsState`, `animateDpAsState`, `animateFloatAsState`)
- `AnimatedVisibility` for show/hide
- `AnimatedContent` for content swaps (with `transitionSpec` defining enter/exit)
- `animateContentSize()` on resizing containers (Card expand/collapse)
- `updateTransition` for state-driven multi-property animation
- `LookaheadScope` for adaptive layout transitions
- Predictive back animations via `PredictiveBackHandler` (API 34+)
- `Modifier.scale` press feedback inside `clickable(interactionSource = ...)`
- `LazyListState` natural scroll inertia (no override)

## When motion is forbidden by default

- `rememberInfiniteTransition` for decoration (looping pulses, glows, breathing animations on idle elements)
- `LaunchedEffect(Unit) { while (true) { ... } }` animation loops
- Parallax tied to `LazyListState.firstVisibleItemScrollOffset`
- Auto-advancing `HorizontalPager` carousels
- "Hero" entrance animations on every element on first composition
- `Modifier.graphicsLayer` decorative rotations / skews on idle elements
- Animated gradient backgrounds via `infiniteRepeatable`
- Floating decorative `Canvas` shapes
- Shimmer placeholders that span more than one list at a time

## Reduced motion

Honor accessibility settings:

```kotlin
val reduceMotion = LocalAccessibilityManager.current?.let {
    Settings.Global.getFloat(
        LocalContext.current.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f
} ?: false

val duration = if (reduceMotion) 0 else MotionTokens.DurationMedium2.toInt()
```

For any animation with a duration > short4 (200ms), check this and provide a 0ms fallback.

## Press feedback (pick one per project, document in the agent instruction file (AGENTS.md or CLAUDE.md))

- **Built-in ripple** (Material default): no extra code, works automatically with `Button`, `IconButton`, `clickable`
- **Scale**: `Modifier.scale(if (pressed) 0.97f else 1f)` driven by `interactionSource.collectIsPressedAsState()`
- **Tonal flip**: `animateColorAsState` between `surface` and `surfaceVariant`

Pick ONE and stick with it. Apple-leaning anchors prefer scale; Material You uses ripple; Brutalist uses tonal flip.

## Predictive back

On API 34+, all `ModalBottomSheet`, `AlertDialog`, and navigation transitions get predictive back for free. For custom screens:

```kotlin
PredictiveBackHandler(enabled = canGoBack) { progress ->
    try {
        progress.collect { backEvent ->
            // animate based on backEvent.progress (0f..1f)
        }
        // commit the back action
        onBack()
    } catch (e: CancellationException) {
        // user cancelled - reset state
    }
}
```

Forbidden: blocking the system back gesture without a reason. Forbidden: custom back animations that override the system predictive back curve.

## AnimatedVisibility / AnimatedContent rules

`AnimatedVisibility`:

```kotlin
AnimatedVisibility(
    visible = state.isExpanded,
    enter = fadeIn(tween(MotionTokens.DurationMedium2.toInt())) +
            expandVertically(tween(MotionTokens.DurationMedium2.toInt(), easing = MotionTokens.EasingEmphasizedCubicBezier)),
    exit = fadeOut(tween(MotionTokens.DurationShort4.toInt())) +
            shrinkVertically(tween(MotionTokens.DurationShort4.toInt())),
)
```

`AnimatedContent` for state swaps requires an explicit `transitionSpec` — do not use the default. Pick a direction: slide in from end + fade out to start, or fade through, depending on the semantic of the state change.

## Layout animations

`Modifier.animateContentSize()` is allowed on Card and Column containers that resize. Specify the `animationSpec`:

```kotlin
Modifier.animateContentSize(
    animationSpec = tween(
        durationMillis = MotionTokens.DurationMedium2.toInt(),
        easing = MotionTokens.EasingEmphasizedCubicBezier,
    )
)
```

LazyColumn item add/remove uses `Modifier.animateItem()` (formerly `animateItemPlacement`) — allowed and encouraged.
