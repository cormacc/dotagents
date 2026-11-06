# Terminal org-tasks plugin

`cormacc.org-tasks` embeds the existing `ot` TUI in a tab-local bottom split. It owns context, pane lifecycle, sizing and focus, not Org parsing or task writes. The adapter source is [src/org_tasks_host.clj](src/org_tasks_host.clj); the shared input authority is [../../skills/org-tasks/scripts/docs/contract.md#Embedded TUI context contract](../../skills/org-tasks/scripts/docs/contract.md#embedded-tui-context-contract).

## Requirements and installation

Use Herdr 0.9.5 or later on Linux or macOS, with Babashka, Bash and `ps` on the server's PATH. Keep the full dotagents checkout: the adapter runs its co-located `skills/org-tasks/scripts/ot`. No new dependencies are required.

1. Link this plugin separately from the quota plugin, from the repository root:

   ```sh
   herdr plugin link "$PWD/herdr-plugin/org-tasks"
   ```

2. The owner's Home Manager module supplies only `~/.local/share/herdr-plugins/org-tasks` as a guarded out-of-store leaf. After an explicitly requested activation, register it with `herdr plugin link "$HOME/.local/share/herdr-plugins/org-tasks"`. Foreign occupants are rejected before activation writes. Registration persists across server restarts; no live server restart is needed to link a plugin. This does not replace the Herdr directory, plugin registry or quota configuration.

3. Optionally copy [config.example.edn](config.example.edn) to `config.edn` under the directory printed by `herdr plugin config-dir cormacc.org-tasks`. `:compact-rows` accepts integers 3--20; `:expanded-fraction` accepts numbers 0.3--0.9. Defaults are 6 rows and 0.65. Unknown keys and invalid values fail before pane creation. There is no workspace-root mode or directory map.

## Actions

```sh
herdr plugin action invoke cormacc.org-tasks.open
herdr plugin action invoke cormacc.org-tasks.expand
herdr plugin action invoke cormacc.org-tasks.collapse
herdr plugin action invoke cormacc.org-tasks.close
herdr plugin log list --plugin cormacc.org-tasks
```

`open` creates or reuses this tab's owned pane and requests compact mode without taking focus. `expand` requires an open interface and focuses it. `collapse` retains compact context and restores the source agent's focus when that same source terminal still exists. `close` closes only a verified live instance. `Esc` exits the TUI; reopening creates a new instance. Do not invoke the manifest pane directly: the `open` action supplies its private instance and source-tab identity.

A key can invoke an action without changing the existing quota binding. Choose an unused key before adding this optional stanza to Herdr configuration:

```toml
[[keys.command]]
key = "prefix+t"
type = "plugin_action"
command = "cormacc.org-tasks.open"
description = "compact task context"
```

Split placement follows the focused pane's native geometry; it does not restructure unrelated panes or span the whole window. Compact sizing targets the configured rows at the owned bottom boundary; expanded sizing uses a fraction of that split's height. Herdr's minimum pane size can clamp either request. Reinvoke the action after an external layout change to reapply sizing.

## Source and safety

The producer follows the focused eligible local agent in the same tab. Focusing the task pane retains that source, but focusing a non-agent pane, losing the agent or losing its foreground process makes context unavailable. Eligibility checks the host's recognised agent and its foreground executable name or `argv0`; a retained label alone is insufficient. SSH and Mosh foreground processes produce the shared explicit remote-unsupported state. A remote directory is never resolved locally. No source is taken from another tab or the plugin checkout. The existing `ot` resolver owns nearest-ancestor task-memory resolution and missing-memory handling; focus does not initialise memory.

Each session has a separate private state directory. Each interface uses a fresh UUID, private `0700` directory, `0600` snapshots and atomic replacement. Heartbeats keep source identity stable; source transitions, including A to B to A, issue a new context identity. The producer polls at 500 ms with bounded host requests and stops on host failure. The consumer's documented lease disables mutations after producer loss. A per-tab OS file lock serialises opens and mode changes; a pending-open reservation prevents a late supervisor from adopting a superseded instance.

Reuse and controls require the same tab, terminal ID, supervisor PID, process start/command identity and live heartbeat. Stale or foreign panes are not closed, replaced or sent input. A failed producer can leave an old unavailable pane; reopening creates a fresh owned instance rather than touching that pane. Task input goes directly to the TUI, never through adapter-generated keyboard commands. Forms, pending root switches, refresh and mutation validation remain owned by the shared consumer. Pi loading/settings and desktop layout are unchanged.

## Verification

From the repository root:

```sh
bb scripts/test-herdr-org-tasks.bb
bb scripts/test-herdr-plugin.bb
bb test-clojure org-tasks.embedding-test org-tasks.tui-test org-tasks.cli-test org-tasks.launcher-test
```

The first runner supplies offline host fixtures and passes real producer snapshots to the shared consumer. It covers source transitions, lease expiry, ownership, mode/focus controls, process recovery, configuration and cross-process locking. The quota runner retains its existing assertions. Root `bb test` includes both runners. Native Linux and macOS checks must separately cover open/reuse, resize/focus, two disposable roots and mutation, close, process exit/reopen and supervisor restart. Do not run native probes against a user's layout or agent processes.
