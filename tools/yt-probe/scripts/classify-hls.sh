#!/usr/bin/env bash
# Classify a YouTube HLS master URL as live vs VOD by URL pattern, then
# validate against actual segment reachability. Goal: prove a cheap
# URL-only heuristic that LiveYouTubeService can use to gate HLS fallback
# (HLS-VOD is broken because the throttling param `n` stays raw and the
# CDN returns 403 on .ts segments).
set -uo pipefail

BIN="$(dirname "$0")/../.build/debug/yt-play"
if [ ! -x "$BIN" ]; then
  echo "yt-play binary not found — run: swift build --product yt-play" >&2
  exit 1
fi

UA="YourTube/1.0 CFNetwork/1568.200.51 Darwin/24.0.0"

probe_one() {
  local id="$1"
  echo ""
  echo "=========================================================="
  echo "videoId: $id"

  local master
  master=$("$BIN" --id "$id" --print-hls-url 2>/dev/null) || { echo "  resolve FAILED"; return; }

  # Pattern classification
  local source playlist_type
  source=$(echo "$master" | grep -oE "/source/[a-z_]+" | head -1)
  playlist_type=$(echo "$master" | grep -oE "/playlist_type/[A-Z]+" | head -1)
  local class="unknown"
  if [[ "$source" == "/source/yt_live_broadcast" ]] || [[ "$playlist_type" == "/playlist_type/DVR" ]]; then
    class="LIVE"
  elif [[ "$source" == "/source/youtube" ]] || [[ "$playlist_type" == "/playlist_type/CLEAN" ]]; then
    class="VOD"
  fi
  echo "  master URL: source=$source playlist_type=$playlist_type"
  echo "  → URL-pattern verdict: $class"

  # Fetch master, get first variant, get first segment, probe segment
  local body variant seg
  body=$(curl -s -A "$UA" "$master")
  variant=$(echo "$body" | grep -E '^https://.*index\.m3u8' | head -1)
  if [ -z "$variant" ]; then
    echo "  no variant found in master — body had $(echo "$body" | wc -l) lines"
    return
  fi
  seg=$(curl -s -A "$UA" "$variant" | grep -E '^https://' | head -1)
  if [ -z "$seg" ]; then
    echo "  no segment URL in variant playlist"
    return
  fi
  local seg_n
  seg_n=$(echo "$seg" | grep -oE "/n/[^/]+")
  local seg_status
  seg_status=$(curl -o /dev/null -s -I -w "%{http_code}" -A "$UA" "$seg")
  echo "  segment HEAD: $seg_status (n param: $seg_n)"

  local actual="VOD"
  [[ "$seg_status" == "200" || "$seg_status" == "206" ]] && actual="LIVE-or-OK"
  echo "  → empirical (segment fetchable): $actual"

  if [[ "$class" == "LIVE" && "$actual" == "LIVE-or-OK" ]]; then
    echo "  ✓ heuristic agrees: keep HLS fallback"
  elif [[ "$class" == "VOD" && "$actual" != "LIVE-or-OK" ]]; then
    echo "  ✓ heuristic agrees: skip HLS fallback (segments 403)"
  else
    echo "  ⚠ heuristic disagrees with empirical — investigate"
  fi
}

if [ "$#" -eq 0 ]; then
  set -- jNQXAC9IVRw dQw4w9WgXcQ 9bZkp7q19f0 jfKfPfyJRdk
fi

for id in "$@"; do
  probe_one "$id"
done
