# Component Anatomy (Android — Compose + Material 3)

Exact rules for every composable you generate. Numbers are non-negotiable. If a number doesn't fit your case, ask the user to extend the theme rather than improvising.

All sizes assume Material 3 minimums on Android 14+ / API 34+. Touch targets are **48dp minimum** (Material 3 standard, stricter than the legacy 44dp).

---

## Button

Material 3 variants — pick the right one, do not invent:

| Variant | Composable | Use |
|---|---|---|
| Primary | `Button` | Single most important action on the screen |
| Secondary | `FilledTonalButton` | Important but not primary |
| Tertiary | `OutlinedButton` | Alternative actions |
| Quaternary | `TextButton` | Low-emphasis, often inside dialogs / cards |
| Icon-only | `IconButton` / `FilledIconButton` / `OutlinedIconButton` | Toolbar, list-trailing |
| Floating | `FloatingActionButton` / `ExtendedFloatingActionButton` | One per screen, primary action only |

**Sizing:**

| Size | Height | Horizontal padding | Text style | Icon |
|---|---|---|---|---|
| sm | 40.dp | `MaterialTheme.spacing.md` (16) | `labelLarge` 14.sp | 18.dp |
| md (default) | 48.dp | `MaterialTheme.spacing.lg` (24) | `labelLarge` 14.sp | 18.dp |
| lg | 56.dp | `MaterialTheme.spacing.xl` (32) | `titleSmall` 16.sp | 24.dp |

**Rules:**
- Touch target ≥ 48.dp. Use `Modifier.minimumInteractiveComponentSize()` if the visual height is smaller.
- Shape: `MaterialTheme.shapes.large` (Material 3 default — pill-ish on Expressive). Override only via theme, never inline.
- Press feedback: built-in ripple. Override `LocalIndication` only at the theme level if the anchor demands it.
- `IconButton` requires `contentDescription` on the inner `Icon` — never `null` unless the parent provides the label.
- Disabled: built-in M3 disabled colors (`disabledContainerColor`, `disabledContentColor`). Do not override.
- Variants per project: at most 5. Document the picked set in `the agent instruction file (AGENTS.md or CLAUDE.md)`.
- Forbidden: gradient buttons, shadow + outline on the same button, two icons + label, all-caps labels (Material 3 default is sentence case).

---

## TextField

Use `OutlinedTextField` (default) or `TextField` (filled). Pick ONE per project.

**Sizing:**
- Min height: 56.dp (Material 3 default — meets 48.dp touch target)
- Horizontal padding: handled by Material 3 internals; do not override
- `label`: always provided as a `@Composable { Text(...) }` slot
- `supportingText`: required for any field with validation rules (hint or error)
- `leadingIcon` / `trailingIcon`: 24.dp `Icon`, with `contentDescription`
- `singleLine = true` for short input; multi-line uses `minLines` + `maxLines`
- IME: `keyboardOptions = KeyboardOptions(keyboardType = ..., imeAction = ...)`, `keyboardActions` handles submit
- Error: `isError = true` triggers M3 error styling; `supportingText` should have `Modifier.semantics { error("...") }`

**Rules:**
- Never wrap in `Modifier.background(...)` — use `colors = OutlinedTextFieldDefaults.colors(...)` from theme
- Never `Modifier.padding` inside a field — use field slots
- Never replace the focus ring; M3 handles it
- Forbidden: pill-shaped TextFields unless anchor allows, glass backgrounds, gradient borders, floating-label hacks

---

## Card

Pick ONE Card variant per surface. Document choice in `the agent instruction file (AGENTS.md or CLAUDE.md)`.

| Variant | Use |
|---|---|
| `ElevatedCard` | Default for content cards in Material You / Apple Music anchors |
| `FilledCard` | Subtle grouping; use when surface tint is enough |
| `OutlinedCard` | Default for Linear / Stripe / Notion / Editorial anchors |

**Rules:**
- Background: handled by variant. Do not `Modifier.background(...)`.
- Shape: `MaterialTheme.shapes.large` (default Card shape). Override only via theme.
- Padding: use a `Column(modifier = Modifier.padding(MaterialTheme.spacing.lg))` inside; never on the Card itself.
- Spacing inside: pick one — `compact` (12.dp), `default` (16.dp), `spacious` (24.dp). Do not mix on one surface.
- `onClick` overload makes it interactive — use it instead of wrapping in `Modifier.clickable`.
- Forbidden: nested Cards more than 1 level deep, mixing elevated + outlined on one screen, mixing shape sizes inside one card.

---

## ListItem

