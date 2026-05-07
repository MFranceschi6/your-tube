# YT-0028 — Library & Playlist Management — Decision Log

> Senior iOS design consultant decisions. Builds on YT-0027 aesthetic direction
> (calm, content-first, Podcasts-inspired). When in doubt: **do less**.
> Aesthetic tone is shared — see [`../YT-0027/aesthetic-direction.md`](../YT-0027/aesthetic-direction.md).

---

## Q1 — Cover composition

**Decision: Hybrid.** Single artwork @ 1 track · deterministic gradient @ 0/2/3 tracks · 4-up 2×2 grid @ ≥4 tracks.

| Track count | Cover treatment |
|---|---|
| 0 | Generated gradient + `music.note` glyph centered, 28% opacity |
| 1 | Single artwork, full-bleed |
| 2–3 | Generated gradient + first letter of playlist name (32pt SF Pro 700, 30% white) |
| ≥4 | 2×2 grid of first 4 track artworks, 8pt continuous radius, 1pt inner divider |

**Generated gradient:** hash playlist `UUID → 2 oklch stops` in the same lightness/chroma band (`oklch(0.45 0.12 H1)` → `oklch(0.30 0.12 H2)`). Deterministic per playlist — never re-rolls between launches.

**HIG.** "Imagery should reinforce content, not decorate it." 4-up *summarizes*; 2-up *decorates*. Skip the in-between.

**Apple reference.** Music ("Made For You" 4-up). Photos (Memories 4-up).

**Anti-patterns.**
- ❌ 2-up / 3-up grids — read as broken layout.
- ❌ Non-deterministic per-playlist gradients (color shifts on reinstall).
- ❌ Vibrant gradients (chroma > 0.15) — fight the artwork in 4-up case.
- ❌ Custom illustrations inside the cover frame.

---

## Q2 — List style

**Decision: `List` with `.insetGrouped` style + `.scrollContentBackground(.hidden)`.** Not `Form`, not `LazyVStack`.

```
List {
  Section { RecentlyPlayedRow() }
  Section("Playlists") {
    ForEach(playlists) { PlaylistRow($0) }
      .onMove(perform: move)
      .onDelete(perform: delete)
  }
}
.listStyle(.insetGrouped)
.scrollContentBackground(.hidden)
.background(Color.black)
```

**HIG.** "Use system list styles for system content — they adapt to context (Dynamic Type, accessibility, edit mode) automatically."

**Apple reference.** Music (Library tab). Files (Browse tab). Reminders (list-of-lists).

**Anti-patterns.**
- ❌ `Form` — wrong semantic; users expect form behavior (toggles, pickers).
- ❌ `LazyVStack` "for performance" — costs `.onMove`, edit mode, swipe actions.
- ❌ `.insetGrouped` without `.scrollContentBackground(.hidden)` — system white background bleeds through dark mode.

---

## Q3 — PlaylistDetail header

**Decision: Pinned cover with parallax-shrink on scroll.** Cover starts 240pt → shrinks to 64pt as user scrolls past it. Title swaps from large to inline-toolbar at scroll threshold.

```
ScrollView {
  GeometryReader { geo in /* track minY → derive scale */ }
    .frame(height: 240)
    // cover view
  // ... rest of content
}
.toolbar {
  ToolbarItem(.principal) {
    if isCollapsed { Text(playlist.name).fontWeight(.semibold) }
  }
}
```

Animation: linear interpolation tied to scroll offset. **Never** `.spring` on a non-physical interaction.

**HIG.** "Maintain visual continuity — content stays anchored as the user explores details."

**Apple reference.** Apple Music (album / playlist detail). Podcasts (show detail).

**Anti-patterns.**
- ❌ Static cover above scrolling list — wastes vertical space, hogs small screens.
- ❌ Gradient-bleed header — conflicts with deterministic-gradient cover (Q1); reads as "atmospheric music app".
- ❌ Parallax that grows on rubber-band — gimmicky.
- ❌ `.spring` animation tied to scroll — wobbly.

---

## Q4 — Play / Shuffle / Share

**Decision: Two equal-width pill buttons (Play + Shuffle) side-by-side. Share moves to toolbar ellipsis menu.**

```
HStack(spacing: 12) {
  Button(action: play) {
    Label("Play", systemImage: "play.fill")
  }
  .buttonStyle(.borderedProminent)
  .tint(.accentColor)

  Button(action: shuffle) {
    Label("Shuffle", systemImage: "shuffle")
  }
  .buttonStyle(.bordered)
  .tint(.white.opacity(0.15))
}
.controlSize(.large)
.frame(maxWidth: .infinity)
```

Ellipsis menu (toolbar): Share · Add Tracks · Rename · Edit · Sort · Delete Playlist (destructive).

**HIG.** "Place primary actions where the eye lands; secondary in toolbars where users hunt for them when needed."

**Apple reference.** Apple Music (album/playlist detail — exactly this layout).

