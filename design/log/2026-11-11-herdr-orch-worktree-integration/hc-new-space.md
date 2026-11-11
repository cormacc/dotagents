# hc new-space test (task 05686844, 2026-11-11)

herdr-app 0.1.15 (`/nix/store/315f77xx611a1zgkgzrcckyllp9hdcl3-herdr-app-0.1.15`), `hc 0.1.15`. The test ran in the user's running app with the user present. The app was not restarted or reconfigured. The caller was the pi agent in terminal `t3` in the "agents" space (`herdr.space=sp9e5571646029ef64fff55b417ad3805c`). This repository is a submodule of dotfiles.

## Commands

```shell
git worktree add --detach .tmp/hc-new-space/checkout HEAD
hc run --json --bg --no-notify --outlive-parent --name hc-ns-test \
  --cwd /Users/cormacc/dotfiles/agents/.tmp/hc-new-space/checkout --parent t3 \
  --label herdr.space=spcf1a7428c19b44acba2ac959dbc77661 \
  --label herdr.tab=tb5fe3243604104edfa7c69fe63a47d04e \
  --label oh.label=hc-ns-test -- pi
hc get t3c --json
```

The `herdr.space` and `herdr.tab` values were new ids in the app's `sp`/`tb` + 32-hex form. `place!` and `launch!` in `skills/herdr-orch/scripts/src/herdr_orch/hc.clj` pass the same labels for tab placement, with the parent's space id.

## Results

- New space :: yes. `hc run` returned terminal `t3c` with `pi` idle. The app added the label `where: "checkout / 1"`, and the user saw a new space called "checkout".
- Nesting :: no. The user saw "checkout" as a top-level space with the right-justified suffix "agents (detached)". The "agents" space row had no suffix. The mod's Git commands give the same common directory (`/Users/cormacc/dotfiles/.git/modules/agents`) from the main checkout and from the test checkout, with the main checkout not linked and the test checkout linked, so the orch-spaces `ordered()` rule should have nested it. The cause was not found. A candidate that was not tested: the bundled `worktree` mod passes `machine: this.on ?? ""` to `space.new`, and orch-spaces keys a repository by `[machine, common dir]`, so a space adopted from labels can have a different `machine` value.
- Agent row :: under the parent's "agents" space, not under "checkout". orch-spaces builds the agent tree from the hc `--parent` link.

## Decision

Keep the refusal of `--workspace` under the hc runtime. Under hc, a new space adds a top-level row, does not nest, and does not move the child's agent row. It also exposes the child to herdr-app's `Remove worktree`, which calls `space.close` before Git refuses a locked removal. The user chose to avoid complexity for display only. A backlog task covers a tree view of the current delegation through the `oh` CLI, or better names in the Herdr views. No investigation of the nesting failure is planned.

## Cleanup

`hc kill t3c` and `hc rm t3c` exited 0, and `hc ls --all` no longer lists `t3c`. `git worktree remove .tmp/hc-new-space/checkout` exited 0. The user confirmed that the "checkout" space left the sidebar.
