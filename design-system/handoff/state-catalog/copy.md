# YT-0073 — Canonical Copy

> **Source of truth.** Every string a user sees in an empty / loading / error state on any list-driven screen, in English. Implementations quote this verbatim — no paraphrasing, no synonyms, no platform-specific wording.
>
> **Style rules.**
> - Action verbs first. "Search for something", not "You can search by tapping…"
> - Sentence case throughout. Not Title Case.
> - No emoji. No exclamation marks. No "Oops" / "Whoops" / "Uh oh" / "Yikes".
> - Failures describe what *happened*, not how the user feels about it. "Couldn't load your library." — not "Something went wrong."
> - Buttons: imperative present tense, ≤ 3 words. "Try again", not "Tap to try again."

---

## Cell C1 — Search · Loading

No copy. Skeleton only. Screen reader announces `"Loading"` on the list container.

---

## Cell C2 — Search · Empty (idle, no query yet)

| Slot | Copy |
|---|---|
| Title | **Search YourTube** |
| Body  | Find tracks, channels, and topics from your subscriptions. |
| Primary action | *(none — input field is the affordance)* |
| Helper text below input | (suggestion chips: "lofi", "focus", "ambient", "podcasts") |

> **Note.** This is the *empty input* state, not "no results". The search bar is focused; the chips are clickable suggestions that pre-fill the input.

---

## Cell C3 — Search · Empty (query submitted, zero matches)

| Slot | Copy |
|---|---|
| Title | **No results for "{query}"** |
| Body  | Check your spelling or try a different search. |
| Primary action | **Clear search** |
| Action target  | Clears the input and returns to C2 |

> The query string is rendered verbatim, in straight double-quotes, truncated with ellipsis if it exceeds 32 characters. Do not lower-case it. Do not strip punctuation.

---

## Cell C4 — Search · Error (generic — server / parse / unknown)

| Slot | Copy |
|---|---|
| Title | **Couldn't search** |
| Body  | Something went wrong on our end. Try again in a moment. |
| Primary action | **Try again** |
| Action target  | Re-run the same search query |

---

## Cell C5 — Search · Error (offline)

| Slot | Copy |
|---|---|
| Title | **You're offline** |
| Body  | Connect to the internet to search. Your saved playlists are still available in Library. |
| Primary action | **Try again** |
| Secondary action | **Go to Library** |
| Action targets | Primary: re-run search · Secondary: navigate to Library tab |

> **Why two actions on offline only.** Offline materially changes what the user can do — searching is impossible, but their downloaded content is right there. Surfacing Library is a one-tap recovery. No other error variant gets a secondary action.

---

## Cell C6 — Library · Loading

No copy. Skeleton only.

---

## Cell C7 — Library · Empty (no playlists)

| Slot | Copy |
|---|---|
| Title | **No playlists yet** |
| Body  | Create one to organize tracks for offline listening. |
| Primary action | **Create playlist** |
| Action target  | Opens the "New playlist" dialog (Android M3 dialog / iOS alert with TextField) |

> **Recently Played row stays visible above the empty state.** It's a separate destination, not a playlist. The "no playlists" copy is scoped to the playlist list, not the screen.

---

## Cell C8 — Library · Error

| Slot | Copy |
|---|---|
| Title | **Couldn't load your library** |
| Body  | Check your connection and try again. |
| Primary action | **Try again** |
| Action target  | Re-fetch the playlist index |

> Library is local-first; this error is rare (corruption, migration failure, file-permissions). Copy stays generic on purpose — the user doesn't need to know it's a SQLite migration; they need a button.

---

## Cell C9 — Playlist Detail · Loading

No copy. Skeleton header (cover + title placeholder) + 6 skeleton rows.

---

## Cell C10 — Playlist Detail · Empty (zero tracks in playlist)

| Slot | Copy |
|---|---|
| Title | **This playlist is empty** |
| Body  | Add tracks from search or your history. |
| Primary action | **Find tracks** |
| Action target  | Switches to the Search tab, focuses the search input |

> Playlist header (cover, name, "0 tracks · 0 min") still renders above the empty state. The body of the screen is what's empty, not the screen itself.

---

## Cell C11 — Playlist Detail · Error

| Slot | Copy |
|---|---|
| Title | **Couldn't load this playlist** |
| Body  | Check your connection and try again. |
| Primary action | **Try again** |
| Action target  | Re-fetch the playlist by ID |

---

## Cell C12 — History · Loading

No copy. Skeleton ×6 track rows.

---

## Cell C13 — History · Empty (nothing played yet)

| Slot | Copy |
|---|---|
| Title | **Nothing played yet** |
| Body  | Tracks you play will show up here. |
| Primary action | **Browse search** |
| Action target  | Switches to the Search tab |

> No "Clear" toolbar button is visible in this state. The toolbar action only renders when history is non-empty.

---

## Cell C14 — History · Error

