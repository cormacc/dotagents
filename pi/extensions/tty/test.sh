#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -f "index.ts" ]; then
  echo "not ok - index.ts not found"
  exit 1
fi

if ! grep -q "pi.registerCommand(\"t\"" index.ts; then
  echo "not ok - /t command not registered"
  exit 1
fi

if ! grep -q "tty_capture" index.ts || ! grep -q "tty_list" index.ts; then
  echo "not ok - tty tools not registered"
  exit 1
fi

echo "# Running tty helper unit tests..."
if command -v tsx >/dev/null 2>&1; then
  tsx ./index.test.ts
else
  npx --yes tsx ./index.test.ts
fi

cat <<'CHECKLIST'

# Manual tty/tmux-backend smoke checklist
# Run from inside a disposable tmux session after loading/reloading the extension:
# - /t s printf 'hello\\n'
# - /t w sh -c 'printf start; sleep 30'; watch window is joined below pi with focus in pi.
# - In watch window: Ctrl-C cancels job; second Ctrl-C closes window.
# - /t l shows current windows plus known spawned/watched pane ids.
# - tty_list tool returns windows and known pane ids.
# - tty_capture with no target captures latest watched pane, when present.
# - /t t <pane-id> 50 captures explicit pane output.
# - /t j <single-pane-window-index> joins below pi pane.
# - /t j <multi-pane-window-index> refuses.
# - /t j <other-window>; /t j <third-window> replaces the join.
# - /t m <single-pane-window-index> joins below pi and keeps focus.
# - /t k sends two Ctrl-C then kills the currently joined pane.
# - /t k <window-index> sends two Ctrl-C then kills the target window.
# - /t k with no joined pane shows "No active monitor" message.
# - /t b returns the joined pane to a window.
# - /t b <pane-id> works after simulated state loss/reload.
# - Start outside tmux and confirm clear $TMUX error.
# - Set stale TMUX and confirm clear stale-server error.
# - /t s pi --model <verified-model> starts sibling pi; intercom can list/ask it.
CHECKLIST
