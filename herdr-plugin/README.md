# herdr-orch Herdr plugin

Herdr plugin `cormacc.herdr-orch`. It is an adapter over the `herdr-orch` skill in the same checkout: every entrypoint runs `../skills/herdr-orch/scripts/oh`, resolved from `HERDR_PLUGIN_ROOT`. The plugin contains no orchestration logic.

## Prerequisites

- Herdr 0.9.1 or later, on Linux or macOS.
- Babashka (`bb`) and Bash on the `PATH` of the Herdr server.
- For quota data: valid Anthropic or OpenAI Codex subscription credentials in Pi's file store (`PI_CODING_AGENT_DIR` or `~/.pi/agent`). The owned Babashka collector needs no npm package. It does not refresh, prompt or fall back to native credentials. Explicit native reporting and unsupported OS stores are described in [../skills/herdr-orch/scripts/docs/contract.md#Usage](../skills/herdr-orch/scripts/docs/contract.md#usage).

## Install

From a local checkout:

```sh
herdr plugin link "$PWD/herdr-plugin"
```

From GitHub:

```sh
herdr plugin install cormacc/dotagents/herdr-plugin
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

## Test

```sh
bb scripts/test-herdr-plugin.bb
```

The root `bb test` task also runs it.
