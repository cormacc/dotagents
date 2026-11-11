# hc new-space test (task 05686844, 2026-11-11)

herdr-app 0.1.15 (`/nix/store/315f77xx611a1zgkgzrcckyllp9hdcl3-herdr-app-0.1.15`), `hc 0.1.15`. Both runs used the user's running app with the user present; the app was not restarted. The caller was the pi agent in terminal `t3` in the "agents" space. This repository is a submodule of dotfiles.

## Commands

```shell
git worktree add --detach .tmp/<dir>/<checkout> HEAD
hc run --json --bg --no-notify --outlive-parent --name <name> \
  --cwd <checkout> --parent t3 \
  --label herdr.space=sp<32 hex, new> --label herdr.tab=tb<32 hex, new> \
  --label oh.label=<name> -- pi
hc get <name> --json
```

`place!` and `launch!` in `skills/herdr-orch/scripts/src/herdr_orch/hc.clj` pass the same labels for tab placement, with the parent's space id.

## Run 1 (20:41, stale mod)

- New space :: yes. `hc run` returned `t3c` with `pi` idle; the app added the label `where: "checkout / 1"`, and a space called "checkout" appeared.
- Nesting :: no. "checkout" was top-level, with the suffix "agents (detached)".
- Agent row :: under the parent's "agents" space, because orch-spaces builds the agent tree from the hc `--parent` link.

## Run 2 (21:20, reloaded mod)

The app process started at 14:13:29 and the submodule fix (`9534e37`) was committed at 14:13:58. herdr-app does not reload a mod when its source changes (`herdr-app/README.md`), so run 1 used an orch-spaces build without the fix. `touch -h ~/.config/herdr-app/mods/orch-spaces` reloaded the mod at 21:20:29. Then:

- A checkout opened through the bundled `Open worktree` menu action (`space.new`) nested under "agents".
- A second `hc run` space (`t3h`, "hc-check") also nested under "agents", and its agent row stayed under its parent `pi` row.

So label-created spaces nest like app-created ones; the `machine` key hypothesis of run 1 is refuted. orch-spaces draws nested worktree spaces after the main space's agent and tab rows, so a nested space can look like a child of the last tab row.

## Decision

No workspace placement under the hc runtime. A new space only adds a row: the child's agent row stays under its parent's space. It also exposes the child to herdr-app's `Remove worktree`, which calls `space.close` before Git refuses a locked removal. The user chose to avoid complexity for display only, and a later review removed `--workspace` from the Herdr TUI runtime too. Task 166a41b8 covers the delegation tree view.

## Cleanup

`hc kill` and `hc rm` exited 0 for `t3c`, `t3g` (the app's shell in the opened checkout) and `t3h`, and `hc ls --all` no longer lists them. `git worktree remove` exited 0 for each checkout. The user confirmed after each run that its spaces left the sidebar.
