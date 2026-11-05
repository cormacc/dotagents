# herdr-orch Herdr plugin

Herdr plugin `cormacc.herdr-orch`. It is an adapter over the `herdr-orch` skill in the same checkout: every entrypoint runs `../skills/herdr-orch/scripts/oh`, resolved from `HERDR_PLUGIN_ROOT`. The plugin contains no orchestration logic.

## Prerequisites

- Herdr 0.9.1 or later, on Linux or macOS.
- Babashka (`bb`) and Bash on the `PATH` of the Herdr server.
- For quota data: `quota-axi` 0.1.69 and Node 22.19 or later. Install quota-axi explicitly with `skills/herdr-orch/scripts/quota-axi-setup install`; neither the plugin nor `oh` installs it. Sign in to each provider's native CLI. On macOS, grant Keychain access once with `quota-axi --provider claude --allow-keychain-prompt` and choose "Always Allow".

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

The popup shows `oh usage --format text` and refreshes every 60 s. Press `q` to close it, or any other key to refresh. It runs from `$HOME`, so it reads the packaged and home herdr-orch config layers, not a project layer.

## Tab-bar status

Herdr plugin v1 cannot add tab-bar or footer items. The status line is a `[ui] tab_bar_right` command entry in Herdr's own config that runs `oh usage --format status`, for example `anthropic 68%s 39%w | openai-codex 88%w`. The suffix gives the window kind: `s` session, `w` weekly, `m` monthly; `~` marks stale data. `auth?`, `rl?` and `err?` mark a provider without usable data, `?` a provider whose data has no session, weekly or monthly window, and `quota: setup?` means that quota-axi is missing or is the wrong version.

To get the entry for this checkout:

```sh
herdr plugin action invoke cormacc.herdr-orch.quota-stanza
herdr plugin log list --plugin cormacc.herdr-orch
```

The action only prints the stanza. Add it to the `[ui]` table of `~/.config/herdr/config.toml`, then run:

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
