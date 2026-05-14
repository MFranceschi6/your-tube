# YT-0026 — Haptics & Accessibility

> Companion to `decision-log.md` and `swiftui-spec.md`. Covers haptic table, VoiceOver labels, focus-on-tab-activation contract, `UIAccessibility.post(.announcement)` rules, Dynamic Type behavior, and reduce-motion contract for the Search screen.

## Haptics — `.sensoryFeedback` (iOS 17+)

All haptics use the SwiftUI `.sensoryFeedback(_:trigger:)` modifier. Drop to `UIImpactFeedbackGenerator` only where the modifier doesn't apply (none in this surface).

### Full table

| UI event | Variant | Why |
|---|---|---|
| Suggestion chip — single tap | `.selection` | Like a segmented-control flip; quick and dry |
| Suggestion chip — context-menu "Remove" | `.warning` | Destructive but recoverable (Undo) |
| Suggestion chip — context-menu open (long-press) | (no haptic — iOS provides one automatically for `.contextMenu`) | n/a |
| Suggestion chip — curated, context menu | (no menu — curated chips have no remove action) | n/a |
| Submit query (Return key, `.onSubmit(of: .search)`) | `.impact(weight: .medium)` | Confident commit |
| Cancel button (system, `.searchable`) | system-provided | iOS handles it |
| System clear (`xmark.circle.fill`, `.searchable`) | system-provided | iOS handles it |
| Result row tap → starts playback | `.impact(weight: .medium)` | Mirrors play tap in NowPlaying (`YT-0027/decision-log.md` §8) |
| Result row `.contextMenu` selection (Play next / Add to queue / Add to playlist) | `.selection` | Menu item commits |
| Result row `.contextMenu` — Share (`ShareLink`) | system-provided | iOS handles it |
| Retry button (state-catalog C4/C5) | `.impact(weight: .medium)` | Confident commit |
| Offline "Go to Library" secondary CTA (C5) | `.selection` | Navigation, not commit |
| Network reconnect during error state → auto-recover | (no haptic) | Recovery is silent; the screen swap is the signal |

### Pattern — bind haptics to the trigger value

```swift
.sensoryFeedback(.selection, trigger: vm.body) { old, new in
  if case .results = new, case .loading = old { return true }   // loading → results
  return false
}
```

Use `trigger:condition:` form to keep haptics from firing on every `body` change — only on the transitions the user perceives as commit.

### Explicitly NO haptics

- Every keystroke in the SearchBar — the system keyboard already provides keystroke haptics per the user's Settings → Sounds & Haptics preference.
- Debounced auto-submission (D2) — only the Return-key fast-path fires `.impact(weight: .medium)`; the silent debounced fire is mute.
- EQ indicator transitions — decorative.
- Skeleton-to-results swap — the visual change is enough; firing haptics here would feel "snappy" in the bad way.

---

## VoiceOver labels — every interactive element

All strings come from `Localizable.strings`. No hardcoded literals.

| Element | VoiceOver label (state-aware) | Hint / traits |
|---|---|---|
| `.searchable` field | `"Search YourTube"` (system) | trait: `searchField`; hint: `"Find tracks, channels, and topics"` |
| System clear (`xmark.circle.fill`) | `"Clear text"` (system) | trait: `button`; system-provided |
| System Cancel button | `"Cancel"` (system) | trait: `button`; system-provided |
| Suggestion chip — recent | `"Recent search: {query}, double-tap to search, action available to remove"` | trait: `button`; `accessibilityCustomActions = [.init(name: "Remove from recent searches") { ... }]` |
| Suggestion chip — curated | `"Suggested search: {query}, double-tap to search"` | trait: `button` |
| Suggestion chip — selected | same as above + `accessibilityValue("Selected")` | trait: `button`; `selected` set |
| Skeleton row (C1) | `accessibilityHidden(true)` — not focusable | list container value: `"Loading"` |
| Result row | `"\(title) by \(channel), \(duration)"` | trait: `button`; custom actions: `"Add to playlist"`, `"Share"`, `"Play next"`, `"Add to queue"` |
| Result row — active (EQ playing) | `"\(title) by \(channel), \(duration), now playing"` | trailing "now playing" status appended |
| Result row — active (paused) | `"\(title) by \(channel), \(duration), paused"` | |
| EQ indicator capsules | `accessibilityHidden(true)` — decorative | the row label carries the "now playing" status |
| C2 empty cell | `"Search YourTube. Find tracks, channels, and topics from your subscriptions."` (system `ContentUnavailableView`) | heading |
| C3 no-results | `"No results for \"\(query)\". Check your spelling or try a different search."` | heading; **`UIAccessibility.post(.announcement, ...)` on appear** |
| C4 generic error | `"Couldn't search. Something went wrong on our end. Try again in a moment."` | heading; announces |
| C5 offline | `"You're offline. Connect to the internet to search. Your saved playlists are still available in Library."` | heading; announces |
| Retry button | `"Try again"` | trait: `button` |
| Offline secondary CTA | `"Go to Library"` | trait: `button`; hint: `"Opens the Library tab"` |

