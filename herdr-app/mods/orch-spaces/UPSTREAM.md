# Upstream source

Derived from the `spaces` and `agents` mods bundled with herdr-app (`herdr-desktop`), under the Apache License 2.0 in `LICENSE`. The fork started from 0.1.10. The bundled changes up to 0.1.15 were reviewed and carried in on 2026-11-11.

| herdr-app | `spaces` `main.js` SHA-256 | `agents` `main.js` SHA-256 |
|---|---|---|
| 0.1.15 (reviewed) | `98d3f3637296f9e8aa8f19ad7b4f4b2e1375144f8e73ad53d90ebc5a438c87b8` | `0f013ba91718309927c009080263c7dd43c878c6ffe0ce7b5eec63b6d9e33eea` |
| 0.1.10 (fork base) | `65f0f8bb48b8e9266266dc0adbd785c6d7372429db5ca9807cea2dfe0b48fefb` | `e4bafc03c6a4b77a8bd5330c36b1ec2c9e734d179f90c231aa859f143d21bda6` |

- `main.js` starts from the bundled `spaces` mod.
- The agent row, status mark and sidebar toggle come from the bundled `agents` mod.

Carried in from 0.1.15 `spaces`: the rail layout (fixed-width cells, a status badge on the machine icon, a tooltip on each machine head), reconnection of a rebuilt machine, the `hc machine login` guidance with a Copy control, `space.new` with an explicit `machine` (an empty string for this computer), ref-only Git reads run in parallel, the sync of the app's space order to the shown order with `space.move`, and drag state that holds space ids, not indexes.

Carried in from 0.1.15 `agents`: its lead-pane choice and tab-title rule (`leadPanes`, `title`), applied to the rows of tabs that hold no agent.

Not carried in from 0.1.15, because orch-spaces does not have the feature: the `agents` mod's pins in the core (`terminal.pin`, `herdr.pinned`), its per-agent titles in a shared tab (orch-spaces names agents by `oh.label`), the removal of its held-done rank, and its click that focuses a listed agent by terminal address. The orch-spaces agent row already focuses an agent's pane, peeks one without a pane, and shows the expand control only on the rail.

Local changes: each space row is followed by its agents and their live sub-agents as one tree (`tree.js`), named by the `oh.label` terminal label, with its tabs that hold no agent as rows beside them; the narrow rail shows spaces only and carries the control that widens the sidebar. Linked-checkout detection compares Git's absolute git directory with its common directory, so submodule worktrees nest under their main checkout; the bundled 0.1.15 mod still compares the common directory with `<toplevel>/.git`. Managed child agent rows show the display string from `oh worktree list --all --format json` before the status mark, matched by terminal address to `pane-id`; their tooltips add the branch. The home skill runs from the main checkout on existing Git refresh triggers, with no new timer; parallel checks of places in one repository share one listing run. Failed listings clear the worktree state without hiding the tree. The mod does not write the ledger or remove or unlock checkouts.
