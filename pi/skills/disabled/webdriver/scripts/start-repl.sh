#!/usr/bin/env bash
# Start a babashka nREPL with etaoin available for browser automation.
# Usage: ./start-repl.sh [port]
# Default port: 7778

set -euo pipefail

SKILL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${1:-7778}"

cd "$SKILL_DIR"
echo "Starting webdriver bb nREPL on port $PORT ..."
echo "Connect with: clj-nrepl-eval -p $PORT \"(require '[etaoin.api :as e])\""
exec bb nrepl-server "$PORT"
