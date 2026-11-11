# Herdr app personal layout

This layout combines the bundled workspace status with one sidebar list of spaces and their agents, local quota, system load and the selected org-tasks task. The [mod sources](mods/) are the authority for behaviour; the [design record](../design/log/2026-11-05-herdr-app-orch-mod.org) records the accepted contract.

## Install and select

### Without Home Manager

The mods need the dotagents skills at `~/.agents/skills`: the quota mod runs `~/.agents/skills/herdr-orch/scripts/oh` and the task widget runs `~/.agents/skills/org-tasks/scripts/ot`. Link the checkout's `skills/` directory there.

1. From the repository root, link the mods and the layout into the app's folder (`~/.config/herdr-app`, or `$HERDR_APP_HOME`). Move any existing files at these paths aside first.

   ```sh
   app="${HERDR_APP_HOME:-$HOME/.config/herdr-app}"
   mkdir -p "$app/mods" "$app/layouts"
   for m in herdr-orch system org-tasks orch-spaces; do ln -s "$PWD/herdr-app/mods/$m" "$app/mods/$m"; done
   ln -s "$PWD/herdr-app/layouts/herdr-orch.json" "$app/layouts/herdr-orch.json"
   ```

2. Optionally, add the bindings from [keys.darwin.json](keys.darwin.json) (macOS) or [keys.linux.json](keys.linux.json) (Linux) to your own `keys.json`.
3. Restart Herdr app, then explicitly select the `herdr-orch` layout.

### With the owner's Home Manager module

1. Initialise the dotagents submodule and apply the parent dotfiles Home Manager configuration using the normal host procedure. It links only `mods/herdr-orch`, `mods/system`, `mods/org-tasks`, `mods/orch-spaces`, `layouts/herdr-orch.json` and the platform's key table (`keys.darwin.json` or `keys.linux.json`) as `keys.json` into `~/.config/herdr-app`; it rejects foreign occupants at those paths. It does not select a layout. Move any conflicting personal files yourself before applying.
2. In Herdr app, explicitly select the `herdr-orch` layout. The existing layout remains selected until you do this.

To remove the personal layout, select another layout in Herdr app first. Remove the personal leaf declarations and `checkHerdrAppLeaves` guard from the parent `agents.nix`, then reapply the parent configuration using the normal host procedure. Do not remove app-owned directories or the source checkout.

