#!/usr/bin/env bash
# Popup pane: `oh usage --format text`, refreshed every QUOTA_PANE_INTERVAL
# seconds (default 60). Press q to close; any other key refreshes at once.
# QUOTA_PANE_ONCE=1 prints one screen and exits, for smoke tests.
set -u
. "$(dirname "$0")/lib.sh"
oh="$(plugin_oh)" || exit 1
interval="${QUOTA_PANE_INTERVAL:-60}"

# Run from HOME so that the plugin checkout is not read as a project config root.
cd "$HOME" || exit 1

while :; do
  [ -n "${QUOTA_PANE_ONCE:-}" ] || clear
  printf 'Subscription quota  %s\n\n' "$(date '+%H:%M:%S')"
  "$oh" usage --format text
  [ -n "${QUOTA_PANE_ONCE:-}" ] && exit 0
  printf '\nq: close   any other key: refresh\n'
  if read -r -s -n 1 -t "$interval" key && [ "$key" = q ]; then
    exit 0
  fi
done
