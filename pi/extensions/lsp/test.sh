#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if command -v tsx >/dev/null 2>&1; then
  exec tsx ./lsp.test.ts
fi
exec npx --yes tsx ./lsp.test.ts
