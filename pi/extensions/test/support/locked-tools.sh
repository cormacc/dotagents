# Sourced by extension runners so every suite uses the repository lockfile.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
TSX="$root/node_modules/.bin/tsx"
TSC="$root/node_modules/.bin/tsc"

fail_locked_deps() {
  echo "not ok - $1; run npm ci from $root" >&2
  exit 1
}

if [ ! -x "$TSX" ]; then
  fail_locked_deps "locked tsx not found at $TSX"
fi

run_tsx() {
  "$TSX" "$@"
}
