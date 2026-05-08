#!/usr/bin/env python3
"""
parse_yt_perf.py — parse YT_PERF instrumentation logs and compare Android vs iOS first-note latency.

Both clients emit the same event vocabulary with identical formatting:

    [YT_PERF] EVENT t=<elapsed_ms>ms videoId=<id> [key=value ...]

`t` resets to 0 on every TAP. Events between two TAPs form one session. The script slices
each input file into sessions, computes per-stage deltas, and produces a side-by-side
median / p95 table.

Capture inputs first:

    # Android (with adb on PATH)
    adb -s emulator-5554 logcat -v time '*:S' YT_PERF:I > android.log

    # iOS (Mac running the simulator app)
    log stream --level info \\
        --predicate 'subsystem == "com.matteofranceschi.yourtube" AND category == "YT_PERF"' \\
        > ios.log

Then:

    python3 parse_yt_perf.py --android android.log --ios ios.log

Multiple files per platform are allowed and merged into the platform's session pool.

Stdlib only. Python >= 3.8.
"""
from __future__ import annotations

import argparse
import re
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

# ── Parser ────────────────────────────────────────────────────────────────────

# Matches the YT_PERF payload regardless of whether it's wrapped by adb logcat
# (`I/YT_PERF (29068): EVENT t=…`), Android's tag-prefixed format, or iOS'
# os.Logger header (`[com.…:YT_PERF] EVENT t=…`). Anchors on the literal token
# `YT_PERF`, then accepts any non-letter glue (`]`, `:`, `(`, `)`, digits, …)
# until the next ALL-CAPS event name.
PERF_LINE = re.compile(
    r"YT_PERF[^A-Z]*?(?P<event>[A-Z][A-Z_]+)\s+t=(?P<t>-?\d+)ms\s+videoId=(?P<vid>\S+)(?P<ctx>.*)$"
)


@dataclass
class Event:
    name: str
    t_ms: int
    video_id: str
    context: str  # trailing "key=value key=value …" string, may be empty


@dataclass
class Session:
    """Events between one TAP and the next TAP (or end of file)."""

    tap: Event
    events: List[Event] = field(default_factory=list)

    def first(self, name: str) -> Optional[Event]:
        for e in self.events:
            if e.name == name:
                return e
        return None

    def has_fail(self) -> bool:
        return any(e.name == "FAIL" for e in self.events)


def parse(lines: Iterable[str]) -> List[Session]:
    sessions: List[Session] = []
    current: Optional[Session] = None
    for raw in lines:
        m = PERF_LINE.search(raw)
        if not m:
            continue
        ev = Event(
            name=m.group("event"),
            t_ms=int(m.group("t")),
            video_id=m.group("vid"),
            context=m.group("ctx").strip(),
        )
        if ev.name == "TAP":
            if current is not None:
                sessions.append(current)
            current = Session(tap=ev)
        else:
            if current is None:
                # Stray event before any TAP — ignore. Tracer always TAPs first.
                continue
            current.events.append(ev)
    if current is not None:
        sessions.append(current)
    return sessions


# ── Stages (cross-platform mapping) ───────────────────────────────────────────

# Each stage is (label, start_event, end_event). For the "ready" milestone we
# accept the first iOS-or-Android signal that means "buffered enough to play":
#   Android → STATE_READY
#   iOS     → STATUS_READY_TO_PLAY  (timeControlStatus may flip a few ms later)
READY_EVENTS = ("STATE_READY", "STATUS_READY_TO_PLAY")

STAGES: List[Tuple[str, Optional[str], object]] = [
    ("tap → extract_start", None, "EXTRACT_START"),
    ("extract_duration",    "EXTRACT_START", "EXTRACT_DONE"),
    ("extract → prepare",   "EXTRACT_DONE",  "PREPARE"),
    ("prepare → ready",     "PREPARE",       READY_EVENTS),
    ("ready → first_audio", READY_EVENTS,    "FIRST_AUDIO"),
    ("total (tap → first_audio)", None,      "FIRST_AUDIO"),
]


def first_of(session: Session, target: object) -> Optional[Event]:
    """Return the first event matching `target` (str or tuple of strs)."""
    if isinstance(target, str):
        return session.first(target)
    for name in target:
        e = session.first(name)
        if e is not None:
            return e
    return None


def stage_delta(session: Session, start: Optional[object], end: object) -> Optional[int]:
    end_ev = first_of(session, end)
    if end_ev is None:
        return None
    if start is None:
        return end_ev.t_ms  # baseline = TAP at t=0
    start_ev = first_of(session, start)
    if start_ev is None:
        return None
    return end_ev.t_ms - start_ev.t_ms


# ── Aggregation ───────────────────────────────────────────────────────────────


def percentile(values: Sequence[int], p: float) -> Optional[float]:
    """Linear-interpolation percentile. Returns None for empty input."""
    if not values:
        return None
    s = sorted(values)
    if len(s) == 1:
        return float(s[0])
    k = (len(s) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(s) - 1)
    frac = k - lo
    return s[lo] + (s[hi] - s[lo]) * frac


