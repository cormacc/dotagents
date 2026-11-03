#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
source ../test/support/locked-tools.sh

"$TSX" ./index.test.ts
"$TSX" ./pi-integration.test.ts
# `herdr-agent-state.ts` is a top-level extension with no directory of its own, so its
# suite lives in the shared `pi/extensions/test/` directory and is run from here -- the
# same pattern `tasks/test.sh` uses for `../lib/agent-paths.test.ts`.
"$TSX" ../test/herdr-agent-state.test.ts
