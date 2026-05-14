# Obsidian Planning Workflow

This project uses a local Obsidian vault for planning and task tracking.

- Vault path: `obsidian-vault/`
- Git policy: the vault is intentionally gitignored.
- Shared conventions: this file is tracked and should be updated when the workflow changes.

## Recommended Plugins

Use Obsidian's community plugin installer where possible.

### Core planning

| Plugin | Plugin ID | Use |
| --- | --- | --- |
| Tasks | `obsidian-tasks-plugin` | Query tasks across the vault, due dates, recurring work, custom statuses, and dashboard task lists. |
| Bases | core plugin | Database-like task views from frontmatter properties, including tables, cards, filters, formulas, and grouping. |
| Task List Kanban | `task-list-kanban` | Optional kanban over Markdown task lines when drag-and-drop task movement is desired. |
| Dataview | `dataview` | Optional advanced dashboards if Tasks and Bases are not expressive enough. |

Avoid relying on the discontinued Projects plugin for new planning infrastructure. Prefer Bases for database-like views because it is a core Obsidian plugin and stores data in local Markdown properties.

### Agent-friendly plugins

These extend the vault so agents (and the user) can work against it more reliably. None are mandatory; install incrementally.

| Plugin | Plugin ID | Why | Recommended config |
| --- | --- | --- | --- |
| Linter | `obsidian-linter` | Auto-format frontmatter property order, list-item style, and date strings. Reduces drift that the agent would otherwise have to detect and correct task-by-task. | `Format on save: OFF` (avoid races while agents edit). `Format on file change: OFF`. Run via the "Lint all files in vault" command on a cadence the user controls. Enable rules: YAML key sort, YAML timestamp, trailing whitespace, consecutive blank lines. Disable any rule that rewrites task-list checkbox text. |
| Templater | `templater-obsidian` | JS-driven templates. Lets `Daily-Note.md` actually inline the output of `lifecycle.py list --status review` instead of leaving a paste placeholder. Used together with Periodic Notes. | Enable user scripts. Add a user function `lifecycle(status)` that shells out to `python3 .claude/skills/obsidian-project-management/lifecycle.py list --status <status>` and returns Markdown. Trigger on `tp.file.creation_date`. |
| Periodic Notes | `periodic-notes` | Auto-creates the Daily-Note from the template each day. Without this, `Daily-Note.md` is just a stale skeleton. | Daily Notes folder: `Dashboards/Daily/`. Template: `Templates/Daily-Note.md`. Date format: `YYYY-MM-DD`. Disable Weekly/Monthly until needed. |
| Local REST API | `obsidian-local-rest-api` | Exposes vault contents over HTTP. Required by some MCP bridges; on its own, lets scripts read backlinks, tags, and the live link graph beyond what raw Markdown reveals. | Bind to `127.0.0.1` only. Token-protected. Do not expose on LAN. Treat the token as a secret per `.claude/rules/security.md`. |
| MCP Obsidian bridge | `mcp-obsidian` (external MCP server, not an Obsidian plugin) | Exposes the vault as MCP tools so an agent can query, search, and edit notes through a structured interface instead of raw file reads. Best fit when the vault grows beyond what `grep` and `lifecycle.py` cover comfortably. | Run as a local MCP server pointed at this vault. Disable write tools while the toolkit gate is `gated`; re-enable selectively post-MVP. Pair with Local REST API if the bridge requires it. |

### Optional, situational

| Plugin | Plugin ID | When to add | Notes |
| --- | --- | --- | --- |
| Smart Connections | `smart-connections` | Vault grows past ~500 notes and `grep` over `Tasks/` becomes noisy. | Embedding-based search. Useful for *your* recall; agents already do this kind of reasoning, so it mostly helps the human side of the workflow. Watch the model + token cost. |
| Properties View | core plugin | Always. | Already core in current Obsidian. Surfaces frontmatter inline; pairs well with Linter. |
| QuickAdd | `quickadd` | Manual one-key task creation feels slow. | Personal ergonomics; agents do not consume it. Keep it consistent with `Templates/Task.md`. |
| File Hider | `obsidian-file-hider` | The vault root gets noisy. | Hides `Templates/`, `Bases/` from the file pane without affecting indexing. Cosmetic. |

### Plugins to avoid for this workflow

- **Projects** — discontinued; superseded by Bases.
- **Kanban** (legacy) — duplicates Bases card view; introduces a parallel source of truth for task status. Use Task List Kanban over Bases instead if drag-and-drop is needed.
- **AI co-pilot plugins** that rewrite notes inline (Smart Composer, AI Assistants) — they conflict with agent edits and risk losing the structured frontmatter shape.

