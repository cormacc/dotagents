#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

for runner in pi/extensions/*/test.sh; do
  printf '\n==> %s\n' "$runner"
  bash "$runner"
done

printf '\n==> pi/extensions/ext-dev.test.ts\n'
./node_modules/.bin/tsx pi/extensions/ext-dev.test.ts
