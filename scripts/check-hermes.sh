#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

printf '\n==> org-tasks backend contract\n'
python3 -m unittest \
  hermes/org-tasks/tests/test_backend.py \
  hermes/org-tasks/tests/test_packaging.py \
  -v

printf '\n==> org-tasks Desktop ESM contract\n'
node --no-warnings --experimental-vm-modules \
  hermes/org-tasks/tests/test_desktop.mjs

api_python="${HERMES_PYTHON:-}"
if [[ -z "$api_python" && -x "$HOME/.hermes/hermes-agent/venv/bin/python" ]]; then
  api_python="$HOME/.hermes/hermes-agent/venv/bin/python"
fi
if [[ -z "$api_python" ]] && python3 -c 'import fastapi' >/dev/null 2>&1; then
  api_python=python3
fi

if [[ -n "$api_python" ]]; then
  printf '\n==> org-tasks FastAPI routes (%s)\n' "$api_python"
  PYTHONWARNINGS=ignore "$api_python" -m unittest \
    hermes/org-tasks/tests/test_plugin_api.py \
    -v
else
  printf '\nERROR: org-tasks FastAPI routes require HERMES_PYTHON or a python3 with FastAPI.\n' >&2
  exit 1
fi
