# Toast / Snackbar Behavior

> Platform-specific implementation contracts for queue management, collapse, dismissal, and positioning. Read alongside `copy.md` for the full picture.

---

## Queue — max one visible at a time

Only one snackbar is visible at any time on both platforms.

When a new toast fires while one is already visible:

1. **Same trigger type within the collapse window (3 s)** — apply the collapse rule from `copy.md` (update the existing toast message in-place, reset the timer). Do not stack a second toast.
2. **Different trigger type** — dismiss the current toast immediately (without committing any pending Undo action), then show the new toast.
3. **Same trigger type, outside the collapse window** — treat as a different trigger (rule 2 above).

> **Undo safety on forced dismiss.** When a toast carrying an `Undo` action is force-dismissed because a new toast fired, commit the pending destructive action immediately. Never leave a pending undo in an ambiguous state.

---

## Collapse rule detail

A collapse fires when the **same trigger** (same `#` row from `copy.md`) fires again while the current toast for that trigger is still visible and the elapsed time since the first toast is less than 3 seconds.

Collapsed behavior:

- Replace the message body with the `N`-item form (e.g., `Removed 3 tracks`).
- Keep the same action label (e.g., `Undo`).
- Reset the dismiss timer to the full duration.
- The single `Undo` action reverses **all** N removals in the order they occurred, restoring original positions.

Triggers marked "No collapse" in `copy.md` always follow the new-toast-dismisses-old rule (queue rule 2).

---

## Dismiss conditions

A toast is dismissed by whichever of these fires first:

1. **Timer expiry** — Short = 4 s, Long = 10 s from the moment the toast becomes visible (or from the last collapse update for collapsed toasts).
2. **Manual swipe** — user swipes the toast away on either platform.
3. **Navigation change** — the user navigates to a different top-level destination. Commit any pending Undo action immediately on navigation.
4. **New toast** — a different trigger fires (queue rule 2). Commit any pending Undo action immediately.
5. **Action button tap** — Undo or Retry tapped; execute the action and dismiss.

---

## Position

Toasts sit **above the MiniPlayer** (when visible) or above the navigation bar (when no track is loaded).

The bottom edge of the toast must not overlap the MiniPlayer or the system navigation bar.

### Shared inset math

The `safeBottomInset` values established in `YT-0014/decision-log.md` Q4 apply here unchanged:

| MiniPlayer state | `safeBottomInset` |
|---|---|
| Visible (track loaded) | 152 dp / pt |
| Hidden (no track) | 88 dp / pt |

The toast container's bottom edge is positioned at `safeBottomInset` from the bottom of the screen. Add a visual gap of **8 dp / pt** between the toast bottom and the MiniPlayer top (already included in the 152 dp figure from Q4).

---

## Android implementation

**Component:** M3 `Snackbar` composable hosted in `SnackbarHost`.

**State:** `SnackbarHostState` — one instance per `Scaffold`. Emit snackbars via `SnackbarHostState.showSnackbar(message, actionLabel, duration)` from a coroutine (typically in the `LaunchedEffect` watching a `UiEffect` flow from the ViewModel).

**Scaffold wiring:**

```kotlin
val snackbarHostState = remember { SnackbarHostState() }

Scaffold(
    snackbarHost = {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.padding(
                bottom = LocalMiniPlayerState.current.safeBottomInset
            ),
        )
    },
) { innerPadding -> /* screen content */ }
```

The `SnackbarHost` is already positioned by `Scaffold` above the `bottomBar`; the additional `padding(bottom = safeBottomInset)` lifts it above the MiniPlayer.

**Duration mapping:**

| Copy.md value | `SnackbarDuration` |
|---|---|
| Short (4 s) | `SnackbarDuration.Short` |
| Long (10 s) | `SnackbarDuration.Long` |

**Undo / Retry result:**

```kotlin
val result = snackbarHostState.showSnackbar(...)
when (result) {
    SnackbarResult.ActionPerformed -> viewModel.onUndoOrRetry()
    SnackbarResult.Dismissed -> viewModel.onSnackbarDismissed()
}
```

**Collapse:** maintain a pending-removal list in the ViewModel. When a second removal fires within 3 s, cancel the in-flight `showSnackbar` coroutine (using `Job.cancel()`), update the ViewModel state with the incremented count, and call `showSnackbar` again with the collapsed message. The SnackbarHostState cancellation dismisses the previous toast without animating in a new one if the same `SnackbarHostState` is reused — in practice on M3 this re-animates briefly; this is acceptable.

**Colors (non-negotiable):**

| Role | Token |
|---|---|
| Container | `colorScheme.inverseSurface` |
| Content | `colorScheme.inverseOnSurface` |
| Action label | `colorScheme.inversePrimary` |

Do not override these. Use the default M3 `Snackbar` composable.

---

## iOS implementation

**Component:** Custom SwiftUI overlay anchored via `safeAreaInset(edge: .bottom)` on the `TabView` or root `ZStack`, sitting above tab bar content.

**Positioning:**

```swift
.safeAreaInset(edge: .bottom, spacing: 0) {
    if let toast = toastState.current {
        ToastBanner(toast: toast)
            .padding(.bottom, miniPlayerState.safeBottomInset)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .animation(.spring(response: 0.3, dampingFraction: 0.8), value: toastState.current)
    }
}
```

`miniPlayerState.safeBottomInset` is the iOS equivalent of the Android `LocalMiniPlayerState.current.safeBottomInset`. The value follows the same 152 pt / 88 pt math.

**State management:** A `ToastCoordinator` observable object holds at most one active `ToastItem`. All screens post to it via environment injection. The coordinator handles the queue (one active + one pending replacement) and the 3-second collapse window via `Task`/`withTimeout`.

**Dismissal timer:** Use `Task { try await Task.sleep(for: .seconds(duration)) }` started when the toast becomes visible. Cancel on manual dismiss, navigation change, or forced replacement.

**Reduce Motion:** Wrap the transition in `withAnimation` only when `UIAccessibility.isReduceMotionEnabled == false`. Otherwise use an instant `nil` / non-nil toggle.

**Colors:**

| Role | Value |
|---|---|
| Container | `#1C1C1E` (system `secondarySystemBackground` in dark) |
| Content | `#FFFFFF` (label) |
| Action label | `#8B5CF6` (accent) |
| Corner radius | `12 pt` |
| Horizontal margin | `16 pt` each side |
| Vertical padding | `14 pt` top + bottom |

---

## Anti-patterns

- Do not stack two toasts simultaneously.
- Do not show a toast for a non-reversible destructive action that should use a dialog (see `README.md`).
- Do not show a toast on every keystroke or intermediate state — only on settled, complete actions.
- Do not position the toast over the keyboard; use `KeyboardAdaptive` or equivalent to push the toast above the software keyboard when a text field is active.
- Do not use `SnackbarDuration.Indefinite` unless the user is required to act (e.g., a connectivity banner) — and that pattern is not a toast; it is a persistent banner outside this catalog's scope.
