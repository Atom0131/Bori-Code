#!/usr/bin/env bash
# Backs up Bori Code's projects and auto-enter stats from a phone to this computer, read only.
#
#   scripts/backup_fold.sh [serial] [dest]
#
# serial  adb serial of the phone (default: the only device, or $ANDROID_SERIAL)
# dest    folder that gets a new projects-<YYYYMMDD-HHMM>/ inside (default: ../fold-backups)
#
# It pulls Android/data/<app>/files/Bori (the projects) and files/auto-enter (the copy of the
# auto-enter stats that Bori Code keeps there), checks the file counts match, and prints the
# auto-enter totals with the time they saved (20 s per Enter, as in AutoEnterStats).
# Nothing on the phone is changed.
set -euo pipefail

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
SERIAL="${1:-${ANDROID_SERIAL:-}}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DEST_ROOT="${2:-$(cd "$HERE/../.." && pwd)/fold-backups}"
SECONDS_PER_ENTER=20

adb_() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

# The dev build first (what the test phones run), then the GitHub and Play builds.
APP=""
for id in com.bori.code.debug com.bori.code.full com.bori.code; do
    if adb_ shell "[ -d /sdcard/Android/data/$id/files ]" 2>/dev/null; then APP=$id; break; fi
done
[ -n "$APP" ] || { echo "No Bori Code data folder found on the phone." >&2; exit 1; }
SRC="/sdcard/Android/data/$APP/files"

DEST="$DEST_ROOT/projects-$(date +%Y%m%d-%H%M)"
mkdir -p "$DEST"
echo "Backing up $APP → $DEST"

status=0
for part in Bori auto-enter; do
    if ! adb_ shell "[ -d '$SRC/$part' ]" 2>/dev/null; then
        echo "  $part: not on the phone yet (skipped)"
        continue
    fi
    adb_ pull "$SRC/$part" "$DEST/" >/dev/null 2>&1 || true   # the counts below catch a failed pull
    on_phone=$(adb_ shell "find '$SRC/$part' -type f | wc -l" | tr -d '\r ')
    here=$(find "$DEST/$part" -type f | wc -l)
    size=$(du -sh "$DEST/$part" | cut -f1)
    if [ "$on_phone" = "$here" ]; then
        echo "  $part: $here files, $size ✓"
    else
        echo "  $part: MISMATCH, $on_phone on the phone but $here copied" >&2
        status=1
    fi
done

STATS="$DEST/auto-enter/stats.json"
if [ -f "$STATS" ]; then
    echo
    python3 -I - "$STATS" "$SECONDS_PER_ENTER" <<'PY'
import datetime, json, sys
days = json.load(open(sys.argv[1]))["days"]
per = int(sys.argv[2])
today = datetime.date.today()
monday = today - datetime.timedelta(days=today.weekday())
def total(start):
    return sum(n for d, n in days.items() if start <= datetime.date.fromisoformat(d) <= today)
def saved(n):
    m = (n * per + 30) // 60
    return "none" if n <= 0 else "under a minute" if m < 1 else f"{m} min" if m < 60 else f"{m // 60} h {m % 60} min"
rows = [("Today", days.get(today.isoformat(), 0)), ("This week", total(monday)),
        ("This month", total(today.replace(day=1))), ("This year", total(today.replace(month=1, day=1))),
        ("All time", sum(days.values()))]
print(f"Auto-enter (about {per} s saved per Enter)")
for label, n in rows:
    print(f"  {label:<11} {n:>6} Enters   {saved(n)} saved")
if days:
    best = max(days.items(), key=lambda kv: kv[1])
    print(f"  Busiest day: {best[0]} with {best[1]} Enters ({saved(best[1])})")
    print(f"  Days used:   {len(days)}, since {min(days)}")
PY
fi
exit $status
