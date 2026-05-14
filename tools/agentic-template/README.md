# Agentic Template (Post-MVP)

Scaffold to extract this repo's reusable agentic-development pieces into a fresh project. Gated until MVP ships (see `docs/agentic-toolkit.md`).

## What gets extracted

| Group | Path |
| --- | --- |
| Baseline agents (8) | `.claude/agents/{android,ios,shared-contract,docs,build-ops}-engineer.md`, `mobile-reviewer.md`, `obsidian-task-{curator,reviewer}.md` |
| Post-MVP agents (7) | `.claude/agents/{test,qa-validator,architecture-reviewer,release,migration,security-auditor,task-search}-*.md` |
| Project-management skill | `.claude/skills/obsidian-project-management/{SKILL.md,lifecycle.py}` |
| Generic skills | `.claude/skills/{changelog-from-tasks,task-graph-viz,cross-platform-parity-diff,pr-stack,flaky-test-triage,agent-budget}/SKILL.md` |
| Rules | `.claude/rules/{security,testing}.md` |
| Planning doc | `docs/obsidian-planning.md` |
| Toolkit doc + hooks proposal | `docs/agentic-toolkit.md`, `docs/agentic-toolkit-hooks.json` |
| Vault templates | `obsidian-vault/Templates/{Task,Epic,Polish-Task,Daily-Note}.md` |
| Bases shell | `obsidian-vault/Bases/Tasks.base` (rendered with target schema) |

## What stays project-specific

- `CLAUDE.md` body (only the "Project: ..." header is templated).
- `AGENTS.md` body.
- `docs/api-contracts.md`, `docs/design-system.md`, `docs/release-notes.md`.
- `obsidian-vault/Tasks/`.
- `design-system/` and any product-art assets.
- `tools/perf-trace/`, `tools/yt-probe/`, `tools/ytmusic-probe/` (your-tube specific).

## Variables

Replaced at extraction time:

| Variable | Replaces | Example |
| --- | --- | --- |
| `{{PROJECT_NAME}}` | Project display name | `My App` |
| `{{PROJECT_PREFIX}}` | Task ID prefix | `MA-` (replaces `YT-`) |
| `{{INITIAL_MILESTONE}}` | First milestone | `MVP` |
| `{{PRIMARY_PLATFORMS}}` | Platforms list | `android,ios` |
| `{{ANDROID_PACKAGE}}` | Android app id | `com.myapp` (only if Android included) |
| `{{IOS_BUNDLE_ID}}` | iOS bundle id | `com.myapp.ios` (only if iOS included) |

## Usage

```bash
# Dry run — prints what would be copied, no writes.
./extract.sh \
  --target ~/projects/new-app \
  --project-name "New App" \
  --prefix NA- \
  --platforms android,ios \
  --dry-run

# Real extract.
./extract.sh \
  --target ~/projects/new-app \
  --project-name "New App" \
  --prefix NA- \
  --platforms android,ios
```

The script never writes inside its own repo. Target directory must not exist or must be empty (the script aborts otherwise).

## Activation note

This README and `extract.sh` ship in the gated state. The script is allowed to be **read** while gated; it must not be **run** until the toolkit gate is `active`. Running pre-gate risks creating downstream projects that import an unfinished kit.
