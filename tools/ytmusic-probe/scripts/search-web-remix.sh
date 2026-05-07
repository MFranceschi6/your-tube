#!/usr/bin/env bash
# Probe: YT Music InnerTube /search via WEB_REMIX client.
# Compares: same query via current iOS-style WEB client vs WEB_REMIX.
# Usage: ./search-web-remix.sh "song title artist"
# Out:   reports/search-web-remix-<timestamp>.json (REMIX) + .web.json (WEB)

set -eu
QUERY="${1:-Daft Punk Get Lucky}"
TS="$(date +%Y%m%d-%H%M%S)"
REPORT_DIR="$(cd "$(dirname "$0")/.." && pwd)/reports"
mkdir -p "$REPORT_DIR"

UA_REMIX='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

# WEB_REMIX shape mirrors what ytmusicapi posts to music.youtube.com.
# Public client key/version pulled from ytmusicapi/_continuations + ytmusicapi/auth/oauth in upstream source.
# Same key value also appears verbatim in view-source:music.youtube.com.
read -r -d '' BODY_REMIX <<'JSON' || true
{
  "context": {
    "client": {
      "clientName": "WEB_REMIX",
      "clientVersion": "1.20240501.01.00",
      "hl": "en",
      "gl": "US"
    },
    "user": {"lockedSafetyMode": false}
  },
  "query": "__QUERY__"
}
JSON
BODY_REMIX="${BODY_REMIX/__QUERY__/${QUERY//\"/\\\"}}"

echo "== POST music.youtube.com /youtubei/v1/search (WEB_REMIX) =="
curl -sS -m 15 \
  -H "Content-Type: application/json" \
  -H "User-Agent: $UA_REMIX" \
  -H "Accept-Language: en-US,en;q=0.9" \
  -H "Origin: https://music.youtube.com" \
  -H "Referer: https://music.youtube.com/" \
  -X POST \
  --data "$BODY_REMIX" \
  "https://music.youtube.com/youtubei/v1/search?prettyPrint=false" \
  -o "$REPORT_DIR/search-web-remix-$TS.json" \
  -w 'http=%{http_code} size=%{size_download} t=%{time_total}\n'

# Parallel: same query via WEB client (mirror of iOS LiveYouTubeService.fetchInnerTubeSearch).
read -r -d '' BODY_WEB <<'JSON' || true
{
  "context": {
    "client": {
      "clientName": "WEB",
      "clientVersion": "2.20260114.08.00"
    }
  },
  "query": "__QUERY__"
}
JSON
BODY_WEB="${BODY_WEB/__QUERY__/${QUERY//\"/\\\"}}"

echo "== POST www.youtube.com /youtubei/v1/search (WEB, baseline) =="
curl -sS -m 15 \
  -H "Content-Type: application/json" \
  -H "User-Agent: Mozilla/5.0" \
  -H "Accept-Language: en-US,en" \
  -X POST \
  --data "$BODY_WEB" \
  "https://www.youtube.com/youtubei/v1/search?prettyPrint=false" \
  -o "$REPORT_DIR/search-web-baseline-$TS.json" \
  -w 'http=%{http_code} size=%{size_download} t=%{time_total}\n'

echo
echo "REMIX top-level keys:"
python3 -c "import json,sys; d=json.load(open('$REPORT_DIR/search-web-remix-$TS.json')); print(sorted(d.keys()))" || true
echo "WEB top-level keys:"
python3 -c "import json,sys; d=json.load(open('$REPORT_DIR/search-web-baseline-$TS.json')); print(sorted(d.keys()))" || true
echo "Reports:"
echo "  $REPORT_DIR/search-web-remix-$TS.json"
echo "  $REPORT_DIR/search-web-baseline-$TS.json"
