# Copy & Voice (Android)

Real copy is non-negotiable. The fastest tell of AI slop is "Lightning fast / Built for the modern web" lorem-ipsum-with-buzzwords. This file is the rule set.

## Hard bans (never write these)

See `ban-patterns.md` § 2 for the full list. Headline:

- "Lorem ipsum"
- "Lightning fast" / "Blazing fast"
- "Built for the modern X"
- "Powered by AI"
- "Unleash the power of"
- "Get started in seconds"
- "The future of X"
- "Beautiful and intuitive"
- "Seamlessly integrate"
- "Effortlessly"
- "Reimagined" / "Redesigned"

Plus generic CTA labels: "Get Started", "Learn More", "Click Here", "Submit".

## Required behavior when copy is needed

If the user has not given you the copy AND you don't have enough context to write it specifically:

```
STOP. Ask the user:
"I need real copy for [section]. Tell me:
 1. What does this product/feature actually do (one sentence, no marketing)?
 2. Who is it for (specific role)?
 3. What's the single most important thing the headline should say?"
```

Do NOT invent product positioning. Do NOT use placeholder copy and tell the user "replace later".

## Sentence case — Material 3 default

**All Material 3 components use sentence case for labels.** This is non-negotiable on Android — Material 3 design guidance explicitly removed Title Case from buttons and tabs.

- ✅ "Save changes"
- ❌ "Save Changes"
- ✅ "Add a column"
- ❌ "Add A Column"

The only exceptions:
- Proper nouns (product names, company names, place names)
- Brutalist anchor (uppercase deliberate)
- App name in `TopAppBar` title

## Headline rules (`headlineLarge`, `headlineMedium`, `displayLarge`)

- One claim, not three. Not "Fast, secure, and beautiful — the all-in-one X."
- Specific verbs over abstract nouns. "Renders 1M rows in 80ms" not "Optimized performance."
- Numbers when you have them. Not when you don't.
- Length: ≤ 8 words for `displayMedium`+, ≤ 60 chars for any hero on a screen.
- Avoid colon constructions ("X: the Y for Z") unless the user's voice already uses them.

## Subhead rules (`titleMedium`, `bodyLarge`)

- One sentence. ≤ 22 words.
- Says WHAT and FOR WHO. Not why-it-matters or how-it-feels.
- Plain language. If a 14-year-old wouldn't understand a word, replace it.

## Button labels

- Verb-led, specific to the action. "Start free trial" not "Get Started". "Read docs" not "Learn More". "Add column" not "Submit".
- ≤ 4 words.
- Sentence case (Material 3 default — never Title Case).
- `ExtendedFloatingActionButton` label: ≤ 3 words, verb-led.

## Empty state copy

Format: `[What's missing] + [why you'd want one] + [exact next action]`

✅ "No projects yet. Projects group your work and let you share with teammates. Create your first project to start."
❌ "No data found. Get started by creating a project."

## Error message copy

Format: `[What happened in plain language] + [what to do about it]`

✅ "We couldn't reach the server. Check your connection and try again — your draft is saved on this device."
❌ "Error: Network request failed (500)"

If you don't know the cause, ask for it. Don't ship a generic "Something went wrong".

## Android microcopy patterns (platform conventions)

| Context | Forbidden | Allowed |
|---|---|---|
| Sign-up CTA | "Get Started" | "Create account" |
| Confirm delete | "Are you sure?" | "Delete `<item>`? This can't be undone." |
| Loading | "Loading..." | "Indexing 1,247 documents (00:42 remaining)" |
| Success Snackbar | "Success!" | "Saved to Drafts" |
| Dialog dismiss | "Yes" / "OK" (when ambiguous) | "Delete" / "Discard" / "Keep editing" |
| Dialog acknowledge | "Got it!" | "OK" (Android dialog convention — short, neutral) |
| Banner dismiss | "Dismiss" | "Got it" (informational banner only) |
| 404 / not found | "Page not found" | "We couldn't find that. It may have been moved or deleted." |
| Login prompt | "Sign in to continue" | "Sign in to save your changes" |
| Permission rationale | "We need permission" | "Allow location to find nearby stores. We never share your location." |

**Android conventions worth respecting:**

- "OK" is the canonical short acknowledgment in `AlertDialog` — not "Got it!" or "Confirm" when no specific verb fits
- "Cancel" is the canonical dismiss for destructive dialogs (not "Nevermind" or "Go back")
- "Allow" / "Don't allow" for permission dialogs (Android system convention; mirror it)
- Snackbar action: ≤ 1 word ideally, ≤ 2 max ("UNDO", "Retry") — UPPERCASE is the legacy snackbar convention but Material 3 has moved to sentence case; pick one and document it

## Voice

The voice comes from the chosen anchor:

- **Linear/Vercel**: terse, present-tense, technical. "Ships in 2 weeks." "Renders instantly."
- **Stripe**: confident, calm, declarative. "Build a global business with one integration."
- **Notion**: warm, conversational, second-person. "Everything you need, in one place you'll actually open."
- **Editorial**: long-form, considered, paragraph-shaped. Three sentences, not three bullets.
- **Apple Music**: aspirational, sensory. "Built for All Day. And Then Some." (Apple uses Title Case in marketing but in-app uses sentence case — follow in-app rules.)
- **Material You**: friendly, second-person, warm but practical. "Pick where to start" / "Make it yours."
- **Brutalist**: blunt, capslock optional, anti-marketing. "WE MADE A THING. IT WORKS."

## Localization readiness

- Never concatenate strings: not `"Hello, " + name`. Use `stringResource(R.string.hello_user, name)`.
- Never assume English-length: leave room for German (1.3x) and Russian (1.2x).
- Keep button labels short for translation safety — ≤ 4 words.
- Use `pluralStringResource(R.plurals.items, count, count)` for any countable noun.

## Final check

After writing any copy: read it aloud. If it sounds like a Compose tutorial sample app, rewrite it. If it sounds like a marketing landing page bolted onto an Android app, rewrite it.
