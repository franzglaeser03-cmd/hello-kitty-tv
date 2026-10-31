#!/usr/bin/env bash
# Read-only privacy report. Lists every package holding a permission that can capture the
# screen, the microphone or what you do, whether it can still run, what is using the memory,
# and, once the DNS filter is on, what the TV has been asking for.
# A permission says what an app could do, not that it does it.
# Usage: ./scripts/audit.sh [LABEL]        writes report/audit-LABEL.txt (default: today)
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
LABEL=${1:-$(date +%Y%m%d-%H%M)}
need_device
load_conf
mkdir -p "$REPORT"
OUT=$REPORT/audit-$LABEL.txt
TRAFFIC=/sdcard/Android/data/dnsfilter.android/files/PersonalDNSFilter/log
WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-audit.XXXXXX") || die "could not create a temp dir"
trap 'rm -rf "$WORK"' EXIT

dev 'dumpsys package' | tr -d '\r' > "$WORK/packages.txt"
dev 'pm list packages -d' | tr -d '\r' | sed 's/^package://' > "$WORK/disabled.txt"
dev 'ps -A -o RSS,NAME' | tr -d '\r' > "$WORK/ps.txt"

# Which list a package is on, for the reader.
list_name() {
  local p=$1 f
  for f in packages alexa user-apps protected never-disable; do
    list_of "$(fos "$f.txt")" | awk '{print $1}' | grep -qx "$p" && { printf '%s' "$f"; return; }
  done
  printf 'none'
}

# group, permission names
capability() {
  local group=$1; shift
  local pkg state mem
  printf '\n%s\n' "$group"
  awk -v want="$*" '
    BEGIN { n = split(want, w, " "); for (i = 1; i <= n; i++) perm["android.permission." w[i] ":"] = 1 }
    /^  Package \[/ { p = $2; gsub(/[\[\]]/, "", p); next }
    $1 in perm && /granted=true/ && p != "" { print p }' "$WORK/packages.txt" | sort -u |
  while read -r pkg; do
    if grep -qx "$pkg" "$WORK/disabled.txt"; then state=disabled
    else state=enabled; fi
    mem=$(awk -v p="$pkg" '$2 == p || index($2, p ":") == 1 { s += $1 } END { print int(s / 1024) }' "$WORK/ps.txt")
    if [[ ${mem:-0} -gt 0 ]]; then state="$state, running ${mem} MB"; fi
    printf '  %-48s %-26s list: %s\n' "$pkg" "$state" "$(list_name "$pkg")"
  done
}

{
  step "what can capture what"
  capability "screen or video capture" CAPTURE_VIDEO_OUTPUT CAPTURE_SECURE_VIDEO_OUTPUT READ_FRAME_BUFFER ACCESS_SURFACE_FLINGER MANAGE_MEDIA_PROJECTION
  capability "TV picture and inputs" CAPTURE_TV_INPUT TV_INPUT_HARDWARE
  capability "microphone and audio" RECORD_AUDIO CAPTURE_AUDIO_OUTPUT CAPTURE_AUDIO_HOTWORD
  capability "camera" CAMERA
  capability "reading key presses, or sending them" READ_INPUT_STATE INJECT_EVENTS
  capability "reading other apps' logs" READ_LOGS
  capability "which app you use and when" PACKAGE_USAGE_STATS

  step "hooks a stranger could have left behind"
  for k in enabled_accessibility_services enabled_notification_listeners always_on_vpn_app; do
    printf '  %-36s %s\n' "$k" "$(dev1 settings get secure "$k")"
  done
  printf '  %-36s %s\n' http_proxy "$(dev1 settings get global http_proxy)"
  printf '  %-36s %s\n' "device admin" "$(dev 'dumpsys device_policy' | tr -d '\r' | sed -n 's/^ *admin=ComponentInfo{\(.*\)}/\1/p' | head -3 | tr '\n' ' ')"
  printf '  %-36s %s\n' "third-party apps" "$(dev 'pm list packages -3 -i' | tr -d '\r' | grep -vc 'overlay')"
  dev 'pm list packages -3 -i' | tr -d '\r' | grep -v overlay | sed 's/^package:/    /'

  step "privacy settings"
  while read -r ns key value; do
    now=$(dev1 settings get "$ns" "$key")
    [[ $now == "$value" ]] && mark=ok || mark=DIFFERENT
    printf '  %-10s %-52s %s\n' "$mark" "$key" "$now"
  done < <(list_of "$(fos settings.txt)")

  step "memory by app, largest first"
  awk '$1 ~ /^[0-9]+$/ && $2 ~ /\./ { n = $2; sub(/:.*/, "", n); s[n] += $1 } END { for (n in s) printf "%d %s\n", s[n] / 1024, n }' "$WORK/ps.txt" |
    sort -rn | head -30 | while read -r mb pkg; do
      printf '  %5s MB  %-48s list: %s\n' "$mb" "$pkg" "$(list_name "$pkg")"
    done

  step "what the TV asked for (DNS filter log)"
  if dev "cat $TRAFFIC/trafficlog_0.log $TRAFFIC/trafficlog_1.log 2>/dev/null" | tr -d '\r' > "$WORK/log.txt" && [[ -s $WORK/log.txt ]]; then
    printf '  %s log lines\n\n  blocked, most asked first\n' "$(wc -l < "$WORK/log.txt" | tr -d ' ')"
    grep -E 'FILTERED|BLOCKED' "$WORK/log.txt" | sed -nE 's/.*(FILTERED|BLOCKED)[^A-Za-z0-9]*([A-Za-z0-9_.-]+).*/\2/p' | sort | uniq -c | sort -rn | head -40 | sed 's/^/  /'
    printf '\n  allowed, most asked first\n'
    grep -Ev 'FILTERED|BLOCKED' "$WORK/log.txt" | sed -nE 's/.*(ALLOWED|ACCEPTED|FORWARD|FWD|DNS Request)[^A-Za-z0-9]*([A-Za-z0-9_.-]+\.[a-z]{2,}).*/\2/p' | sort | uniq -c | sort -rn | head -60 | sed 's/^/  /'
  else
    note "no log yet: the filter is not installed, or nothing has been asked since it started"
  fi
} 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tee "$OUT"

printf '\nsaved: %s\n' "${OUT#$ROOT/}"
