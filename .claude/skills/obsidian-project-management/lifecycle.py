#!/usr/bin/env python3
"""
Task lifecycle manager for the YourTube Obsidian planning vault.

Subcommands:
  audit                              Sweep all tasks, recompute status from depends_on.
  cascade <ID> [<ID> ...]            Recompute downstream of just-completed IDs.
  set <ID> <status> [--reason ...]   Single-task transition with validation + auto-cascade on done.
  list [filters]                     List task IDs/titles/status.
  show <ID>                          Show one task's deps, dependents, parent/children.
  next-id [--prefix YT] [--pad 4]    Print next free task ID (max existing + 1). MUST be used
                                     before creating a new task note to avoid duplicate IDs.
  check-ids                          Report any duplicate IDs across filenames + frontmatter.

Common flags:
  --apply                            For audit: actually write changes (default is dry-run).
  --milestone X / --platform Y / --epic Z / --status S
                                     Filters for audit and list.
  --force                            For set: skip dep/child safety checks.

Allowed statuses: backlog, ready, in-progress, blocked, review, smoke, done, wont-do.

Status meaning:
  review  — code/logic review by agent (no device needed).
  smoke   — manual device/visual test by Matteo required before close.

Tasks with needs_smoke: true auto-route from review → smoke when set to done.
Tasks with needs_smoke: false (or absent) go directly review → done.

Run from repo root or anywhere; the script resolves the vault relative to its own location.
"""

import argparse
import os
import re
import sys
from datetime import datetime

VAULT_TASKS_DIR = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "../../../obsidian-vault/Tasks")
)
TODAY = datetime.now().strftime("%Y-%m-%d")
NOW_TS = datetime.now().strftime("%Y-%m-%dT%H:%M:%S")

ALLOWED_STATUSES = {"backlog", "ready", "in-progress", "blocked", "review", "smoke", "done", "wont-do"}
TERMINAL = {"done", "wont-do"}

# Allowed status transitions. Source maps to a set of allowed targets.
# `set` enforces these unless --force is given.
#
# smoke sits between review and done for tasks with needs_smoke: true.
# Requesting `done` from `review` on a needs_smoke task auto-routes to `smoke`
# (see cmd_set). The actual review→done shortcut is only for needs_smoke: false.
ALLOWED_TRANSITIONS = {
    "backlog":     {"ready", "blocked", "wont-do", "in-progress"},
    "ready":       {"in-progress", "blocked", "backlog", "wont-do"},
    "in-progress": {"review", "blocked", "ready", "wont-do"},
    "review":      {"smoke", "done", "in-progress", "blocked", "wont-do"},
    "smoke":       {"done", "in-progress", "blocked", "wont-do"},
    "blocked":     {"ready", "in-progress", "backlog", "wont-do"},
    "done":        {"wont-do"},  # reopening done is rare; require --force
    "wont-do":     {"backlog"},
}


# ---------- frontmatter parsing ----------

FM_RE = re.compile(r"^---\n(.*?)\n---", re.DOTALL)


def split_frontmatter(content):
    m = FM_RE.match(content)
    if not m:
        return None, content
    return m.group(1), content[m.end():]


def get_field(fm, key):
    m = re.search(rf"^{re.escape(key)}:\s*(.*)$", fm, re.MULTILINE)
    if not m:
        return None
    val = m.group(1).strip()
    if val.startswith('"') and val.endswith('"'):
        val = val[1:-1]
    return val


def get_list_field(fm, key):
    # inline: key: [a, b]
    m = re.search(rf"^{re.escape(key)}:\s*\[(.*?)\]\s*$", fm, re.MULTILINE)
    if m:
        body = m.group(1).strip()
        if not body:
            return []
        return [x.strip().strip('"').strip("'") for x in body.split(",") if x.strip()]
    # block:
    # key:
    #   - a
    #   - b
    m = re.search(rf"^{re.escape(key)}:\s*\n((?:[ \t]+-[ \t]*\S.*\n?)+)", fm, re.MULTILINE)
    if m:
        return re.findall(r"-\s*(\S+)", m.group(1))
    return []


