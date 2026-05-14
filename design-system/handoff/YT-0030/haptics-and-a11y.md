# YT-0030 — Haptics & Accessibility (iOS)

Companion to [`decision-log.md`](decision-log.md) decisions 7, 8, 9, 10. VoiceOver labels, `.sensoryFeedback` table, Dynamic Type clamps, hit targets, AirDrop / Files-app receive a11y.

---

## Haptics — `.sensoryFeedback` (iOS 17+)

All haptics use SwiftUI's `.sensoryFeedback` modifier. Do **not** drop down to `UIFeedbackGenerator` — the system handles pre-warming.

### Full table

| UI event | Modifier | Trigger value |
|---|---|---|
| Open export sheet (Share… tapped) | `.sensoryFeedback(.impact(weight: .light), trigger: exportSheetPresented) { o, n in !o && n }` | fires on false → true |
| Tap **Share** in export sheet | `.sensoryFeedback(.impact(weight: .medium), trigger: shareTapCount)` | counter increments on each tap |
| Tap **Save to Files** | `.sensoryFeedback(.impact(weight: .medium), trigger: saveToFilesPresented) { o, n in !o && n }` | once per open |
| Tap **Copy link** | `.sensoryFeedback(.selection, trigger: linkCopiedTickCount)` | once per copy — pairs with toast T08 |
| Long-press a playlist row → `.contextMenu` opens | `.sensoryFeedback(.impact(weight: .light), trigger: contextMenuOpenCount)` | system also fires its own — ours is redundant ON purpose; the system haptic is sometimes suppressed by simulator builds |
| Open file picker (Import… tapped) | `.sensoryFeedback(.impact(weight: .light), trigger: pickerPresented) { o, n in !o && n }` | once per present |
| Import success preview sheet appears | (none — the sheet's enter animation + the toast carries the confirmation) | — |
| Tap **Done** in success sheet | `.sensoryFeedback(.selection, trigger: doneTapCount)` | once per tap |
| Tap **View Playlist** in success sheet | `.sensoryFeedback(.impact(weight: .light), trigger: viewPlaylistTapCount)` | once per tap — "navigation" feel |
| Tap **Try Again** in any error variant | `.sensoryFeedback(.selection, trigger: retryTapCount)` | once per tap |
| Tap **Open App Store** in future-schema | `.sensoryFeedback(.impact(weight: .medium), trigger: openAppStoreCount)` | once per tap |
| Op loading overlay (C15) appears | (none — visual cue is enough; doubling with haptic feels overstuffed) | — |
| Op loading overlay dismisses → success | (none — the success sheet's appearance is the haptic anchor) | — |
| Op loading overlay dismisses → error | `.sensoryFeedback(.error, trigger: latestErrorVariant)` | fires when state transitions from `.reading` → `.error(.*)` — error variant matters; future-schema vs. parse should not double-fire |

**Single-trigger pattern (avoids spurious fires)** — use the closure form `{ old, new in ... }` whenever the haptic is asymmetric:

```swift
.sensoryFeedback(.impact(weight: .light), trigger: exportSheetPresented) { old, new in
    !old && new   // fire on appear only, not on dismiss
}
```

**Do NOT** fire a haptic on every state transition in the import state machine — that produces a buzzy 3-haptic sequence (overlay-appears → overlay-dismisses → success-sheet-appears) on a normal-speed import. Anchor haptics to **user taps** and to **terminal state transitions** (success / error), not to intermediate machine moves.

---

## VoiceOver

Sheet titles and per-element labels. Apply state-catalog C16 + state-catalog README § Accessibility for the error variants.

### Sheet and screen titles

| Surface | `accessibilityLabel` / role | Spoken on appear |
|---|---|---|
| Export sheet (root) | `accessibilityElement(children: .contain)` + `accessibilityLabel("Share playlist")` | "Share playlist" |
| Import success sheet | `accessibilityElement(children: .contain)` + `accessibilityLabel("Imported playlist")` | "Imported playlist" |
| Op loading overlay (C15) | `accessibilityElement(children: .ignore)` + `accessibilityLabel("Loading")` + `accessibilityAddTraits(.isModal)` — POST `.announcement` with `.polite` when overlay appears | "Loading" |
| Error variant (1 parse / 2 future-schema / 3 wrong-ext / 4 read-perm) | `ContentUnavailableView` ships its own combined label; POST `.announcement` with `.assertive` when the view appears | (the title — e.g. "Couldn't import playlist — file is invalid.") |

The error variants use `.assertive` on first appearance because they require the user to abandon the current path and take a different action. State-catalog README § Live announcements section covers this rule.

### Per-element labels

| Element | `accessibilityLabel` | hint / traits |
|---|---|---|
| Sheet drag indicator | system-provided | (don't override) |
| Playlist name in export sheet | (taken from displayed `Text`; mark with `.accessibilityAddTraits(.isHeader)`) | header |
| File metadata row ("12 tracks · 4.2 KB · .ytplaylist.json") | `accessibilityElement(children: .combine)` + label "12 tracks, 4.2 kilobytes, dot y t playlist dot j s o n file" | (read as a single group) |
| **Share** (ShareLink) | system-provided by `ShareLink` — DO NOT override | `.isButton` |
| **Save to Files** | "Save \"{playlistName}\" to Files" | `.isButton` |
| **Copy link** | "Copy link to \"{playlistName}\"" | `.isButton` |
| Import success title | (heading; auto via `Text.font(.title2.bold())` + `.accessibilityAddTraits(.isHeader)`) | header |
| Track preview row | "\"{title}\" by \"{channel}\"" | (no role — non-interactive informational row) |
| `+ N more tracks` row | "Plus {N} more tracks" | (no role) |
| **Done** | "Done" | `.isButton` |
| **View Playlist** | "View imported playlist" | `.isButton` |
| Error variant icon | `accessibilityHidden(true)` — decorative; title carries meaning (state-catalog rule) | — |
| Error variant **Try Again** | "Try again — choose another file" | `.isButton` |
| Error variant **Open App Store** (variant 2 only) | "Open App Store to update YourTube" | `.isButton` |
| Op loading overlay (interior) | `accessibilityHidden(true)` — the container's label + `.isModal` trait is enough | — |

### Live announcement table

| Event | Channel | Politeness | Message |
|---|---|---|---|
| Export sheet appears | `.accessibilityLabel` on contain-children root | (system reads on appear) | "Share playlist" |
| Op loading overlay appears (export) | `UIAccessibility.post(notification: .announcement, argument: "Loading")` | Polite (default) | "Loading" |
| Op loading overlay appears (import) | same | Polite | "Loading" |
| Share sheet opens (via `ShareLink`) | system-owned | system | (system announces) |
| Toast T05 fires after export | `ToastHost` posts `.announcement` Polite | Polite | "Exported \"My Mix\"" |
| Import success sheet appears | `accessibilityLabel` on contain-children root | (system) | "Imported playlist" |
| Toast T06 fires after Done / View Playlist | `ToastHost` posts `.announcement` Polite | Polite | "Imported \"My Mix\" — 12 tracks" |
| Error variant appears (any) | `UIAccessibility.post(notification: .announcement, argument: "<title>")` with `accessibilitySpeechQueueAnnouncement = false` (assertive equivalent) | **Assertive** | the title text |

iOS does not have first-class `.assertive` vs. `.polite` semantics for `.announcement`; the closest is `UIAccessibility.Notification.announcement` with the `UIAccessibilityAnnouncementPriority` API (iOS 17+). Use:

```swift
let attributed = AttributedString("Couldn't import playlist — file is invalid.")
var container = AttributeContainer()
container.accessibilitySpeechAnnouncementPriority = .high
let announcement = attributed.mergingAttributes(container)
UIAccessibility.post(notification: .announcement, argument: announcement)
```

---

## Hit targets

All interactive elements meet the 44 pt minimum:

```swift
Button(...) { ... }                       // SwiftUI Button — 44pt min in standard buttonStyle
ShareLink(...) { Label(...) }             // Wrap labels with .frame(minHeight: 50)
Image(systemName: "ellipsis.circle")
    .frame(width: 44, height: 44)
    .contentShape(Rectangle())
```

The export sheet's primary/secondary buttons use `.controlSize(.large)` which is 50 pt tall — visibly larger than the 44 pt floor, matching the sheet-anchored "tap targets the user is looking for" pattern (cite YT-0027 § Tap targets).

---

## Dynamic Type

All text uses semantic font roles. `ContentUnavailableView` (used by error variants) handles Dynamic Type automatically — never wrap it in a custom font.

| UI element | Font | Behavior at AX5 |
|---|---|---|
| Sheet title (playlist name) | `.title2.weight(.semibold)` | `.lineLimit(2)` + `.minimumScaleFactor(0.8)`; layout wraps the metadata row below |
| Metadata row | `.subheadline` + `.foregroundStyle(.secondary)` | wraps to multiple lines; no clamp |
| Button labels | `.body` (inferred from `.controlSize(.large)`) | scales freely; button grows vertically |
| Track preview title | `.body` | `.lineLimit(1)` + `.truncationMode(.tail)` |
| Track preview channel | `.subheadline` + `.secondary` | `.lineLimit(1)` |
| `+ N more tracks` | `.subheadline` + `.secondary` | scales freely |
| Error variant title | `ContentUnavailableView` `.title` (system) | system clamps |
| Error variant body | `ContentUnavailableView` `.description` (system) | system clamps |

The sheet is `ScrollView`-wrapped so the full content remains reachable at AX5. Do not collapse the metadata row or hide the secondary buttons at large scale.

`.dynamicTypeSize(...DynamicTypeSize.accessibility3)` is acceptable to apply on the metadata row's filename suffix (`.ytplaylist.json`) where layout density matters, but elsewhere allow free scaling.

---

## Color contrast

All values verified against `colors_and_type.css` dark-theme tokens with the export sheet's `.regularMaterial` background:

| Pair | Ratio | Passes |
|---|---|---|
| Title `.primary` on material bg | ~14:1 | AAA |
| Metadata `.secondary` on material bg | ~7:1 | AA |
| `.borderedProminent` button label on accent `#8B5CF6` | ~4.6:1 | AA (do not lighten the accent) |
| `.bordered` button label on material bg | ~14:1 | AAA |
| Error variant title on `Color(.systemBackground)` | ~16:1 | AAA |
| Error variant icon `.tertiary` on bg | ~4.1:1 | AA non-text |

**Do not tint the error icon red.** Cite state-catalog README. Red is reserved for destructive confirmation (delete-playlist) — not for recoverable errors.

---

## VoiceOver reading order

Export sheet (focus on appear lands on title via `.accessibilityElement(children: .contain)` + first descendant):
1. "Share playlist" (container label, auto-announced)
2. "My Mix" (title, header)
3. "12 tracks, 4.2 kilobytes, dot y t playlist dot j s o n file"
4. "Share, button" (ShareLink — system label)
5. "Save My Mix to Files, button"
6. "Copy link to My Mix, button"
7. (swipe down → dismiss; focus returns to invoker)

Import success sheet:
1. "Imported playlist" (container)
2. "Imported \"My Mix\" — 12 tracks" (header)
3. (preview rows — read as individual non-interactive items)
4. "Done, button"
5. "View imported playlist, button"

Error variants (assertive on first appearance interrupts; focus then lands on the title):
1. *(announcement)* "Couldn't import playlist — file is invalid." (variant 1)
2. (body)
3. "Try again — choose another file, button"

---

## AirDrop / Files-app receive — a11y

When `.onOpenURL` or `scene(_:openURLContexts:)` fires (decision 8), the user reaches the import flow without going through the picker. From their perspective, they accepted the AirDrop in the system share sheet (system VoiceOver narrates that), then YourTube comes to the foreground and the op loading overlay appears.

**No extra accessibility handling required** beyond the standard sheet announcement (overlay's "Loading", then success preview's "Imported playlist", or the error variant's assertive announcement). The system already narrates "Opening YourTube" during the app launch / foreground transition.

If `ImportRouter.handle(url:)` fires while the user is mid-task in another part of the app (warm `.onOpenURL`), the import sheet presents itself; VoiceOver will reset focus to the sheet on appear (system behavior). Do not override.

---

## Reduce Motion contract

Read `@Environment(\.accessibilityReduceMotion) var reduceMotion`.

| Animation | Default | Reduce Motion |
|---|---|---|
| Sheet entry (export / import success) | slide up | system handles — cross-fade replaces slide automatically on reduce-motion |
| Error variant transition (state machine error path) | `.transition(.opacity)` | unchanged (opacity is not vestibular-triggering) |
| Op loading overlay appear/dismiss | `.easeInOut(duration: 0.12)` | `.animation(nil, value: visible)` — instant |
| Toast appear/dismiss | `ToastHost` default | `ToastHost` already honors `accessibilityReduceMotion` (YT-0027 § Reduce-motion) — do not override |

Pattern for the overlay:

```swift
.animation(reduceMotion ? nil : .easeInOut(duration: 0.12), value: visible)
```

Do not conditional-out the whole animation block — provide a reduced variant. Pattern compiles with iOS 17+.
