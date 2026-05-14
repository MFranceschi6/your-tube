# YT-0029 — Haptics & Accessibility (iOS Recently Played)

> Covers row VoiceOver reads, swipe-to-delete announcement, "Clear All" toolbar + alert, day-section headers via rotor, empty / error live regions, `.sensoryFeedback` table.

## `.sensoryFeedback` table

iOS uses the modern `.sensoryFeedback(_:trigger:)` modifier (iOS 17+) for haptics. Each entry below pairs a triggering state change with the `SensoryFeedback` style.

| UI event | Modifier | Notes |
|---|---|---|
| Row tap (play from here) | none — playback haptic owned by YT-0027 | — |
| Long-press (context menu opens) | system-rendered haptic | `.contextMenu` provides the native long-press haptic automatically — do not add one. |
| Swipe-to-delete commit (row dismissed) | `.sensoryFeedback(.success, trigger: removedEntryId)` | Triggered after the destructive `Button(role: .destructive)` action fires. |
| Swipe cancelled (spring-back) | none | — |
| Toolbar Menu opens | `.sensoryFeedback(.selection, trigger: menuOpenCount)` | Optional; system-rendered `Menu` haptic is fine on its own. Use only if menu open events feel underweight in user testing. |
| Context menu item tap (Play Next / Add to Queue / Add to Playlist / Share) | `.sensoryFeedback(.selection, trigger: contextActionId)` | — |
| Context menu item tap (Remove from History — destructive) | `.sensoryFeedback(.impact(weight: .medium), trigger: removeContextActionId)` | Pairs with destructive role. |
| Clear-all confirm tap (Clear button in alert) | `.sensoryFeedback(.warning, trigger: clearAllId)` | Bulk destructive — warning is the right scale. |
| Retry button tap (C14) | `.sensoryFeedback(.selection, trigger: retryCount)` | — |

No haptics on alert / sheet / context-menu open or close — system handles. No haptic on auto-dismiss of any surface — only on user action.

