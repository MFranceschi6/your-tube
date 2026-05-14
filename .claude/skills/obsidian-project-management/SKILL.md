---
name: obsidian-project-management
description: Manage this repo's gitignored Obsidian planning vault as a Jira-like workflow. Use when creating, triaging, reviewing, or maintaining project tasks, task notes, sprint boards, Obsidian Bases, or Tasks/Kanban dashboards for this native mobile app.
---

# Obsidian Project Management

Use this skill for project planning work in `obsidian-vault/`.

## Workflow

1. Read `docs/obsidian-planning.md`.
2. Inspect `AGENTS.md`, `CLAUDE.md`, relevant tracked docs, and the relevant platform directory before creating tasks.
3. Keep all planning notes inside `obsidian-vault/`, which is intentionally gitignored.
4. Do not create or change task notes unless the user asks for task preparation, triage, review, or maintenance.
5. Prefer Obsidian Bases and frontmatter as the source of truth for Jira-like task metadata.
6. Use Tasks-compatible checkbox lines for acceptance criteria and validation checklists.
7. When a task is being implemented or reviewed, route agent personality from `platform`, `area`, `agent_profile`, and `review_profile` before editing code.
8. Move cards on the Kanban by updating frontmatter `status`, then refresh `updated`.
9. Track hard prerequisites with `depends_on`; keep contextual references in `links`.
10. Keep MVP roadmap metadata current: `milestone`, `epic`, `phase`, `blocked_reason`, `validation_command`, and minimal `tags`.
11. Split oversized tasks into child task notes with `parent_id`, `child_tasks`, and `split_reason` before implementation when scope is too large for one reviewable diff.

## Task Defaults

- Task note folder: `obsidian-vault/Tasks/`
- Dashboard folder: `obsidian-vault/Dashboards/`
- Bases folder: `obsidian-vault/Bases/`
- Template folder: `obsidian-vault/Templates/`

Use stable IDs like `YT-0001`. Never hand-pick an ID. Before creating a new task note you MUST get the next free ID from the lifecycle script:

```bash
python3 .claude/skills/obsidian-project-management/lifecycle.py next-id
```

This scans both filenames and frontmatter `id:` and returns `max + 1` (gaps are not reused, to avoid resurrecting stale references in commit messages or links). Use the printed ID for both the filename prefix and the `id:` field. To audit existing duplicates, run `lifecycle.py check-ids`.

Allowed statuses: `backlog`, `ready`, `in-progress`, `blocked`, `review`, `done`, `wont-do`.

Allowed priorities: `P0`, `P1`, `P2`, `P3`.

Allowed platforms: `ios`, `android`, `shared`, `docs`, `ops`.

Current MVP tasks use `milestone: MVP` and tags `task`, `mvp`.

Current MVP epics: `MVP Planning`, `Shared Contracts`, `Android Foundation`, `Android Core`, `Android Features`, `Android Validation`, `iOS Core`, `iOS Features`, `iOS Validation`, `Cross Platform Validation`, `Release Readiness`.

Allowed phases: `scaffold`, `contract`, `core`, `feature`, `test`, `validation`, `release`.

Use `depends_on` for task IDs that must be `done` before work starts. If `depends_on` contains incomplete tasks, status should normally be `blocked`.

Use `parent_id` for child tasks and `child_tasks` on the parent. If a task is split, record a short `split_reason`; parent tasks should not move to `done` until child tasks are complete.

Default routing:

- `android`: `agent_profile: android-engineer`, `review_profile: mobile-reviewer`
- `ios`: `agent_profile: ios-engineer`, `review_profile: mobile-reviewer`
- `shared`: `agent_profile: shared-contract-engineer`, `review_profile: mobile-reviewer`
- `docs`: `agent_profile: docs-maintainer`, `review_profile: obsidian-task-reviewer`
- `ops`: `agent_profile: build-ops-engineer`, `review_profile: mobile-reviewer`

Task lifecycle:

- Start work: confirm all `depends_on` items are `done`, set `status: in-progress`, fill routing profiles, update `updated`.
- Split first: if the task combines multiple independently reviewable outcomes, create child tasks before implementation and link parent/children with `parent_id` and `child_tasks`.
- Ready for review: set `status: review` after implementation and validation.
- Blocked: set `status: blocked`, set `blocked_reason`, and add the blocker or incomplete dependency in `Context`.
- Reviewed complete: set `status: done` only after acceptance criteria and validation are satisfied.

## Lifecycle Script

All status transitions and dep-driven recomputation should go through:

```bash
python3 .claude/skills/obsidian-project-management/lifecycle.py <subcommand> [args]
```

Subcommands:

- `set <ID> <status> [--reason "..."] [--force]` — single-task transition with validation. Refuses illegal transitions (e.g. `done → in-progress` without `--force`), refuses to move to `in-progress` or `done` while deps are unmet (without `--force`), requires `--reason` to move to `blocked`. Auto-runs cascade after a `done` write. Refreshes `updated`.
- `cascade <ID> [<ID> ...]` — recompute the direct dependents of just-completed task IDs. Promotes to `ready` if every dep is now `done`; otherwise sets `blocked` with a refreshed `blocked_reason` listing the unmet dep IDs.
- `audit [--milestone X] [--platform Y] [--epic Z] [--status S] [--apply]` — full sweep. For every non-active task (not `in-progress` / `review` / `done` / `wont-do`): if all deps done → `ready`; if any dep unmet → `blocked` with reason. Default is dry-run; `--apply` writes. Use this after bulk changes or to reconcile drift.
- `list [filters]` — quick list of IDs/status/platform/title.
- `show <ID>` — print one task's deps, dependents, parent/children.
- `next-id [--prefix YT] [--pad 4]` — print the next free task ID (`max(existing) + 1`). Required before any new task note is created.
- `check-ids [--prefix YT]` — report duplicate IDs across filenames + frontmatter; exits non-zero if any found.

Run from any cwd; the script resolves the vault relative to its own location.

Do not manually update downstream statuses after a `done` move — `set <ID> done` (or `cascade <ID>`) does it. Do not maintain `blocked_reason` by hand for dep-only blockers — the script writes a canonical `Waiting on <ID>(<status>), ...` reason.

`wont-do` deps are treated as satisfied (deliberately not blocking dependents).

## Review Rules

When reviewing or maintaining tasks:

- Merge duplicates.
- Split only when one task has clearly separable outcomes.
- Keep acceptance criteria observable.
- Mark blockers explicitly in `status` and in the `Context` section.
- Move durable product behavior and shared contracts to tracked files under `docs/`.

## Safety

Never write secrets, signing material, bundle identifiers, provisioning data, API keys, private tokens, or environment values into the vault.
