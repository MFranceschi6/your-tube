# Toast / Snackbar Copy — Trigger Catalog

> **Source of truth.** When implementing a snackbar or toast on any screen, use the message and action label exactly as written here. Do not paraphrase.
>
> **Column definitions**
> - **Trigger** — the user action or system event that fires the toast.
> - **Message** — the string shown in the toast body. Past tense, ≤50 characters, no trailing period.
> - **Action Label** — the text on the optional action button. Absent when no action is offered.
> - **Duration** — `Short` = 4 s (M3 default); `Long` = 10 s. Use `Long` only for destructive-undo triggers where recovery time matters.
> - **Collapse Rule** — how to handle the same trigger firing multiple times before the previous toast is dismissed.

---

## Catalog

| # | Trigger | Message | Action Label | Duration | Collapse Rule |
|---|---|---|---|---|---|
| T01 | Track removed from playlist (single) | `Removed "{{trackTitle}}"` | `Undo` | Long | Same trigger within 3 s → `Removed {{N}} tracks` with a single `Undo` |
| T02 | Track removed from playlist (collapsed) | `Removed {{N}} tracks` | `Undo` | Long | Increment N on each additional remove within the Undo window; reset when dismissed |
| T03 | Playlist deleted | `Deleted "{{playlistName}}"` | `Undo` | Long | No collapse — playlist delete is a distinct per-playlist event; queue if fired rapidly |
| T04 | Track added to playlist | `Added to "{{playlistName}}"` | — | Short | Same playlist within 3 s → `Added {{N}} tracks to "{{playlistName}}"` |
| T05 | Playlist exported | `Exported "{{playlistName}}"` | — | Short | No collapse |
| T06 | Import succeeded | `Imported {{N}} tracks` | — | Short | No collapse |
| T07 | Import failed — malformed file | `Couldn't import — file not recognised` | — | Short | No collapse |
| T08 | Link copied | `Link copied` | — | Short | No collapse — deduplicate: dismiss existing before showing a new one if fired within 1 s |
| T09 | Cache cleared | `Cache cleared` | — | Short | No collapse |
| T10 | History cleared | `History cleared` | — | Short | No collapse |
| T11 | Settings saved | `Changes saved` | — | Short | No collapse |
| T12 | Network error (transient, retryable) | `Couldn't connect` | `Retry` | Short | Deduplicate: if already showing, reset the timer rather than stacking |
| T13 | Save failed (generic) | `Couldn't save` | `Retry` | Short | Deduplicate: reset timer if already visible |
| T14 | Download started | `Download started` | — | Short | No collapse — one toast per download initiation |
| T15 | Download failed | `Download failed` | `Retry` | Short | No collapse |
| T16 | Recent search removed | `Removed from recent searches` | `Undo` | Long | No collapse — single-entry remove |

---

## String rules

1. **Past tense** for completed actions: "Removed", "Exported", "Cleared", "Copied". Not "Removing", "Exporting".
2. **Factual for errors**: "Couldn't import" not "Import failed due to an error". No blame.
3. **No trailing period.**
4. **No exclamation mark.**
5. **No emoji.**
6. **No "Successfully" prefix.** "Removed" is already a success signal.
7. **≤50 characters** in the message body. Use the collapsed form (T02, T04 collapsed variants) for multi-item scenarios.
8. **"Undo"** not "Cancel" or "Dismiss" for the reversal action.
9. **"Retry"** not "Try Again" for the retry action. (The state catalog uses "Try Again" for full-screen error states — that is a different surface.)
10. **Track title and playlist name** are user content: wrap in curly quotes `"…"` in the message. If the title exceeds 24 characters, truncate to 21 characters and append `…` so the total message stays within 50 characters. Example: `Removed "Bohemian Rhaps…"`.

---

## Variable substitution reference

| Variable | Type | Notes |
|---|---|---|
| `{{trackTitle}}` | String | User-supplied track title. Truncate to 21 chars + `…` if needed. |
| `{{playlistName}}` | String | User-supplied playlist name. Truncate to 18 chars + `…` if needed. |
| `{{N}}` | Integer | Count of affected items. Always ≥ 2 when used in collapsed messages. |

---

## Triggers NOT covered by this catalog

- **Full-screen error states** (empty / loading / error) — see `design-system/handoff/state-catalog/`.
- **Confirmation dialogs** (destructive actions requiring user decision before execution) — see `design-system/handoff/YT-0014/decision-log.md` Q8.
- **Inline form validation** — not a toast; rendered adjacent to the relevant field.
- **Onboarding / first-launch messages** — not toasts; see onboarding surface when it exists.
