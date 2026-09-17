#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
root="$(cd ../../.. && pwd)"
TSX="$root/node_modules/.bin/tsx"

if [ ! -x "$TSX" ]; then
  echo "not ok - locked tsx not found at $TSX; run npm ci from $root" >&2
  exit 1
fi

"$TSX" ./index.test.ts
# `herdr-agent-state.ts` is a top-level extension with no directory of its own, so its
# suite lives in the shared `pi/extensions/test/` directory and is run from here -- the
# same pattern `tasks/test.sh` uses for `../lib/agent-paths.test.ts`.
"$TSX" ../test/herdr-agent-state.test.ts
