# Icons & Imagery (Android — Compose)

## Icon library — Material Symbols, one family per project

Android has ONE acceptable icon source: **Material Symbols** via `androidx.compose.material.icons.Icons`. Pick ONE family and stick with it. Document the choice in `the agent instruction file (AGENTS.md or CLAUDE.md)`.

| Family | Import | When |
|---|---|---|
| `Icons.Filled` (default) | `androidx.compose.material.icons.Icons.Filled.*` | Material You / Apple Music anchors — high-emphasis surfaces |
| `Icons.Outlined` | `androidx.compose.material.icons.Icons.Outlined.*` | Notion / Stripe / Editorial / Linear — most common Material 3 default |
| `Icons.Rounded` | `androidx.compose.material.icons.Icons.Rounded.*` | Friendly brand / Material You with softer feel |
| `Icons.Sharp` | `androidx.compose.material.icons.Icons.Sharp.*` | Brutalist / hard-edged anchors |
| `Icons.TwoTone` | `androidx.compose.material.icons.Icons.TwoTone.*` | Rare — only when anchor specifies and brand demands it |

**Hard rules:**

- **Never mix two families in one project.** No `Icons.Filled.Home` next to `Icons.Outlined.Settings`.
- **Selection convention** is the only allowed exception: `Icons.Filled.Home` for selected `NavigationBarItem`, `Icons.Outlined.Home` for unselected. Document this convention in the agent instruction file (AGENTS.md or CLAUDE.md) if you use it.
- **No third-party icon packs** (Phosphor, Lucide ports, FontAwesome) unless explicitly declared in the agent instruction file (AGENTS.md or CLAUDE.md) with a justification.
- **No raw vector drawables for common icons.** If Material Symbols has it, use Material Symbols.
- For specialty icons not in the set: import as `ImageVector` from `androidx.compose.ui.graphics.vector.ImageVector`, place in `ui/icons/` package, document.

## Icon sizing — tied to nearby text

| Context | Size |
|---|---|
| Inline with body text | match text size (16.dp for `bodyLarge`, 14.dp for `bodyMedium`) |
| Inside a `Button` | 18.dp (md) or 24.dp (lg) — Material 3 defaults |
| Standalone in `IconButton` | 24.dp (Material 3 default) |
| `NavigationBarItem` icon | 24.dp |
| `TopAppBar` action / nav icon | 24.dp |
| `FloatingActionButton` icon | 24.dp (default), 36.dp (large FAB) |
| `ListItem` leading | 24.dp (Icon) or 40.dp (Avatar) |
| Empty state hero icon | 48.dp |
| Section heading icon | 20.dp max |

```kotlin
Icon(
    imageVector = Icons.Outlined.Settings,
    contentDescription = "Settings", // never null on icon-only IconButton
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.size(24.dp), // 24.dp is the only allowed literal — Material 3 default
)
```

## Icon color

- Always use `tint = MaterialTheme.colorScheme.*`. NEVER `tint = Color(0xFF...)`.
- Default: `MaterialTheme.colorScheme.onSurfaceVariant`
- Active / selected: `MaterialTheme.colorScheme.onSurface` or `MaterialTheme.colorScheme.primary`
- Destructive: `MaterialTheme.colorScheme.error`
- Inside a `Button`: inherit from button content color (`tint = LocalContentColor.current`)
- Forbidden: hardcoded color literals as tints

## Accessibility — TalkBack labels

- Every icon-only `IconButton`: `Icon(..., contentDescription = "Save")` — required.
- Decorative icons inside a labeled element: `contentDescription = null` (the parent provides the label) — required.
- NEVER `contentDescription = ""` (empty string) — TalkBack treats as unlabeled and announces "button".
- NEVER `contentDescription = "icon"` or `"image"` — describe the action, not the visual.

## Forbidden icon usage

- Mixing icon families in one project (Filled + Outlined on the same screen, except the selection convention)
- Emoji as icons or bullets in serious productivity UI (sparkle, rocket, lightning, fire)
- Icons larger than the heading they accompany
- Icons without `contentDescription` on icon-only `IconButton`
- Decorative icons floating in backgrounds via `Modifier.offset` + `Canvas`
- `Icon` with `Modifier.background(...)` outside of a `IconButton` / `FilledIconButton` wrapper

---

## Imagery

### Photography — use Coil

Use `coil-compose` (`io.coil-kt.coil3:coil-compose`) for all network and local image loading. Never `Image(painterResource(...))` for network images. Never `BitmapFactory.decodeStream`.

```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(url)
        .crossfade(true)
        .build(),
    contentDescription = "Profile photo of $name", // describe the content
    contentScale = ContentScale.Crop,
    modifier = Modifier
        .size(56.dp)
        .clip(MaterialTheme.shapes.medium),
)
```

**Rules:**

- Default: NONE. Use real product screenshots, not stock people.
- If real photos required: ONE consistent treatment (desaturated / monochrome / untouched). Never mix.
- Always provide a `contentDescription` describing the content. For decorative-only: `contentDescription = null`.
- Always provide a placeholder via `placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)` or a `Brush` from theme.
- Always provide an `error` painter for failed loads.
- Use `ContentScale.Crop` for square thumbnails, `Fit` for full-bleed hero, `FillBounds` only when the image is purely decorative.

**Forbidden:**

- Stock photos of people pointing at laptops
- "Abstract tech" gradients labeled as imagery
- AI-generated stock photos with telltale fingers / faces
- Picsum / Unsplash filler in production code
- `https://placehold.co/...` URLs

### Illustration

- Default: NO illustration. Let typography and structure carry the screen.
- If required: ONE style throughout (line, geometric, painterly — pick one).
- Forbidden: "isometric people" stock illustration set; "rocket launching" empty states; generic "team collaboration" vectors

### App icon / branding

- The user must provide their app icon as `mipmap-anydpi-v26/ic_launcher.xml` (adaptive icon with foreground + background). If they haven't, ask — do not generate a placeholder bird/star/circle.
- Splash screen: use the AndroidX SplashScreen API (`androidx.core.splashscreen`), NOT a `Theme.SplashScreen` hack. The splash icon = the app icon foreground; the background = `colorScheme.background`. No other art.

### Screenshots & product visuals

- Real screenshots ALWAYS preferred over mockups
- Frame: subtle 1.dp border (`MaterialTheme.colorScheme.outlineVariant`) + `MaterialTheme.shapes.medium` corners
- Annotations: use `MaterialTheme.colorScheme.primary` accent, never red-on-screenshot

## Decorative graphics

By default: NONE. Whitespace and typography are the design.

If the chosen anchor explicitly uses decoration (Brutalist hard offset shadows, Material You expressive shapes), follow the anchor recipe. Never improvise via `Canvas` shapes.