The quota mod requires the home-installed `~/.agents/skills/herdr-orch/scripts/oh` and its runtime prerequisites. It runs `oh usage --format json` from your home directory and reports quota for this computer, not a selected remote machine. When `oh` cannot start and its file is absent, the status shows `oh not installed`, and the popup gives the path and a link to the [install-surfaces reference](../skills/herdr-orch/references/install-surfaces.md). See the [usage contract](../skills/herdr-orch/scripts/docs/contract.md#usage) for provider setup and cache behaviour.

Named Pi Anthropic subscriptions appear as separate `anthropic-<N>` rows (`anthropic-1`, `anthropic-2`, ...) alongside the default `anthropic` row. The footer, popup and visibility preferences use the full local account identity, so hiding one account does not hide its sibling. Quota values and unavailable states remain account-specific. The parser retains explicit `baseProvider` and `accountId` fields; older single-account rows remain supported. Account management stays in Pi; the quota command never refreshes or writes credentials.

## Spaces and agents

The `orch-spaces` block replaces the bundled Spaces and Agents lists. It starts from the `spaces` mod bundled with herdr-app 0.1.10 and puts each space's agents under it ([provenance](mods/orch-spaces/UPSTREAM.md)).

- Tree :: an agent is shown under the terminal that started it while that terminal is live, else under its space. A sub-agent that has its own pane is shown once, under its parent.
- Tabs :: a tab that holds no agent is a row at the root of its space, beside the agents, in tab order. Its name is the tab name (its number when nobody named it). A tab that holds only a sub-agent shows through that sub-agent, under its parent.
- Name :: the `oh.label` that `oh` gives an agent it starts (`planner-1-heavy`, nested `planner-1/scout-2-fable`), else the terminal's name, else the program's name.
- Worktree :: a managed child's agent row shows the `oh worktree list --all --format json` display string (for example `⑂ +3 ~2`) before its status mark. The tooltip adds the branch to the agent name; the row shows counts, not the branch. The mod runs the home skill's `oh` from the main checkout on the existing Git refresh triggers, with no extra timer. A failed listing (including an older home skill without `--all`) clears the worktree state and leaves the tree visible. The mod does not write the ledger or remove or unlock checkouts.
- Click :: an agent with a pane gets focus; one without a pane opens in a peek; a tab row selects that tab.
- Order :: `agent.next` and `agent.prev` follow the list as shown.
- Rail :: the narrow sidebar shows spaces only, with the control that widens it again.

## Task widget

The `org-tasks` block below the tab area shows the selected task of the focused terminal's project: the task, its top-level parent, every STARTED subtask and the next pending one. With no selection it shows the status counts. The rows, their order, emphasis and counts come from `ot compact --format json` ([contract](../skills/org-tasks/scripts/docs/contract.md#ot-compact---width-n)); the mod only picks the project and draws the rows in theme colours.

- Project :: the folder of the focused terminal, an agent's or a shell's, so a tab without an agent still shows its tasks. In a space on another machine, the folder is on that machine, and `ot` runs there. A mod pane, a project without `TASKS.org` or a failed `ot` run shows a line that says so, never another project's tasks. A pane's folder is its foreground process's, so a new folder must hold for two one-second polls before the widget switches.
- Refresh :: on changes to the task files that `ot compact` lists, through `watchFolder` on the project root that `ot` resolved, and every 30 seconds as a fallback. After a failed `ot` run, any change under that root reloads. A first run that fails has no root to watch and waits for the fallback. `watchFolder` sees only this computer, so a project on another machine reloads every 5 seconds instead; a poll is skipped while the previous run has not ended.
- Select :: `org-tasks.select` (or a click on the widget) runs `ot tui` in a detached `hc` terminal inside a dialog, with `hc run --on <machine>` for a space on another machine. `Esc` ends `ot`; the terminal is removed and the dialog closes.
- Toggle :: `layout.toggle:org-tasks` hides or shows the block. The app saves the state with the layout.
- Key hint :: the title row shows the bound keys, right-justified and muted, such as `cmd-shift-t: select / cmd-shift-o: show/hide`, whether or not a task is selected. It uses no row of its own; in a narrow widget the title truncates first. The widget reads the live key table; an unbound action has no hint, and with neither bound the row does not show.
- Size :: the widget resizes its block to the rows it shows with `layout.resize`. Layout props can name another block id: `{"mod": "org-tasks", "props": {"block": "my-tasks"}}`.

The mod runs `~/.agents/skills/org-tasks/scripts/ot` by absolute path, not `ot` on `PATH`: an app started from the desktop session can have a `PATH` without the shell's additions, such as `~/.local/bin`. Layout props can name another executable for this computer: `{"mod": "org-tasks", "props": {"ot": "/path/to/ot"}}`. On another machine, Herdr runs `sh` there, which starts `$HOME/.agents/skills/org-tasks/scripts/ot` in that machine's home; that machine needs the dotagents skills and `bb`. When `ot` cannot start and its file is absent, the widget shows `ot not installed at <path>`, or `<machine>:<path>` for another machine, with a link to the [install-surfaces reference](../skills/herdr-orch/references/install-surfaces.md).

Activation links the platform's key table as `keys.json`. It binds `org-tasks.select` and `layout.toggle:org-tasks`, which match `prefix+t` and `prefix+ctrl+o` in the terminal Herdr plugin:

| Platform | Key table | `org-tasks.select` | `layout.toggle:org-tasks` |
|---|---|---|---|
| macOS | [keys.darwin.json](keys.darwin.json) | `cmd-shift-t` | `cmd-shift-o` |
| Linux | [keys.linux.json](keys.linux.json) | `ctrl-alt-t` | `ctrl-alt-shift-t` |

Linux does not use the `ctrl-shift-` forms of the macOS keys: the app's Linux defaults bind `ctrl-shift-t` to `tab.new` and `ctrl-shift-o` to `pane.split:right`. The `ctrl-alt-` chords do not take keys from terminal programs, such as `M-t` in readline and Emacs. The link is out-of-store, so key changes made in the app appear here for review. The app writes a default `keys.json` whenever the file is missing, so activation moves a plain file at that path aside as `keys.json.pre-hm-<time>` before it links the tracked file; a foreign symlink still fails activation.

## Refresh and limits

Quota refresh starts on display, repeats every 60 seconds, and shares an in-flight request with the Refresh action. A failed request marks the last successful snapshot stale immediately; the popup shows its age and a safe failure category. After five minutes without a successful request, it shows unavailable instead. The app's native command limit is 30 seconds. The collector reads accounts in sequence, so many slow account reads can reach this limit; the UI then reports a failed or timed-out request, not partial numeric evidence.

The system companion supports CPU and RAM on Linux and macOS; Linux GPU data requires NVIDIA. Missing GPU data does not suppress CPU and RAM. System load follows the selected machine; Activity Monitor is available only for the local macOS machine. Windows, AMD and Intel GPU collection are outside scope.

When you save a linked mod's source, the app does not reload the mod. To load a change, update the timestamp of the mod link itself, for example `touch -h ~/.config/herdr-app/mods/herdr-orch`, or restart the app. This is an accepted limit.

## Provenance and updates

The personal layout is based on the shipped `main` layout from herdr-app 0.1.10. The running app executable and the `herdr-app` command both reported 0.1.10 when the layout was copied. The copy does not receive upstream layout changes automatically: compare it with the new shipped `main` after an app upgrade, retain the three-item status row, and validate the result in the app.

The quota UI is adapted from [Lakris2407/herdr-mods quota](https://github.com/Lakris2407/herdr-mods/tree/612d5e8fc8117db8a75f457b2f0ba48f50550626/quota/mods/quota), and the system companion is vendored from [the system mod](https://github.com/Lakris2407/herdr-mods/tree/612d5e8fc8117db8a75f457b2f0ba48f50550626/quota/mods/system), both at revision `612d5e8fc8117db8a75f457b2f0ba48f50550626` under MIT. Their notices are in [quota LICENSE](mods/herdr-orch/LICENSE) and [system LICENSE](mods/system/LICENSE); [system provenance](mods/system/UPSTREAM.md) records its local change.

The `orch-spaces` mod is derived from the `spaces` and `agents` mods bundled with herdr-app 0.1.10 (`herdr-desktop` 0.1.10), under the Apache License 2.0 in [its LICENSE](mods/orch-spaces/LICENSE). [orch-spaces provenance](mods/orch-spaces/UPSTREAM.md) records the SHA-256 of each bundled `main.js` that it starts from, and its local changes.

Run the portable offline suite with `bb scripts/test-herdr-app.bb`. To include the parent wiring integration checks, set the explicit path: `HERDR_APP_WIRING=../agents.nix bb scripts/test-herdr-app.bb`. An invalid path fails the integration checks.

When updating either Lakris2407 mod, compare the pinned revision with the new upstream source, preserve the local quota contract and local macOS action restriction, update provenance and licences as needed, and run `bb scripts/test-herdr-app.bb`. After an app upgrade, compare the SHA-256 of `~/.config/herdr-app/mods/spaces/main.js` and `~/.config/herdr-app/mods/agents/main.js` with the values in [orch-spaces provenance](mods/orch-spaces/UPSTREAM.md). If either changed, review the bundled changes, carry the relevant ones into `orch-spaces`, and record the new app version and hashes. This repository does not keep the 0.1.10 bundled source, so the hashes detect a change but do not supply a diff base. Offline tests do not replace native Linux and macOS validation, including the restart check.