def set_field(fm, key, value):
    """Replace `key: ...` line in frontmatter, preserving everything else."""
    pattern = rf"^{re.escape(key)}:\s*.*$"
    replacement = f"{key}: {value}"
    new_fm, n = re.subn(pattern, replacement, fm, count=1, flags=re.MULTILINE)
    if n == 0:
        # field missing: append before closing
        new_fm = fm.rstrip() + f"\n{replacement}"
    return new_fm


def write_frontmatter(path, fm, body):
    with open(path, "w") as f:
        f.write(f"---\n{fm}\n---{body}")


# ---------- vault load ----------

def load_vault():
    if not os.path.isdir(VAULT_TASKS_DIR):
        sys.exit(f"Vault tasks dir not found: {VAULT_TASKS_DIR}")
    tasks = {}
    for fname in sorted(os.listdir(VAULT_TASKS_DIR)):
        if not fname.endswith(".md"):
            continue
        path = os.path.join(VAULT_TASKS_DIR, fname)
        with open(path) as f:
            content = f.read()
        fm, body = split_frontmatter(content)
        if fm is None:
            continue
        tid = get_field(fm, "id")
        if not tid:
            continue
        needs_smoke_raw = get_field(fm, "needs_smoke") or "false"
        tasks[tid] = {
            "path": path,
            "fname": fname,
            "fm": fm,
            "body": body,
            "title": get_field(fm, "title") or "",
            "status": get_field(fm, "status") or "unknown",
            "milestone": get_field(fm, "milestone") or "",
            "platform": get_field(fm, "platform") or "",
            "epic": get_field(fm, "epic") or "",
            "phase": get_field(fm, "phase") or "",
            "deps": get_list_field(fm, "depends_on"),
            "parent_id": get_field(fm, "parent_id"),
            "child_tasks": get_list_field(fm, "child_tasks"),
            "needs_smoke": needs_smoke_raw.lower() == "true",
        }
    return tasks


DEFAULT_ID_PREFIX = "YT"
DEFAULT_ID_PAD = 4
ID_RE = re.compile(r"\b([A-Z]+)-(\d+)\b")


def collect_all_ids(prefix=DEFAULT_ID_PREFIX):
    """
    Scan the tasks dir and collect every <prefix>-NNNN occurrence.
    Returns dict: id_str -> sorted list of distinct filenames that claim that ID
    (via filename prefix OR frontmatter `id:`). >1 entry = real duplicate.
    """
    if not os.path.isdir(VAULT_TASKS_DIR):
        sys.exit(f"Vault tasks dir not found: {VAULT_TASKS_DIR}")
    seen = {}  # id -> set of filenames
    for fname in os.listdir(VAULT_TASKS_DIR):
        if not fname.endswith(".md"):
            continue
        ids_in_file = set()
        m = re.match(rf"^({re.escape(prefix)}-\d+)\b", fname)
        if m:
            ids_in_file.add(m.group(1))
        path = os.path.join(VAULT_TASKS_DIR, fname)
        try:
            with open(path) as f:
                content = f.read()
        except OSError:
            continue
        fm, _ = split_frontmatter(content)
        if fm is not None:
            tid = get_field(fm, "id")
            if tid and tid.startswith(f"{prefix}-"):
                ids_in_file.add(tid)
        for tid in ids_in_file:
            seen.setdefault(tid, set()).add(fname)
    return {tid: sorted(files) for tid, files in seen.items()}


def compute_next_id(prefix=DEFAULT_ID_PREFIX, pad=DEFAULT_ID_PAD):
    """
    Return the next free task ID as a string: '<prefix>-NNNN'.
    Uses max(existing) + 1 so gaps from deleted tasks are NOT reused
    (avoids resurrecting stale references in commit messages / links).
    """
    ids = collect_all_ids(prefix=prefix)
    max_n = 0
    for tid in ids:
        m = re.match(rf"^{re.escape(prefix)}-(\d+)$", tid)
        if m:
            max_n = max(max_n, int(m.group(1)))
    return f"{prefix}-{max_n + 1:0{pad}d}"


