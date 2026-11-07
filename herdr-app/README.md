# Herdr app personal layout

This layout combines the bundled workspace status with local quota, system load and the selected org-tasks task. The [mod sources](mods/) are the authority for behaviour; the [design record](../design/log/2026-11-05-herdr-app-orch-mod.org) records the accepted contract.

## Install and select

1. Initialise the dotagents submodule and apply the parent dotfiles Home Manager configuration using the normal host procedure. It links only `mods/herdr-orch`, `mods/system`, `mods/org-tasks` and `layouts/herdr-orch.json` into `~/.config/herdr-app`; it rejects foreign occupants at those paths. It does not select a layout. Move any conflicting personal files yourself before applying.
2. In Herdr app, explicitly select the `herdr-orch` layout. The existing layout remains selected until you do this.

To remove the personal layout, select another layout in Herdr app first. Remove the four personal leaf declarations and `checkHerdrAppLeaves` guard from the parent `agents.nix`, then reapply the parent configuration using the normal host procedure. Do not remove app-owned directories or the source checkout.

The quota mod requires the home-installed `~/.agents/skills/herdr-orch/scripts/oh` and its runtime prerequisites. It runs `oh usage --format json` from your home directory and reports quota for this computer, not a selected remote machine. See the [usage contract](../skills/herdr-orch/scripts/docs/contract.md#usage) for provider setup and cache behaviour.

Named Pi Anthropic subscriptions appear as separate `anthropic-<N>` rows (`anthropic-1`, `anthropic-2`, ...) alongside the default `anthropic` row. The footer, popup and visibility preferences use the full local account identity, so hiding one account does not hide its sibling. Quota values and unavailable states remain account-specific. The parser retains explicit `baseProvider` and `accountId` fields; older single-account rows remain supported. Account management stays in Pi; the quota command never refreshes or writes credentials.

## Task widget

The `org-tasks` block below the tab area shows the selected task of the focused agent's project: the task, its top-level parent, every STARTED subtask and the next pending one. With no selection it shows the status counts and the key hints. The rows, their order, emphasis and counts come from `ot compact --format json` ([contract](../skills/org-tasks/scripts/docs/contract.md#ot-compact---width-n)); the mod only picks the project and draws the rows in theme colours.

- Project :: the folder of the focused pane when that pane runs a local agent. A shell pane, a space on another machine, a project without `TASKS.org` or a failed `ot` run shows a line that says so, never another project's tasks. A pane's folder is its foreground process's, so a new folder must hold for two one-second polls before the widget switches.
- Refresh :: on changes to the task files that `ot compact` lists, through `watchFolder` on the project folder, and every 30 seconds as a fallback.
- Select :: `org-tasks.select` (or a click on the widget) runs `ot tui` in a detached `hc` terminal inside a dialog. `Esc` ends `ot`; the terminal is removed and the dialog closes.
- Toggle :: `layout.toggle:org-tasks` hides or shows the block. The app saves the state with the layout.
- Size :: the widget resizes its block to the rows it shows with `layout.resize`. Layout props can name another block id: `{"mod": "org-tasks", "props": {"block": "my-tasks"}}`.

`ot` must be on the app's `PATH`; the parent configuration installs `~/.local/bin/ot`. Layout props can name another executable: `{"mod": "org-tasks", "props": {"ot": "/path/to/ot"}}`.

The repository does not manage `keys.json`. Add these lines to its `global` section; on Linux, use the `ctrl-shift-` forms if `cmd` is not available:

```json
"cmd-shift-t": "org-tasks.select",
"cmd-shift-o": "layout.toggle:org-tasks"
```

They match `prefix t` and `prefix O` in the terminal Herdr plugin. The app's linked mod folder does not hot-reload edits to its source; restart the app, or remove and re-create the link, to load a change.

## Refresh and limits

Quota refresh starts on display, repeats every 60 seconds, and shares an in-flight request with the Refresh action. A failed request marks the last successful snapshot stale immediately; the popup shows its age and a safe failure category. After five minutes without a successful request, it shows unavailable instead. The app's native command limit is 30 seconds. The collector reads accounts in sequence, so many slow account reads can reach this limit; the UI then reports a failed or timed-out request, not partial numeric evidence.

The system companion supports CPU and RAM on Linux and macOS; Linux GPU data requires NVIDIA. Missing GPU data does not suppress CPU and RAM. System load follows the selected machine; Activity Monitor is available only for the local macOS machine. Windows, AMD and Intel GPU collection are outside scope.

## Provenance and updates

The personal layout is based on the shipped `main` layout from herdr-app 0.1.10. The running app executable and the `herdr-app` command both reported 0.1.10 when the layout was copied. The copy does not receive upstream layout changes automatically: compare it with the new shipped `main` after an app upgrade, retain the three-item status row, and validate the result in the app.

The quota UI is adapted from [Lakris2407/herdr-mods quota](https://github.com/Lakris2407/herdr-mods/tree/612d5e8fc8117db8a75f457b2f0ba48f50550626/quota/mods/quota), and the system companion is vendored from [the system mod](https://github.com/Lakris2407/herdr-mods/tree/612d5e8fc8117db8a75f457b2f0ba48f50550626/quota/mods/system), both at revision `612d5e8fc8117db8a75f457b2f0ba48f50550626` under MIT. Their notices are in [quota LICENSE](mods/herdr-orch/LICENSE) and [system LICENSE](mods/system/LICENSE); [system provenance](mods/system/UPSTREAM.md) records its local change.

Run the portable offline suite with `bb scripts/test-herdr-app.bb`. To include the parent wiring integration checks, set the explicit path: `HERDR_APP_WIRING=../agents.nix bb scripts/test-herdr-app.bb`. An invalid path fails the integration checks.

When updating either upstream mod, compare the pinned revision with the new upstream source, preserve the local quota contract and local macOS action restriction, update provenance and licences as needed, and run `bb scripts/test-herdr-app.bb`. Offline tests do not replace native Linux and macOS validation, including restart and linked-source reload checks.
