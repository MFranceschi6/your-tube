# Toast / Snackbar Accessibility

> Applies to every trigger in `copy.md`. Platform-specific APIs are given for Android (Compose / TalkBack) and iOS (SwiftUI / VoiceOver).

---

## Live-region mode by trigger type

| Trigger type | Android `LiveRegionMode` | iOS `UIAccessibility.Notification` | Rationale |
|---|---|---|---|
| Confirmations (T01–T06, T08–T11, T14) | `LiveRegionMode.Polite` | `.announcement` | Non-urgent. Queued behind any in-progress speech. |
| Errors (T07, T12, T13, T15) | `LiveRegionMode.Assertive` | `.announcement` (immediately) | User must know the action failed so they can retry. |

**Rule:** Use `Assertive` / immediate announcement **only for error toasts**. Confirmatory toasts (action succeeded, content copied, cache cleared) are `Polite`. Do not use `Assertive` for success or neutral confirmations — it interrupts reading and degrades the screen-reader experience.

---

## Android — Compose / TalkBack

The default M3 `Snackbar` composable posts an accessibility announcement automatically via `LiveRegionMode.Polite` on the container `Row`. This is sufficient for confirmation toasts (T01–T06, T08–T11, T14).

For error toasts (T07, T12, T13, T15), override to `Assertive`:

```kotlin
Snackbar(
    modifier = Modifier.semantics {
        liveRegion = LiveRegionMode.Assertive
    },
    action = { /* Retry button */ },
) {
    Text(message)
}
```

**Action button accessibility:**

- The action button (Undo, Retry) MUST NOT be hidden from the accessibility tree.
- Do not set `Modifier.semantics { invisibleToUser() }` on the action button.
- Content description defaults to the action label text — this is correct; do not override with a generic description.
- Minimum touch target: 48 dp (inherited from `TextButton` defaults in M3).

**Focus behavior:**

- TalkBack does not auto-focus the snackbar on appearance. This is intentional for `Polite` toasts.
- For `Assertive` error toasts, the live-region announcement interrupts current speech; TalkBack does not move focus to the snackbar automatically. The user can navigate to the Retry button via swipe.
- Do not force-focus the snackbar; it interrupts the user's navigation flow.

---

## iOS — SwiftUI / VoiceOver

The custom `ToastBanner` component must post an accessibility announcement when it appears.

**Confirmation toasts (Polite equivalent):**

```swift
DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
    UIAccessibility.post(notification: .announcement, argument: toast.message)
}
```

The 0.1 s delay ensures the announcement queues after any in-flight speech (simulating `Polite`).

**Error toasts (Assertive equivalent):**

```swift
UIAccessibility.post(notification: .announcement, argument: toast.message)
```

Post immediately with no delay. `UIAccessibility.post(.announcement)` interrupts current speech on VoiceOver — use this only for errors (T07, T12, T13, T15).

**Action button accessibility:**

```swift
Button(toast.actionLabel) {
    toast.onAction()
}
.accessibilityLabel(toast.actionLabel)
// Do NOT use .accessibilityHidden(true) on the action button.
```

Minimum touch target: 44 pt. Use `.contentShape(Rectangle())` with a minimum frame of 44 pt if the button's visual size is smaller.

**Focus behavior:**

- VoiceOver does not auto-focus the toast on appearance — the announcement via `UIAccessibility.post` is the feedback mechanism.
- If the toast contains a Retry action and the screen reader is active, consider posting both the message and the label as one announcement string: `"\(toast.message). \(toast.actionLabel) button available."` so the user knows an action exists without needing to navigate.

---

## Dynamic Type

The toast body text and action button label must honor Dynamic Type / font scaling.

- Android: use `MaterialTheme.typography.bodyMedium` (respects `fontScale` automatically).
- iOS: use `.font(.subheadline)` with `.dynamicTypeSize(...)` unrestricted, or the system default — do not cap `dynamicTypeSize`.
- The toast container width is fixed at screen width minus 2 × 16 pt margin; text wraps. Do not truncate the message at large type sizes — let it wrap to two lines if needed. The toast height grows to fit.
- Action button text does not truncate; it sits on a new line below the message if the combined width would overflow.

---

## Color contrast

| Element | Foreground | Background | Ratio | Passes? |
|---|---|---|---|---|
| Message text | `#FFFFFF` | `#1C1C1E` | 19.3:1 | Yes |
| Action label | `#8B5CF6` | `#1C1C1E` | 4.6:1 | Yes (>4.5:1 floor) |

Do not lighten `#8B5CF6` — it would fall below the 4.5:1 WCAG AA threshold for normal text.

---

## Summary checklist

- [ ] Confirmation toasts post `LiveRegionMode.Polite` (Android) or delayed `.announcement` (iOS).
- [ ] Error toasts post `LiveRegionMode.Assertive` (Android) or immediate `.announcement` (iOS).
- [ ] Action button (Undo, Retry) is never hidden from the accessibility tree.
- [ ] Action button minimum touch target is 48 dp (Android) / 44 pt (iOS).
- [ ] Toast body text respects Dynamic Type; container grows to fit wrapping text.
- [ ] Color contrast meets 4.5:1 for body text and action label.
