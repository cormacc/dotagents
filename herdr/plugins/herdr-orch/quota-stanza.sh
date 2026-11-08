#!/usr/bin/env bash
# Action: print a Herdr [ui] tab_bar_right entry for the quota status line.
# Plugin v1 cannot edit Herdr config, so the operator pastes the stanza.
set -eu
. "$(dirname "$0")/lib.sh"
oh="$(plugin_oh)"

# The entry runs through `/bin/sh -lc`, so the path is single-quoted for the shell.
# A path with a quote or backslash would also need TOML and shell escaping.
case "$oh" in
  *\'* | *\"* | *\\*)
    printf 'herdr-orch: cannot quote oh path %s\n' "$oh" >&2
    exit 1 ;;
esac
toml_command="'$oh' usage --format status"

cat <<EOF
# Add to the [ui] table of ~/.config/herdr/config.toml, then run:
#   herdr config check && herdr server reload-config
# The 30 s timeout covers two bounded provider reads and cache coordination.
# Set tab_bar_position = "bottom" to show the tab row as a footer.
tab_bar_right = [
  { type = "command", command = "$toml_command", interval_seconds = 60, timeout_seconds = 30 },
]
EOF
