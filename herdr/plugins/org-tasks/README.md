# Terminal org-tasks plugin

`cormacc.org-tasks` embeds the existing `ot` TUI as a compact, passive bottom split in each tab, and opens the task browser in a popup for selection. It owns context, pane lifecycle and sizing, not Org parsing or task writes. The adapter source is [src/org_tasks_host.clj](src/org_tasks_host.clj); the shared input authority is [../../../skills/org-tasks/scripts/docs/contract.md#Embedded TUI context contract](../../../skills/org-tasks/scripts/docs/contract.md#embedded-tui-context-contract).

## Requirements and installation

Use Herdr 0.9.5 or later on Linux or macOS, with Babashka, Bash and `ps` on the server's PATH. The adapter runs `~/.agents/skills/org-tasks/scripts/ot` when it is executable, so that it uses the same `ot` as the herdr-app task widget; otherwise it runs the co-located `skills/org-tasks/scripts/ot`, so keep the full dotagents checkout. When neither is executable, `serve` and `select-pane` fail with an error that names both paths. No new dependencies are required.

1. Link this plugin separately from the [herdr-orch plugin](../herdr-orch/README.md), from the repository root:

   ```sh
   herdr plugin link "$PWD/herdr/plugins/org-tasks"
   ```

2. Registration persists across server restarts, and no live server restart is needed to link a plugin. Herdr records the resolved checkout path: if you move the checkout, run `herdr plugin unlink cormacc.org-tasks` and link it again, or its actions report "Plugin action not found". The owner's Home Manager module does this for you: it supplies `~/.local/share/herdr-plugins/org-tasks` as a guarded out-of-store leaf and links it when Herdr does not list the plugin or lists a different root. A failure warns and does not abort activation.

3. Optionally copy [config.example.edn](config.example.edn) to `config.edn` under the directory printed by `herdr plugin config-dir cormacc.org-tasks`. `:compact-rows` accepts integers 3--20 and defaults to 5. Unknown keys and invalid values fail before pane creation. There is no workspace-root mode or directory map.

## Use

The compact pane shows in every tab by default. The tracked [../../config.toml](../../config.toml) binds the actions in `[[keys.command]]` stanzas with `type = "plugin_action"`; run `herdr server reload-config` after an edit. Herdr's default bindings are unchanged.

| Key | Action | Effect |
| --- | --- | --- |
| `prefix+t` | `select` | Opens the task browser in a popup for the focused local project. `s` selects without closing, so you can keep browsing; `Esc` closes it, and Herdr returns focus to the earlier pane. |
| `prefix+ctrl+o` | `toggle` | Turns the compact pane off or on in every tab. The setting is global and persists across server restarts. |

```sh
herdr plugin action invoke cormacc.org-tasks.select
herdr plugin action invoke cormacc.org-tasks.toggle
herdr plugin log list --plugin cormacc.org-tasks
```

While display is on, the startup hook and a `tab.created` event hook open the compact pane in each tab that has no live instance. Both run the idempotent `ensure` command. The compact pane never takes focus. `Esc` in a compact pane exits that instance; toggle off and on to reopen it. When every other pane in its tab has closed, such as a collected subagent's, the compact pane closes too, so Herdr closes the tab. Do not invoke the manifest panes directly: `ensure` supplies the private instance and tab identity, and `select` supplies the project directory.

The compact pane shows the selected task or, with no selected task, a status-count line such as `12 tasks: 7 TODO / 2 STARTED / 3 DONE`. Its last line always shows the bound actions, centred and muted, such as `ctrl+b t: select / ctrl+b ctrl+o: show/hide`. The supervisor reads `keys.prefix` (default `ctrl+b`) and the `cormacc.org-tasks.*` bindings from Herdr's `config.toml` (`HERDR_CONFIG_PATH` overrides the path) when the pane starts. An unbound action has no hint.

Herdr draws the manual pane label on the pane border; the manifest title `Org tasks` seeds it. The supervisor copies the TUI's terminal title, `Tasks :: <project folder> :: <plan file>`, into that label with `herdr pane rename`.

Split placement follows the target pane's native geometry; it does not restructure unrelated panes or span the whole window. Compact sizing targets the configured rows at the owned bottom boundary. Herdr's minimum pane size can clamp the request.

## Source and safety

The producer follows the focused pane in the same tab, an agent or a shell, and publishes its foreground folder. A tab without an agent therefore still shows its project. Focusing the task pane or another plugin's pane retains the previous source: a non-agent pane whose folder is a registered plugin root counts as plugin UI. A pane without a usable local folder makes context unavailable. A pane's folder is its foreground process's, so a new folder in the same pane must hold for about one second before the producer publishes it; a focus change publishes at once. SSH and Mosh foreground processes produce the shared explicit remote-unsupported state. A remote directory is never resolved locally. No source is taken from another tab or the plugin checkout. The existing `ot` resolver owns nearest-ancestor task-memory resolution and missing-memory handling; focus does not initialise memory.

Each session has a separate private state directory. Each interface uses a fresh UUID, private `0700` directory, `0600` snapshots and atomic replacement. Heartbeats keep source identity stable; source transitions, including A to B to A, issue a new context identity. The producer polls at 500 ms with bounded host requests and stops on host failure. The consumer's documented lease disables mutations after producer loss. A per-tab OS file lock serialises opens and closes; a pending-open reservation prevents a late supervisor from adopting a superseded instance.

Reuse and controls require the same tab, terminal ID, supervisor PID, process start/command identity and live heartbeat. Stale or foreign panes are not closed, replaced or sent input. A failed producer can leave an old unavailable pane; reopening creates a fresh owned instance rather than touching that pane. Task input goes directly to the TUI, never through adapter-generated keyboard commands. The popup runs standalone `ot tui` in the selected project; task writes go through `ot` there. Pi loading/settings and desktop layout are unchanged.

## Verification

From the repository root:

```sh
bb scripts/test-herdr-org-tasks.bb
bb scripts/test-herdr-plugin.bb
bb test-clojure org-tasks.embedding-test org-tasks.tui-test org-tasks.cli-test org-tasks.launcher-test
```

The first runner supplies offline host fixtures and passes real producer snapshots to the shared consumer. It covers source transitions, lease expiry, ownership, global display toggling, popup selection, key hints, label mirroring, process recovery, supervisor retry after a Herdr request timeout or a source pane that closes mid-sample, closing a compact pane left alone in its tab, configuration and cross-process locking. The quota runner retains its existing assertions. Root `bb test` includes both runners. Native Linux and macOS checks must separately cover startup and new-tab display, toggle, popup selection and focus return, two disposable roots, process exit/reopen and supervisor restart. Do not run native probes against a user's layout or agent processes.
