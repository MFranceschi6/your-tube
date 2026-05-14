# MOCKUP_INDEX.md — patch for YT-0013 / YT-0014 handoff

> Apply this patch to `design-system/MOCKUP_INDEX.md` in the local repo. The exact insertion site depends on the index's existing structure; the **Handoff Packages** section below is the contract — drop it in wherever the index already groups handoff artifacts (or create the section if it does not exist yet).

---

## Insert / merge into the "Handoff Packages" section

```markdown
## Handoff Packages

Handoff packages are the **source of truth** for implementation. They sit at `design-system/handoff/<TICKET>/` and override the corresponding HTML mockup wherever the two disagree.

### Android

| Ticket | Surface | Folder | Mockup (layout reference only) |
|---|---|---|---|
| YT-0011 | App shell | `design-system/handoff/YT-0011/` | `design-system/mockups/android/YT-0011-0012-0013-shell-search-player.html` (combined) |
| YT-0012 | Search | `design-system/handoff/YT-0012/` | `design-system/mockups/android/YT-0011-0012-0013-shell-search-player.html` (combined) |
| **YT-0013** | **Now Playing** | **`design-system/handoff/YT-0013/`** | **`design-system/mockups/android/YT-0011-0012-0013-shell-search-player.html` (combined)** |
| **YT-0014** | **Library** | **`design-system/handoff/YT-0014/`** | **`design-system/mockups/android/YT-0014-library.html`** |

### Reading order for a handoff folder

1. `README.md` — entry point, build order, tokens.
2. `decision-log.md` — Q-by-Q rationale, anti-patterns.
3. `compose-spec.md` — Compose view hierarchy, snippets, file layout, mockup→Compose token map.
4. Subject-specific deep dives (`haptics-and-a11y.md`, `media3-parity.md`, etc).
5. `mockup.html` — pointer + drift list to the legacy HTML mockup.

### Precedence rule

When the markdown in `handoff/<TICKET>/` and the HTML mockup disagree, **the markdown wins**. The HTML mockup is layout reference only and predates the post-implementation audit at `design-system/handoff/YT-0011/audit.md`.
```

---

## Existing rows that may need a "→ see handoff" footnote

If the Android Mockups table already lists rows for YT-0011 / YT-0012 / YT-0013 / YT-0014, append the handoff path to each row's notes column:

| Mockup row | Append |
|---|---|
| `mockups/android/YT-0011-0012-0013-shell-search-player.html` | "Handoff: YT-0011, YT-0012, YT-0013 (each in `handoff/<TICKET>/`)." |
| `mockups/android/YT-0014-library.html` | "Handoff: `design-system/handoff/YT-0014/`." |

The handoff folders own the behavioral contract; the mockup table just points at them.

---

## Diff summary (for the PR description)

- Added `design-system/handoff/YT-0013/` with `README.md`, `decision-log.md`, `compose-spec.md`, `haptics-and-a11y.md`, `media3-parity.md`, and `mockup.html` pointer.
- Added `design-system/handoff/YT-0014/` with `README.md`, `decision-log.md`, `compose-spec.md`, and `mockup.html` pointer.
- Updated `design-system/MOCKUP_INDEX.md` to add a **Handoff Packages** section and footnote the two affected mockup rows.
