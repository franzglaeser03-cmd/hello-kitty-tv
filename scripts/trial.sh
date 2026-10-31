#!/usr/bin/env bash
# Tries switching off packages the lists do not cover, safely: disables them, reboots, checks
# that the TV still works, and turns them back on if it does not. Follows "A TV the lists do not
# cover" in AGENTS.md. Packages on never-disable.txt are refused.
# Usage: ./scripts/trial.sh PACKAGE...        all together, then one by one if that fails
#        ./scripts/trial.sh --check           only run the checks
# What passes is written to report/trial-passed.txt; record it with configure.sh --remove so
# restore.sh can bring it back. Checks: home screen, no error dialog, remote input devices, TV
# inputs, voice runtime, and every installed streaming app opening.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need_device
load_conf
mkdir -p "$REPORT"
PASSED=$REPORT/trial-passed.txt
LOG=$REPORT/trial-log.txt
APPS="com.amazon.firebat org.smarttube.stable com.netflix.ninja com.disney.disneyplus com.hbo.hbonow
com.apple.atve.amazon.appletv com.spotify.tv.android com.zdf.android.mediathek
de.swr.ard.avp.mobile.android.amazon tv.arte.plus7"
BEFORE_INPUTS=""

say() { printf '  %s\n' "$*" | tee -a "$LOG"; }

focus() { dev 'dumpsys window' | tr -d '\r' | grep -m1 mCurrentFocus | sed -E 's/.*u0 //; s/\}.*//'; }

# Runs every check; prints what failed, nothing when all is well.
checks() {
  local f pkg comp out home inputs fatal running bluetooth
  adb_reconnect
  dev 'input keyevent KEYCODE_WAKEUP' >/dev/null
  dev 'input keyevent KEYCODE_HOME' >/dev/null
  sleep 6
  f=$(focus)
  case $f in
    *Application*Error*|*"Not Responding"*|*vizzini*|*FallbackHome*|"") printf 'focus:%s ' "${f:-none}" ;;
  esac
  home=$(front_activity | cut -d/ -f1)
  case $home in
    tv.dumbtv.home|com.amazon.tv.launcher) ;;
    *) printf 'home:%s ' "${home:-none}" ;;
  esac
  inputs=$(dev 'dumpsys tv_input' | tr -d '\r' | grep -cE 'TunerInputService|HDMIInputService')
  [[ ${inputs:-0} -ge 2 ]] || printf 'tv-inputs:%s ' "${inputs:-0}"
  [[ -n $BEFORE_INPUTS && $inputs != "$BEFORE_INPUTS" ]] && printf 'tv-inputs-changed:%s/%s ' "$BEFORE_INPUTS" "$inputs"
  # Alexa's runtime and the input stack, which the voice button and the remote need.
  # Read first and search after: `grep -q` closing a pipe counts as a failure under pipefail.
  running=$(dev 'ps -A -o NAME' | tr -d '\r')
  for pkg in com.amazon.vizzini com.android.bluetooth; do
    grep -qx "$pkg" <<<"$running" || printf 'not-running:%s ' "$pkg"
  done
  bluetooth=$(dev 'dumpsys bluetooth_manager' | tr -d '\r')
  grep -q 'enabled: true' <<<"$bluetooth" || printf 'bluetooth-off '
  dev 'logcat -c' >/dev/null 2>&1
  for pkg in $APPS; do
    adb_reconnect
    present "$pkg" || continue
    grep -qx "package:$pkg" <<<"$(dev 'pm list packages -d' | tr -d '\r')" && continue
    comp=$(dev "cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER -a android.intent.action.MAIN $pkg" | tr -d '\r' | tail -1)
    case $comp in */*) ;; *) comp=$(dev "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER -a android.intent.action.MAIN $pkg" | tr -d '\r' | tail -1) ;; esac
    case $comp in */*) ;; *) printf 'no-entry:%s ' "$pkg"; continue ;; esac
    out=$(dev "am start -W -n $comp" | tr -d '\r')
    grep -q 'Status: ok' <<<"$out" || grep -q 'Complete' <<<"$out" || printf 'open-failed:%s ' "$pkg"
    sleep 5
    f=$(focus)
    case $f in
      *Application*Error*|*"Not Responding"*) printf 'error-dialog:%s ' "$pkg" ;;
    esac
    dev 'input keyevent KEYCODE_HOME' >/dev/null
    sleep 2
    dev "am force-stop $pkg" >/dev/null
  done
  fatal=$(dev 'logcat -d -b crash' | tr -d '\r' | grep -E 'Process: ' | sed -E 's/.*Process: ([^,]+).*/\1/' | sort -u | tr '\n' ' ')
  [[ -n $fatal ]] && printf 'crashed:%s' "${fatal// /,} "
  return 0
}

boot_and_check() {
  reboot_wait
  sleep "${TRIAL_SETTLE:-40}"
  checks
}

try() {
  local p problems state off=()
  for p in "$@"; do
    state=$(dev "pm disable-user --user 0 $p" | tr -d '\r' | head -1)
    say "$p: $state"
    case $state in
      *protected*) say "$p: Amazon refuses; add it to protected.txt for lockdown.sh" ;;
      *disabled-user*) off+=("$p") ;;
    esac
  done
  [[ ${#off[@]} -gt 0 ]] || return 0
  sleep "${PM_WRITE_SECONDS:-12}"
  problems=$(boot_and_check)
  if [[ -z $problems ]]; then
    say "PASS: ${off[*]}"
    printf '%s\n' "${off[@]}" >> "$PASSED"
    return 0
  fi
  say "FAIL ($problems): $*"
  for p in "$@"; do dev "pm enable --user 0 $p" >/dev/null; done
  sleep "${PM_WRITE_SECONDS:-12}"
  return 1
}

if [[ ${1:-} == --check ]]; then
  problems=$(checks)
  if [[ -z $problems ]]; then printf 'all checks passed\n'; else printf 'problems: %s\n' "$problems"; exit 1; fi
  exit 0
fi

[[ $# -gt 0 ]] || die "name the packages to try"
never=$(list_of "$(fos never-disable.txt)" | awk '{print $1}')
wanted=()
for p in "$@"; do
  if grep -qx "$p" <<<"$never"; then note "$p is on never-disable.txt: refused"; continue; fi
  present "$p" || { note "$p is not on this TV"; continue; }
  wanted+=("$p")
done
[[ ${#wanted[@]} -gt 0 ]] || die "nothing to try"

step "the TV as it is now"
BEFORE_INPUTS=$(dev 'dumpsys tv_input' | tr -d '\r' | grep -cE 'TunerInputService|HDMIInputService')
problems=$(checks)
if [[ -n $problems ]]; then
  note "already failing before the trial: $problems"
  note "fix that first, or the trial cannot tell what caused what"
  exit 1
fi
note "all checks passed"

step "together: ${wanted[*]}"
if try "${wanted[@]}"; then
  step "done"
  exit 0
fi
[[ ${#wanted[@]} -gt 1 ]] || exit 1

step "one at a time"
reboot_wait
sleep "${TRIAL_SETTLE:-40}"
for p in "${wanted[@]}"; do try "$p" || true; done
step "done"
note "passed: $(tr '\n' ' ' < "$PASSED")"
