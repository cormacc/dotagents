#!/usr/bin/env bash
# Register the pi-settings clean filter for this clone of cormacc/dotagents.
#
# Pi writes runtime state back into ~/.pi/agent/settings.json, which is
# symlinked from this checkout at pi/settings.json: lastChangelogVersion
# on every pi upgrade, and a per-installation deviceId (created on first
# use, for example for Sign in with ChatGPT). Neither belongs in git.
#
# This script registers a git clean filter that drops those fields when
# the file is staged, while leaving the working-tree copy untouched
# (smudge = cat). defaultProvider and defaultModel are tracked: since
# pi 0.84.3 a /model choice is session-only and only Ctrl+S in the
# selector saves the default, so a change there is deliberate.
#
# A git command that rewrites the working tree (stash, reset --hard,
# checkout -- pi/settings.json) writes back the committed copy, which
# lacks the stripped fields; pi recreates them as needed.
#
# Safe to re-run; idempotent. Exits early when the filter is current.
#
# When this repo is consumed as a git submodule of cormacc/dotfiles at
# ~/dotfiles/agents/, run this script from within the submodule
# (~/dotfiles/agents/) so the filter is registered against the
# submodule's .git/config (which is what owns pi/settings.json).
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required but not installed." >&2
  echo "Install jq (e.g. 'nix profile install nixpkgs#jq' or via your" >&2
  echo "package manager) and re-run." >&2
  exit 1
fi

# `|| true` matters: under `set -e` a failing command substitution aborts the
# assignment itself, so without it a non-repo cwd exits 128 silently and the
# diagnostic below never runs.
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$REPO_ROOT" ]]; then
  echo "ERROR: not inside a git repository." >&2
  exit 1
fi
cd "$REPO_ROOT"

CLEAN="jq 'del(.lastChangelogVersion, .deviceId)' --indent 2"
if [[ "$(git config --get filter.pi-settings.clean || true)" == "$CLEAN" &&
      "$(git config --get filter.pi-settings.smudge || true)" == "cat" &&
      "$(git config --get filter.pi-settings.required || true)" == "true" ]]; then
  exit 0
fi

git config filter.pi-settings.clean "$CLEAN"
git config filter.pi-settings.smudge "cat"
git config filter.pi-settings.required true

echo "Registered pi-settings clean filter for $REPO_ROOT/.git/config:"
git config --get-regexp '^filter\.pi-settings\.' | sed 's/^/  /'
echo
echo "If pi/settings.json was committed unfiltered before"
echo "this filter was installed, renormalize it once:"
echo
echo "    git add --renormalize pi/settings.json"
echo "    git commit -m 'chore: renormalize settings.json under pi-settings filter'"
