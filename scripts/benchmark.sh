#!/usr/bin/env bash
# Measures how the TV is doing, so a change can be judged by numbers: free memory, processes,
# how long each app takes to open from cold, and how smoothly the home screen draws.
# It changes no setting, but it does open and close the apps, so run it with the sound down.
# Usage: ./scripts/benchmark.sh LABEL [--boot] [--no-apps]
#        ./scripts/benchmark.sh --compare LABEL LABEL
#   --boot     reboot first, time it, and let the TV settle (SETTLE_SECONDS, 180 by default)
#   --no-apps  skip the app launches
# Writes report/benchmark-LABEL.txt.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SETTLE_SECONDS=${SETTLE_SECONDS:-180}
RUNS=${RUNS:-2}
APPS="com.amazon.firebat org.smarttube.stable com.amazon.firetv.youtube com.netflix.ninja
com.disney.disneyplus com.hbo.hbonow com.apple.atve.amazon.appletv com.zdf.android.mediathek
de.swr.ard.avp.mobile.android.amazon tv.arte.plus7"

compare() {
  local a=$REPORT/benchmark-$1.txt b=$REPORT/benchmark-$2.txt
  [[ -f $a ]] || die "no $a"
  [[ -f $b ]] || die "no $b"
  printf '%-44s %12s %12s\n' measure "$1" "$2"
  awk -F'\t' -v OFS='\t' 'NR==FNR { first[$1]=$2; order[++n]=$1; next }
    { second[$1]=$2; if (!($1 in first)) order[++n]=$1 }
    END { for (i=1;i<=n;i++) printf "%-44s %12s %12s\n", order[i], first[order[i]], second[order[i]] }' "$a" "$b"
}

if [[ ${1:-} == --compare ]]; then
  [[ $# == 3 ]] || die "usage: benchmark.sh --compare LABEL LABEL"
  compare "$2" "$3"
  exit 0
fi

LABEL=${1:-}
[[ -n $LABEL && $LABEL != -* ]] || die "usage: benchmark.sh LABEL [--boot] [--no-apps]"
shift
BOOT=0; WITH_APPS=1
for a in "$@"; do
  case $a in
    --boot) BOOT=1 ;;
    --no-apps) WITH_APPS=0 ;;
    *) die "unknown flag: $a" ;;
  esac
done
need_device
mkdir -p "$REPORT"
OUT=$REPORT/benchmark-$LABEL.txt
: > "$OUT"
put() { printf '%s\t%s\n' "$1" "$2" >> "$OUT"; printf '  %-44s %s\n' "$1" "$2"; }

if [[ $BOOT == 1 ]]; then
  step "reboot"
  started=$(date +%s)
  reboot_wait
  put "boot seconds (reboot to boot_completed)" "$(( $(date +%s) - started ))"
  note "letting the TV settle for $SETTLE_SECONDS s"
  sleep "$SETTLE_SECONDS"
fi

step "memory"
meminfo=$(dev 'cat /proc/meminfo' | tr -d '\r')
kb() { printf '%s\n' "$meminfo" | awk -v k="$1:" '$1 == k { print $2 }'; }
put "ram total MB" "$(( $(kb MemTotal) / 1024 ))"
put "ram available MB" "$(( $(kb MemAvailable) / 1024 ))"
put "ram free MB" "$(( $(kb MemFree) / 1024 ))"
put "swap used MB" "$(( ( $(kb SwapTotal) - $(kb SwapFree) ) / 1024 ))"
put "processes" "$(dev 'ps -A' | tr -d '\r' | awk 'NR>1' | wc -l | tr -d ' ')"
put "android apps running" "$(dev 'ps -A -o NAME' | tr -d '\r' | grep -c '^[a-z][a-z0-9_]*\.[a-z]')"
put "disabled packages" "$(dev 'pm list packages -d' | grep -c '^package:')"

