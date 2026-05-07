# Obsidian Planning Workflow

This project uses a local Obsidian vault for planning and task tracking.

- Vault path: `obsidian-vault/`
- Git policy: the vault is intentionally gitignored.
- Shared conventions: this file is tracked and should be updated when the workflow changes.

## Recommended Plugins

Use Obsidian's community plugin installer where possible.

| Plugin | Plugin ID | Use |
| --- | --- | --- |
| Tasks | `obsidian-tasks-plugin` | Query tasks across the vault, due dates, recurring work, custom statuses, and dashboard task lists. |
| Bases | core plugin | Database-like task views from frontmatter properties, including tables, cards, filters, formulas, and grouping. |
| Task List Kanban | `task-list-kanban` | Optional kanban over Markdown task lines when drag-and-drop task movement is desired. |
| Dataview | `dataview` | Optional advanced dashboards if Tasks and Bases are not expressive enough. |

Avoid relying on the discontinued Projects plugin for new planning infrastructure. Prefer Bases for database-like views because it is a core Obsidian plugin and stores data in local Markdown properties.

## Task Model

Represent each meaningful unit of work as one task note under `obsidian-vault/Tasks/` when the user asks to prepare tasks.

Use this frontmatter shape:

```yaml
---
type: task
id: YT-0001
title: "Short imperative title"
status: backlog
priority: P2
milestone: MVP
epic: "MVP Planning"
phase: scaffold
platform: shared
area: planning
owner: ""
agent_profile: shared-contract-engineer
review_profile: mobile-reviewer
design_skill: ""
design_mockup: ""
created: 2026-05-02
updated: 2026-05-02
due: null
depends_on: []
parent_id: null
child_tasks: []
split_reason: ""
blocked_reason: ""
validation_command: ""
tags:
  - task
  - mvp
links: []
---
```

Allowed `status` values:

- `backlog`
- `ready`
- `in-progress`
- `blocked`
- `review`
- `done`
- `wont-do`

Allowed `priority` values:

- `P0`: urgent or blocking
- `P1`: important user-facing or architectural work
- `P2`: normal planned work
- `P3`: cleanup, polish, or nice-to-have

Allowed `platform` values:

- `ios`
- `android`
- `shared`
- `docs`
- `ops`

All current MVP work uses `milestone: MVP`. Use `epic` for roadmap-level grouping inside the MVP milestone. Current MVP epics:

- `MVP Planning`
- `Shared Contracts`
- `Android Foundation`
- `Android Core`
- `Android Features`
- `Android Validation`
- `iOS Core`
- `iOS Features`
- `iOS Validation`
- `Cross Platform Validation`
- `Release Readiness`

Allowed `phase` values:

- `scaffold`
- `contract`
- `core`
- `feature`
- `test`
- `validation`
- `release`

Use `depends_on` for hard task prerequisites only. Values are stable task IDs, not file links:

```yaml
depends_on:
  - YT-0002
  - YT-0004
```

Keep `links` for reference material and related work that is useful context but does not block the task.

Use `parent_id`, `child_tasks`, and `split_reason` only when a task is intentionally split:

```yaml
# Parent task
child_tasks:
  - YT-0036
  - YT-0037
split_reason: "Too broad to implement and review as one change."

# Child task
parent_id: YT-0012
```

When splitting a task:

- Create child task notes with new stable IDs.
- Keep each child independently implementable, reviewable, and validatable.
- Child tasks inherit `milestone`, `epic`, `platform`, `agent_profile`, and `review_profile` from the parent unless the split deliberately crosses ownership.
- Move hard prerequisites from the parent to each child when they apply.
- Add each child ID to the parent's `child_tasks`.
- Set the parent `status: blocked` until its `child_tasks` are done, unless the parent still has actionable work of its own.
- Prefer splitting before implementation starts; if a task is already `in-progress`, split only when the current scope has become clearly unsafe or hard to review.

