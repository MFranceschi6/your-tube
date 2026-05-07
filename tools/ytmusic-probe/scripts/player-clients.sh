#!/usr/bin/env bash
# Probe: /youtubei/v1/player against same videoId via different InnerTube clients.
# Goal: confirm whether WEB_REMIX, IOS_MUSIC, ANDROID_MUSIC streamingData URLs
# still carry the `n` throttling param (YouTubeKit's pain point).
# Usage: ./player-clients.sh <videoId>

set -eu
VIDEO_ID="${1:-4D7u5KF7SP8}"   # Daft Punk - Get Lucky (Song)
TS="$(date +%Y%m%d-%H%M%S)"
REPORT_DIR="$(cd "$(dirname "$0")/.." && pwd)/reports"
mkdir -p "$REPORT_DIR"

post_player() {
  local label="$1" host="$2" body="$3" ua="$4" referer="$5"
  local out="$REPORT_DIR/player-$label-$VIDEO_ID-$TS.json"
  echo "== $label =="
  curl -sS -m 20 \
    -H "Content-Type: application/json" \
    -H "User-Agent: $ua" \
    -H "Accept-Language: en-US,en;q=0.9" \
    -H "Origin: $referer" \
    -H "Referer: $referer/" \
    -X POST \
    --data "$body" \
    "$host/youtubei/v1/player?prettyPrint=false" \
    -o "$out" \
    -w "  http=%{http_code} size=%{size_download} t=%{time_total}\n"
  echo "  -> $out"
}

# 1. WEB_REMIX (YT Music desktop)
read -r -d '' B_REMIX <<JSON || true
{
  "context": {
    "client": {
      "clientName": "WEB_REMIX",
      "clientVersion": "1.20240501.01.00",
      "hl": "en", "gl": "US"
    }
  },
  "videoId": "$VIDEO_ID",
  "playbackContext": {"contentPlaybackContext": {"signatureTimestamp": 19838}}
}
JSON
post_player "web_remix" \
  "https://music.youtube.com" \
  "$B_REMIX" \
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
  "https://music.youtube.com"

# 2. IOS_MUSIC (YT Music iOS app) — historically returns unsigned URLs (no n).
read -r -d '' B_IOS_MUSIC <<JSON || true
{
  "context": {
    "client": {
      "clientName": "IOS_MUSIC",
      "clientVersion": "7.04.2",
      "deviceMake": "Apple",
      "deviceModel": "iPhone16,2",
      "osName": "iOS",
      "osVersion": "17.5.1.21F90",
      "hl": "en", "gl": "US"
    }
  },
  "videoId": "$VIDEO_ID"
}
JSON
post_player "ios_music" \
  "https://music.youtube.com" \
  "$B_IOS_MUSIC" \
  "com.google.ios.youtubemusic/7.04.2 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)" \
  "https://music.youtube.com"

# 3. ANDROID_MUSIC (YT Music Android app)
read -r -d '' B_AND_MUSIC <<JSON || true
{
  "context": {
    "client": {
      "clientName": "ANDROID_MUSIC",
      "clientVersion": "7.04.51",
      "androidSdkVersion": 34,
      "osName": "Android",
      "osVersion": "14",
      "hl": "en", "gl": "US"
    }
  },
  "videoId": "$VIDEO_ID"
}
JSON
post_player "android_music" \
  "https://music.youtube.com" \
  "$B_AND_MUSIC" \
  "com.google.android.apps.youtube.music/7.04.51 (Linux; U; Android 14) gzip" \
  "https://music.youtube.com"

# 4. WEB (regular youtube.com — baseline, this is what iOS uses today)
read -r -d '' B_WEB <<JSON || true
{
  "context": {
    "client": {
      "clientName": "WEB",
      "clientVersion": "2.20260114.08.00"
    }
  },
  "videoId": "$VIDEO_ID"
}
JSON
post_player "web_baseline" \
  "https://www.youtube.com" \
  "$B_WEB" \
  "Mozilla/5.0" \
  "https://www.youtube.com"

echo
echo "== Summary =="
python3 - <<PY
import json, glob, re, os
ts = "$TS"; vid = "$VIDEO_ID"
labels = ["web_remix","ios_music","android_music","web_baseline"]
for lbl in labels:
    matches = sorted(glob.glob(f"$REPORT_DIR/player-{lbl}-{vid}-*.json"))
    if not matches:
        print(f"{lbl}: <no file>"); continue
    p = matches[-1]
    try:
        d = json.load(open(p))
    except Exception as e:
        print(f"{lbl}: parse error {e}"); continue
    pstatus = d.get("playabilityStatus",{}).get("status","?")
    sd = d.get("streamingData",{}) or {}
    af = sd.get("adaptiveFormats",[]) or []
    f  = sd.get("formats",[]) or []
    hls = sd.get("hlsManifestUrl","")
    audio_only = [x for x in af if (x.get("mimeType","")).startswith("audio/")]
    # Inspect first audio URL: signatureCipher present? n= present?
    first = audio_only[0] if audio_only else (af[0] if af else None)
    has_url = bool(first and first.get("url"))
    has_cipher = bool(first and first.get("signatureCipher"))
    n_in_url = ""
    if first and first.get("url"):
        m = re.search(r"[?&]n=([^&]+)", first["url"])
        n_in_url = m.group(1)[:8]+"..." if m else ""
    print(f"{lbl:14} status={pstatus:10} formats={len(f):3} adaptive={len(af):3} audioOnly={len(audio_only):3} hls={'Y' if hls else 'N'} url={'Y' if has_url else 'N'} cipher={'Y' if has_cipher else 'N'} n={n_in_url or '-'}")
PY