def find_duplicate_ids(prefix=DEFAULT_ID_PREFIX):
    """Return dict of id -> sources, only for ids appearing in >1 source."""
    seen = collect_all_ids(prefix=prefix)
    return {tid: srcs for tid, srcs in seen.items() if len(srcs) > 1}


def save_task(task, status=None, blocked_reason=None):
    fm = task["fm"]
    if status is not None:
        fm = set_field(fm, "status", status)
        task["status"] = status
    if blocked_reason is not None:
        # blocked_reason is always quoted
        safe = blocked_reason.replace('"', '\\"')
        fm = set_field(fm, "blocked_reason", f'"{safe}"')
    fm = set_field(fm, "updated", TODAY)
    task["fm"] = fm
    write_frontmatter(task["path"], fm, task["body"])


# ---------- core lifecycle logic ----------

def unmet_deps(task, tasks):
    """Return list of dep IDs that are not yet done."""
    out = []
    for d in task["deps"]:
        dep = tasks.get(d)
        if not dep:
            out.append(f"{d}(missing)")
        elif dep["status"] not in TERMINAL or dep["status"] == "wont-do":
            # treat wont-do as satisfied (deliberately not blocking dependents)
            if dep["status"] != "wont-do":
                out.append(f"{d}({dep['status']})")
    return out


def recompute_one(task, tasks):
    """
    Decide what status a task should have given current dep states.
    Returns (new_status, new_blocked_reason) or (None, None) if no change.
    Only transitions tasks that are in non-active dep-driven states.
    """
    if task["status"] in {"in-progress", "review", "smoke", "done", "wont-do"}:
        return None, None

    unmet = unmet_deps(task, tasks)
    cur = task["status"]

    if not unmet:
        # all deps done (or no deps): should be ready
        if cur != "ready":
            return "ready", ""
        return None, None

    # has unmet deps: should be blocked
    reason = "Waiting on " + ", ".join(unmet) + "."
    if cur != "blocked":
        return "blocked", reason
    return None, None


# ---------- subcommands ----------

def cmd_audit(args):
    tasks = load_vault()
    candidates = []
    for tid, t in tasks.items():
        if args.milestone and t["milestone"] != args.milestone:
            continue
        if args.platform and t["platform"] != args.platform:
            continue
        if args.epic and t["epic"] != args.epic:
            continue
        if args.status and t["status"] != args.status:
            continue
        candidates.append(tid)

    changes = []
    for tid in sorted(candidates):
        t = tasks[tid]
        new_status, new_reason = recompute_one(t, tasks)
        if new_status is None:
            continue
        changes.append((tid, t["status"], new_status, new_reason))

    print(f"Vault: {len(tasks)} tasks. Audit candidates: {len(candidates)}.")
    if not changes:
        print("No changes needed.")
        return

    print(f"\n{'APPLY' if args.apply else 'DRY-RUN'} — {len(changes)} change(s):")
    for tid, old, new, reason in changes:
        suffix = f"  ({reason})" if reason else ""
        print(f"  {tid}: {old} → {new}{suffix}")

    if not args.apply:
        print("\nRe-run with --apply to write changes.")
        return

    for tid, _old, new, reason in changes:
        save_task(tasks[tid], status=new, blocked_reason=reason)
    print(f"\nWrote {len(changes)} file(s). updated={TODAY}")