**Anti-patterns.**
- ❌ Three equal-weight pill buttons (mockup) — Play loses its primacy.
- ❌ Full-width primary Play + tiny Shuffle/Share — implies Shuffle is rare; it isn't.
- ❌ Share as a top toolbar standalone icon — convention puts Share in the ellipsis menu.

---

## Q5 — Edit interactions

**Decision: NATIVE.** `EditButton` + `.onMove` + `.onDelete` + `.swipeActions(edge: .trailing)`. **Override the mockup** — its custom leading `✕` and trailing drag handles are web-isms.

| Resting state | Edit mode |
|---|---|
| Tap row → push detail | Tap `−` → confirm remove |
| Swipe trailing → destructive Remove | Drag triple-bar → reorder |
| Long-press → context menu | (no swipe; system handles) |

Toolbar: `EditButton` in the secondary slot (top-right, paired with `+` on Library; standalone on Detail).

Context menu (long-press): Remove from Playlist · Add to Playlist · Share Track. No "Edit" or "Rename" in context menus — those are screen-level actions.

**HIG.** "Use system controls for system tasks — they're learned, accessible, and adapt automatically." VoiceOver reads "Delete" on the system `−` glyph automatically.

**Apple reference.** Reminders, Notes, Music Library — all native edit mode.

**Anti-patterns flagged from mockup.**
- ❌ Custom leading `✕` / trash glyph outside edit mode — clutters resting state.
- ❌ Custom trailing drag handle (3 dots / hamburger) always-visible — system uses three horizontal lines, only in edit mode.
- ❌ Showing edit affordances at all times — doubles up with swipe gesture; iOS hides edit behind explicit mode.

---

## Q6 — Rename flow

**Decision: `.alert` with `TextField`** (iOS 16+).

```swift
.alert("Rename playlist", isPresented: $showRename) {
  TextField("Name", text: $newName)
  Button("Save") { commit() }
  Button("Cancel", role: .cancel) {}
} message: {
  Text("Choose a new name for this playlist.")
}
```

**HIG.** "Use the simplest presentation that fits the task. Alerts handle short, focused decisions — including a single field of input."

**Apple reference.** Reminders (rename list). Notes (rename folder). Files (rename file).

**Anti-patterns.**
- ❌ Bottom sheet (mockup) — overkill for one field; `.medium` detent looks empty with one TextField.
- ❌ Inline rename (tap row → field appears) — gesture ambiguity (tap to open vs tap to rename).
- ❌ Full-screen modal — wrong scale.
- ❌ `.alert` without Cancel — escape must always be one tap.

---

## Q7 — Delete confirmation

**Decision split by destructiveness:**

- **Playlist deletion → `.confirmationDialog`** (action sheet). Hard to undo, deserves friction.
- **Track removal from playlist → `.swipeActions` destructive role + Undo snackbar**. Cheap to re-add; friction is wrong.

Playlist delete copy:
```
Title:   "Delete this playlist?"
Message: "The tracks will stay in your library. This can't be undone."
Buttons: "Delete Playlist" (destructive) | "Cancel"
```

**Copy tone rules:**
- Question form, sentence-case, no "!"
- Destructive button uses noun ("Delete Playlist") — clearer than verb-only when multiple deletables on screen.
- Tell user what *won't* be lost — reduces hesitation.
- Avoid "Are you sure?" — patronizing.

**HIG.** "Confirm destructive actions, but only when undo is impossible or expensive."

**Apple reference.** Photos (delete album → action sheet). Mail (move to trash → no confirm, undo).

**Anti-patterns.**
- ❌ `.alert` for action sheet content (multiple destructive paths look ugly past 2-3 buttons).
- ❌ Toast undo for playlist delete — too important; users miss toasts.
- ❌ Confirm dialog on every track removal — friction fatigue.

---

## Q8 — Add tracks flow

**Decision: Sheet (`.medium` detent expandable to `.large`)** with "New Playlist" row inline at top. Triggered from TrackRow context menu's "Add to Playlist".

Sheet contents:
1. **"New Playlist"** row at top (accent-tinted icon, takes user to a Create flow that auto-adds the track).
2. **Section "Playlists"** — multi-select checkmarks (a track can live in multiple playlists).
3. **Toolbar**: Cancel (leading) / Done (trailing, accent).

```swift
.sheet(isPresented: $showAddToPlaylist) {
  AddToPlaylistSheet(track: track)
    .presentationDetents([.medium, .large])
    .presentationDragIndicator(.visible)
}
```

**HIG.** "Sheets are for focused tasks that interrupt context briefly."

**Apple reference.** Apple Music (Add to Playlist sheet — multi-select + "New Playlist" inline). Photos (Add to Album).

**Anti-patterns.**
- ❌ Pushed nav — loses originating context, requires back-tap to return.
- ❌ Context menu only with playlist names inline — breaks at >5 playlists.
- ❌ Sheet without "New Playlist" inline — forces dismiss → create → re-add cycle.
- ❌ Single-select — multi-playlist membership is normal.

