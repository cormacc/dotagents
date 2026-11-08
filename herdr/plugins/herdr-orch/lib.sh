# Shared helpers for herdr-plugin entrypoints. Sourced, not executed.

# The plugin lives at herdr/plugins/herdr-orch in the dotagents checkout
# (linked or GitHub-installed clone), so `oh` resolves relative to
# HERDR_PLUGIN_ROOT, three levels below the checkout root.
plugin_oh() {
  local root="${HERDR_PLUGIN_ROOT:?HERDR_PLUGIN_ROOT is not set; run this through Herdr}"
  local oh
  oh="$(cd "$root/../../.." && pwd -P)/skills/herdr-orch/scripts/oh"
  if [ ! -x "$oh" ]; then
    printf 'herdr-orch: oh not found at %s\n' "$oh" >&2
    return 1
  fi
  printf '%s\n' "$oh"
}
