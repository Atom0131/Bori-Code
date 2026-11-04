#!/usr/bin/env bash
# One-time tuning of a phone for Bori Code, over adb from the PC (the same rights Shizuku would
# give; no root). Prints each command and asks before running it.
#
#   scripts/fold_tune.sh [serial] [app id]          apply
#   scripts/fold_tune.sh --undo [serial] [app id]   put the system settings back
#
# What it does:
#   - compiles the whole app ahead of time (ART "speed"). Only works on a non-debuggable build
#     (fullFast or a release); run it again after every install.
#   - turns off Android's phantom-process killer, which kills programs running inside Linux
#     (Android 12+; it's "Disable child process restrictions" in Developer options on 14+)
#   - keeps the app out of Doze and background restrictions while a CLI is working
# It never touches thermal limits: those protect the phone from overheating.
set -euo pipefail

UNDO=false
if [[ "${1:-}" == "--undo" ]]; then UNDO=true; shift; fi
SERIAL="${1:-${ANDROID_SERIAL:-}}"
APP="${2:-com.bori.code.debug}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=(-s "$SERIAL")

run() {
    printf '\n$ adb shell %s\n' "$*"
    read -r -p "Run it? [y/N] " answer
    if [[ "$answer" == [yY]* ]]; then "${ADB[@]}" shell "$@" || echo "(failed)"; else echo "(skipped)"; fi
}

"${ADB[@]}" shell pm path "$APP" >/dev/null || { echo "$APP isn't installed on this device"; exit 1; }

if $UNDO; then
    run settings put global settings_enable_monitor_phantom_procs true
    run device_config delete activity_manager max_phantom_processes
    run cmd deviceidle whitelist -"$APP"
    run cmd appops set "$APP" RUN_ANY_IN_BACKGROUND default
    exit 0
fi

if "${ADB[@]}" shell dumpsys package "$APP" | grep -q "DEBUGGABLE"; then
    echo "Note: $APP is a debuggable build. ART ignores compiled code for it, so the compile step"
    echo "won't help; install the fullFast build (:app:assembleFullFast) for speed."
fi
run cmd package compile -m speed -f "$APP"

run settings put global settings_enable_monitor_phantom_procs false
# Android 12-13 only read this limit; harmless on newer versions.
run device_config put activity_manager max_phantom_processes 2147483647

run cmd deviceidle whitelist +"$APP"
run cmd appops set "$APP" RUN_ANY_IN_BACKGROUND allow

cat <<EOF

One thing only you can set, on Samsung phones:
  Settings > Battery > Background usage limits > Never sleeping apps > add Bori - Code.
EOF
