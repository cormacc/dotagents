# Shared helpers for herdr-plugin entrypoints. Sourced, not executed.

# The home skill comes first so that this plugin and the herdr-app mods, which
# run the home skill by absolute path, use the same `oh`. The checkout-relative
# fallback keeps a GitHub-only install working: the plugin lives at
# herdr/plugins/herdr-orch, three levels below the dotagents checkout root.
plugin_oh() {
  local root="${HERDR_PLUGIN_ROOT:?HERDR_PLUGIN_ROOT is not set; run this through Herdr}"
  local home_oh="${HOME:-}/.agents/skills/herdr-orch/scripts/oh"
  local checkout_oh
  checkout_oh="$(cd "$root/../../.." && pwd -P)/skills/herdr-orch/scripts/oh"
  local oh
  for oh in "$home_oh" "$checkout_oh"; do
    if [ -x "$oh" ]; then
      printf '%s\n' "$oh"
      return 0
    fi
  done
  printf 'herdr-orch: oh not found at %s or %s\n' "$home_oh" "$checkout_oh" >&2
  return 1
}