Use Material 3 `ListItem` from `androidx.compose.material3`.

- Min height: 56.dp (1-line), 72.dp (2-line), 88.dp (3-line)
- `headlineContent`: required, `bodyLarge` style
- `supportingContent`: optional, `bodyMedium`, max 2 lines
- `leadingContent`: 24.dp `Icon` OR 40.dp `Avatar` OR 56.dp `AsyncImage`
- `trailingContent`: 24.dp `Icon` OR `Switch` OR meta `Text` (`labelMedium`)
- `colors = ListItemDefaults.colors(...)` from theme
- Wrap in `Modifier.clickable` for interactivity (no `onClick` slot in ListItem)
- Divider: use `HorizontalDivider()` between items, NOT after the last item
- Forbidden: shadows on list items, gradient backgrounds, custom heights below 56.dp

---

## TopAppBar

Pick ONE per screen — they are not interchangeable:

| Variant | When |
|---|---|
| `TopAppBar` (small) | Default screens, content-heavy lists |
| `CenterAlignedTopAppBar` | Modal screens, dialogs that promote to full screen, simple confirmation flows |
| `MediumTopAppBar` | Section landing screens with one prominent title |
| `LargeTopAppBar` | Top-level destinations where the title IS the brand statement |

**Rules:**
- Always pair with `Scaffold(topBar = { ... })`
- Always set `scrollBehavior` if the screen scrolls: `TopAppBarDefaults.enterAlwaysScrollBehavior()` (default) or `pinnedScrollBehavior()`
- Edge-to-edge: do NOT add `statusBarsPadding()`; `Scaffold` + window insets handle it
- `navigationIcon` slot for back arrow with `contentDescription = "Back"`
- `actions` slot for `IconButton`s — max 3 visible, overflow into `IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }` + `DropdownMenu`
- Forbidden: custom AppBar via raw `Row`, gradient backgrounds, animated logos, "scrolling collapsing parallax hero" mash-ups outside `LargeTopAppBar`

---

## Navigation

| Composable | Destinations | Use |
|---|---|---|
| `NavigationBar` | 3–5 | Phone bottom nav |
| `NavigationRail` | 3–7 | Tablet, foldable expanded, landscape |
| `NavigationDrawer` (`ModalNavigationDrawer` / `PermanentNavigationDrawer`) | 5+ | Top-level + buckets |
| `NavigationSuiteScaffold` | varies | Adaptive — preferred for adaptive layouts |

**NavigationBar rules:**
- Max 5 `NavigationBarItem`s. Hard ceiling.
- Each item: `icon` (selected vs unselected via state), `label`, `selected`, `onClick`
- `alwaysShowLabel = true` (Material 3 default)
- Use Material Symbols filled = selected, outlined = unselected (or vice versa, document choice)
- Forbidden: hiding labels, FAB inside NavigationBar, custom heights, more than 5 items, mixing icon families

---

## FloatingActionButton

- **One FAB per screen.** Hard rule. If you need two actions, the second goes in `TopAppBar.actions`.
- FAB is the **single most important action** on the screen. Not "another action".
- `FloatingActionButton` (default 56.dp) or `LargeFloatingActionButton` (96.dp) or `SmallFloatingActionButton` (40.dp) or `ExtendedFloatingActionButton` (label + icon)
- `contentDescription` required on the icon
- Position: bottom-end via `Scaffold(floatingActionButton = { ... }, floatingActionButtonPosition = FabPosition.End)`
- `ExtendedFloatingActionButton`: label sentence case, ≤ 3 words
- Forbidden: 2 FABs, decorative FAB without action, FAB that opens a menu of FABs, "speed dial"

---

## ModalBottomSheet

- Use `ModalBottomSheet` from `androidx.compose.material3`
- `sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = ...)`
- `onDismissRequest`: required
- Shape: `MaterialTheme.shapes.extraLarge` (top corners only — Material 3 handles it)
- Inside: vertical stack with `MaterialTheme.spacing.lg` padding
- Drag handle: built-in via `BottomSheetDefaults.DragHandle()`
- Predictive back: handled automatically by `ModalBottomSheet` on API 34+
- Edge-to-edge: respect `WindowInsets.navigationBars` via `contentWindowInsets` parameter
- Forbidden: bottom sheets with no drag handle, sheets taller than 90% screen without internal scroll, sheets that block back gesture

---

## AlertDialog / BasicAlertDialog

