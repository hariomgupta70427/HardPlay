#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# HardPlay — TDLib build log watcher
#
#   bash tools/watch-tdlib.sh [logfile]
#
# Two jobs, and the second is the one that isn't obvious:
#
#  1. Print interesting lines from the build log as they appear.
#  2. **Keep a wsl.exe client attached.** WSL2 tears down the whole VM roughly
#     60s after the last client detaches, killing the build regardless of setsid
#     or nohup (see CLAUDE.md). Something has to stay in the foreground for the
#     duration, and this is it.
#
# Why polling instead of `tail -F`: the log lives on /mnt/d, which is a drvfs
# mount, and drvfs has no inotify. `tail -F` there fails outright with
#   tail: error reading '...': No data available
# taking the watcher — and therefore the VM — down with it. Tracking a byte
# offset and re-reading works on drvfs because only the *notification* mechanism
# is missing, not the reads.
# ---------------------------------------------------------------------------
set -uo pipefail

LOG="${1:-/mnt/d/Work/Github Repo/HardPlay/tools/tdlib-build.log}"
INTERVAL="${WATCH_INTERVAL:-5}"

# Stage markers, the script's own success/failure lines, the 16 KB alignment
# check, and the ways a cross-compile dies. Deliberately not the whole log: every
# matched line becomes a notification.
PATTERN='^=== |^BUILD-|LOAD align|ninja: build stopped|FAILED:|No space left|Killed|error: |Segmentation fault'

# Wait for the file rather than assuming it exists: the watcher is usually
# started a second or two after the build.
for _ in $(seq 1 60); do
  [ -f "$LOG" ] && break
  sleep 1
done
if [ ! -f "$LOG" ]; then
  echo "BUILD-WATCH-FAILED: no log at $LOG"
  exit 1
fi

# Start from the end: prior stages are already in the log and re-announcing them
# would fire a burst of notifications for work that finished before we attached.
POS=$(wc -c < "$LOG")

while true; do
  NEW=$(wc -c < "$LOG" 2>/dev/null || echo "$POS")

  # A shrunken file means the build restarted and truncated the log.
  if [ "$NEW" -lt "$POS" ]; then
    POS=0
  fi

  if [ "$NEW" -gt "$POS" ]; then
    tail -c "+$((POS + 1))" "$LOG" 2>/dev/null | grep -E "$PATTERN" || true
    POS=$NEW
  fi

  # Stop once the build is done *and* the log has stopped moving, so the final
  # BUILD-SUCCESS line is always delivered before the watcher exits.
  if ! pgrep -f 'bash \./build-tdlib\.sh' > /dev/null 2>&1; then
    sleep "$INTERVAL"
    FINAL=$(wc -c < "$LOG" 2>/dev/null || echo "$POS")
    if [ "$FINAL" -gt "$POS" ]; then
      tail -c "+$((POS + 1))" "$LOG" 2>/dev/null | grep -E "$PATTERN" || true
    fi
    echo "=== watcher: build process gone, stopping"
    exit 0
  fi

  sleep "$INTERVAL"
done
