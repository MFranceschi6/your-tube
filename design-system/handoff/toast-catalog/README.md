# Toast / Snackbar Copy Catalog

> **Audience:** Claude Code (Android and iOS engineers), implementing transient notifications in Compose and SwiftUI.
> **Source task:** YT-0181.
> **Scope:** All transient, non-blocking user feedback messages across both platforms.

This catalog is the **source of truth** for toast and snackbar copy, behavior, and accessibility. It is referenced by platform handoff packages where individual screens emit toasts. Platform engineers implement from this catalog; the copy table in `copy.md` is the contract.

---

## What is in this folder

| File | Purpose |
|---|---|
| `README.md` | This file. When to use toast vs dialog vs inline error. |
| `copy.md` | Full trigger catalog: message, action label, duration, collapse rule. |
| `behavior.md` | Queue, collapse, dismiss, and MiniPlayer-aware positioning for both platforms. |
| `accessibility.md` | Live-region mode per trigger type, platform APIs, action-button accessibility. |
| `tone.md` | Voice and style rules applying to every string in the catalog. |
| `mockup.html` | Dark-theme visual reference — 3 states: simple, undo, error+retry. |

---

## When to use each pattern

### Toast / Snackbar

Use a transient snackbar (Android: `Snackbar` composable via `SnackbarHostState`; iOS: custom bottom overlay) when:

- The action is **complete** and the user does not need to make a decision.
- The outcome is **low risk** and optionally reversible within a short window.
- The message is **supplementary** — the UI itself already reflects the change (e.g., the row is already gone).
- The action label, when present, is a single undo or retry affordance.

**Canonical triggers:** track removed from playlist (with Undo), playlist deleted (with Undo), link copied, cache cleared, history cleared, export succeeded, import succeeded.

### Dialog (AlertDialog / sheet)

Use a confirmation dialog when:

- The action is **irreversible** and the cost of a mistake is high.
- The user must actively **decide** before the action executes.
- The outcome cannot be recovered by a simple undo within seconds.

**Canonical triggers:** deleting an entire playlist (permanent metadata loss), clearing all history (batch irreversible). See `YT-0014/decision-log.md` Q8 for the full-playlist delete contrast case.

### Inline error

Use an inline error (field-level message or persistent banner) when:

- The error is tied to a **specific form field** or input surface (validation).
- The error is **persistent** — it stays visible until the user fixes the condition.
- The user needs the error text visible **while they act** (e.g., while they re-type).

**Canonical triggers:** empty playlist name field, malformed import file warning displayed before confirmation, settings field out of range.

---

## Decision boundary summary

| Pattern | Blocking? | Reversible? | User decides first? | Persists? |
|---|---|---|---|---|
| Toast / Snackbar | No | Optional (Undo) | No | No — auto-dismisses |
| Dialog | Yes | Depends | Yes | Until dismissed |
| Inline error | No | Yes (fix input) | No | Yes — until resolved |

---

## Relationship to other catalogs

- **State catalog** (`design-system/handoff/state-catalog/`) covers empty / loading / error states for full list screens. It explicitly excludes toast copy ("Toast / Snackbar copy. Those are transient action confirmations — not list states.").
- **Library handoff** (`design-system/handoff/YT-0014/decision-log.md` Q3) establishes the track-remove undo pattern. This catalog generalises that pattern and sets the copy.
- **MiniPlayer inset math** from `YT-0014/decision-log.md` Q4 (`safeBottomInset`) drives the bottom positioning rule in `behavior.md`.
