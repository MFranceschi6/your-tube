# YT-0073 — Cross-Platform Empty / Loading / Error State Catalog

> **Audience:** Claude Code, implementing list-driven screens in Compose (Android) and SwiftUI (iOS).
> **Source mockup:** `mockup.html` — every cell rendered side-by-side in dark theme.
> **Status contract:** every list-driven screen MUST render exactly one of `loading`, `content`, `empty`, `error` at any time. No mixed states.
> **Not a binding visual spec.** Match the *intent*, *copy*, and *interaction contract*. Use idiomatic Material 3 / SwiftUI primitives — never port web CSS literally.

---

## What's in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. The index of cells, the status contract, accessibility expectations. |
| `copy.md` | Canonical user-facing English copy for every cell. Source of truth — implementations quote this verbatim. |
| `loading.md` | Loading variant per screen: skeleton template (count, height, shimmer rules) vs. spinner, with rationale. |
| `empty.md` | Empty variant per screen: icon (Material Symbols rounded / SF Symbol), title, body, primary action label, action target. |
| `error.md` | Error variant per screen: icon, title, body, retry label, retry action contract. Distinguishes offline / server / parse where it changes user action. |
| `mockup.html` | Every cell rendered in dark theme on iOS-393 and Android-360 device frames. Focused on intent, not pixel-perfect. |

---

## Catalog matrix

Every list-driven screen × every state. **A blank cell is a defect**, not "out of scope".

| Screen           | Loading              | Empty                                   | Error                              |
| ---------------- | -------------------- | --------------------------------------- | ---------------------------------- |
| Search           | C1 — skeleton ×6     | C2 — idle (chips) · C3 — no results     | C4 — generic · C5 — offline        |
| Library          | C6 — skeleton ×4     | C7 — no playlists                       | C8 — generic                       |
| Playlist detail  | C9 — skeleton header + ×6 | C10 — empty playlist               | C11 — generic                      |
| History          | C12 — skeleton ×6    | C13 — nothing played yet                | C14 — generic                      |
| Settings (ops)   | C15 — overlay spinner| n/a — page is static form               | C16 — operation failed             |

Cell IDs (`C1…C16`) are stable across `copy.md` / `loading.md` / `empty.md` / `error.md` / `mockup.html`. When a follow-up implementation task changes a string, change the cell — don't fork it.

> **Why Settings has no empty/loading rows.** Settings is a form, not a list. Rows render synchronously from local state. The only async surface is *operations* (export, import, clear cache, clear history) — those get a loading overlay (C15) and an inline error (C16). The page itself never renders an empty or error state.

---

## Status contract (every list-driven screen)

State machine, in order of precedence:

```
loading  →  empty   (request succeeded, zero items)
loading  →  content (request succeeded, ≥1 item)
loading  →  error   (request failed)
content  →  loading (refresh requested) — keep stale content visible, show subtle refresh indicator, NOT a full skeleton
empty    →  loading (retry / refetch)
error    →  loading (retry tapped)
```

Hard rules:

1. **Never render two states at once.** No "skeleton + empty illustration" hybrid. No "error toast over content" — if the data is stale and the refresh failed, keep the stale content and surface the failure as a subtle inline banner, not a full error screen. Initial-load failure is a full error screen.
2. **Skeleton ≠ spinner.** Skeleton when the *shape of the result is known* (search results, history rows, playlist rows). Spinner only for opaque operations where shape is unknown (Settings export/import/clear cache).
3. **Empty is not error.** "Search returned zero matches for `lofi xyz`" is `empty` (state C3), not `error`. The request succeeded.
4. **Retry is mandatory in error.** Every `ErrorState` has a single primary action labeled `Try Again` (or `Retry` — pick one and stick to it; this catalog uses `Try Again`). Retry re-runs the same request that produced the error.
5. **Offline distinguishes only when the user action changes.** If retry is the same regardless of cause, collapse to one error variant (C8, C11, C14, C16). Search is the one screen where offline gets its own variant (C5) because the user can also reach for cached/recent searches.

---

## Tokens — non-negotiable

All values are dark-theme. Light-theme follows `colors_and_type.css` overrides without per-cell adjustment.

| Token                  | Value                            | Used by                           |
|---|---|---|
| `--color-bg`           | `#0F0F0F`                        | screen background                  |
| `--color-surface`      | `#1C1C1E`                        | empty/error card on iOS form       |
| `--color-fg-primary`   | `#FFFFFF`                        | titles                             |
| `--color-fg-secondary` | `rgba(235,235,245,0.80)`         | body copy                          |
| `--color-fg-tertiary`  | `rgba(235,235,245,0.40)`         | empty/error icon glyph color       |
| `--color-accent`       | `#8B5CF6`                        | primary action button only         |
| `--color-error`        | `#FF453A`                        | error icon tint (subtle, see below)|
| `--skeleton-bg`        | `#2C2C2E`                        | skeleton block fill                |
| `--skeleton-shimmer`   | `rgba(255,255,255,0.06)`         | skeleton shimmer overlay           |
| skeleton shimmer cycle | `1400ms`, linear, infinite       | all skeletons                      |
| empty icon size        | `56dp` / `56pt`                  | every empty state                  |
| error icon size        | `56dp` / `56pt`                  | every error state                  |
| icon color             | `--color-fg-tertiary`            | empty AND error (NOT red)          |

**Error icon is NOT red.** Red would imply destructive or system-level alarm. A failed list load is recoverable and benign — render the icon at `--color-fg-tertiary` like an empty state, and let the *retry button* carry the affordance. Red is reserved for delete / destructive confirmation surfaces.