### Race-condition note

When agents are running, prefer plugins that **read** the vault over plugins that **rewrite on save**. Linter and Smart Connections both have rewrite modes; keep them in on-demand mode to avoid clobbering an in-flight agent edit. If a plugin must rewrite, scope it to a folder agents do not touch (e.g. `Dashboards/`).

### Installed status

| Plugin | Status | Notes |
| --- | --- | --- |
| Tasks | installed (existing) | — |
| Bases (core) | enabled (existing) | — |
| Task List Kanban | installed (existing) | — |
| Kanban (legacy) | installed (existing) | Avoid for new boards; doc recommends Bases. |
| Linter | installed | `lintOnSave: false`. Run "Lint all files in vault" on demand. |
| Templater | installed | User scripts at `Templates/Scripts/`. Folder template binds `Dashboards/Daily/` → `Templates/Daily-Note`. System commands enabled. |
| Periodic Notes | installed | Daily folder `Dashboards/Daily/`, format `YYYY-MM-DD`, template `Templates/Daily-Note`. |
| Local REST API | installed | **Requires manual step:** open Obsidian → Settings → Local REST API → generate API key. Bind to `127.0.0.1` only. The generated key is a secret — never commit it, never paste it into a tracked file. |

Open Obsidian once after this change to load the plugins. Linter, Templater, and Periodic Notes work immediately. Local REST API needs the key generated via UI before any external client (including the MCP bridge) can connect.

### MCP Obsidian bridge — manual setup

The MCP Obsidian bridge is not an Obsidian plugin; it's a standalone MCP server that talks to the Local REST API. Two implementations exist:

- `MarkusPfundstein/mcp-obsidian` (Python, more mature).
- `smithery-ai/mcp-obsidian` and similar Node forks.

Install procedure (Python, recommended):

1. Generate the Local REST API key in Obsidian (see above).
2. `pipx install mcp-obsidian` or `uv tool install mcp-obsidian`.
3. Add an entry to your Claude Code MCP configuration (per-project `.mcp.json` or user-level config):

   ```json
   {
     "mcpServers": {
       "obsidian": {
         "command": "mcp-obsidian",
         "env": {
           "OBSIDIAN_API_KEY": "<paste-from-obsidian-ui>",
           "OBSIDIAN_HOST": "127.0.0.1"
         }
       }
     }
   }
   ```

4. Reload Claude Code.

While the agentic toolkit (`docs/agentic-toolkit.md`) is `gated`, restrict the MCP server to read tools only — the existing `Read` + `lifecycle.py` flow already covers writes, and giving an agent unbounded write access via MCP introduces drift risk before the toolkit is exercised.

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
needs_smoke: false
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
- `review` — code/logic review by agent; no device required.
- `smoke` — manual device or visual test by Matteo required before close.
- `done`
- `wont-do`

**`needs_smoke` field** (`true` / `false`, default `false`): tasks that require a real-device or visual smoke test before closing. When `needs_smoke: true`, `lifecycle.py set <ID> done` from `review` auto-routes to `smoke` instead of `done`; the downstream cascade fires only when `smoke → done`. Tasks with `needs_smoke: false` go directly `review → done`.

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

Allowed `milestone` values:

- `MVP` — work required to ship the first release.
- `post-MVP` — roadmap work scheduled after MVP cuts. Tasks may reference epics from either list below; do not mix MVP and post-MVP scope inside a single task.

Use `epic` for roadmap-level grouping inside the active milestone.

Current MVP epics:

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

Current post-MVP epics:

- `Search Enhancements`
- `Playlist Integrations`
- `Album Detection`
- `Discovery & Subscriptions`
- `Tags & Smart Playlists`
- `Search History & Library Polish`
- `External Sharing`

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

Do not treat Android and iOS as a strict task-by-task ping-pong by default. Prefer short platform waves: complete a small Android group to a stable checkpoint, then use the corresponding iOS tasks as the parity pass. Android leads; iOS picks up the validated shape afterwards.

Rules:

- Prefer one implementation platform at a time for normal feature delivery; only touch both platforms in one turn when the user explicitly asks for parity or when the task itself is cross-platform.
- Start iOS parity after the reference Android wave is `review` or `done`, not while the Android shape is still changing heavily.
- Use shared docs such as `docs/api-contracts.md`, `docs/design-system.md`, and manual validation docs as the product source of truth; do not copy platform-specific implementation details across platforms.
- When moving from an Android wave to iOS, reuse the accepted behavior, fixtures, validation expectations, and edge cases, but still implement them with iOS-native architecture and UI patterns.
- If the Android wave reveals missing shared behavior, record that in tracked `docs/` before starting the iOS parity wave.

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