Use `blocked_reason` for human-readable blocker context. For dependency-only blockers, `Waiting on incomplete depends_on tasks.` is enough because the exact IDs live in `depends_on`.

Use `validation_command` for the smallest primary validation command or manual validation action an agent should run or record before moving the task to review.

Use tags sparingly. Current task notes should use:

```yaml
tags:
  - task
  - mvp
```

UI tasks with a concrete design-system reference may also add:

```yaml
tags:
  - design-system
  - mockup
```

Use `design_skill` and `design_mockup` only when a task has a concrete design-system workflow:

```yaml
design_skill: yourtube-design
design_mockup: design-system/mockups/ios/example.html
```

`design_skill` names the design agent/skill to load before implementation or review. `design_mockup` points to the primary visual reference; keep supporting design files in `links`.

Use `delivery_channel` when a task is executed by an external delivery loop instead of a local agent. Allowed values:

- `claude-design` — task is sent to `claude.ai/design` for the design artifacts to be produced. The user filters on `delivery_channel: claude-design` AND `status: ready` to pull a batch and pass it externally; the deliverables land back under `design-system/handoff/` and the task moves to `review` for the local reviewer.

```yaml
delivery_channel: claude-design
```

Tasks without `delivery_channel` are picked up by the routed local agent per the Agent Routing table.

## Platform Parity Waves

Do not treat Android and iOS as a strict task-by-task ping-pong by default. Prefer short platform waves: complete a small iOS group to a stable checkpoint, then use the corresponding Android tasks as the parity pass. iOS leads because design mockups are produced for iOS first; Android picks up the validated shape afterwards.

Rules:

- Prefer one implementation platform at a time for normal feature delivery; only touch both platforms in one turn when the user explicitly asks for parity or when the task itself is cross-platform.
- Start Android parity after the reference iOS wave is `review` or `done`, not while the iOS shape is still changing heavily.
- Use shared docs such as `docs/api-contracts.md`, `docs/design-system.md`, and manual validation docs as the product source of truth; do not copy platform-specific implementation details across platforms.
- When moving from an iOS wave to Android, reuse the accepted behavior, fixtures, validation expectations, and edge cases, but still implement them with Android-native architecture and UI patterns.
- If the iOS wave reveals missing shared behavior, record that in tracked `docs/` before starting the Android parity wave.

Current MVP checkpoint guidance:

- Shared contract first: complete `YT-0002` before codec/import-export parity work on either platform.
- Core foundation checkpoint: finish iOS `YT-0021`, `YT-0022`, and `YT-0025` before broad Android feature implementation. Use those as the reference checkpoint for Android `YT-0006`, `YT-0007`, `YT-0008`, and `YT-0011`, with `YT-0020` already acting as the shared codec/model counterpart.
- Player-state checkpoint: finish iOS `YT-0023` and `YT-0024` before starting Android `YT-0009` and `YT-0010`.
- Feature parity wave: once iOS search, now playing, library, history, sharing, and settings are each working at least as one coherent vertical slice, use iOS `YT-0026` through `YT-0031` as the parity reference for Android `YT-0012` through `YT-0017`.
- Cross-platform validation starts after both parity waves are complete enough to run real export/import and MVP flows, culminating in `YT-0034`, `YT-0033`, `YT-0019`, and `YT-0035`.

## Agent Routing

Task agents must derive their working profile from task metadata so the user does not need to restate the desired personality. When `agent_profile` or `review_profile` is missing, fill it from this table the next time the task is touched.

| Task metadata | Implementation profile | Review profile | Notes |
| --- | --- | --- | --- |
| `platform: android` | `android-engineer` | `mobile-reviewer` | Kotlin, Gradle, Compose, Android lifecycle, Room/DataStore, Media3. |
| `platform: ios` | `ios-engineer` | `mobile-reviewer` | Swift, SwiftUI, Xcode, SwiftData, AVFoundation, XCTest/XCUITest. |
| `platform: shared` | `shared-contract-engineer` | `mobile-reviewer` | Shared contracts, fixtures, parity behavior, cross-platform validation. |
| `platform: docs` | `docs-maintainer` | `obsidian-task-reviewer` | Product docs, validation checklists, planning conventions, task quality. |
| `platform: ops` | `build-ops-engineer` | `mobile-reviewer` | Build wiring, CI, release readiness, validation orchestration. |

