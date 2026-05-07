# YT-0073 — Empty variant per screen

> **Rule:** every empty cell = `icon (56pt/dp) + title + body + primary action`. The icon is decorative; the title carries the announcement.

---

## Layout (every cell)

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
                       │  Primary action  │      52dp/pt height, --color-accent
                       └──────────────────┘
```

- Vertically centered in the available content area (below header, above MiniPlayer + tab bar where present).
- Horizontal padding: 32dp/pt minimum on each side. Title and body are center-aligned. Body max-width 280pt to prevent over-long lines.
- Empty container is a single accessibility group; focus on appear lands on the title.

---

## C2 — Search · Empty (idle)

**Trigger:** Search tab opened, no query entered.

| Slot | Spec |
|---|---|
| Icon (Android) | `Icons.Rounded.Search` (Material Symbols rounded, weight matches surrounding text — 400) |
| Icon (iOS) | `magnifyingglass` (SF Symbols, weight `.regular`) |
| Title | "Search YourTube" |
| Body | "Find tracks, channels, and topics from your subscriptions." |
| Primary action | *(none)* |
| Suggestion chips | Below the search input, above the empty card: "lofi", "focus", "ambient", "podcasts". `--radius-full`, 36pt height, `--color-surface-variant` background. Tapping a chip pre-fills the input and submits. |
| Action target | n/a |

> **Special case.** This is the only empty state with no primary action button — the search input *is* the action. The chips are accelerators, not the empty state itself.

---

## C3 — Search · Empty (no results for query)

**Trigger:** Query submitted, server returned 0 matches.

| Slot | Spec |
|---|---|
| Icon (Android) | `Icons.Rounded.SearchOff` |
| Icon (iOS) | `magnifyingglass` (same as C2 — SF Symbols has no clean "search off"; the changed copy carries the meaning) |
| Title | `No results for "{query}"` |
| Body | "Check your spelling or try a different search." |
| Primary action | "Clear search" |
| Action target | Clears the input, returns to C2 |

> Title announces politely on appear via `LiveRegionMode.Polite` / `UIAccessibility.post(.announcement, …)`. The user is waiting for an answer; silence reads as "still loading".

---

## C7 — Library · Empty

**Trigger:** Library has zero playlists. Recently Played row stays visible above.

| Slot | Spec |
|---|---|
| Icon (Android) | `Icons.Rounded.LibraryMusic` |
| Icon (iOS) | `music.note.list` |
| Title | "No playlists yet" |
| Body | "Create one to organize tracks for offline listening." |
| Primary action | "Create playlist" |
| Action target | Opens "New playlist" creation surface — Material 3 dialog (Android) or `.alert` with `TextField` (iOS) |

---

## C10 — Playlist Detail · Empty

**Trigger:** Playlist exists, has zero tracks. Header (cover, name, "0 tracks · 0 min") still renders above.

| Slot | Spec |
|---|---|
| Icon (Android) | `Icons.Rounded.MusicNote` |
| Icon (iOS) | `music.note` |
| Title | "This playlist is empty" |
| Body | "Add tracks from search or your history." |
| Primary action | "Find tracks" |
| Action target | Switches to Search tab, focuses the search input |

---

## C13 — History · Empty

**Trigger:** No tracks have been played yet. "Clear" toolbar button is hidden.

| Slot | Spec |
|---|---|
| Icon (Android) | `Icons.Rounded.History` |
| Icon (iOS) | `clock.arrow.circlepath` |
| Title | "Nothing played yet" |
| Body | "Tracks you play will show up here." |
| Primary action | "Browse search" |
| Action target | Switches to Search tab |

---

## Action button — every cell

- Height: 52dp/pt. Min hit target: 48dp / 44pt regardless.
- Background: `--color-accent` (`#8B5CF6`).
- Label color: `--color-fg-on-accent` (`#FFFFFF`).
- Radius: `--radius-md` (12px). NOT `--radius-full` — pills are reserved for chips and search bar.
- Padding: horizontal 24dp, vertical 0 (height drives).
- Pressed state: scale 0.97 (iOS spring) / Material ripple (Android).
- Disabled state: 0.4 opacity. Not used in this catalog — empty states never have a disabled action.
- Web mockup uses subtle accent shadow (`0 4px 12px rgba(139,92,246,0.3)`) for visual lift; **drop on native dark BG** (per YT-0027 aesthetic direction — shadows don't read on dark surfaces).

---

## Why these icons (and not others)

| Cell | Chosen | Rejected |
|---|---|---|
| C2 | `magnifyingglass` / `Search` | `text.magnifyingglass` (too literal — feels like a tooltip) |
| C3 | (same glyph, different copy) | A "frowning face" — anthropomorphizing failure |
| C7 | `music.note.list` / `LibraryMusic` | `folder` (folders aren't a YourTube concept), `tray` (storage metaphor — wrong) |
| C10 | `music.note` / `MusicNote` | `music.note.list` (would conflate with C7 — playlist is a list of tracks, not a list of playlists) |
| C13 | `clock.arrow.circlepath` / `History` | `clock` alone (too neutral — doesn't read as "history") |

> **Glyph parity is not pixel parity.** SF Symbols and Material Symbols don't render identically. Match the *concept*. The user perceives "this is the music library empty state" — they don't compare glyphs across platforms.

---

## Accessibility — empty cells

- The icon is `accessibilityHidden = true` (decorative; described by the title).
- Focus on appear: the title (heading semantics).
- Empty does NOT auto-announce **except** C3 (search no-results), which announces via polite live region — the user is waiting for an answer.
- Action button has explicit `accessibilityLabel` matching the visible label.
- TalkBack / VoiceOver reads the cell as: `"{title}. heading. {body}. {action label}. button."`
