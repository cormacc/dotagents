#!/usr/bin/env bash
# Popup pane: read-only checks of what the herdr-orch plugin and the herdr-app
# mods need. Bash only, so that it reports a missing Babashka instead of
# failing on it. It writes nothing.
# DOCTOR_ONCE=1 prints the report and exits, for smoke tests.
# Exit status: 1 when a check fails, else 0; a warning does not fail.
set -u
. "$(dirname "$0")/lib.sh" || exit 1

MIN_HERDR=0.9.5
INSTALL_URL=https://github.com/cormacc/dotagents/blob/main/skills/herdr-orch/references/install-surfaces.md
failed=0

report() { # report <PASS|WARN|FAIL> <check> <detail>
  printf '%-4s  %-12s %s\n' "$1" "$2" "$3"
  [ "$1" = FAIL ] && failed=1
  return 0
}

# version_ge A B: A >= B for dotted numeric versions; awk, because BSD sort lacks -V on older macOS.
version_ge() {
  awk -v a="$1" -v b="$2" 'BEGIN { n = split(a, x, "."); m = split(b, y, "."); if (m > n) n = m
    for (i = 1; i <= n; i++) { if (x[i] + 0 > y[i] + 0) exit 0; if (x[i] + 0 < y[i] + 0) exit 1 } exit 0 }'
}

# Prints the directory's real path, or nothing when it does not exist.
real_dir() { (cd "$1" 2>/dev/null && pwd -P); }

check_herdr() {
  local herdr="${HERDR_BIN_PATH:-herdr}" version
  version="$("$herdr" --version 2>/dev/null | awk '{print $2; exit}')"
  if [ -z "$version" ]; then
    report FAIL herdr "cannot read the version from $herdr --version"
  elif version_ge "$version" "$MIN_HERDR"; then
    report PASS herdr "$version (>= $MIN_HERDR)"
  else
    report FAIL herdr "$version is older than $MIN_HERDR"
  fi
}

check_bb() {
  local bb
  if bb="$(command -v bb)"; then
    report PASS babashka "$bb"
  elif command -v brew >/dev/null 2>&1; then
    report FAIL babashka "bb is not on PATH. Install: brew install borkdude/brew/babashka"
  else
    report FAIL babashka "bb is not on PATH. Install: https://github.com/babashka/babashka#installation"
  fi
}

check_oh() {
  local oh source
  if oh="$(plugin_oh 2>/dev/null)"; then
    case "$oh" in
      "${HOME:-}"/.agents/*) source=home ;;
      *) source=checkout ;;
    esac
    report PASS oh "$oh ($source)"
  else
    report FAIL oh "$(plugin_oh 2>&1 >/dev/null). See $INSTALL_URL"
  fi
}

# The herdr-app mods and the agents use the home skill. A home skill from
# another checkout runs a different revision of `oh` than this plugin's.
check_home_skill() {
  local home_skill="${HOME:-}/.agents/skills/herdr-orch" home_real plugin_real
  home_real="$(real_dir "$home_skill")"
  plugin_real="$(real_dir "$HERDR_PLUGIN_ROOT/../../../skills/herdr-orch")"
  if [ -z "$home_real" ]; then
    report WARN home-skill "$home_skill is absent; the herdr-app mods and agents need it. See $INSTALL_URL"
  elif [ -z "$plugin_real" ] || [ "$home_real" = "$plugin_real" ]; then
    report PASS home-skill "$home_skill -> $home_real"
  else
    report WARN home-skill "version skew: $home_skill is $home_real, this plugin's checkout has $plugin_real"
  fi
}

check_pi_traits() {
  local pi="${PI_CODING_AGENT_DIR:-${HOME:-}/.pi/agent}"
  if [ ! -d "$pi" ]; then
    report PASS pi-traits "no Pi agent directory at $pi; not needed"
  elif [ -e "$pi/extensions/traits" ]; then
    report PASS pi-traits "$pi/extensions/traits"
  else
    report WARN pi-traits "$pi/extensions/traits is absent; Pi does not expand traits interactively"
  fi
}

: "${HERDR_PLUGIN_ROOT:?HERDR_PLUGIN_ROOT is not set; run this through Herdr}"
printf 'herdr-orch doctor\n\n'
check_herdr
check_bb
check_oh
check_home_skill
check_pi_traits

[ -n "${DOCTOR_ONCE:-}" ] && exit "$failed"
printf '\nPress any key to close.\n'
read -r -s -n 1 _
exit "$failed"
