# Agentic Toolkit (Post-MVP)

Reusable agentic-development kit for this repo and future projects. **Activation gated until MVP ships.** Agents, skills, hooks, and Obsidian templates listed here are present in-tree but inert: do not invoke them, do not wire them into `settings.json`, and do not route Obsidian tasks through them while the MVP milestone is open.

## Activation Gate

The toolkit becomes live when **all of**:

1. The MVP milestone (`milestone: MVP` in `obsidian-vault/Tasks/`) reports zero non-`done`/non-`wont-do` tasks via `python3 .claude/skills/obsidian-project-management/lifecycle.py audit --milestone MVP`.
2. `docs/release-notes.md` records the MVP cut.
3. The user explicitly says "lift the toolkit gate" or moves this section's status from `gated` to `active`.

Status: `gated` (added 2026-05-08).

To activate after MVP:

- Remove the `post_mvp: true` frontmatter line from new agent files in `.claude/agents/` (test-engineer, qa-validator, architecture-reviewer, release-engineer, migration-engineer, security-auditor, task-search-agent).
- Merge `docs/agentic-toolkit-hooks.json` into `.claude/settings.json` under `hooks`.
- Move the new templates in `obsidian-vault/Templates/` into rotation by referencing them from the daily workflow.
- Run `tools/agentic-template/extract.sh --dry-run` to validate the extraction script before publishing the starter repo.

Until activation, the only behavior change is the existence of these files. Routing in `docs/obsidian-planning.md` continues to map to the existing 8 agent profiles.

## What's Included

### New Agent Personalities (`.claude/agents/`)

All gated with `post_mvp: true` in frontmatter. Routing tables in `docs/obsidian-planning.md` are **not** updated yet — these agents are reachable only by explicit invocation.

| Agent | Purpose | Activates |
| --- | --- | --- |
| `test-engineer` | Writes/audits tests only. Never edits prod code. Used to enforce coverage gaps surfaced in review. | Post-MVP |
| `qa-validator` | Runs the `validation_command` of every task in `status: review` and reports pass/fail. Read + Bash only. | Post-MVP |
| `architecture-reviewer` | Pre-implementation review of arch proposals (module boundaries, contract shape, parity risk). Distinct from PR review. | Post-MVP |
| `release-engineer` | Version bumps, changelog assembly from `done` tasks per epic, store metadata, signing checklist. | Post-MVP |
| `migration-engineer` | DB schema/data migrations, Room/SwiftData versioning, codec parity bumps. | Post-MVP |
| `security-auditor` | PII/secret/permission scan for auth-sensitive task diffs. Defers to `.claude/rules/security.md`. | Post-MVP |
| `task-search-agent` | Semantic + structural query over `obsidian-vault/Tasks/` (200+ notes). Read-only. | Post-MVP |

### New Skills (`.claude/skills/`)

Project-agnostic, reusable across future repos.

| Skill | Purpose |
| --- | --- |
| `changelog-from-tasks` | Compose release notes from `done` tasks grouped by `epic` and `phase`. |
| `task-graph-viz` | Export `depends_on` graph as Mermaid for review. |
| `cross-platform-parity-diff` | Diff feature behavior between iOS and Android task pairs. |
| `pr-stack` | Sequential PR strategy backed by `depends_on`. |
| `flaky-test-triage` | Cluster flaky-test signal, file follow-up tasks. |
| `agent-budget` | Telemetry shell: token + duration per agent invocation. |

Each skill ships only `SKILL.md` (no scripts) until activation; scripts land when the workflow needs them.

### Hooks Proposal (`docs/agentic-toolkit-hooks.json`)

Three hooks proposed; all **inactive** (file is reference-only, not loaded by the harness):

- `PostToolUse(Edit on android/** or ios/**)` — surface the `validation_command` of the current `in-progress` task.
- `Stop` — warn if an `in-progress` task has uncommitted edits at session end.
- `SessionStart` — print `lifecycle.py list --status review` and `--status blocked` as briefing.

Activation procedure documented at the top of the JSON file.

### Obsidian Templates (`obsidian-vault/Templates/`)

The vault is gitignored, so these arrived locally. Templates are inert until referenced in daily workflow.

- `Epic.md` — completion-note template auto-spawned at end of an epic.
- `Polish-Task.md` — ready-to-fill skeleton for the epic-polish auto-spawn rule.
- `Daily-Note.md` — pre-populates `lifecycle.py list --status review` / `--status blocked` snippets and a "started today" / "closed today" frame.

Existing `Task.md` template stays canonical for new tasks.

### Reusable Starter (`tools/agentic-template/`)

Scaffold and `extract.sh` script that copies the project-agnostic pieces into a new repo:

- `.claude/agents/` (the 8 baseline + 7 post-MVP)
- `.claude/skills/obsidian-project-management/` (SKILL.md + `lifecycle.py`)
- `.claude/rules/{security,testing}.md`
- `docs/obsidian-planning.md`
- Generic skills (`changelog-from-tasks`, `task-graph-viz`, etc.)
- Vault templates (`Task.md`, `Epic.md`, `Polish-Task.md`, `Daily-Note.md`)

Variables substituted: `{{PROJECT_PREFIX}}` (replaces `YT-`), `{{INITIAL_MILESTONE}}` (replaces `MVP`), `{{PRIMARY_PLATFORMS}}` (replaces `android,ios`).

Per-project bits **not** copied: `CLAUDE.md` body, `AGENTS.md` body, `docs/api-contracts.md`, `docs/design-system.md`, `design-system/`, `obsidian-vault/Tasks/`, `obsidian-vault/Bases/Tasks.base` (regenerated empty).

Run with `--dry-run` first; the script never writes inside its own repo.

## Drift Audit

`.agents/skills/` and `.claude/skills/` are **separate copies, not symlinks**. As of 2026-05-08 they are byte-identical except `.agents/skills/yourtube-design/` is `.agents`-only. Risk: future edits to one will silently diverge.

Resolution options (decide post-MVP):

- **Symlink one to the other.** Smallest diff; survives `git status`.
- **Adopt `.claude/skills/` as canonical, delete `.agents/skills/`.** Cleanest if `.agents/` was historical.
- **Adopt `.agents/skills/` as canonical, delete `.claude/skills/`.** Matches Claude Agent SDK conventions.

Decision deferred. Until decided, edit both folders together, or run a one-shot diff before commits:

```bash
diff -rq .claude/skills .agents/skills
```

## Migration Map (Post-MVP Order)

1. Lift gate (set this doc's status to `active`).
2. Resolve skill-folder drift (one option above).
3. Activate hooks: merge `docs/agentic-toolkit-hooks.json` into `.claude/settings.json`.
4. Remove `post_mvp: true` from agent frontmatter; update `docs/obsidian-planning.md` Agent Routing table.
5. First post-MVP epic uses `qa-validator` + `release-engineer` end-to-end as a smoke test of the toolkit.
6. Publish `tools/agentic-template/` as a separate template repo when the toolkit clears one full release cycle.

## Non-Goals

- This toolkit does not replace the existing 8 baseline agents or the `lifecycle.py` script.
- It does not introduce a CI pipeline; CI gating remains a separate decision.
- It does not commit any vault content (`obsidian-vault/` stays gitignored).
