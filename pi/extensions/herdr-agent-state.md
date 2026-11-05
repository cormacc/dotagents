# herdr-agent-state extension

Reports the pi session state to Herdr, so that the Herdr pane shows the agent as `idle`, `working`, or `blocked`. Herdr owns `herdr-agent-state.ts`: `herdr integration install pi` writes it, and a reinstall or update overwrites it. Do not edit it. Put local hooks in a separate extension file.

## Behaviour

- Active only when `HERDR_ENV=1`, `HERDR_SOCKET_PATH`, and `HERDR_PANE_ID` are set, and only in TUI mode. RPC, JSON, and print modes have no pane for Herdr to show.
- Sends `pane.report_agent_session` on `session_start`, `working` on `agent_start`, and `idle` on `agent_settled`.
- Another extension can mark the pane `blocked` with `pi.events.emit("herdr:blocked", { active: true, label })` and clear it with `{ active: false }`.

The extension is Herdr-owned and does not check process ancestry. The herdr-app leak probe recorded in [the support change record](../../design/log/2026-11-04-herdr-orch-herdr-app-support.org) found no visible effect on the TUI pane named by leaked variables; it did not establish whether Herdr rejected the report or never received it.

There are no slash commands, tools, or keybindings.

## Dependencies

Herdr, which supplies the socket and the environment variables. No npm dependencies.

## Tests

`../test/herdr-agent-state.test.ts` drives the file through a disposable UNIX socket that stands in for Herdr. `herdr-orch/test.sh` runs it.

## Compatibility review

Last review: 2026-10-29, against pi 1.4.0.

- Herdr 0.9.5: `herdr integration status` reports the pi integration as current (v9). Herdr showed this pi 1.4.0 session's pane as `working` while the agent ran.
- Checks: `herdr-agent-state.test.ts`, and `pi --mode rpc` load with empty stderr.