def aggregate(sessions: List[Session]) -> Dict[str, Dict[str, Optional[float]]]:
    """Returns { stage_label: { "n": int, "median": float, "p95": float, "min": int, "max": int } }."""
    out: Dict[str, Dict[str, Optional[float]]] = {}
    for label, start, end in STAGES:
        deltas = [d for s in sessions for d in [stage_delta(s, start, end)] if d is not None and d >= 0]
        if not deltas:
            out[label] = {"n": 0, "median": None, "p95": None, "min": None, "max": None}
            continue
        out[label] = {
            "n": len(deltas),
            "median": float(statistics.median(deltas)),
            "p95": percentile(deltas, 0.95),
            "min": float(min(deltas)),
            "max": float(max(deltas)),
        }
    return out


# ── Rendering ─────────────────────────────────────────────────────────────────


def fmt_ms(v: Optional[float]) -> str:
    if v is None:
        return "—"
    return f"{v:7.0f}"


def render_per_session(platform: str, sessions: List[Session]) -> str:
    if not sessions:
        return f"\n{platform}: no sessions found.\n"
    lines = [f"\n=== {platform} — per-session timeline ({len(sessions)} sessions) ===\n"]
    header = (
        f"{'#':>2}  {'videoId':<20} {'source':<22} {'tap→ext':>8} {'extract':>8} "
        f"{'ext→prep':>9} {'prep→rdy':>9} {'rdy→1st':>8} {'total':>7} {'fail':>5}"
    )
    lines.append(header)
    lines.append("-" * len(header))
    for i, s in enumerate(sessions, 1):
        # `source=...` lives in the TAP context; parse it loosely.
        source = "?"
        for tok in s.tap.context.split():
            if tok.startswith("source="):
                source = tok.split("=", 1)[1]
                break
        cells = [stage_delta(s, start, end) for _, start, end in STAGES]
        # cells order: tap→ext_start, extract_dur, ext→prep, prep→rdy, rdy→1st, total
        lines.append(
            f"{i:>2}  {s.tap.video_id[:20]:<20} {source[:22]:<22} "
            f"{fmt_ms(cells[0])} {fmt_ms(cells[1])} {fmt_ms(cells[2])} "
            f"{fmt_ms(cells[3])} {fmt_ms(cells[4])} {fmt_ms(cells[5])} "
            f"{'YES' if s.has_fail() else '':>5}"
        )
    return "\n".join(lines) + "\n"


def render_aggregate_compare(android: Dict, ios: Dict) -> str:
    lines = [
        "\n=== Aggregate comparison (median / p95, ms) ===\n",
        f"{'stage':<28} {'A_n':>4} {'A_med':>7} {'A_p95':>7}  {'i_n':>4} {'i_med':>7} {'i_p95':>7}  {'A−i med':>9}",
        "-" * 84,
    ]
    for label, _, _ in STAGES:
        a = android.get(label, {})
        i = ios.get(label, {})
        a_med = a.get("median")
        i_med = i.get("median")
        diff = (a_med - i_med) if (a_med is not None and i_med is not None) else None
        lines.append(
            f"{label:<28} "
            f"{a.get('n', 0):>4} {fmt_ms(a_med):>7} {fmt_ms(a.get('p95')):>7}  "
            f"{i.get('n', 0):>4} {fmt_ms(i_med):>7} {fmt_ms(i.get('p95')):>7}  "
            f"{fmt_ms(diff):>9}"
        )
    lines.append("")
    lines.append("Positive 'A−i med' means Android is slower than iOS at that stage.")
    return "\n".join(lines) + "\n"


# ── Main ──────────────────────────────────────────────────────────────────────


def read_files(paths: List[Path]) -> List[str]:
    out: List[str] = []
    for p in paths:
        with p.open("r", encoding="utf-8", errors="replace") as f:
            out.extend(f.readlines())
    return out


def main(argv: List[str]) -> int:
    ap = argparse.ArgumentParser(
        description="Parse YT_PERF first-note instrumentation logs and compare Android vs iOS.",
        epilog=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--android", action="append", default=[], type=Path,
                    help="Android logcat file capturing YT_PERF lines (repeatable).")
    ap.add_argument("--ios", action="append", default=[], type=Path,
                    help="iOS log stream file capturing YT_PERF lines (repeatable).")
    ap.add_argument("--no-per-session", action="store_true",
                    help="Skip the per-session timeline (only show aggregate).")
    args = ap.parse_args(argv)

    if not args.android and not args.ios:
        ap.error("at least one of --android / --ios is required")

    android_sessions = parse(read_files(args.android)) if args.android else []
    ios_sessions = parse(read_files(args.ios)) if args.ios else []

    if not args.no_per_session:
        sys.stdout.write(render_per_session("Android", android_sessions))
        sys.stdout.write(render_per_session("iOS", ios_sessions))

    a_agg = aggregate(android_sessions)
    i_agg = aggregate(ios_sessions)
    sys.stdout.write(render_aggregate_compare(a_agg, i_agg))

    # Surface failure rate up-front so it doesn't get lost in the table.
    a_fail = sum(1 for s in android_sessions if s.has_fail())
    i_fail = sum(1 for s in ios_sessions if s.has_fail())
    if android_sessions or ios_sessions:
        sys.stdout.write(
            f"\nFAIL sessions: Android {a_fail}/{len(android_sessions)}, "
            f"iOS {i_fail}/{len(ios_sessions)}\n"
        )

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
