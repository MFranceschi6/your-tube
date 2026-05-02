#!/usr/bin/env python3
"""
Post-done cascade for YourTube Obsidian task vault.

Usage:
    python3 cascade-done.py YT-0004
    python3 cascade-done.py YT-0004 YT-0020

For each completed task ID, finds all downstream tasks whose depends_on
includes it, then:
  - Moves to 'ready' if every dep is now done
  - Keeps 'blocked' but refreshes blocked_reason if any dep is still incomplete
"""

import os
import re
import sys
from datetime import datetime

TASKS_DIR = os.path.join(
    os.path.dirname(__file__),
    "../../../obsidian-vault/Tasks"
)
TASKS_DIR = os.path.normpath(TASKS_DIR)
NOW = datetime.now().strftime("%Y-%m-%dT%H:%M:%S")


def load_all_tasks():
    tasks = {}
    for fname in os.listdir(TASKS_DIR):
        if not fname.endswith(".md"):
            continue
        path = os.path.join(TASKS_DIR, fname)
        content = open(path).read()
        tid_match = re.search(r"^id: (\S+)", content, re.MULTILINE)
        status_match = re.search(r"^status: (\S+)", content, re.MULTILINE)
        deps_match = re.search(r"depends_on:\s*\n((?:\s+- \S+\n?)+)", content)
        if not tid_match:
            continue
        tid = tid_match.group(1)
        tasks[tid] = {
            "path": path,
            "content": content,
            "status": status_match.group(1) if status_match else "unknown",
            "deps": re.findall(r"- (\S+)", deps_match.group(1)) if deps_match else [],
        }
    return tasks


def update_task_file(path, content, new_status, new_blocked_reason):
    content = re.sub(r"^status: \S+", f"status: {new_status}", content, flags=re.MULTILINE)
    content = re.sub(
        r'^blocked_reason: .*',
        f'blocked_reason: "{new_blocked_reason}"',
        content,
        flags=re.MULTILINE,
    )
    content = re.sub(r"^updated: .*", f"updated: {NOW}", content, flags=re.MULTILINE)
    open(path, "w").write(content)


def cascade(completed_ids):
    tasks = load_all_tasks()
    completed_set = {tid for tid, t in tasks.items() if t["status"] == "done"}

    changed = []

    for tid, task in sorted(tasks.items()):
        if not any(dep in completed_ids for dep in task["deps"]):
            continue
        if task["status"] in ("done", "wont-do", "ready", "in-progress", "review"):
            continue

        incomplete_deps = [d for d in task["deps"] if tasks.get(d, {}).get("status") != "done"]

        if not incomplete_deps:
            update_task_file(task["path"], task["content"], "ready", "")
            changed.append((tid, "blocked", "ready", ""))
        else:
            reason = "Waiting on " + ", ".join(incomplete_deps) + "."
            update_task_file(task["path"], task["content"], "blocked", reason)
            changed.append((tid, task["status"], "blocked", reason))

    if not changed:
        print("No downstream tasks affected.")
        return

    print(f"Cascade for: {', '.join(completed_ids)}\n")
    for tid, old, new, reason in changed:
        arrow = f"{old} → {new}"
        suffix = f"  ({reason})" if reason else ""
        print(f"  {tid}: {arrow}{suffix}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 cascade-done.py <TASK-ID> [<TASK-ID> ...]")
        sys.exit(1)
    cascade(set(sys.argv[1:]))
