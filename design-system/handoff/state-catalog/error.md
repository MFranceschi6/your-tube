# YT-0073 — Error variant per screen

> **Rule:** every error cell = `icon (56pt/dp) + title + body + Try again button`. The icon is **not red** — color it `--color-fg-tertiary` like an empty state. Red is reserved for destructive surfaces.

---

## Layout (every cell — same as empty)

```
                       ┌───────────────┐
                       │     icon      │  56dp/pt, color: --color-fg-tertiary
                       └───────────────┘
                                                 ← 16dp gap
                          Title                  yt-title-medium  (white, 600)
                                                 ← 8dp gap
                       Body sentence.            yt-body-medium   (secondary, 400)
                                                 ← 24dp gap
                       ┌──────────────────┐
                       │   Try again      │      52dp/pt height, --color-accent
                       └──────────────────┘
                                                 ← 12dp gap (offline only)
                       ┌──────────────────┐
                       │  Go to Library   │      text button, --color-accent label
                       └──────────────────┘      (C5 only — no other cell has a secondary action)
```

---

## Retry contract (binding)

When the user taps **Try again**:

1. The screen transitions to `loading` (skeleton or spinner per `loading.md`).
2. The **same request that produced the error** is re-issued. Same parameters, same endpoint. No new debounce, no new query parsing — re-issue the same `Request` object.
3. The retry has its own timeout (8 s default). On timeout → return to error state with the same cell.
4. Three consecutive retries that all fail within 30 seconds are collapsed: the fourth tap shows the same error state but the body becomes "Still not working — check your connection and try again later." (TODO: separate cell or keep collapsed; current decision: keep collapsed in v1, revisit if retry-loop telemetry shows >2% sessions hitting it.)
5. Retry never silently succeeds. Even on success, the loading state is rendered for at least 200 ms before content swaps in — sub-200ms swap reads as a no-op, leaving the user uncertain whether the tap registered.

---

## Cause discrimination — when to split

The catalog collapses error variants per screen *unless* offline materially changes what the user can do.

| Screen | Offline variant? | Why |
|---|---|---|
| Search | **Yes (C5)** | Offline = search is impossible, but Library is right there. Surfacing the alternative is high-value. |
| Library | No (C8 only) | Library is local-first. If it failed offline, retry is the same as if it failed for any other reason. |
| Playlist detail | No (C11 only) | Same as Library — local-first. |
| History | No (C14 only) | Same. |
| Settings ops | No (C16 only) | Per-operation copy already discriminates the cause; offline is encoded in "check your connection" body where relevant. |

For non-search screens, the underlying cause (offline / 5xx / parse) is logged for telemetry but **not** surfaced to the user. The retry button is the same regardless.

---

## C4 — Search · Error (generic)

**Trigger:** request returned non-2xx, parse failed, or unknown failure. Connectivity is not the cause (covered by C5).

| Slot | Spec |
|---|---|
| Icon (Android) | `Icons.Rounded.ErrorOutline` |
| Icon (iOS) | `exclamationmark.triangle` (weight `.regular`) |
| Title | "Couldn't search" |
| Body | "Something went wrong on our end. Try again in a moment." |
| Primary action | "Try again" |
| Action target | Re-run the same query |

---

## C5 — Search · Error (offline)

**Trigger:** request failed and `NetworkMonitor.isOnline == false`. Detection: platform connectivity API; do NOT infer offline from a generic timeout.

| Slot | Spec |
|---|---|
| Icon (Android) | `Icons.Rounded.WifiOff` |
| Icon (iOS) | `wifi.slash` |
| Title | "You're offline" |
| Body | "Connect to the internet to search. Your saved playlists are still available in Library." |
| Primary action | "Try again" |
| Secondary action | "Go to Library" |
| Action targets | Primary: re-run search · Secondary: navigate to Library tab |

> **Secondary action is a text button**, not an outlined button. Style: `--color-accent` label, no background, `--radius-md`, 44pt min height for hit target.

---

## C8 — Library · Error

**Trigger:** local data store returned an error (corruption, migration failure, file-permissions). Rare.

| Slot | Spec |
|---|---|
| Icon (Android) | `Icons.Rounded.ErrorOutline` |
| Icon (iOS) | `exclamationmark.triangle` |
| Title | "Couldn't load your library" |
| Body | "Check your connection and try again." |
| Primary action | "Try again" |
| Action target | Re-fetch the playlist index |