| Slot | Copy |
|---|---|
| Title | **Couldn't load history** |
| Body  | Try again in a moment. |
| Primary action | **Try again** |
| Action target  | Re-fetch the history list |

---

## Cell C15 — Settings · Loading (operation in progress)

Inline overlay on the Settings row that triggered the operation. No empty/error screen takeover.

| Slot | Copy |
|---|---|
| Indicator | spinner (24dp / 24pt) replaces the row chevron |
| Status text | (replaces row subtitle) |
| Per-operation status | Export: **Preparing playlists…** · Import: **Reading file…** · Clear cache: **Clearing cache…** · Clear history: **Clearing history…** |
| Cancel | Tap the row again to cancel (where reversible — export, import). Clear cache and clear history are not cancellable once started. |

> The Settings page itself stays interactive. Other rows remain tappable. Operations are scoped to the row that started them.

---

## Cell C16 — Settings · Error (operation failed)

Inline error banner attached to the row that failed. No screen takeover.

| Slot | Copy |
|---|---|
| Banner background | `--color-error-surface` (`rgba(255,69,58,0.12)`) |
| Banner icon | `exclamationmark.triangle` / `error` (rounded outline) |
| Status text by operation | Export failed: **Couldn't export playlists.** · Import failed: **Couldn't import that file.** · Clear cache failed: **Couldn't clear cache.** · Clear history failed: **Couldn't clear history.** |
| Body (smaller) | (per operation, see below) |
| Primary action | **Try again** (right-aligned text button) |
| Dismiss action | **Dismiss** (right-aligned, secondary text button) |

### Per-operation body copy

| Operation | Body |
|---|---|
| Export | The file couldn't be created. Check available storage and try again. |
| Import | This file may be corrupted or made with a newer version. |
| Clear cache | The app couldn't clear the cache. Try again, or restart the app. |
| Clear history | History stays as-is. Try again. |

> **Import is the only operation where the body discriminates the cause** (corrupted file vs. version mismatch). Both produce the same banner; the user's recovery is the same (try a different file or update the app), so a single body covers both.

---

## Strings table (machine-readable)

```yaml
# state-catalog.copy.v1
# All strings are en-US. Localization keys mirror cell IDs.

C2.title:        "Search YourTube"
C2.body:         "Find tracks, channels, and topics from your subscriptions."

C3.title:        "No results for \"{query}\""
C3.body:         "Check your spelling or try a different search."
C3.action:       "Clear search"

C4.title:        "Couldn't search"
C4.body:         "Something went wrong on our end. Try again in a moment."
C4.action:       "Try again"

C5.title:        "You're offline"
C5.body:         "Connect to the internet to search. Your saved playlists are still available in Library."
C5.action:       "Try again"
C5.action_alt:   "Go to Library"

C7.title:        "No playlists yet"
C7.body:         "Create one to organize tracks for offline listening."
C7.action:       "Create playlist"

C8.title:        "Couldn't load your library"
C8.body:         "Check your connection and try again."
C8.action:       "Try again"

C10.title:       "This playlist is empty"
C10.body:        "Add tracks from search or your history."
C10.action:      "Find tracks"

C11.title:       "Couldn't load this playlist"
C11.body:        "Check your connection and try again."
C11.action:      "Try again"

C13.title:       "Nothing played yet"
C13.body:        "Tracks you play will show up here."
C13.action:      "Browse search"

C14.title:       "Couldn't load history"
C14.body:        "Try again in a moment."
C14.action:      "Try again"

C15.export:      "Preparing playlists…"
C15.import:      "Reading file…"
C15.clear_cache: "Clearing cache…"
C15.clear_hist:  "Clearing history…"

C16.export.title:      "Couldn't export playlists."
C16.export.body:       "The file couldn't be created. Check available storage and try again."
C16.import.title:      "Couldn't import that file."
C16.import.body:       "This file may be corrupted or made with a newer version."
C16.clear_cache.title: "Couldn't clear cache."
C16.clear_cache.body:  "The app couldn't clear the cache. Try again, or restart the app."
C16.clear_hist.title:  "Couldn't clear history."
C16.clear_hist.body:   "History stays as-is. Try again."
C16.action:            "Try again"
C16.dismiss:           "Dismiss"
```

---

## Forbidden strings (do not use)

| Don't | Do | Why |
|---|---|---|
| "Oops! Something went wrong." | "Couldn't load your library." | Anthropomorphizing failure is patronizing. State the fact. |
| "We're sorry, but…" | (just say what failed) | Apologies don't help recovery. |
| "Try again later." | "Try again in a moment." | "Later" is unbounded. "A moment" implies seconds. |
| "Network error." | "Check your connection and try again." | Tell them what to *do*. |
| "No items." | (use the screen-specific copy) | Generic empties feel unfinished. Each screen earns its own copy. |
| "Tap here to retry" | "Try again" (on the button) | The button IS the affordance. Don't narrate the gesture. |
