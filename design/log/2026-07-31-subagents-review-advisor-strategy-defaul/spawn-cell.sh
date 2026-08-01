#!/usr/bin/env bash
# Spawn one evaluation cell and GUARANTEE the prompt is dispatched.
#
#   ./spawn-cell.sh <label> <persona> [extra subagent args...]
#
# Works around an observed failure where `subagent start` leaves the composed
# prompt typed into a freshly spawned pane but unsubmitted (the Enter appears to
# be swallowed while pi is still starting up / showing a notification). Without
# this, every spawn needs a manual focus + Enter.
#
# Strategy: after starting, poll the agent's lifecycle state. A dispatched
# prompt drives it to `working`. If it is still `idle` we send an explicit Enter
# and re-check, up to a bounded number of attempts.
set -euo pipefail

OH="$HOME/.agents/skills/herdr-orch/scripts/oh"
LABEL="${1:?usage: spawn-cell.sh <label> <persona> [args...]}"; shift
PERSONA="${1:?persona required}"; shift

json="$("$OH" task start "$PERSONA" "$@")"
task=$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["task"])')
child=$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["child"])')
pane=$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["pane-id"])')
plabel=$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["label"])')

# NB: herdr agent get nests the payload as result.agent.agent_status.
state_of() {
  herdr agent get "$child" 2>/dev/null \
    | python3 -c 'import json,sys
try: d=json.load(sys.stdin)
except Exception: print("unknown"); raise SystemExit
a=((d.get("result") or {}).get("agent")) or (d.get("result") or {}) or {}
print(a.get("agent_status") or "unknown")' 2>/dev/null || echo unknown
}

dispatched=false
nudges=0
for attempt in 1 2 3 4 5 6; do
  sleep 5
  st="$(state_of)"
  if [[ "$st" == "working" ]]; then
    dispatched=true
    break
  fi
  # Only nudge on a definite idle. An unknown reading means we could not observe
  # the agent, and sending Enter on a guess risks submitting stray empty input.
  if [[ "$st" == "idle" ]]; then
    echo "  [$LABEL] idle after attempt $attempt — prompt held unsubmitted, sending Enter" >&2
    herdr agent send-keys "$child" enter >/dev/null 2>&1 || true
    nudges=$((nudges + 1))
  else
    echo "  [$LABEL] state=$st after attempt $attempt — waiting (no nudge)" >&2
  fi
done

echo "CELL $LABEL"
echo "  task      $task"
echo "  child     $child"
echo "  pane      $pane"
echo "  label     $plabel"
echo "  dispatched $dispatched (final state: $(state_of), enter-nudges: $nudges)"