### Live-region-style announcements

iOS does not have a direct `liveRegion` equivalent for arbitrary views; we post announcements at the right moment:

| Trigger | Mechanism | Notes |
|---|---|---|
| Results populate after submit | `UIAccessibility.post(notification: .announcement, argument: "\(count) results for \(query)")` in an `.onChange(of: vm.body)` watcher when `body` becomes `.results` | Fire only when transitioning **from `.loading`** — not on subsequent re-renders. |
| No-results appears | `UIAccessibility.post(notification: .screenChanged, argument: titleView)` | iOS Search-empty pattern. `.screenChanged` because C3 replaces a loading state. |
| Error cell appears (C4 / C5) | `UIAccessibility.post(notification: .screenChanged, argument: titleView)` | Polite-equivalent; matches state-catalog `error.md` § Polite live region. |
| Loading state | (no announcement) | List container carries `accessibilityValue("Loading")`; no announcement. |
| Chip removed via context menu | system-provided context-menu dismissal announce + toast announce | toast-catalog already handles this. |

Implementation for the results count:

```swift
.onChange(of: vm.body) { oldValue, newValue in
  if case .loading = oldValue, case .results(let items) = newValue {
    UIAccessibility.post(
      notification: .announcement,
      argument: "\(items.count) results for \(vm.query)"
    )
  }
}
```

---

## Focus-on-tab-activation contract (D7)

Per `decision-log.md` §D7: when the Search tab activates and `query.isEmpty`, the field receives focus and **VoiceOver announces the field label**.

| Scenario | Focus | VoiceOver announces |
|---|---|---|
| Cold launch → Search tab | search field | `"Search YourTube. Search field. Find tracks, channels, and topics."` (system) |
| Switch from Library tab → Search tab (blank query) | search field | same as above |
| Switch from another tab → Search tab (query preserved from `@SceneStorage`) | last focused element OR the results `List` | does NOT re-announce |
| Process death / state restore | results list (if non-blank query) | does NOT re-announce |
| Back from NowPlaying → Search (results visible) | results list (preserved scroll position) | does NOT re-announce |

The `@FocusState` + `.onAppear { if vm.query.isEmpty { isSearchFocused = true } }` pattern in `swiftui-spec.md` produces this behavior. `.onAppear` runs on view entry — re-entry from NowPlaying via push-pop is still `.onAppear`, hence the `vm.query.isEmpty` guard.

---

## Dynamic Type (`accessibilityXxx` sizes)

All text uses system `.font(.body)` / `.headline` / `.subheadline` style tokens — they scale with Dynamic Type automatically. The `.font(.system(size: ..., weight: ...))` calls in `swiftui-spec.md` use explicit sizes; **wrap each in `.dynamicTypeSize(...)` clamps** where the layout demands it.

| UI element | Style | Behavior at `.accessibility5` |
|---|---|---|
| `.searchable` field | system-provided | grows; field gets taller. |
| Suggestion chip label | `.system(size: 14, weight: .medium)` | scales freely; chip height grows; horizontal scroll absorbs it. |
| Result row title | `.system(size: 15, weight: .medium)` | scales freely; `.lineLimit(1)` + `.truncationMode(.tail)` (inherited from `TrackRow`). |
| Result row subtitle (channel · duration) | `.system(size: 13)` | scales freely. |
| Duration badge | `.system(size: 10, weight: .medium).monospacedDigit()` | **clamp** via `.dynamicTypeSize(...DynamicTypeSize.accessibility3)` — tabular layout otherwise breaks. |
| State-catalog cell title (C2/C3/C4/C5) | system `ContentUnavailableView` title style | scales freely; ContentUnavailableView wraps in a `ScrollView` per state-catalog `README.md`. |
| Action button label (Try again / Clear search / Go to Library) | `.controlSize(.large)` button | scales freely. |
| Body copy | `.body` | scales freely; `.lineLimit(nil)` + `.fixedSize(horizontal: false, vertical: true)`. |