step "network"
wifi=$(dev 'dumpsys wifi' | grep -m1 'mWifiInfo' | tr -d '\r')
put "wifi signal dBm" "$(printf '%s' "$wifi" | sed -n 's/.*RSSI: \(-*[0-9]*\).*/\1/p')"
put "wifi link Mbps" "$(printf '%s' "$wifi" | sed -n 's/.*Link speed: \([0-9]*\)Mbps.*/\1/p')"
put "wifi band MHz" "$(printf '%s' "$wifi" | sed -n 's/.*Frequency: \([0-9]*\)MHz.*/\1/p')"
gw=$(gateway)
put "ping router ms" "$(dev "ping -c5 -i0.3 -W2 $gw" | tr -d '\r' | sed -n 's/.*= [0-9.]*\/\([0-9.]*\)\/.*/\1/p')"

# `am start -W` waits for the first frame. A force-stop first makes every launch a cold one.
launch_ms() {
  local pkg=$1 comp out
  comp=$(dev "cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER -a android.intent.action.MAIN $pkg" | tr -d '\r' | tail -1)
  case $comp in */*) ;; *) return 1 ;; esac
  dev "am force-stop $pkg" >/dev/null
  sleep 2
  out=$(dev "am start -W -n $comp" | tr -d '\r')
  printf '%s\n' "$out" | sed -n 's/^TotalTime: \([0-9]*\).*/\1/p'
  sleep 6
  dev 'input keyevent KEYCODE_HOME' >/dev/null
  sleep 3
  dev "am force-stop $pkg" >/dev/null
}

if [[ $WITH_APPS == 1 ]]; then
  step "cold start, milliseconds"
  dev 'input keyevent KEYCODE_WAKEUP' >/dev/null
  for pkg in $APPS; do
    # A weak Wi-Fi link drops adb now and then; that is not an app failing to open.
    adb_reconnect
    present "$pkg" || continue
    grep -qx "package:$pkg" <<<"$(dev 'pm list packages -d' | tr -d '\r')" && continue
    times=""
    for ((i = 0; i < RUNS; i++)); do
      t=$(launch_ms "$pkg") || { times=""; break; }
      times="$times ${t:-?}"
    done
    [[ -n $times ]] && put "open $pkg" "$(printf '%s\n' $times | sort -n | head -1)"
  done
fi

step "home screen"
adb_reconnect
dev 'input keyevent KEYCODE_HOME' >/dev/null
sleep 5
home=$(front_activity | cut -d/ -f1)
put "home screen app" "${home:-unknown}"
if [[ -n $home ]]; then
  dev "dumpsys gfxinfo $home reset" >/dev/null
  # One shell for all the keys: starting `input` over and over would measure adb, not the TV.
  dev "for i in 1 2 3 4 5 6 7 8; do input keyevent KEYCODE_DPAD_RIGHT; done; for i in 1 2 3 4 5 6 7 8; do input keyevent KEYCODE_DPAD_LEFT; done" >/dev/null
  gfx=$(dev "dumpsys gfxinfo $home" | tr -d '\r')
  put "home frames drawn" "$(printf '%s\n' "$gfx" | sed -n 's/^Total frames rendered: \([0-9]*\).*/\1/p' | head -1)"
  put "home janky frames" "$(printf '%s\n' "$gfx" | sed -n 's/^Janky frames: \(.*\)/\1/p' | head -1)"
  put "home frame time 50th percentile" "$(printf '%s\n' "$gfx" | sed -n 's/^50th percentile: \(.*\)/\1/p' | head -1)"
  put "home frame time 90th percentile" "$(printf '%s\n' "$gfx" | sed -n 's/^90th percentile: \(.*\)/\1/p' | head -1)"
  put "home frame time 99th percentile" "$(printf '%s\n' "$gfx" | sed -n 's/^99th percentile: \(.*\)/\1/p' | head -1)"
  put "home app memory MB" "$(dev "ps -A -o RSS,NAME" | tr -d '\r' | awk -v p="$home" '$2 == p { s += $1 } END { print int(s / 1024) }')"
fi

step "saved"
note "${OUT#$ROOT/}"
