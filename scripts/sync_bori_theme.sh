#!/usr/bin/env bash
# Copies the Bori theme engine (v2) into :core:theme.
#
# The engine lives in "Bori Habit Tracker theme engine version 2", exported from the Bori Habit
# Tracker (its scripts/export_from_habit_tracker.sh). Its sources are copied here unchanged; don't
# edit core/theme/src, change the engine and sync again. core/theme/build.gradle.kts is Bori Code's
# own (this project's version catalog) and is not touched.
#
# Usage: scripts/sync_bori_theme.sh [path to the engine folder]
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${1:-$HERE/../../Bori- habit tracker/Bori Habit Tracker theme engine version 2}"
SRC="$ENGINE/theme/src/main"
DST="$HERE/core/theme/src/main"

[ -d "$SRC/java/com/bori/theme" ] || { echo "No theme engine at $ENGINE" >&2; exit 1; }

rm -rf "$DST"
mkdir -p "$DST"
cp -r "$SRC/." "$DST/"
cp "$ENGINE/ORIGIN.md" "$HERE/core/theme/ORIGIN.md"
{
  echo
  echo "Synced into Bori Code by scripts/sync_bori_theme.sh on $(date +%F)."
} >> "$HERE/core/theme/ORIGIN.md"
echo "Synced $(find "$DST" -name '*.kt' | wc -l) Kotlin files from $ENGINE"