def cmd_cascade(args):
    tasks = load_vault()
    seed_ids = set(args.ids)
    missing = [tid for tid in seed_ids if tid not in tasks]
    if missing:
        sys.exit(f"Unknown task ID(s): {', '.join(missing)}")

    # find direct dependents
    affected = []
    for tid, t in tasks.items():
        if any(d in seed_ids for d in t["deps"]):
            new_status, new_reason = recompute_one(t, tasks)
            if new_status is not None:
                affected.append((tid, t["status"], new_status, new_reason))

    if not affected:
        print(f"No downstream changes for: {', '.join(sorted(seed_ids))}")
        return

    print(f"Cascade from: {', '.join(sorted(seed_ids))}")
    for tid, old, new, reason in affected:
        suffix = f"  ({reason})" if reason else ""
        print(f"  {tid}: {old} → {new}{suffix}")

    for tid, _old, new, reason in affected:
        save_task(tasks[tid], status=new, blocked_reason=reason)
    print(f"\nWrote {len(affected)} file(s). updated={TODAY}")


def cmd_set(args):
    tasks = load_vault()
    t = tasks.get(args.id)
    if not t:
        sys.exit(f"Task not found: {args.id}")

    new = args.status
    if new not in ALLOWED_STATUSES:
        sys.exit(f"Invalid status '{new}'. Allowed: {sorted(ALLOWED_STATUSES)}")

    cur = t["status"]
    if cur == new:
        print(f"{args.id} already {new}; nothing to do.")
        return

    # Auto-route: review → done on a needs_smoke task becomes review → smoke.
    # The cascade fires only when the task actually reaches done (from smoke).
    if new == "done" and cur == "review" and t["needs_smoke"] and not args.force:
        print(f"{args.id}: needs_smoke=true — routing review → smoke instead of done.")
        new = "smoke"

    if not args.force:
        allowed = ALLOWED_TRANSITIONS.get(cur, set())
        if new not in allowed:
            sys.exit(
                f"Transition not allowed: {cur} → {new}. "
                f"Allowed from '{cur}': {sorted(allowed) or 'none'}. "
                f"Use --force to override."
            )

    # Safety checks
    if new == "in-progress" and not args.force:
        unmet = unmet_deps(t, tasks)
        if unmet:
            sys.exit(
                f"{args.id} has unmet deps: {unmet}. "
                f"Resolve or use --force."
            )

    if new == "done" and not args.force:
        unmet = unmet_deps(t, tasks)
        if unmet:
            sys.exit(f"{args.id} has unmet deps: {unmet}. Use --force to override.")
        for child_id in t["child_tasks"]:
            child = tasks.get(child_id)
            if not child:
                continue
            if child["status"] not in TERMINAL:
                sys.exit(
                    f"{args.id} has incomplete child {child_id} ({child['status']}). "
                    f"Use --force to override."
                )

    if new == "blocked" and not args.reason and not args.force:
        sys.exit("Blocking requires --reason. Use --force to bypass.")

    reason = ""
    if new == "blocked":
        reason = args.reason or t["fm"] and ""
    # if leaving blocked, clear reason
    save_task(t, status=new, blocked_reason=reason)
    print(f"{args.id}: {cur} → {new}{f' ({reason})' if reason else ''}")

    # Auto-cascade on done
    if new == "done":
        # rebuild after write
        tasks = load_vault()
        affected = []
        for tid, other in tasks.items():
            if args.id in other["deps"]:
                ns, nr = recompute_one(other, tasks)
                if ns is not None:
                    affected.append((tid, other["status"], ns, nr))
        if affected:
            print(f"\nCascade: {len(affected)} downstream change(s):")
            for tid, old, ns, nr in affected:
                suffix = f"  ({nr})" if nr else ""
                print(f"  {tid}: {old} → {ns}{suffix}")
                save_task(tasks[tid], status=ns, blocked_reason=nr)


def cmd_list(args):
    tasks = load_vault()
    rows = []
    for tid, t in tasks.items():
        if args.milestone and t["milestone"] != args.milestone:
            continue
        if args.platform and t["platform"] != args.platform:
            continue
        if args.epic and t["epic"] != args.epic:
            continue
        if args.status and t["status"] != args.status:
            continue
        rows.append((tid, t["status"], t["platform"], t["title"]))

    rows.sort()
    print(f"{len(rows)} task(s):")
    for tid, status, plat, title in rows:
        print(f"  {tid}  {status:<12} {plat:<8} {title}")


