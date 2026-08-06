#!/usr/bin/env bash
# Register the pi-settings clean filter for this clone of cormacc/dotagents.
#
# Pi writes runtime preferences (defaultProvider, defaultModel,
# lastChangelogVersion) back into ~/.pi/agent/settings.json on every
# /model swap or pi upgrade. That file is symlinked from this checkout
# at pi/settings.json, so without this filter every provider swap shows
# up as a tracked change.
#
# This script registers a git clean filter that drops those volatile
# fields when the file is staged, while leaving the working-tree copy
# untouched (smudge = cat). Pi keeps writing whatever it likes; git
# only ever sees the durable subset (notably `packages`,
# `hideThinkingBlock`, `defaultThinkingLevel`).
#
# Safe to re-run; idempotent.
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

git config filter.pi-settings.clean \
  "jq 'del(.lastChangelogVersion, .defaultProvider, .defaultModel)' --indent 2"
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
