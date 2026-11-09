# herdr-orch Herdr plugin

Herdr plugin `cormacc.herdr-orch`. It is an adapter over the `herdr-orch` skill in the same checkout: every entrypoint runs `../../../skills/herdr-orch/scripts/oh`, resolved from `HERDR_PLUGIN_ROOT`. The quota pane and status stanza contain no orchestration logic. The sidebar tree reads the `oh` ledgers, as described below.

The separate [org-tasks plugin](../org-tasks/README.md) embeds the shared task TUI in a tab-local bottom split. It has its own registration and configuration.

## Prerequisites

- Herdr 0.9.5 or later, on Linux or macOS.
- Babashka (`bb`) and Bash on the `PATH` of the Herdr server.
- For quota data: valid Anthropic or OpenAI Codex subscription credentials in Pi's file store (`PI_CODING_AGENT_DIR` or `~/.pi/agent`). The owned Babashka collector needs no npm package. It does not refresh, prompt or fall back to native credentials. Explicit native reporting and unsupported OS stores are described in [../../../skills/herdr-orch/scripts/docs/contract.md#Usage](../../../skills/herdr-orch/scripts/docs/contract.md#usage).

## Install

From a local checkout:

```sh
herdr plugin link "$PWD/herdr/plugins/herdr-orch"
```

From GitHub:

```sh
herdr plugin install cormacc/dotagents/herdr/plugins/herdr-orch
```

## Quota pane

```sh
herdr plugin pane open --plugin cormacc.herdr-orch --entrypoint quota
```

Herdr has no default key for a plugin pane. To bind one, add a custom command to `config.toml`; `type = "shell"` runs the CLI detached, because a `keys.command` cannot open a plugin pane directly:

```toml
[[keys.command]]
key = "prefix+u"
type = "shell"
command = "\"$HERDR_BIN_PATH\" plugin pane open --plugin cormacc.herdr-orch --entrypoint quota"
description = "subscription quota"
```

The popup shows `oh usage --format text` and refreshes every 60 s. Press `q` to close it, or any other key to refresh. It runs from `$HOME`, so it reads the packaged and home herdr-orch config layers, not a project layer.

## Tab-bar status

Herdr plugin v1 cannot add tab-bar or footer items. The status line is a `[ui] tab_bar_right` command entry in Herdr's own config that runs `oh usage --format status`, for example `anthropic 4%s 64%w | openai-codex 12%w`. Each number is the percentage of the window already used, as the vendors' usage pages show it. The suffix gives the window kind: `s` session, `w` weekly, `m` monthly; a window of unknown kind shows its length instead, for example `30d` or `5h`; `~` marks stale data. `auth?`, `rl?` and `err?` mark a provider without usable data, `?` a provider whose data has no window that the line can show.

To get the entry for this checkout:

```sh
herdr plugin action invoke cormacc.herdr-orch.quota-stanza
herdr plugin log list --plugin cormacc.herdr-orch
```

The action only prints the stanza, with a 30 s command timeout. Add it to the `[ui]` table of `~/.config/herdr/config.toml`, then run:

```sh
herdr config check
herdr server reload-config
```

Herdr runs the command with the active pane's working directory, so a project's `.agents/subagents/config.edn` decides which providers appear. To show the tab row, and so the status line, as a footer, set `tab_bar_position = "bottom"` in the same `[ui]` table.

## Sidebar agent tree

The plugin decorates Herdr's native Agents sidebar with the `oh` agent tree. Under each space, every live `oh` child appears after its parent, named by a tree mark (`├`, `└`, `│`) and the last segment of its ledger `label`, for example `└ scout-1-light`. The mark is part of the name, not a separate token, because Herdr draws a `·` separator between row tokens. Every other agent on the local server is a root: it has no mark, and its name is `local:<workspace label>:<agent>`, for example `local:agents:pi`. The agent part is the ledger label of a root `oh` agent, else the Herdr agent name, else the agent kind. A workspace without a label shows its id. `herdr agent list` output is unchanged, and next/previous agent navigation follows the displayed order.

How it works:

- Hierarchy comes from the ledgers `.tmp/herdr-orch/ledger/*.json` under the git root of each agent's working directory. A child is joined to its parent by session (`parent-session` and `child-session`), across every discovered root, so a child in a managed worktree sits under its parent. Liveness comes from `herdr agent list`: an agent whose parent is not listed is shown as a root, and ledger `closed-at` is not read.
- The reconcile (`bb tree.bb reconcile`) reads workspace labels from `herdr workspace list`. It reports `display_agent` for every agent, and an `order` token for each agent in an `oh` tree, with `herdr pane report-metadata`, and sets the session's agent view with `agent.view.set` (source `plugin:cormacc.herdr-orch`; sort by workspace, then `order`, then pane order).
- It runs once from the startup hook and from the `pane.created`, `pane.closed`, `pane.agent_detected`, `pane.agent_status_changed`, `tab.closed`, `workspace.closed` and `workspace.renamed` hooks. It is one-shot: no daemon and no polling. Concurrent runs serialise on a file lock under the plugin state directory.
- It writes no ledger and sends no pane input.

Add the rows stanza to `~/.config/herdr/config.toml` (the tracked [../../config.toml](../../config.toml) has it):

```toml
[ui.sidebar.agents]
rows = [["state_icon", "agent"]]
```

`herdr server reload-config` does not apply `[ui.sidebar.agents]` to an open client. Use the `reload config` item of the UI menu, or restart the client.

### Toggle

`prefix+ctrl+t` runs the `tree-toggle` action. It turns the tree off (tokens, display names and the plugin view are cleared, and the hooks then do nothing) and on again (rows and view are rebuilt). The invoking session changes at once; other sessions follow at their next hook. The setting is global and persists across server restarts. Bind it in `config.toml`:

```toml
[[keys.command]]
key = "prefix+ctrl+t"
type = "plugin_action"
command = "cormacc.herdr-orch.tree-toggle"
description = "toggle the sidebar agent tree"
```

Display toggles use the `prefix+ctrl+<letter>` convention; the org-tasks overlay toggle is `prefix+ctrl+o`. Without a key binding, run `herdr plugin action invoke cormacc.herdr-orch.tree-toggle`.

### Limits

- Plugin v1 cannot add sidebar rows, so a tab without an agent has no tree row.
- Local server only: remote (`--machine`) servers are not reconciled, so a remote agent keeps Herdr's name without the `<machine>:<workspace>:` prefix. The machine part is always `local`.
- Herdr does not restore token metadata after a server restart. The startup hook reports it again.
- `agent.view.set` replaces `ui.agent_panel_sort` for the session while the tree is on. The sort starts with `workspace_order`, so spaces stay grouped.

## Test

```sh
bb scripts/test-herdr-plugin.bb
bb scripts/test-herdr-orch-tree.bb
```

The root `bb test` task also runs both.