def cmd_next_id(args):
    nid = compute_next_id(prefix=args.prefix, pad=args.pad)
    dups = find_duplicate_ids(prefix=args.prefix)
    if dups:
        print(
            f"WARNING: {len(dups)} duplicate ID(s) already in vault — "
            f"run `check-ids` to inspect. New ID still safe.",
            file=sys.stderr,
        )
    print(nid)


def cmd_check_ids(args):
    dups = find_duplicate_ids(prefix=args.prefix)
    if not dups:
        print(f"No duplicate {args.prefix}- IDs found.")
        return
    print(f"{len(dups)} duplicate ID(s) found:")
    for tid in sorted(dups):
        print(f"  {tid}:")
        for fname in dups[tid]:
            print(f"    - {fname}")
    sys.exit(1)


def cmd_show(args):
    tasks = load_vault()
    t = tasks.get(args.id)
    if not t:
        sys.exit(f"Task not found: {args.id}")

    print(f"{t['fname']}")
    print(f"  id:        {args.id}")
    print(f"  title:     {t['title']}")
    print(f"  status:    {t['status']}")
    print(f"  milestone: {t['milestone']}")
    print(f"  epic:      {t['epic']}")
    print(f"  phase:     {t['phase']}")
    print(f"  platform:  {t['platform']}")
    print(f"  parent:    {t['parent_id'] or '-'}")
    print(f"  children:  {t['child_tasks'] or '-'}")
    print(f"  deps:")
    for d in t["deps"]:
        dep = tasks.get(d)
        s = dep["status"] if dep else "MISSING"
        print(f"    - {d} ({s})")
    if not t["deps"]:
        print("    (none)")
    dependents = [tid for tid, other in tasks.items() if args.id in other["deps"]]
    print(f"  dependents:")
    for d in dependents:
        print(f"    - {d} ({tasks[d]['status']})")
    if not dependents:
        print("    (none)")


# ---------- CLI ----------

def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    audit = sub.add_parser("audit", help="Sweep all tasks; recompute dep-driven status.")
    audit.add_argument("--apply", action="store_true", help="Write changes (default: dry-run).")
    audit.add_argument("--milestone")
    audit.add_argument("--platform")
    audit.add_argument("--epic")
    audit.add_argument("--status")
    audit.set_defaults(func=cmd_audit)

    cascade = sub.add_parser("cascade", help="Recompute direct dependents of given task IDs.")
    cascade.add_argument("ids", nargs="+")
    cascade.set_defaults(func=cmd_cascade)

    setp = sub.add_parser("set", help="Transition one task to a new status.")
    setp.add_argument("id")
    setp.add_argument("status")
    setp.add_argument("--reason", default="", help="Required when moving to 'blocked'.")
    setp.add_argument("--force", action="store_true", help="Bypass transition/dep/child checks.")
    setp.set_defaults(func=cmd_set)

    listp = sub.add_parser("list", help="List tasks.")
    listp.add_argument("--milestone")
    listp.add_argument("--platform")
    listp.add_argument("--epic")
    listp.add_argument("--status")
    listp.set_defaults(func=cmd_list)

    showp = sub.add_parser("show", help="Show one task's deps and dependents.")
    showp.add_argument("id")
    showp.set_defaults(func=cmd_show)

    nextp = sub.add_parser(
        "next-id",
        help="Print next free task ID. MUST be called before creating a new task note.",
    )
    nextp.add_argument("--prefix", default=DEFAULT_ID_PREFIX)
    nextp.add_argument("--pad", type=int, default=DEFAULT_ID_PAD)
    nextp.set_defaults(func=cmd_next_id)

    checkp = sub.add_parser("check-ids", help="Report duplicate task IDs across filenames + frontmatter.")
    checkp.add_argument("--prefix", default=DEFAULT_ID_PREFIX)
    checkp.set_defaults(func=cmd_check_ids)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
