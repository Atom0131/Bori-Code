#!/usr/bin/env bash
# Copies Bori Keyboard (com.bori.keyboard) into :core:keyboard, the terminal's keys.
#
#   scripts/sync_bori_keyboard.sh [path/to/bori-keyboard]
#
# Without a path, it pulls the project from the phone over adb (Bori Keyboard is written inside
# Bori Code, in its projects folder). Only the engine and the keyboard views are copied; the IME
# service and the settings screens stay behind. The copy then gets the same small patches every
# time, so a sync is mechanical:
#   - package com.bori.keyboard -> com.bori.core.keyboard
#   - TypingSession talks to an InputHost (ime/InputHost.kt, Bori Code's own) instead of the IME
#   - Dictionaries keeps a configurable number of languages (the terminal keeps one)
#   - Prefs takes its SharedPreferences file name; UserStore uses terminal_words.db
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/core/keyboard/src/main"
PHONE_DIR=/storage/emulated/0/Android/data/com.bori.code.debug/files/Bori/bori-keyboard

if [[ $# -ge 1 ]]; then
    SRC="$1"
else
    SRC="$(mktemp -d)"
    trap 'rm -rf "$SRC"' EXIT
    adb pull "$PHONE_DIR/app" "$SRC/" >/dev/null
    adb pull "$PHONE_DIR/ATTRIBUTION.md" "$SRC/" >/dev/null
fi
MAIN="$SRC/app/src/main"
JAVA="$MAIN/java/com/bori/keyboard"
[[ -f "$JAVA/ime/TypingSession.kt" ]] || { echo "No Bori Keyboard sources in $SRC" >&2; exit 1; }

OUT="$DEST/java/com/bori/core/keyboard"
# InputHost is Bori Code's own file: keep it across syncs.
HOST="$(mktemp)"
cp "$OUT/ime/InputHost.kt" "$HOST"
rm -rf "$OUT" "$DEST/assets/dict"
mkdir -p "$OUT/ime" "$OUT/view" "$OUT/settings" "$DEST/assets/dict"

for d in dict predict learn swipe layout theme clipboard; do
    cp -r "$JAVA/$d" "$OUT/"
done
for f in TypingSession EditorPolicy Feedback TypoRecorder DiagLog SwipeRecorder; do
    cp "$JAVA/ime/$f.kt" "$OUT/ime/"
done
for f in KeyboardView KeyOverlayView SuggestionStripView InputRootView EmojiPanelView EmojiData \
    ClipboardPanelView BigScreen; do
    cp "$JAVA/view/$f.kt" "$OUT/view/"
done
cp "$JAVA/settings/Prefs.kt" "$OUT/settings/"
cp "$HOST" "$OUT/ime/InputHost.kt"
rm -f "$HOST"
cp "$MAIN"/assets/dict/*.bdic "$MAIN"/assets/dict/*_typos.txt "$DEST/assets/dict/"
[[ -f "$SRC/ATTRIBUTION.md" ]] && cp "$SRC/ATTRIBUTION.md" "$ROOT/core/keyboard/ATTRIBUTION.md"

find "$OUT" -name '*.kt' -exec sed -i 's/com\.bori\.keyboard/com.bori.core.keyboard/g' {} +

TS="$OUT/ime/TypingSession.kt"
sed -i \
    -e 's/private val ime: BoriImeService,/private val ime: InputHost,/' \
    -e 's/record(ime,/record(ime.context,/g' \
    -e 's/isTrainerField(info, ime)/isTrainerField(info, ime.context)/' \
    -e 's/^\(\s*\)ime, "pick",/\1ime.context, "pick",/' \
    "$TS"
sed -i \
    -e 's/^class Dictionaries(context: Context) {/class Dictionaries(context: Context, private val maxLanguages: Int = 2) {/' \
    -e 's/loaded.size > 2/loaded.size > maxLanguages/' \
    "$OUT/dict/Dictionaries.kt"
sed -i \
    -e 's/^class Prefs(context: Context) {/class Prefs(context: Context, file: String = "bori_settings") {/' \
    -e 's/getSharedPreferences("bori_settings",/getSharedPreferences(file,/' \
    "$OUT/settings/Prefs.kt"
sed -i 's/SQLiteOpenHelper(context, "user.db",/SQLiteOpenHelper(context, "terminal_words.db",/' \
    "$OUT/learn/UserStore.kt"

# Every patch must have applied; a changed upstream line means this script needs updating.
fail=0
check() { grep -q "$2" "$1" || { echo "Patch missing in $1: $2" >&2; fail=1; }; }
check "$TS" 'private val ime: InputHost,'
check "$OUT/dict/Dictionaries.kt" 'loaded.size > maxLanguages'
check "$OUT/settings/Prefs.kt" 'getSharedPreferences(file,'
check "$OUT/learn/UserStore.kt" '"terminal_words.db"'
if grep -nE '\bime[,)]|BoriImeService' "$TS" >&2; then
    echo "TypingSession still passes the IME itself (see above)" >&2
    fail=1
fi
[[ $fail -eq 0 ]] || exit 1
echo "Synced Bori Keyboard into core/keyboard."