Area can refine the profile without overriding the platform:

- `area: planning` uses `obsidian-task-curator` for task creation/maintenance and `obsidian-task-reviewer` for task review.
- `area: ui`, `player`, `search`, `library`, `settings`, and `history` stay with the platform engineer, adding platform UI/design skills as needed.
- `area: model`, `sharing`, `contracts`, and cross-platform `validation` should check `docs/api-contracts.md` and fixture compatibility.
- `area: build` stays with the platform engineer for platform-local Gradle/Xcode changes; `platform: ops` uses `build-ops-engineer`.
- Security-sensitive work still follows `.claude/rules/security.md` before any profile-specific preference.

Design-system routing:

- When `design_skill: yourtube-design` is present, load `.agents/skills/yourtube-design/SKILL.md`, which in turn loads the design-system source in `design-system/`.
- Treat `design-system/mockups/MOCKUP_INDEX.md` as the task-to-mockup index and `design_mockup` as the primary per-task reference.
- Keep implementation ownership with the platform engineer (`android-engineer` or `ios-engineer`); the design skill is supplemental context, not a replacement for platform idioms.
- Review UI tasks against the linked mockup, `docs/design-system.md`, accessibility requirements, and platform-native interaction patterns.

## Kanban Status Updates

The Bases board groups cards by `status`; changing `status` is the Kanban move.

A task with incomplete `depends_on` items should normally be `status: blocked`. Move it to `ready` when all dependencies are `done` and the task has no other blocker.

A parent task with incomplete `child_tasks` should normally remain `blocked` or `in-progress` and should not move to `done` until every child is complete.

When an agent starts work on a task note:

- Set `status: in-progress`.
- Set or confirm `agent_profile` from the routing table.
- Set or confirm `review_profile` from the routing table.
- Check `depends_on`; do not start if a listed task is not `done` unless the user explicitly asks to override.
- Check whether the task is too large to complete as one reviewable diff; split it before implementation if needed.
- Clear `blocked_reason` when moving out of `blocked`, unless a non-dependency blocker remains.
- Set `updated` to the current date.

When implementation is finished:

- Check off completed acceptance criteria and validation items when they are truly done.
- Set `status: review` if the task needs review.
- Set `status: blocked` and add the blocker in `## Context` if the agent cannot proceed.
- Set `blocked_reason` whenever `status: blocked`.
- Set `status: done` only after the requested review passes or the user explicitly says review is not needed.

### Visual-pass policy for UI tasks

A task with a non-empty `design_mockup` (or any `## Acceptance Criteria` mentioning a mockup or visual pass) does **not** move to `status: done` until both of the following are true:

1. The required screenshots are present under `design-system/screenshots/<task-id>-*.png` (one per major state listed in the task's Reviewer Instructions or, if absent, one per primary screen state the task ships).
2. The visual-pass acceptance checkbox is ticked.

Exception: if a hard `depends_on` keeps the build from running, the task remains in `status: review` with `blocked_reason` describing what is missing. It does not silently move to `done` with the visual-pass checkbox unticked.

Capture screenshots using the iOS-Simulator workflow currently in use on this Mac (assistant launches the sim, describes the tap sequence, user executes, assistant captures via `xcrun simctl io <udid> screenshot`). Save resampled (`sips --resampleHeightWidthMax 1800`).

### Epic polish auto-spawn

When the post-done cascade detects that every task in an `epic` is now `done` or `wont-do` (step 4 below), and any of those tasks recorded **non-blocking** review nits in `## Review Notes` that were not resolved before close, spawn a single `<Epic> Polish` task that batches them. Frontmatter shape:

- `epic`: the just-closed epic
- `phase: validation`
- `priority: P3` (or P2 if the nits include accessibility / a11y / regression risk)
- `links`: every source task whose nits this consolidates
- `## Acceptance Criteria`: one checkbox per nit, each citing source-task ID and file:line

This avoids leaving every closed epic with a residual nit trail and keeps the polish work visible in the Bases board instead of buried in review notes.

When a task moves to `done` — post-done cascade:

1. Find every task in the vault whose `depends_on` includes the just-completed ID.
2. For each downstream task: if every entry in its `depends_on` is now `done`, move it from `blocked` to `ready`, clear `blocked_reason`, and set `updated` to today.
3. For each downstream task that still has at least one incomplete dependency, leave it `blocked` but verify `blocked_reason` is current.
4. Check the `epic` of the completed task: if every task sharing that `epic` is now `done` or `wont-do`, add a completion note to the relevant epic-level context in `docs/` or the vault dashboard, and apply the **Epic polish auto-spawn** rule (see below) if the epic accumulated non-blocking review nits.
5. Check the `phase` of the completed task: if every task in that `phase` across the same `platform` is now `done` or `wont-do`, confirm the phase is complete before starting the next-phase tasks.

When reviewing a task:

- Use `review_profile`, not the implementation profile, as the reviewer personality.
- Keep `status: review` while actionable findings remain.
- Move back to `in-progress` for implementation fixes, `blocked` for unresolved blockers, or `done` when acceptance criteria and validation are complete.
- Confirm `validation_command` has been run or intentionally recorded as manual before moving to `done`.

## Task Note Sections

Use these sections in order:

1. `## Outcome`
2. `## Context`
3. `## Acceptance Criteria`
4. `## Implementation Notes`
5. `## Validation`
6. `## Links`

Acceptance criteria can use Tasks-compatible checkboxes:

```markdown
- [ ] #task The behavior is implemented
- [ ] #task Regression coverage is added when practical
- [ ] #task Smallest relevant validation command passes
```

Keep acceptance criteria about observable outcomes. Keep implementation notes short and avoid turning task notes into hidden design docs. Durable product contracts still belong in `docs/`.

## Agent Rules

- Do not create or update task notes unless the user explicitly asks for task preparation, triage, review, or maintenance.
- Before creating tasks, inspect `AGENTS.md`, `CLAUDE.md`, relevant `docs/`, and the relevant platform directory.
- Before implementing a task ID, open its note, route via `platform`, `area`, `agent_profile`, and `review_profile`, then update the Kanban status before code edits.
- Before reviewing a task ID, use `review_profile` and update the task status according to the review result.
- Keep task notes small, atomic, and reviewable.
- Split a task into subtasks when it combines multiple independently reviewable outcomes, touches unrelated modules, requires separate validation paths, or would produce a diff that is hard to review safely.
- Do not create duplicate tasks for work already represented in the vault.
- Do not store secrets, signing data, API keys, private credentials, or provisioning details in the vault.
- If a task changes shared product behavior, add or update tracked documentation in `docs/` as part of the implementation work, not only in the vault.

## Suggested Views

When the vault is initialized, create Bases views rather than committing board state to the repository:

- `All Tasks`: table filtered to `type == "task"`.
- `Active`: table or cards filtered to active statuses.
- `By Status`: grouped by `status`.
- `Review`: filtered to `status == "review"`.
- `Agent Queue`: active cards grouped by `agent_profile`.
- `Dependencies`: table or cards ordered by `depends_on`.
- `Subtasks`: table showing `parent_id`, `child_tasks`, and `split_reason`.
- `By Epic`: active cards grouped by `epic`.
- `By Phase`: active cards grouped by `phase`.
- `Design System`: tasks tagged `design-system`, showing `design_skill` and `design_mockup`.
- `Blocked`: filtered to `status == "blocked"`.

Use Task List Kanban only if the workflow needs drag-and-drop over Markdown checklist lines. Use legacy Kanban boards only for lightweight visual grouping, not as the source of truth for task metadata.