**Verify in QA**: Settings → Accessibility → Display & Text Size → Larger Text → maximum (`.accessibility5`). The Search screen must:
1. `.searchable` field visible without horizontal truncation of the prompt.
2. Chip strip horizontally scrollable to reveal all 6 chips.
3. Result rows scrollable.
4. State-catalog cell readable end-to-end inside its `ScrollView`.

---

## Reduce-motion contract

Read `@Environment(\.accessibilityReduceMotion)`.

| Animation | Default | Reduce Motion |
|---|---|---|
| EQ indicator bars (D4) | `TimelineView(.animation(minimumInterval: 1/30, paused: !isPlaying))` | **Static** — three bars at fixed mid-heights `[12, 9, 11]` pt (per `swiftui-spec.md` `SearchEqIndicator`). |
| Skeleton shimmer (C1) | 1400 ms shimmer sweep | **Static** — solid `#2C2C2E` blocks (per state-catalog § Shimmer rules). |
| Chip selection color swap | `.animation(.easeInOut(duration: 0.2), value: selection)` | `.animation(.linear(duration: 0), value: selection)` — instant. |
| Result list enter (post-loading) | `.transition(.opacity.combined(with: .move(edge: .bottom)))` | `.transition(.opacity)` only — no slide. |
| State-catalog cell enter | inherited from `ContentUnavailableView` / `ErrorStateCells` | inherited. |
| Color/state changes (active-row tint flicker on play/pause) | implicit `.animation(.default, value: isPlaying)` | **stays** — that's information, not motion. |

Pattern (already in `SearchEqIndicator`):

```swift
if reduceMotion || !isPlaying {
  HStack(alignment: .bottom, spacing: 2) {
    Capsule().fill(barColor).frame(width: 3, height: 12)
    Capsule().fill(barColor).frame(width: 3, height: 9)
    Capsule().fill(barColor).frame(width: 3, height: 11)
  }
  return
}
// TimelineView ...
```

---

## Tap targets

Minimum **44 × 44 pt** for every interactive element (`docs/design-system.md`).

| Element | Visible | Hit area |
|---|---|---|
| `.searchable` field | full-width × ~36 pt | system-provided ≥ 44 pt |
| System clear / Cancel | 22 pt glyph / system text | system-provided ≥ 44 pt |
| Suggestion chip | ~36 pt tall visible | `.contentShape(Rectangle())` extends to 44 pt via SwiftUI `Button` default minimum |
| Result row | ~64 pt tall | full-row tap |
| Row ellipsis (overflow icon) | 22 pt glyph | `.frame(width: 44, height: 44).contentShape(Rectangle())` |
| EQ indicator | 22 × 22 pt | not interactive |
| State-catalog action button (Try again / Go to Library / Clear search) | `.controlSize(.large)` ~50 pt | per state-catalog `empty.md` |

---

## Color contrast

| Pair | Ratio | Pass |
|---|---|---|
| `.searchable` field text (`.primary`) on field fill (`.tertiarySystemFill` over `.systemBackground`) | ~13:1 dark / ~13:1 light | AAA |
| Placeholder (`.placeholderText`) on field fill | ~4.6:1 | AA body |
| Chip label (`.primary`) on unselected chip fill | ~14:1 | AAA |
| Selected chip label (`.white`) on `.accent` (`#8B5CF6`) | 4.6:1 | AA — just over the 4.5:1 floor; do NOT lighten the accent |
| Active-row tint (`Color.accent.opacity(0.16)` over `.systemBackground`) | non-text, indicator only | ≥ 3:1 not required (decorative; EQ bars at full alpha carry meaning) |
| EQ bar `.accent` on active-row tint | ~4.6:1 over the tinted background | AA non-text |
| Skeleton block `#2C2C2E` on `.systemBackground` `#0F0F0F` | ~2:1 | non-text — passes by design |
| Action button label (`.white`) on `.accent` (Try again) | 4.6:1 | AA |

Light theme: `Color.accent` darkens to `#7C3AED` per `tokens.json`; re-validate at theme switch time. iOS automatically swaps the asset-catalog value.

---

## Liquid Glass on iOS 26 — accessibility notes

When the system renders `.searchable` in a Liquid Glass material on iOS 26, the **field content (placeholder, query text, leading magnifyingglass) MUST maintain ≥ 4.5:1 contrast** against the worst-case background behind the glass. iOS handles this via vibrancy automatically — do not override the material with a fixed tint.

If we ever introduce a per-screen glass surface (currently none in Search — see README §"Liquid Glass on iOS 26+"), wrap any text inside it with `.foregroundStyle(.primary)` and let the system manage vibrancy.
