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

When a task moves to `done` — post-done cascade:

1. Find every task in the vault whose `depends_on` includes the just-completed ID.
2. For each downstream task: if every entry in its `depends_on` is now `done`, move it from `blocked` to `ready`, clear `blocked_reason`, and set `updated` to today.
3. For each downstream task that still has at least one incomplete dependency, leave it `blocked` but verify `blocked_reason` is current.
4. Check the `epic` of the completed task: if every task sharing that `epic` is now `done` or `wont-do`, add a completion note to the relevant epic-level context in `docs/` or the vault dashboard (do not create a new task note just for this).
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
- `Blocked`: filtered to `status == "blocked"`.

Use Task List Kanban only if the workflow needs drag-and-drop over Markdown checklist lines. Use legacy Kanban boards only for lightweight visual grouping, not as the source of truth for task metadata.
