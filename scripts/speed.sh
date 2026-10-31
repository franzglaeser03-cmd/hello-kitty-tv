#!/usr/bin/env bash
# Speed tuning that neither the package lists nor the settings list reach. Each part can be undone
# with --undo, and restore.sh calls that. Judge a part with benchmark.sh before keeping it.
# Usage: ./scripts/speed.sh --compile      compile the streaming apps ahead of time
#        ./scripts/speed.sh --cached N     keep at most N stopped apps in memory
#        ./scripts/speed.sh --undo
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need_device

# Measured with benchmark.sh on a Fire TV 4-Series: these opened 20 to 50% faster. Prime Video,
# Disney+ and HBO Max opened slower fully compiled (a large image to page in from slow storage),
# so they stay on Android's own profile-guided compile; see SLOWER. Netflix did not change.
APPS="com.zdf.android.mediathek de.swr.ard.avp.mobile.android.amazon tv.arte.plus7
com.apple.atve.amazon.appletv"
SLOWER="com.amazon.firebat com.disney.disneyplus com.hbo.hbonow com.netflix.ninja org.smarttube.stable
tv.dumbtv.home"

# Android keeps recently used apps cached until memory runs out, then pays for it in swap.
cached() {
  dev "settings put global activity_manager_constants max_cached_processes=$1" >/dev/null
  note "at most $1 stopped apps stay in memory; applies from the next reboot"
}

# Android 9 compiles an app only for the parts it has seen used, when the TV is idle and charging,
# which a TV may never report. "speed" compiles all of it now: it starts faster and costs storage.
compile() {
  local p out
  for p in $APPS; do
    adb_reconnect
    present "$p" || continue
    out=$(dev "cmd package compile -m speed -f $p" | tr -d '\r' | tail -1)
    printf '  %-46s %s\n' "$p" "$out"
  done
  for p in $SLOWER; do
    adb_reconnect
    present "$p" || continue
    out=$(dev "cmd package compile -m speed-profile -f $p" | tr -d '\r' | tail -1)
    printf '  %-46s %s (profile-guided)\n' "$p" "$out"
  done
}

undo() {
  local p
  dev 'settings delete global activity_manager_constants' >/dev/null
  for p in $APPS $SLOWER; do
    present "$p" || continue
    dev "cmd package compile --reset $p" >/dev/null
  done
  note "compiled apps and the cached-app limit are back to Android's defaults"
}

case ${1:-} in
  --compile) compile ;;
  --cached)
    case ${2:-} in ''|*[!0-9]*) die "--cached takes a number" ;; esac
    cached "$2" ;;
  --undo) undo ;;
  *) die "usage: speed.sh --compile | --cached N | --undo" ;;
esac