> **Why `.warning` for Clear All and `.success` for per-row swipe.** Per-row swipe is recoverable by replay; `.success` confirms the action. Bulk Clear All destroys unbounded metadata; `.warning` reflects the impact scale without sounding alarmist (`.error` would over-claim — this isn't an error).

### Wiring example

```swift
struct RecentlyPlayedScreen: View {
  @State private var removedTrigger = UUID()
  @State private var clearAllTrigger = UUID()

  var body: some View {
    // ... screen body
    .sensoryFeedback(.success, trigger: removedTrigger)
    .sensoryFeedback(.warning, trigger: clearAllTrigger)
  }
}
```

The triggers are bumped from the relevant action closures (`viewModel.remove(entry); removedTrigger = UUID()` — value change is what fires the feedback, not the modifier mount).

---

## VoiceOver row read

```swift
TrackRow(track: entry.track, supportingExtra: visibleTime)
  .accessibilityElement(children: .combine)
  .accessibilityLabel(
    "\(entry.track.title), \(entry.track.channel), played \(spokenTime(entry: entry, now: now))"
  )
  .accessibilityHint("Plays this track")
  .accessibilityActions {
    Button("Play Next")          { viewModel.playNext(entry) }
    Button("Add to Queue")       { viewModel.addToQueue(entry) }
    Button("Add to Playlist")    { onAddToPlaylist(entry) }
    Button("Remove from History") { viewModel.remove(entry) }
    Button("Share")              { share(entry.track.shareURL) }
  }
```

The custom `accessibilityActions` block duplicates the context-menu items as VoiceOver rotor-discoverable actions, so a VoiceOver user can trigger any of the 5 actions without performing the long-press gesture (long-press is brittle on VoiceOver).

### Spoken time format

The spoken form mirrors the visual form but uses VoiceOver-friendly phrasing:

| Visual | Spoken |
|---|---|
| "just now" | "just now" |
| "2 minutes ago" | "2 minutes ago" |
| "1 hour ago" | "1 hour ago" |
| "Today, 14:32" | "today at 2:32 PM" (12 h / locale) |
| "Yesterday, 09:15" | "yesterday at 9:15 AM" (12 h / locale) |
| "12 May, 18:04" | "12 May at 6:04 PM" (12 h / locale) |

The spoken form uses `Date.FormatStyle.RelativeStyle.numeric` semantics — let the system handle locale and 12 h / 24 h based on device settings. The visual form is locale-aware too (Q2 / `swiftui-spec.md` `formatHistoryTimestamp`); they only diverge in how the time portion is read aloud.

```swift
func spokenTime(entry: HistoryEntry, now: Date) -> String {
  let delta = now.timeIntervalSince(entry.playedAt)
  if delta < 60 { return "just now" }
  if delta < 3600 {
    let m = Int(delta / 60)
    return "\(m) minute\(m == 1 ? "" : "s") ago"
  }
  if delta < 86_400 {
    let h = Int(delta / 3600)
    return "\(h) hour\(h == 1 ? "" : "s") ago"
  }
  let timeStyle = Date.FormatStyle.time(.shortened)
  let cal = Calendar.current
  if cal.isDateInToday(entry.playedAt) {
    return "today at " + entry.playedAt.formatted(timeStyle)
  }
  if cal.isDateInYesterday(entry.playedAt) {
    return "yesterday at " + entry.playedAt.formatted(timeStyle)
  }
  let date = entry.playedAt.formatted(.dateTime.day().month(.wide))
  return "\(date) at \(entry.playedAt.formatted(timeStyle))"
}
```

---

## VoiceOver — swipe-to-delete

`.swipeActions(edge: .trailing) { Button(role: .destructive) }` is announced by VoiceOver via the system "Actions available" rotor entry — when the user focuses the row, a swipe-down on the rotor surfaces "Remove" as a destructive action. The label "Remove" is taken from the `Button`'s `Label` (`Label("Remove", systemImage: "trash")`).

On commit, the row disappears. We post an announcement so the user knows what happened:

```swift
.onChange(of: lastRemovedTitle) { _, title in
  guard let title else { return }
  UIAccessibility.post(notification: .announcement,
                       argument: "Removed \(title) from history")
}
```

The announcement uses `.announcement` (polite — does not interrupt). Focus naturally lands on the next row.

The destructive role's red glyph + tint is already announced by VoiceOver as "destructive" — do not add a redundant "Are you sure" hint.

---

## VoiceOver — toolbar Menu + Clear All alert

### Ellipsis button

```swift
Image(systemName: "ellipsis.circle")
  .accessibilityLabel("More options")
```

VoiceOver reads: `"More options, menu, button"`. The system handles the menu role announcement.

### Menu item

```swift
Button(role: .destructive) { showClearConfirm = true } label: {
  Label("Clear All", systemImage: "trash")
}
```

VoiceOver reads: `"Clear All, destructive, button"`. The destructive role announces automatically.

### Alert announces destructive impact

```swift
.alert("Clear watch history?", isPresented: $showClearConfirm) {
  Button("Cancel", role: .cancel) {}
  Button("Clear", role: .destructive) { viewModel.clearAll(); clearAllTrigger = UUID() }
} message: {
  Text("This permanently removes all entries from your history. Tracks themselves stay in your library.")
}
```

iOS `.alert` automatically:
- Announces the title as a heading.
- Reads the message after the title.
- Reads the buttons in order (Cancel, then destructive Clear).
- Focuses the alert; user must dismiss before screen interaction resumes.

The destructive role on "Clear" + the explicit message text are sufficient — do not add additional accessibility hints.

---

## Section headers as VoiceOver rotor anchors

```swift
Section {
  /* rows */
} header: {
  Text(group.label)
    .font(.subheadline)
    .foregroundStyle(.secondary)
    .textCase(nil)
    .accessibilityAddTraits(.isHeader)
}
```

`.accessibilityAddTraits(.isHeader)` exposes the header to the rotor's Headings reading mode. A VoiceOver user can swipe through "Today", "Yesterday", "10 May 2026" without listening to every row.

`SwiftUI`'s `List { Section }` adds heading semantics on `iOS 17+` automatically for `header:` text views, but the explicit `.isHeader` trait is the canonical contract — keep it.

---

## Empty state (C13) — announcement contract

Per state-catalog (`README.md` § Accessibility § Live announcements):

> **Empty state does NOT auto-announce** in the general case — it appears in response to a user action they just performed and they will discover it via natural navigation.

History's empty state appears either (a) on first push from Library (the screen-change announcement reads the nav title "Recently Played") or (b) immediately after `viewModel.clearAll()` (the alert dismissed; user knows what they just did).

`ContentUnavailableView` renders its title as a heading; focus lands on the title via the standard screen-change announcement. No additional `UIAccessibility.post` needed.

---

## Error state (C14) — auto-announce

Per state-catalog: errors auto-announce on appear.

```swift
.onChange(of: viewModel.phase) { _, new in
  if case .error = new {
    UIAccessibility.post(notification: .screenChanged,
                         argument: "Couldn't load history. Try again in a moment.")
  }
}
```

`.screenChanged` (not `.announcement`): the user's focus moves to the new content; the announcement reads the title + body in one breath.

---

## Focus order on screen open

1. Nav title ("Recently Played")
2. Ellipsis toolbar item (if entries exist)
3. First section header ("Today")
4. First row
5. ... subsequent rows / section headers in scroll order

The back chevron is reachable via VoiceOver rotor → "Back" but does not appear in linear focus order before the title (standard `NavigationStack` convention).

---

## MiniPlayer overlap — focus order

`.safeAreaInset(edge: .bottom)` ensures the last row's bounds clear the MiniPlayer; the row remains a focus target only if visible.

The MiniPlayer itself owns its own accessibility group; no work needed here.

---

## Dynamic Type

Every text view uses `.font(.system(size: ...))` or semantic types (`.body`, `.subheadline`, `.callout`) so Type Sizes scale. Clamp at `.accessibility3` for layout-critical labels via `.dynamicTypeSize(...DynamicTypeSize.accessibility3)`.

| Element | Style | At `.accessibility3` |
|---|---|---|
| Nav title "Recently Played" | `.largeTitle` (collapses to `.headline` inline on scroll) | scales freely |
| Section header label ("Today" / "12 May 2026") | `.subheadline` `.secondary` | scales freely; clamp at `.accessibility3` to avoid wrap |
| Track title (`TrackRow` headline) | `.body.weight(.semibold)` | scales freely; `lineLimit(1)` + truncate |
| Channel / duration line | `.callout` `.secondary` | scales freely; `lineLimit(1)` |
| Timestamp line | `.footnote` `.secondary` | scales freely; `lineLimit(1)` |
| Skeleton row | matches `TrackRow` heights | grows with text scale via live template |
| Empty / error title | `.title2.weight(.semibold)` | scales freely |
| Empty / error body | `.body` `.secondary` | scales freely |
| Empty / error action button | `.borderedProminent` `.controlSize(.large)` | min 50 pt height preserved |
| Alert title / body / buttons | system | scales freely |
| Context menu / Menu items | system | scales freely |

Verify at `Settings → Accessibility → Display & Text Size → Larger Text` set to maximum on a 393-pt-wide device — no text clips; section headers wrap to two lines at most.

---

## Reduce Motion

| Animation | Default | Reduce Motion |
|---|---|---|
| `swipeActions` spring-back (cancelled swipe) | system spring | system reduced-motion variant (instant snap) |
| Row disappear after destructive commit | system | instant |
| Context menu show / hide | system pop-in | system reduced-motion variant (cross-fade) |
| Skeleton shimmer | `1400 ms linear infinite` | static placeholder fill, no shimmer |
| State transition (loading → content) | `tween` opacity | instant |

Read `accessibilityReduceMotion` from `@Environment` and gate animation modifiers behind it. `swipeActions` and `Menu` handle this themselves; skeleton shimmer is custom and must respect the env flag.

```swift
@Environment(\.accessibilityReduceMotion) private var reduceMotion
```

---

## Tap targets

- Row: ≥ 56 pt — `TrackRow` already meets this.
- Ellipsis toolbar `Image(systemName:)` — system 44 pt min.
- Context menu items — system 44 pt min.
- Alert buttons — system.
- Retry button (C14) — `.controlSize(.large)` → 50 pt.

---

## Color contrast

- Track title `.primary` on `Color(.systemBackground)` (dark `Color.black`): 21:1 — passes AAA.
- Channel / timestamp `.secondary` on `Color(.systemBackground)`: ≈ 7:1 — passes AA body.
- Section header `.secondary` on `Color(.systemBackground)`: ≈ 7:1 — passes AA.
- Destructive role red (`Color(.systemRed)`) on `Color(.systemBackground)`: ≈ 5:1 — passes AA non-text.
- Swipe-action trash glyph (`.white` on `.systemRed`): ≈ 4.5:1 — passes AA non-text (system default; do not override).

---

## Acceptance checklist — verify before ship

- [ ] Row VoiceOver reads: "Sunset Avenue, Lofi Girl, played 2 minutes ago"
- [ ] Section headers expose Heading trait — verify in VoiceOver rotor → "Headings"
- [ ] Swipe-to-commit announces "Removed Sunset Avenue from history" via `.announcement`
- [ ] Clear-all alert focuses the alert; reads title + message + buttons in order
- [ ] Ellipsis toolbar item reads "More options, menu, button"
- [ ] Empty state focus lands on the title via screen-change; no auto-announce beyond that
- [ ] Error state auto-announces via `.screenChanged` on initial-load failure
- [ ] Reduce Motion turned on: no skeleton shimmer; no swipe spring; instant row removal
- [ ] Dynamic Type at largest setting: no clipping anywhere; section headers wrap at most 2 lines
- [ ] All hit targets ≥ 44 pt
