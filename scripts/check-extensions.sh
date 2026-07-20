#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

for runner in pi/extensions/*/test.sh; do
  printf '\n==> %s\n' "$runner"
  bash "$runner"
done

printf '\n==> pi/extensions/ext-dev/index.test.ts\n'
./node_modules/.bin/tsx pi/extensions/ext-dev/index.test.ts

printf '\n==> pi/extensions/test/question.test.ts\n'
./node_modules/.bin/tsx pi/extensions/test/question.test.ts

printf '\n==> pi/extensions/test/question.pi-integration.test.ts\n'
./node_modules/.bin/tsx pi/extensions/test/question.pi-integration.test.ts

printf '\n==> pi/extensions/test/systemprompt.test.ts\n'
./node_modules/.bin/tsx pi/extensions/test/systemprompt.test.ts

printf '\n==> pi/extensions/test/systemprompt.pi-integration.test.ts\n'
./node_modules/.bin/tsx pi/extensions/test/systemprompt.pi-integration.test.ts