### Post-MVP profiles (gated)

The agentic toolkit defines additional profiles that are **not** part of the routing table while `docs/agentic-toolkit.md` is `Status: gated`. Do not auto-route tasks to them until the gate is lifted.

| Future profile | Replaces / supplements | Triggered by |
| --- | --- | --- |
| `test-engineer` | Supplements platform engineer for test-only diffs | Coverage gaps surfaced in review |
| `qa-validator` | Supplements `mobile-reviewer` for batch validation | Tasks in `status: review` |
| `architecture-reviewer` | Pre-implementation review (not PR review) | Proposals that change module boundaries or shared contracts |
| `release-engineer` | Replaces `build-ops-engineer` for release cuts only | `phase: release` |
| `migration-engineer` | Supplements platform engineer for schema/codec bumps | `area: model` with version bump |
| `security-auditor` | Supplements `mobile-reviewer` for sensitive diffs | `area: auth/account/sharing/network/storage` |
| `task-search-agent` | Read-only assistant; not a routing target | Vault queries |

After lifting the gate, fold these rows into the main routing table and remove `post_mvp: true` from each agent's frontmatter.

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

A task with `platform` in `{android, ios}` and a non-empty `design_mockup` (or any `## Acceptance Criteria` mentioning a mockup or visual pass) does **not** move to `status: done` until both of the following are true:

1. The required screenshots are present under `design-system/screenshots/<task-id>-*.png` (one per major state listed in the task's Reviewer Instructions or, if absent, one per primary screen state the task ships).
2. The visual-pass acceptance checkbox is ticked.

Exception: if a hard `depends_on` keeps the build from running, the task remains in `status: review` with `blocked_reason` describing what is missing. It does not silently move to `done` with the visual-pass checkbox unticked.

**`platform: docs` carve-out:** the screenshots requirement does not apply to `platform: docs` handoff tasks where the deliverable is a set of design assets (e.g. `design-system/handoff/<task-id>/`). The assets at the handoff path are themselves the visual record. The task body should still state this exception explicitly under `## Review notes` and reference the handoff folder so reviewers can locate the canonical visual evidence. Audit / verification of the assets in their target context (e.g. assets rendered on a launcher) belongs in dedicated absorption follow-on tasks (`platform: android` or `platform: ios`) which are subject to the standard screenshot rule.

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

1. Run `python3 .claude/skills/obsidian-project-management/lifecycle.py set <ID> done` (or `cascade <ID>` if the task was already `done` and the dependents need reconciling). The script:
   - Finds every task whose `depends_on` includes the completed ID.
   - Moves each downstream task to `ready` if all its deps are now `done`, clearing `blocked_reason`.
   - Otherwise sets it to `blocked` with a canonical `Waiting on <ID>(<status>), ...` reason.
   - Refreshes `updated` on every file it writes.
2. Check the `epic` of the completed task: if every task sharing that `epic` is now `done` or `wont-do`, add a completion note to the relevant epic-level context in `docs/` or the vault dashboard, and apply the **Epic polish auto-spawn** rule (see below) if the epic accumulated non-blocking review nits.
3. Check the `phase` of the completed task: if every task in that `phase` across the same `platform` is now `done` or `wont-do`, confirm the phase is complete before starting the next-phase tasks.

### Lifecycle script

`.claude/skills/obsidian-project-management/lifecycle.py` is the only supported way to change task status:

- `set <ID> <status> [--reason "..."] [--force]` — single transition. Validates against the allowed-transitions table (e.g. `done → in-progress` is rejected without `--force`), refuses to go `→ in-progress` or `→ done` while `depends_on` is unmet (or while `child_tasks` are incomplete), requires `--reason` for `→ blocked`. Auto-cascades after a `done` write.
- `cascade <ID> [<ID> ...]` — recompute the direct dependents of given IDs without changing the seed task itself.
- `audit [--milestone X] [--platform Y] [--epic Z] [--status S] [--apply]` — sweep the whole vault (or a slice). Promotes ready-able tasks and blocks tasks with unmet deps. Default is dry-run; `--apply` writes.
- `list [filters]` — quick listing of `id / status / platform / title`.
- `show <ID>` — print deps, dependents, parent/children.

The script never edits acceptance-criteria checkboxes, body text, or non-status frontmatter beyond `status`, `blocked_reason`, and `updated`. Anything else (epic completion notes, splits, polish auto-spawn) stays a manual editorial action.

`wont-do` is treated as a satisfied dependency: dependents of a `wont-do` task may proceed.

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
