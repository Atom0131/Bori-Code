# Bori Code — Change Notes (late October to November 2026)

Most of this work is on the terminal in the full build. One change affects everyone: **Bori Code is now
licensed GPL-3.0-or-later** (it was Apache-2.0), because the terminal keyboard is Bori Keyboard's
GPL code.

---

## 4.4.0 (November 4): agents keep working, auto-enter from script 6, speed

**Agents no longer stall when the screen goes off.** Terminal agents had no wake lock, so with the
screen off or another app open, Android could suspend the CPU and the agent waited until the phone
woke. Bori Code now holds a partial wake lock while an agent works, or waits on a prompt auto-enter
will answer (`TerminalService.keepAwake`, driven by `TerminalSessionManager.updateWake`). It lets go
when the agent is done, waits for the user, or prints nothing for 15 minutes, and the status
notification says so while it's held.

**Less work under proot.** Claude Code's managed settings turn off its auto-updater and
non-essential traffic (More → Linux updates it). Both CLIs get a "speed on a phone" rule: fewer,
bigger commands, `rg`, no polling, long jobs run once with a log. `bori-bench` in the guest times
program starts, file access, git, search and CPU. The terminal redraws at most once a frame, and not
for hidden panes or while the app is in the background.

**Auto-enter, from the CachyOS agy auto-enter script 6.** It ignores Antigravity sessions from
before it was turned on, never fires from the screen when the transcript says the turn is done or
asks the user, waits 3 s between screen-based Enters and says "agy ignored Enter 3×" when it's
stuck. Its stats use the helper's file format with backups and recovery, and Export/Import stats
(keys settings) move counts between the phone and the PC (higher count wins per day).

**Also:** Add from phone and image preview in the code editor; GitHub browser sign-in for the Linux
environment (when the build has OAuth App keys; otherwise gh's code sign-in); the `fullFast` build
type and phone tuning scripts for testing; the app's first unit tests.

---

## Terminal keyboard: Bori Keyboard itself, typing into a type bar

**Before.** The terminal had its own compact keyboard. It used Bori Keyboard's dictionaries, but
typed letters straight into the terminal. A terminal can't hold a half-typed word, so correction
was limited and the result didn't feel like Bori Keyboard.

**Now.**
- **A type bar is always there**, under the terminal's top keys. You type a command or a prompt, then
  Enter or Send sends the whole line. Enter or Backspace in an empty bar go to the terminal itself.
  Tapping the bar brings up the keyboard.
- **The keys are Bori Keyboard itself**: its keys, suggestion strip, glide typing and trail, undo of
  a correction with Backspace, tap-to-fix, emoji and clipboard panels, and learning. It types into
  the bar the same way the keyboard app types into any app's field.
- **Bigger dictionaries.** About 508k English and 689k Spanish words, plus typo lists. Only the
  language in use is kept in memory (about 20 MB).
- **Commands aren't corrected.** At a shell prompt words are suggested but never replaced, so
  commands and file names stay as typed. At an agent's prompt (Claude Code, Antigravity), normal
  correction applies.
- **The phone's keyboard** is one tap away on the 🌐 key and types into the same bar, so voice
  typing (Whisper and others) works there. The terminal icon on the top bar switches back.
- **Top keys go straight to the terminal:** ESC, TAB, CTRL, ALT, the arrows, Home/End, PgUp/PgDn.
  A key typed with Ctrl or Alt waiting also goes straight there, so Ctrl+C works.
- **DIRECT** (top bar): while it's lit, the keys type straight into the terminal one key at a time,
  for menus that take a single key, such as Claude Code's 1/2/3.
- The terminal's keyboard settings (height, split, gaps, vibration, autocorrect level, number row,
  language) apply to these keys.

**Files:** `core/keyboard/` (copied from Bori Keyboard by `scripts/sync_bori_keyboard.sh`),
`ui/terminal/BoriKeys.kt`, `ui/terminal/TerminalKeyboard.kt`, `ui/terminal/TerminalScreen.kt`.

**Keeping it in sync.** Bori Keyboard is its own project. The script copies its engine and views into
`:core:keyboard` and applies the same few patches every time:
- the package name;
- an `InputHost` seam, so the typing session can type into the type bar instead of an input method
  service;
- the number of dictionary languages kept in memory;
- the settings file name and the learned-words database name.

The copied files aren't edited by hand.

---

## Closing a terminal tab ends everything it started

**Problem.** Closing a Linux tab killed only proot. Killed that way, proot can't stop the programs
it runs, so they kept running with a dead terminal. bash then spun at 100% CPU. On a Galaxy Z Fold,
one left behind by a closed Claude Code tab ran for over an hour, heated the phone and drained the
battery.

**Fix.** Closing a tab now hangs up, then kills, the tab's whole process tree and session. Leftovers
from before (a process of Bori Code's whose parent is gone and whose terminal is deleted) are cleared
when the terminal starts and whenever a shell ends.

**Files:** `terminal/ProcessReaper.kt`, `terminal/TerminalSessionManager.kt`,
`service/TerminalService.kt`.

---

## Earlier in this cycle (October 27)

- **Coding CLI launcher:** a button in the terminal's title bar and in empty panes opens Claude Code
  or Antigravity in a new tab.
- **One status notification** for the terminal and chat, with a heads-up when an agent needs you.
  Claude Code reports through hooks; Antigravity through its transcript.
- **Auto-enter (🤖):** per tab and off by default, it presses Enter for an agent's approval prompts.
  It never fires at a shell prompt, and it turns itself off after 20 minutes with nothing to approve.
  It explains itself the first time.
- **Startup crash fixed:** a debug check loaded the 72 MB Kotlin compiler on every launch, and could
  crash the app after an update.
- **Shorter key vibration** on Samsung phones (about 12 ms instead of 120 ms).
