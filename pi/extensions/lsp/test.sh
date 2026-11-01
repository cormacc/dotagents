#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

source ../test/support/locked-tools.sh
exec "$TSX" ./lsp.test.ts
