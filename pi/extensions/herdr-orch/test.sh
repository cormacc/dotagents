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
node --import tsx ./private-browser.test.ts
"$TSX" ./accounts-integration.test.ts
"$TSX" ./index.test.ts
"$TSX" ./pi-integration.test.ts
"$TSX" ./delivery.test.ts
"$TSX" ./recovery.test.ts
"$TSX" ./recovery-integration.test.ts
