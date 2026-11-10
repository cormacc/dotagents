#!/usr/bin/env bash
# Action: open this plugin's pane entrypoint $1, so that `plugin action invoke`
# and a `plugin_action` key binding can reach the pane.
set -eu
exec "${HERDR_BIN_PATH:?HERDR_BIN_PATH is not set; run this through Herdr}" plugin pane open \
  --plugin "${HERDR_PLUGIN_ID:?HERDR_PLUGIN_ID is not set; run this through Herdr}" --entrypoint "${1:?usage: open-pane.sh <entrypoint>}"