---

## Accessibility — applies to every cell

These rules apply to every cell unless an individual cell file overrides.

### Roles & focus

- Empty / error views are a single accessible group with role `image` (icon) + `text` (title + body) + `button` (action). On iOS, wrap in `accessibilityElement(children: .combine)` and provide a single `accessibilityLabel` of "{title}. {body}. {action label} button."
- Focus on appear: focus lands on the **title**. The icon is `accessibilityHidden` (decorative — described by the title).
- Tab/focus order: title → body → action button. Body is non-interactive; `accessibilityElement(children: .ignore)` on the body in iOS, `Modifier.semantics { heading() }` on title in Compose.

### Live announcements

- **Error state announces automatically** when it appears.
  - Android: `LiveRegion(LiveRegionMode.Polite)` on the error container; emit `Assertive` only on initial-load error in a foreground tab.
  - iOS: `UIAccessibility.post(notification: .screenChanged, argument: <title view>)` when the error view replaces a loading state.
- **Empty state does NOT auto-announce** in the general case — it appears in response to a user action they just performed (submitted search, opened empty playlist) and they will discover it via natural navigation. Exception: search "no results" (C3) announces politely because the user is waiting for an answer.
- **Loading state does NOT announce** every transition — that would spam screen readers. Set `accessibilityValue("Loading")` on the list container while a skeleton is shown; the screen reader will read it on focus.

### TalkBack / VoiceOver text per icon

| Cell | Icon | Spoken label (auto-generated from title — DO NOT also describe the glyph) |
|---|---|---|
| C2  | `magnifyingglass` / `search`           | "Search YourTube. heading." |
| C3  | `magnifyingglass` / `search_off`       | "No results. heading." |
| C7  | `music.note.list` / `library_music`    | "No playlists yet. heading." |
| C10 | `music.note` / `music_note`            | "This playlist is empty. heading." |
| C13 | `clock.arrow.circlepath` / `history`   | "Nothing played yet. heading." |
| C4 / C8 / C11 / C14 / C16 | `exclamationmark.triangle` / `error` (rounded outline) | "{Title}. heading." |
| C5 (offline) | `wifi.slash` / `wifi_off`     | "You're offline. heading." |

Icons are `accessibilityHidden = true`. The title carries the meaning. Do not double-announce the glyph.

### Dynamic Type / Font Scaling

- Every empty/error cell wraps in a `ScrollView` (iOS) or `verticalScroll(rememberScrollState())` (Compose) so the layout never clips at the largest text scale. Hit targets stay at minimum 44pt / 48dp regardless of scale.
- The icon does NOT scale with text. Hold it at 56pt/dp.
- On the largest text scales, the layout should naturally stack and become scrollable; do not collapse the icon or hide the body.

### Color contrast

- Title vs. background: `#FFFFFF` on `#0F0F0F` = 19.5:1. ✅
- Body vs. background: `rgba(235,235,245,0.80)` on `#0F0F0F` ≈ 12:1. ✅
- Action button label vs. accent fill: `#FFFFFF` on `#8B5CF6` = 4.6:1. ✅ (just over the 4.5:1 floor — do not lighten the accent)

---

## Per-platform mapping — the one-page version

| Concept           | Android (Compose / Material 3)                         | iOS (SwiftUI)                                       |
|---|---|---|
| Empty container   | `Column` centered with `Modifier.fillMaxSize()`         | `ContentUnavailableView { Label(...) } description:` |
| Error container   | `Column` centered with `Modifier.fillMaxSize()`         | `ContentUnavailableView` with retry action          |
| Skeleton row      | Custom `Box` with `placeholder()` (Accompanist) shimmer | Custom `Rectangle().redacted(reason: .placeholder)` + shimmer modifier |
| Spinner           | `CircularProgressIndicator()`                           | `ProgressView()`                                    |
| Primary action    | `FilledTonalButton` (empty) / `Button` (error)          | `.buttonStyle(.borderedProminent)` with `.tint(.accentColor)` |
| Icon vocabulary   | `Icons.Rounded.*` (Material Symbols rounded)            | SF Symbols (`Image(systemName:)`)                   |
| Auto-announce     | `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` | `UIAccessibility.post(.screenChanged, ...)`     |

> **Do not invent custom icons.** If the named symbol doesn't exist on a platform, use the closest equivalent from the platform's bundled set. The iOS-native and Android-native variants will *not* match glyph-for-glyph — they will match in *meaning*. That is the goal.

---

## Implementation order (suggested)

1. Add a `ListUiState<T>` sealed type at the data layer: `Loading | Empty | Content(T) | Error(cause)`. Both platforms.
2. Implement skeleton primitives (`SkeletonRow`, `SkeletonPlaylistRow`) once. Reuse across screens.
3. Implement `EmptyState` and `ErrorState` once each. Parameterize by icon, title, body, action.
4. Wire each screen's state machine. Each screen consumes one state, renders one of four branches. No exceptions.
5. Verify auto-announce on error in both screen readers before shipping.
6. Verify Dynamic Type / font scale 200% doesn't clip any cell.

---

## Out of scope for this catalog

- Per-platform code. Cells describe intent — Compose / SwiftUI implementation lands as separate follow-up tasks (one per platform per screen, scoped per epic).
- Custom illustrations. Icon-only, by design.
- Marketing or onboarding "empty" states (e.g. first-launch tour). Those are `OnboardingState`, not `EmptyState`, and live in a different catalog.
- Toast / Snackbar copy. Those are transient action confirmations — not list states.
