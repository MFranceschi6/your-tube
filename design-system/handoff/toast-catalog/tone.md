# Toast / Snackbar Tone and Voice

> Applies to every string in `copy.md`. When writing a new toast trigger that is not yet in the catalog, follow these rules before adding the entry.

---

## Core rules

### 1. Past tense for completed actions

Confirmatory toasts report what happened, not what is happening.

| Correct | Wrong |
|---|---|
| "Removed" | "Removing" |
| "Exported" | "Exporting" |
| "Cleared" | "Clearing" |
| "Copied" | "Copying" |
| "Imported" | "Importing" |
| "Saved" | "Saving" |

### 2. No exclamation marks

Exclamation marks read as forced enthusiasm and are inappropriate for routine feedback.

| Correct | Wrong |
|---|---|
| `Exported "My Playlist"` | `Exported "My Playlist"!` |
| `Link copied` | `Link copied!` |

### 3. No emoji

Emoji introduce inconsistency across screen readers, operating systems, and font renderers. They are not used in toast messages on either platform.

### 4. No "Successfully" prefix

"Successfully" is redundant. A completed action is already a success signal.

| Correct | Wrong |
|---|---|
| `Exported "My Playlist"` | `Successfully exported "My Playlist"` |
| `Changes saved` | `Successfully saved changes` |

### 5. Undo is "Undo" — not "Cancel" or "Dismiss"

The reversal action label is always `Undo`. "Cancel" implies the action was stopped before it happened. "Dismiss" describes closing the toast itself. "Undo" correctly describes reversing a completed action.

| Correct | Wrong |
|---|---|
| `Undo` | `Cancel` |
| `Undo` | `Dismiss` |
| `Undo` | `Revert` |

### 6. Retry is "Retry" — not "Try Again"

The retry action label is always `Retry`. "Try Again" is reserved for full-screen error state primary buttons (see `design-system/handoff/state-catalog/`). Consistency of vocabulary between surfaces matters.

| Correct | Wrong |
|---|---|
| `Retry` | `Try Again` |
| `Retry` | `Refresh` |

### 7. Error messages are factual, not blaming

Do not imply user error. Do not describe the technical cause. State what could not happen.

| Correct | Wrong |
|---|---|
| `Couldn't export` | `Export failed due to an error` |
| `Couldn't import — file not recognised` | `Your file is invalid` |
| `Couldn't connect` | `Network error occurred` |
| `Couldn't save` | `Save operation failed` |

### 8. Message length: ≤50 characters

Keep the message body short enough to be read at a glance. The 50-character ceiling includes spaces and punctuation.

If a message with a variable (track title, playlist name) might exceed 50 characters, apply the truncation rule from `copy.md`: truncate user content to fit, append `…`, and wrap in curly quotes `"…"`.

### 9. No trailing period

Toast messages are not sentences requiring terminal punctuation.

| Correct | Wrong |
|---|---|
| `Link copied` | `Link copied.` |
| `Cache cleared` | `Cache cleared.` |

### 10. User content in curly quotes

When the toast message includes a user-generated string (track title, playlist name), wrap it in curly (typographic) quotes: `"…"` (U+201C / U+201D).

- Android: include the literal curly quote characters in the string resource or format string.
- iOS: use `\u{201C}` / `\u{201D}` or the literal characters in the format string.

---

## Quick-reference checklist for new copy

When adding a new toast trigger to `copy.md`:

- [ ] Message is past tense (completed action) or factual negative ("Couldn't …" for errors).
- [ ] Message is ≤50 characters including variables at a typical length.
- [ ] No trailing period, no exclamation mark, no emoji.
- [ ] No "Successfully" prefix.
- [ ] Action label is `Undo` (reversals) or `Retry` (failures) — not any other word.
- [ ] User content is wrapped in curly quotes with a truncation rule stated.
- [ ] Collapse rule is defined (or explicitly "No collapse").
- [ ] Duration is `Short` unless the trigger has a destructive undo, in which case `Long`.
