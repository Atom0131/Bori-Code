#!/usr/bin/env bash
# Read-only performance snapshot of Bori Code on a connected phone: memory, the Linux programs
# it runs, frame times, temperature and CPU. Changes nothing on the phone.
#
#   scripts/fold_perf_snapshot.sh [label] [serial] [app id]
#
# Writes perf-<label>-<time>.txt in the current folder. Take one with a Claude tab idle, one while
# Claude is printing, one while typing, then again after a change, and compare.
set -euo pipefail

LABEL="${1:-snapshot}"
SERIAL="${2:-${ANDROID_SERIAL:-}}"
APP="${3:-com.bori.code.debug}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=(-s "$SERIAL")
OUT="perf-$LABEL-$(date +%Y%m%d-%H%M%S).txt"

sh_() { "${ADB[@]}" shell "$@" 2>&1; }
section() { printf '\n===== %s =====\n' "$1"; }

# The app's Linux user (u0_aNNN): every process it starts, proot and the CLIs included, runs as it.
USER_NAME="$(sh_ ps -A -o USER,NAME | awk -v app="$APP" '$2 == app { print $1; exit }')"

{
    section "device"
    sh_ getprop ro.product.model
    sh_ getprop ro.build.version.release
    date
    section "app package"
    sh_ dumpsys package "$APP" | grep -E "versionName|pkgFlags|flags=" | head -4
    section "meminfo $APP"
    sh_ dumpsys meminfo "$APP" | sed -n '1,60p'
    section "processes of $APP's user (${USER_NAME:-not running}), RSS KB"
    if [[ -n "$USER_NAME" ]]; then
        sh_ "ps -A -o USER,PID,PPID,RSS,%CPU,TIME,ARGS | grep '^$USER_NAME '"
    fi
    section "frames (gfxinfo)"
    sh_ dumpsys gfxinfo "$APP" | grep -E "Total frames|Janky frames|percentile|Number (Slow|Missed|High)" | head -12
    section "thermal"
    sh_ dumpsys thermalservice | grep -E "Thermal Status|mType=(0|1|2|3)," | head -12
    section "memory (device)"
    sh_ cat /proc/meminfo | grep -E "MemTotal|MemAvailable|SwapTotal|SwapFree"
    section "top"
    sh_ top -b -n 1 -m 15
} > "$OUT"

# Reset frame stats so the next snapshot measures only what happens in between.
sh_ dumpsys gfxinfo "$APP" reset >/dev/null || true
echo "$OUT"