- Use `AlertDialog` (Material 3 styled) or `BasicAlertDialog` (custom content)
- `title`: `Text` with `headlineSmall` style
- `text`: `Text` with `bodyMedium` style
- `confirmButton`: `TextButton` — verb-led label ("Delete", "Save", "Discard")
- `dismissButton`: `TextButton` with "Cancel" (Android convention) or "Keep editing"
- `icon`: optional, 24.dp, decorative
- Width: Material 3 handles; do not override
- Forbidden: nested dialogs, dialogs over dialogs, scrollable dialogs (use ModalBottomSheet for long content), "Yes/No" labels

---

## Snackbar

- Show via `SnackbarHostState` from `Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) })`
- `scope.launch { snackbarHostState.showSnackbar(message = ..., actionLabel = ..., duration = SnackbarDuration.Short) }`
- One action max
- `duration`: `Short` (4s) for confirmations, `Long` (10s) for actionable, `Indefinite` for errors that need user response
- Position: bottom (Material 3 default — do not override)
- Forbidden: top snackbars, snackbars with two actions, custom backgrounds, snackbars that block back gesture

---

## Empty state

Composition (vertical, centered):
1. `Icon` 48.dp, `tint = MaterialTheme.colorScheme.onSurfaceVariant`
2. `Spacer(Modifier.height(MaterialTheme.spacing.md))`
3. Headline `Text` `headlineSmall`, `MaterialTheme.colorScheme.onSurface`
4. `Spacer(Modifier.height(MaterialTheme.spacing.sm))`
5. Description `Text` `bodyMedium`, `MaterialTheme.colorScheme.onSurfaceVariant`, max 2 lines
6. `Spacer(Modifier.height(MaterialTheme.spacing.lg))`
7. Primary action `Button` (single)

**Container:** `Column(Modifier.fillMaxSize().padding(MaterialTheme.spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center)`

**Forbidden:** stock illustration of "person at desk", "rocket launching", any generic empty-state vector. Use a single Material Symbols icon.

---

## Loading state

- **Skeleton (preferred)**: build the eventual layout shape with `Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small))` — NO infinite shimmer animation across the screen
- **`CircularProgressIndicator`**: only for indeterminate operations. 24.dp inside a button, 48.dp page-level
- **`LinearProgressIndicator`**: when % is known (download, upload, multi-step task)
- One indicator per surface. Never both spinner + skeleton.
- Forbidden: full-screen spinner over a screen that could be skeletoned, infinite shimmer placeholders that span the whole screen.

---

## Error state

| Scope | Composition |
|---|---|
| Inline (in-flow) | `Surface(color = errorContainer, shape = MaterialTheme.shapes.medium)` containing `Row { Icon(Icons.Default.ErrorOutline) + Column { Text(headline) + Text(detail) + TextButton("Try again") } }` |
| Transient | Snackbar with `actionLabel = "Retry"`, `duration = Indefinite` |
| Page-level | Empty-state composition with error icon, specific message, and retry button |

**Forbidden:** silent failures, generic "Error occurred", `Log.e` only, errors as a `Toast` (use Snackbar).

---

## Touch targets — hard rule

Every interactive element must have a tap target ≥ 48.dp on each axis. If the visual is smaller:

```kotlin
Modifier
    .minimumInteractiveComponentSize() // ensures 48.dp tap target
    .clickable(onClick = ...)
```

Material 3 components (Button, IconButton, Switch, Checkbox, etc.) honor this by default. Custom `Modifier.clickable` Composables do NOT — you must add `minimumInteractiveComponentSize()` yourself.

---

## Edge-to-edge — hard rule

In every Activity:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge() // androidx.activity.enableEdgeToEdge
    setContent { AppTheme { ... } }
}
```

In your composables:

- Use `Scaffold` — it handles `WindowInsets.systemBars` automatically
- For content that scrolls under `TopAppBar`, the Scaffold's `contentPadding` covers it
- For inputs that need to dodge the keyboard: `Modifier.imePadding()` on the relevant container
- For `LazyColumn` content, pass `contentPadding = paddingValues` from Scaffold so first/last items don't sit under chrome
- NEVER hardcode `Modifier.statusBarsPadding()` or `Modifier.navigationBarsPadding()` ignoring the inset API — only use them inside a Scaffold-less screen, and only if you've documented why

Forbidden: ignoring window insets, pixel-fudging top padding to "look right".

---

## Density modes

- **dense**: subtract 1 step from spacing (`md` → `sm` for component padding), use `sm` button size, use `compact` Card padding (12.dp)
- **balanced** (default): use `md` button size, `default` Card padding (16.dp)
- **airy**: use `lg` button size, `spacious` Card padding (24.dp), +1 step on section spacing
