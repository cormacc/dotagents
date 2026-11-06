#!/usr/bin/env bash
# Runs the herdr-orch Pi adapter suites inside the *installed* Pi runtime (`pi` on PATH), never
# a repository-pinned SDK. It records `pi --version` and refuses to continue when the API the
# extensions resolve is not that runtime. Each suite must report at least one passing test, so a
# new adapter file that the entry point never loads cannot hide behind a green run.
set -euo pipefail

here="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(git -C "$here" rev-parse --show-toplevel)"
# npm prepends repository shims; they must not choose the runtime under test.
IFS=: read -r -a path_entries <<< "$PATH"
installed_entries=()
for entry in "${path_entries[@]}"; do
  case "$entry" in */node_modules/.bin|*/node_modules/.bin/) continue ;; esac
  installed_entries+=("$entry")
done
installed_path="$(IFS=:; printf '%s' "${installed_entries[*]}")"
pi_bin="$(PATH="$installed_path" command -v pi)" || { echo "pi is not on PATH; the adapter checks need the installed Pi runtime" >&2; exit 1; }
version="$(PATH="$installed_path" "$pi_bin" --version 2>&1 | tail -n 1)"
suites=(${HERDR_ORCH_ADAPTER_SUITES:-fork-adapter fork-provider-context measurement-adapter account-launch})

run_pi() {
  PATH="$installed_path" PI_OFFLINE=1 "$pi_bin" --offline --no-session -ne -e "$here/run-tests.ts" --no-skills --no-prompt-templates --no-context-files --no-mcp -p x </dev/null 2>&1
}

echo "==> installed Pi $version"
out="$(HERDR_ORCH_EXPECT_PI_VERSION="$version" HERDR_ORCH_FORBID_BUNDLE_PREFIX="$repo/node_modules" run_pi)" || { printf '%s\n' "$out"; exit 1; }
printf '%s\n' "$out"
for suite in "${suites[@]}"; do
  grep -Eq "^SUITE $suite passed=[1-9][0-9]* failed=0$" <<<"$out" || { echo "suite $suite did not run or did not pass" >&2; exit 1; }
done

# Negative control: a recorded runtime that does not match must fail the run.
echo "==> mismatch control (expected to fail)"
if control="$(HERDR_ORCH_EXPECT_PI_VERSION="0.0.0-not-installed" run_pi 2>&1)"; then
  echo "runtime mismatch control unexpectedly passed" >&2; exit 1
fi
grep -q "runtime mismatch" <<<"$control" || { echo "mismatch control failed for another reason: $control" >&2; exit 1; }
echo "ok"
