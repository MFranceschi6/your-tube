#!/usr/bin/env bash
# Probe HLS VOD sub-manifest URLs with several User-Agents to find which (if any)
# unlock the 403 we observe with AVPlayer's default UA.
#
# Usage: ./scripts/probe-hls-ua.sh VIDEO_ID
set -uo pipefail

VIDEO_ID="${1:-dQw4w9WgXcQ}"
BIN="$(dirname "$0")/../.build/debug/yt-play"

if [ ! -x "$BIN" ]; then
  echo "yt-play binary not found at $BIN — run: swift build --product yt-play" >&2
  exit 1
fi

echo "→ resolving HLS master for $VIDEO_ID"
MASTER_URL=$("$BIN" --id "$VIDEO_ID" --print-hls-url) || { echo "resolve failed"; exit 1; }
echo "master: ${MASTER_URL:0:120}..."

echo "→ fetching master playlist"
MASTER_BODY=$(curl -s -A "Mozilla/5.0" "$MASTER_URL")
if [ -z "$MASTER_BODY" ]; then
  echo "empty master body" >&2
  exit 1
fi

# Extract first 3 variant URLs (lines that start with https and end with index.m3u8)
VARIANTS_FILE="/tmp/hls-probe-variants.txt"
echo "$MASTER_BODY" | grep -E '^https://.*index\.m3u8' | head -3 > "$VARIANTS_FILE"
VARIANT_COUNT=$(wc -l < "$VARIANTS_FILE" | tr -d ' ')
if [ "$VARIANT_COUNT" -eq 0 ]; then
  echo "no variant URLs found in master" >&2
  exit 1
fi
echo "found $VARIANT_COUNT variant URLs"

# Parallel arrays — UA name + UA string. macOS bash 3.2 has no associative arrays.
UA_KEYS=("empty" "curl-default" "cfnetwork-mac" "safari-mac" "safari-ios" "yt-ios-app" "yt-android-app")
UA_VALUES=(
  ""
  "curl/8.0.0"
  "YourTube/1.0 CFNetwork/1568.200.51 Darwin/24.0.0"
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
  "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
  "com.google.ios.youtube/19.05.7 (iPhone15,2; U; CPU iOS 17_0 like Mac OS X)"
  "com.google.android.youtube/19.05.36 (Linux; U; Android 14; en-US)"
)

printf "\n%-15s %-7s %-3s %-7s %s\n" "UA" "method" "var" "status" "size"
printf -- "----------------------------------------------------------------\n"

CFNET_UA="YourTube/1.0 CFNetwork/1568.200.51 Darwin/24.0.0"

# Test 1: GET with various UAs (already known good but recap with one row)
for i in "${!UA_KEYS[@]}"; do
  ua_key="${UA_KEYS[$i]}"
  ua="${UA_VALUES[$i]}"
  v=$(head -1 "$VARIANTS_FILE")
  if [ -z "$ua" ]; then
    result=$(curl -o /tmp/hls-probe-body -s -w "%{http_code} %{size_download}" "$v")
  else
    result=$(curl -o /tmp/hls-probe-body -s -w "%{http_code} %{size_download}" -A "$ua" "$v")
  fi
  status=$(echo "$result" | awk '{print $1}')
  size=$(echo "$result" | awk '{print $2}')
  printf "%-15s %-7s %-3d %-7s %s\n" "$ua_key" "GET" 1 "$status" "$size"
done

# Test 2: HEAD with cfnetwork UA on each variant
vi=0
while IFS= read -r v; do
  vi=$((vi+1))
  result=$(curl -o /dev/null -s -I -w "%{http_code} %{size_download}" -A "$CFNET_UA" "$v")
  status=$(echo "$result" | awk '{print $1}')
  size=$(echo "$result" | awk '{print $2}')
  printf "%-15s %-7s %-3d %-7s %s\n" "cfnet" "HEAD" "$vi" "$status" "$size"
done < "$VARIANTS_FILE"

# Test 3: GET with Range header (AVPlayer often uses Range for media segments)
vi=0
while IFS= read -r v; do
  vi=$((vi+1))
  result=$(curl -o /tmp/hls-probe-body -s -w "%{http_code} %{size_download}" -A "$CFNET_UA" -H "Range: bytes=0-1023" "$v")
  status=$(echo "$result" | awk '{print $1}')
  size=$(echo "$result" | awk '{print $2}')
  printf "%-15s %-7s %-3d %-7s %s\n" "cfnet" "GET-R" "$vi" "$status" "$size"
done < "$VARIANTS_FILE"

# Test 4: GET the master playlist itself with HEAD
result=$(curl -o /dev/null -s -I -w "%{http_code} %{size_download}" -A "$CFNET_UA" "$MASTER_URL")
status=$(echo "$result" | awk '{print $1}')
size=$(echo "$result" | awk '{print $2}')
printf "%-15s %-7s %-3d %-7s %s\n" "cfnet" "HEAD-M" 0 "$status" "$size"

# Test 5: dump first 3 lines of variant body to see if it's a media playlist with EXTINF
echo ""
echo "→ first 5 lines of variant 1 body:"
curl -s -A "$CFNET_UA" "$(head -1 "$VARIANTS_FILE")" | head -5
