#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"
mkdir -p "$root/.tmp"
work="$(mktemp -d "$root/.tmp/check.XXXXXX")"
trap 'rm -rf "$work"' EXIT

printf '==> reproducible Node installs\n'
npm ci --ignore-scripts
npm --prefix pi/extensions/chromium ci --ignore-scripts
npm --prefix pi/extensions/pi-clojure ci --ignore-scripts

printf '\n==> Babashka task tooling\n'
bb test

printf '\n==> skill-creator clean validation and packaging\n'
python3 -m venv "$work/venv"
"$work/venv/bin/python" -m pip install --quiet --disable-pip-version-check -r skills/skill-creator/requirements.txt
scripts/validate-skills.bb --python "$work/venv/bin/python"
"$work/venv/bin/python" skills/skill-creator/scripts/package_skill.py skills/code-review "$work/dist"
test -s "$work/dist/code-review.skill"

printf '\n==> native-system Nix package evaluation\n'
npm run check:nix

printf '\nAll repository checks passed.\n'
