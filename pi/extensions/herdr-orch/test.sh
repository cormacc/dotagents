#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
source ../test/support/locked-tools.sh

mkdir -p ../../../.tmp
account_auth_dir=$(mktemp -d ../../../.tmp/oh-test-auth-XXXXXX)
trap 'rm -rf "$account_auth_dir"' EXIT
unset "${!HERDR_ORCH_@}" HC_TERMINAL_ID
export PI_CODING_AGENT_DIR="$(cd "$account_auth_dir" && pwd)"
export HOME="$PI_CODING_AGENT_DIR/home"
export ORCH_ASSIGNMENT_ROOT="$PI_CODING_AGENT_DIR"
mkdir -p "$HOME" "$PI_CODING_AGENT_DIR/tmp"
export TMPDIR="$PI_CODING_AGENT_DIR/tmp"

"$TSX" ./accounts.test.ts
"$TSX" ./accounts-integration.test.ts
"$TSX" ./index.test.ts
"$TSX" ./pi-integration.test.ts
"$TSX" ./recovery.test.ts
"$TSX" ./recovery-integration.test.ts
# `herdr-agent-state.ts` is a top-level extension with no directory of its own, so its
# suite lives in the shared `pi/extensions/test/` directory and is run from here -- the
# same pattern `tasks/test.sh` uses for `../lib/agent-paths.test.ts`.
"$TSX" ../test/herdr-agent-state.test.ts
