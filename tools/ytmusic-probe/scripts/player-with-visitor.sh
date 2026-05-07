#!/usr/bin/env bash
# Probe v2: harvest visitorData from a fresh GET to music.youtube.com,
# then retry /player with it across clients. Mirrors what ytmusicapi /
# yt-dlp do as a minimum baseline before falling back to PO tokens.
# Usage: ./player-with-visitor.sh <videoId>

set -eu
VIDEO_ID="${1:-4D7u5KF7SP8}"
TS="$(date +%Y%m%d-%H%M%S)"
REPORT_DIR="$(cd "$(dirname "$0")/.." && pwd)/reports"
mkdir -p "$REPORT_DIR"

UA_DESKTOP='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

echo "== Harvest visitorData (SOCS=CAI bypasses EU consent gate) =="
HTML="$REPORT_DIR/music-home-$TS.html"
curl -sS -m 15 -L \
  -H "User-Agent: $UA_DESKTOP" \
  -H "Accept-Language: en-US,en;q=0.9" \
  -b "SOCS=CAISEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg" \
  -c "$REPORT_DIR/cookies-$TS.txt" \
  "https://music.youtube.com/" -o "$HTML"

VISITOR=$(python3 -c "
import re
html = open('$HTML').read()
m = re.search(r'\"visitorData\"\s*:\s*\"([^\"]+)\"', html)
print(m.group(1) if m else '')
")
CLIENT_VER=$(python3 -c "
import re
html = open('$HTML').read()
m = re.search(r'INNERTUBE_CLIENT_VERSION\"\s*:\s*\"([^\"]+)\"', html)
print(m.group(1) if m else '1.20240501.01.00')
")
echo "  visitorData=${VISITOR:0:24}... (len=${#VISITOR})"
echo "  WEB_REMIX clientVersion=$CLIENT_VER"

if [ -z "$VISITOR" ]; then
  echo "  No visitorData harvested — exiting." ; exit 1
fi

post_player() {
  local label="$1" host="$2" body="$3" ua="$4" referer="$5"
  local out="$REPORT_DIR/player-v2-$label-$VIDEO_ID-$TS.json"
  echo "== $label =="
  curl -sS -m 20 \
    -H "Content-Type: application/json" \
    -H "User-Agent: $ua" \
    -H "Accept-Language: en-US,en;q=0.9" \
    -H "Origin: $referer" \
    -H "Referer: $referer/" \
    -H "X-Goog-Visitor-Id: $VISITOR" \
    -b "$REPORT_DIR/cookies-$TS.txt" \
    -X POST \
    --data "$body" \
    "$host/youtubei/v1/player?prettyPrint=false" \
    -o "$out" \
    -w "  http=%{http_code} size=%{size_download} t=%{time_total}\n"
}

read -r -d '' B_REMIX <<JSON || true
{
  "context": {
    "client": {
      "clientName": "WEB_REMIX",
      "clientVersion": "$CLIENT_VER",
      "hl": "en", "gl": "US",
      "visitorData": "$VISITOR"
    }
  },
  "videoId": "$VIDEO_ID",
  "playbackContext": {"contentPlaybackContext": {"signatureTimestamp": 19838}}
}
JSON
post_player "web_remix" "https://music.youtube.com" "$B_REMIX" "$UA_DESKTOP" "https://music.youtube.com"

# IOS client (regular YT iOS app) — yt-dlp's old workaround, returns unsigned URLs.
read -r -d '' B_IOS <<JSON || true
{
  "context": {
    "client": {
      "clientName": "IOS",
      "clientVersion": "19.45.4",
      "deviceMake": "Apple",
      "deviceModel": "iPhone16,2",
      "osName": "iOS",
      "osVersion": "17.5.1.21F90",
      "hl": "en", "gl": "US",
      "visitorData": "$VISITOR"
    }
  },
  "videoId": "$VIDEO_ID"
}
JSON
post_player "ios" "https://www.youtube.com" "$B_IOS" \
  "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)" \
  "https://www.youtube.com"

# IOS_MUSIC
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
      "hl": "en", "gl": "US",
      "visitorData": "$VISITOR"
    }
  },
  "videoId": "$VIDEO_ID"
}
JSON
post_player "ios_music" "https://music.youtube.com" "$B_IOS_MUSIC" \
  "com.google.ios.youtubemusic/7.04.2 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)" \
  "https://music.youtube.com"

# TVHTML5_SIMPLY_EMBEDDED_PLAYER — yt-dlp's primary 2024+ extraction client (no PO required for many videos).
read -r -d '' B_TVE <<JSON || true
{
  "context": {
    "client": {
      "clientName": "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
      "clientVersion": "2.0",
      "hl": "en", "gl": "US",
      "visitorData": "$VISITOR"
    },
    "thirdParty": {"embedUrl": "https://www.youtube.com/"}
  },
  "videoId": "$VIDEO_ID",
  "playbackContext": {"contentPlaybackContext": {"signatureTimestamp": 19838}}
}
JSON
post_player "tv_embed" "https://www.youtube.com" "$B_TVE" "$UA_DESKTOP" "https://www.youtube.com"

echo
echo "== Summary =="
python3 - <<PY
import json, glob, re
ts = "$TS"; vid = "$VIDEO_ID"
labels = ["web_remix","ios","ios_music","tv_embed"]
for lbl in labels:
    matches = sorted(glob.glob(f"$REPORT_DIR/player-v2-{lbl}-{vid}-*.json"))
    if not matches: print(f"{lbl}: <none>"); continue
    p = matches[-1]
    try: d = json.load(open(p))
    except Exception as e: print(f"{lbl}: parse {e}"); continue
    pstatus = d.get("playabilityStatus",{}).get("status","?")
    reason  = d.get("playabilityStatus",{}).get("reason","")[:60]
    sd = d.get("streamingData",{}) or {}
    af = sd.get("adaptiveFormats",[]) or []
    audio = [x for x in af if (x.get("mimeType","")).startswith("audio/")]
    hls = sd.get("hlsManifestUrl","")
    first_audio = audio[0] if audio else None
    n_present = ""
    cipher = ""
    if first_audio:
        if first_audio.get("url"):
            m = re.search(r"[?&]n=([^&]+)", first_audio["url"])
            n_present = "Y" if m else "N"
        if first_audio.get("signatureCipher"):
            cipher = "Y"
            n_in_cipher = "n=" in first_audio["signatureCipher"]
            cipher += f"(n_in_cipher={'Y' if n_in_cipher else 'N'})"
    print(f"{lbl:10} status={pstatus:14} adaptive={len(af):3} audioOnly={len(audio):3} hls={'Y' if hls else 'N'} firstAudio_url={'Y' if first_audio and first_audio.get('url') else 'N'} n_in_url={n_present or '-'} cipher={cipher or '-'}")
    if reason: print(f"           reason: {reason}")
PY