---

## Q9 — Empty library state

**Decision: `ContentUnavailableView` + prominent CTA button.**

```swift
ContentUnavailableView {
  Label("No playlists yet", systemImage: "music.note.list")
} description: {
  Text("Create one to organize tracks for offline listening.")
} actions: {
  Button("Create Playlist") { showCreate = true }
    .buttonStyle(.borderedProminent)
    .tint(.accentColor)
}
```

- **Symbol**: `music.note.list` (56pt, `.tertiary`). Not `square.stack` (too generic), not `rectangle.stack.badge.plus` (badge implies action while we're describing absence).
- **Title**: "No playlists yet" (factual). Not "Create your first playlist" — "first" is patronizing.
- **Body**: tells the user *why* they'd care.
- **CTA**: prominent button, not arrow-pointing-at-toolbar — gets user moving in one tap.

**HIG.** "Empty states explain why and how — clearly, without judgment."

**Apple reference.** Reminders, Photos, Notes — all `ContentUnavailableView` with prominent action.

**Anti-patterns.**
- ❌ "Create your first playlist" — patronizing.
- ❌ `rectangle.stack.badge.plus` — busy; reserve badged glyph for the toolbar `+`.
- ❌ Hint arrow pointing at toolbar — too much eye travel; users miss it.
- ❌ Custom illustration — visual debt; breaks system-feel.

---

## Q10 — Recently Played row

**Decision: Generic row with clock SF Symbol + chevron** (mockup is right). Pushes to a dedicated screen (YT-0029).

**Not** a horizontal carousel. **Not** a header section. **Not** a card.

**HIG.** "Affordances should match user intent. Recently played in a Library is reference, not browse — surface as destination, not feed."

**Apple reference.** Music ("Recently Added" row → list). Files ("Recents" tab → list). Photos ("Recents" album).

**Anti-patterns.**
- ❌ Horizontal carousel — content discovery pattern; wrong category for personal library.
- ❌ Anonymous header section — mixes section semantics with content; unnameable destination.
- ❌ Card with cover collage — over-styles a utility destination.
- ❌ Tab slot — burns a slot reserved for MVP cores.

---

## Mockup web-isms — translate to native

| Mockup CSS / pattern | Native iOS |
|---|---|
| Custom leading `✕` outside edit mode | Native `EditButton` + `−` in `.editMode`. **Override mockup.** |
| Custom trailing 3-dot drag handle, always-visible | Native triple-bar handle, **edit mode only**. |
| Three pill buttons (Play/Shuffle/Share) | Two pills (Play/Shuffle); Share to ellipsis menu. |
| Bottom sheet for rename | `.alert` with `TextField` (iOS 16+). |
| `border-radius: 8px` on thumbnails | `RoundedRectangle(cornerRadius: 8, style: .continuous)`. |
| `box-shadow: 0 8px 24px rgba(0,0,0,0.4)` on cover | Drop on dark surfaces — doesn't read. Reserve for parallax-cover lift. |
| `font-weight: 600` for row name | `.font(.system(size: 17, weight: .semibold))` + `.dynamicTypeSize(...DynamicTypeSize.accessibility3)`. |
| Empty-state custom illustration | `ContentUnavailableView` + SF Symbol. |
| `rgba(255,69,58,1)` red destructive | `.foregroundStyle(.red)` / `Color(.systemRed)` — adapts to vibrancy. |
| `transform: translateX(-72px)` swipe peek | Native `.swipeActions` — don't simulate metrics in CSS. |
| `gap: 12px` Play/Shuffle | `HStack(spacing: 12)`. |

---

## MiniPlayer coexistence

The persistent MiniPlayer adds ~60pt above the tab bar. Apply:

```swift
.safeAreaInset(edge: .bottom) {
  Color.clear.frame(height: miniPlayerVisible ? 60 : 0)
}
```

**Don't** hardcode 60pt as content padding — `safeAreaInset` adjusts scroll-indicator and Dynamic Type metrics correctly; padding doesn't.

---

## Build order

1. `LibraryScreen` shell — `.insetGrouped` List + Recently Played row + empty state.
2. `PlaylistRow` with hybrid cover (single artwork → 4-up grid → gradient fallback).
3. Create flow (sheet from toolbar `+`, single TextField, save → push to detail).
4. `PlaylistDetailScreen` — parallax cover + Play/Shuffle pills + TrackRow list.
5. Native edit mode (`.onMove`, `.onDelete`, `.swipeActions`, `EditButton`).
6. Add to playlist sheet.
7. Rename `.alert` + Delete `.confirmationDialog` + Undo snackbar for track removal.
8. Empty state + accessibility pass (Dynamic Type, VoiceOver, 44pt targets).

A working library with native edit mode beats a polished one with custom drag handles. **Spend the polish budget on the parallax header and the deterministic-gradient cover fallback** — those are where the perceived quality lives.