---

## C11 — Playlist Detail · Error

**Trigger:** playlist by ID could not be loaded.

| Slot | Spec |
|---|---|
| Icon (Android) | `Icons.Rounded.ErrorOutline` |
| Icon (iOS) | `exclamationmark.triangle` |
| Title | "Couldn't load this playlist" |
| Body | "Check your connection and try again." |
| Primary action | "Try again" |
| Action target | Re-fetch by playlist ID |

> Playlist header (cover placeholder, name) does NOT render in error state. The whole screen takes the error layout. (Compare to empty state C10, where the header DOES render — empty means the playlist exists but has no tracks; error means we don't know if it exists.)

---

## C14 — History · Error

**Trigger:** history list could not be loaded.

| Slot | Spec |
|---|---|
| Icon (Android) | `Icons.Rounded.ErrorOutline` |
| Icon (iOS) | `exclamationmark.triangle` |
| Title | "Couldn't load history" |
| Body | "Try again in a moment." |
| Primary action | "Try again" |
| Action target | Re-fetch history |

---

## C16 — Settings · Operation failed (inline banner)

**Form factor:** inline banner attached to the row that failed. Settings page itself stays interactive. No screen takeover.

### Visual

- Banner attaches *below* the failed row, full-width within the row's section group.
- Background: `--color-error-surface` (`rgba(255,69,58,0.12)`).
- Left edge: 3dp accent strip in `--color-error` (`#FF453A`). The only place red appears in this catalog — and only as a 3dp strip, not the icon, not the text.
- Internal padding: 12dp/pt.
- Layout: icon (20dp/pt) → text block (title + body) → action buttons (right-aligned, vertically centered).

### Per-operation content

| Operation | Title | Body |
|---|---|---|
| Export failed     | "Couldn't export playlists." | "The file couldn't be created. Check available storage and try again." |
| Import failed     | "Couldn't import that file." | "This file may be corrupted or made with a newer version." |
| Clear cache failed| "Couldn't clear cache."      | "The app couldn't clear the cache. Try again, or restart the app." |
| Clear history failed| "Couldn't clear history."  | "History stays as-is. Try again." |

### Buttons

- **Try again** — text button, `--color-accent` label, 44pt min hit. Re-runs the operation.
- **Dismiss** — text button, `--color-fg-secondary` label, 44pt min hit. Hides the banner without retrying. Banner auto-dismisses after the next successful operation on the same row.

### Auto-announce

- Banner appears with `LiveRegion(Assertive)` (Android) / `UIAccessibility.post(.announcement, "{title}. {body}")` (iOS). Operation failures are out-of-band events — the user may have moved on; assertive announce is appropriate.
- This is the only assertive announcement in the catalog. Every other error state is polite.

---

## What we explicitly rejected

| Rejected | Why |
|---|---|
| Red error icon (`exclamationmark.triangle.fill` filled, red) | Implies destructive / system alarm. List load failures are recoverable. The retry button carries the affordance. |
| HTTP status code in body | "503 Service Unavailable" is meaningless to users. |
| "Report this error" link | We have no error-reporting backend in MVP. Adding the link before the backend ships is dishonest. |
| Stack trace toggle | Same — and no developer would read it on-device. |
| Modal dialog for errors | Modal blocks navigation; list-level errors should leave the rest of the app reachable. |
| Toast/snackbar for initial-load error | Transient — the user might dismiss and be left with a blank screen. Use the full ErrorState. |
| Auto-retry with exponential backoff | The user lost trust the moment the screen failed. Auto-retry without showing the user feels like the app is hiding something. Manual retry only. |

---

## Accessibility — error cells

- Icon is `accessibilityHidden = true`.
- Focus on appear: the title.
- **Polite live region** for error states that replace a loading state mid-screen (C4, C5, C8, C11, C14).
- **Assertive live region** for C16 (Settings operation failures) — out-of-band failures justify the interrupt.
- Try again button has explicit `accessibilityLabel: "Try again"` — matches visible label, no decoration.
- Secondary action (C5 only) reads as: "Go to Library. button. opens the Library tab." (Compose `Modifier.semantics { contentDescription = …; role = Role.Button }`.)
