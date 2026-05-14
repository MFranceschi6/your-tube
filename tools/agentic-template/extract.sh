#!/usr/bin/env bash
# Agentic Template extractor. Gated until MVP ships (see docs/agentic-toolkit.md).
# Copies the reusable agentic pieces of this repo into a fresh target directory,
# substituting per-project variables. Never writes inside this repo.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"

TARGET=""
PROJECT_NAME=""
PREFIX=""
PLATFORMS="android,ios"
INITIAL_MILESTONE="MVP"
DRY_RUN="false"

usage() {
  cat <<USAGE
Usage: extract.sh --target DIR --project-name NAME --prefix XX- [options]

Required:
  --target DIR              Target directory (must not exist or must be empty)
  --project-name NAME       Display name (e.g. "My App")
  --prefix XX-              Task ID prefix replacing YT-

Optional:
  --platforms LIST          Comma-separated: android,ios (default: android,ios)
  --milestone NAME          Initial milestone label (default: MVP)
  --dry-run                 Print actions, do not write
  -h, --help                Show this help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target) TARGET="$2"; shift 2 ;;
    --project-name) PROJECT_NAME="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    --platforms) PLATFORMS="$2"; shift 2 ;;
    --milestone) INITIAL_MILESTONE="$2"; shift 2 ;;
    --dry-run) DRY_RUN="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

[[ -z "$TARGET" || -z "$PROJECT_NAME" || -z "$PREFIX" ]] && { usage; exit 2; }

# Refuse to write inside the source repo.
if [[ "$(cd "$TARGET" 2>/dev/null && pwd || echo)" == "$SOURCE_REPO"* ]]; then
  echo "error: target is inside the source repo. Refusing to write." >&2
  exit 1
fi

# Refuse if target exists and is non-empty.
if [[ -e "$TARGET" ]]; then
  if [[ "$(ls -A "$TARGET" 2>/dev/null | wc -l | tr -d ' ')" != "0" ]]; then
    echo "error: target $TARGET exists and is non-empty. Aborting." >&2
    exit 1
  fi
fi

# Verify gate is active before allowing a real run.
GATE_FILE="$SOURCE_REPO/docs/agentic-toolkit.md"
if [[ "$DRY_RUN" != "true" ]]; then
  if ! grep -qE "^Status: \`active\`" "$GATE_FILE" 2>/dev/null; then
    echo "error: toolkit gate is not active in docs/agentic-toolkit.md." >&2
    echo "       Run with --dry-run to preview, or lift the gate post-MVP." >&2
    exit 1
  fi
fi

run() {
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "[dry-run] $*"
  else
    eval "$@"
  fi
}

ITEMS=(
  ".claude/agents"
  ".claude/skills/obsidian-project-management"
  ".claude/skills/changelog-from-tasks"
  ".claude/skills/task-graph-viz"
  ".claude/skills/cross-platform-parity-diff"
  ".claude/skills/pr-stack"
  ".claude/skills/flaky-test-triage"
  ".claude/skills/agent-budget"
  ".claude/rules"
  "docs/obsidian-planning.md"
  "docs/agentic-toolkit.md"
  "docs/agentic-toolkit-hooks.json"
  "obsidian-vault/Templates"
)

echo "Source repo : $SOURCE_REPO"
echo "Target dir  : $TARGET"
echo "Project name: $PROJECT_NAME"
echo "Task prefix : $PREFIX"
echo "Platforms   : $PLATFORMS"
echo "Milestone   : $INITIAL_MILESTONE"
echo "Dry run     : $DRY_RUN"
echo

run "mkdir -p '$TARGET'"

for item in "${ITEMS[@]}"; do
  src="$SOURCE_REPO/$item"
  dst="$TARGET/$item"
  if [[ ! -e "$src" ]]; then
    echo "skip: $item (missing in source)"
    continue
  fi
  run "mkdir -p '$(dirname "$dst")'"
  run "cp -R '$src' '$dst'"
done

# Empty Bases shell.
run "mkdir -p '$TARGET/obsidian-vault/Bases'"
run "cp '$SOURCE_REPO/obsidian-vault/Bases/Tasks.base' '$TARGET/obsidian-vault/Bases/Tasks.base'"

# Variable substitution.
SUB_TARGETS=(
  "$TARGET/.claude/agents"
  "$TARGET/.claude/skills"
  "$TARGET/.claude/rules"
  "$TARGET/docs"
  "$TARGET/obsidian-vault/Templates"
)

for dir in "${SUB_TARGETS[@]}"; do
  [[ -d "$dir" ]] || continue
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "[dry-run] substitute YT- -> $PREFIX, MVP -> $INITIAL_MILESTONE in $dir"
  else
    find "$dir" -type f \( -name '*.md' -o -name '*.json' -o -name '*.base' \) -print0 | \
      while IFS= read -r -d '' f; do
        sed -i.bak \
          -e "s/YT-/${PREFIX}/g" \
          -e "s/your-tube/${PROJECT_NAME// /-}/g" \
          -e "s/Your-Tube/${PROJECT_NAME}/g" \
          "$f"
        rm -f "$f.bak"
      done
  fi
done

cat <<NEXT

Done.

Next steps:
  1. cd $TARGET
  2. Replace platform-specific scaffolds (android/, ios/) with your own.
  3. Edit CLAUDE.md and AGENTS.md to describe the new project.
  4. Initialize the Obsidian vault and create your first task using Templates/Task.md.
  5. Decide whether to keep the post-MVP gate active or lift it for the new project.
NEXT
