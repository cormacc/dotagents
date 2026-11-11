# Placement spike (planning, 2026-11-11)

Herdr 0.9.5, disposable session `planner-spike-e919` (stopped and deleted afterwards). Scratch repositories under `.tmp/planner-spike-e919/` (`plain`, and `super` with submodule `sub`), each with `git worktree add` checkouts under `<repo>/.tmp/herdr-orch/worktrees/<name>` on `orch/<name>`. The scratch output under `.tmp/` is not retained; this file is the record.

## Plain repository

- `worktree open --workspace <src> --path <repo>/.tmp/herdr-orch/worktrees/aaaa --label oh-aaaa --no-focus`: exit 0, `worktree_opened`, `already_open: false`, new workspace with the given label, root pane `cwd` = checkout, `workspace.worktree` = `{checkout_path, is_linked_worktree: true, repo_key: <repo>/.git}`. The source workspace gained `worktree` provenance with `is_linked_worktree: false` and the same `repo_key`.
- `worktree list` then reports `open_workspace_id` for both.
- `workspace close <parent>` without `--group`: exit 1, `workspace_group_close_required`.
- `worktree remove --workspace <child>` on a checkout with an untracked file: exit 1, `dirty_worktree_requires_force`; checkout and workspace kept.
- `workspace close <child>`: exit 0; checkout and Git registration kept.
- `workspace create --cwd <checkout>`: workspace has `worktree: null`. `worktree remove --workspace` on it: `not_linked_worktree`. A later `worktree open --path <checkout>` returns `already_open: true` for that workspace and gives it provenance.
- A tab in the parent workspace with `cwd` = checkout does not count as open: `worktree open --path` made a new workspace (`already_open: false`).
- `worktree remove --workspace <child>` on a clean checkout: exit 0, `worktree_removed`, `forced: false`; checkout directory gone, workspace closed, branch kept.

## Submodule repository

- `worktree list --workspace <sub main>`: main row `path` = `<super>/.git/modules/sub`, `is_linked_worktree: true`, `open_workspace_id: null` (also after the workspace gained provenance). The `oh`-style checkout row is correct.
- `worktree open --path <checkout>`: exit 0. Workspace provenance is correct: parent `checkout_path` = `<super>/sub`, `is_linked_worktree: false`; child `is_linked_worktree: true`; same `repo_key` (`<super>/.git/modules/sub`). `workspace close <parent>` requires `--group`.
- `worktree open --workspace <sub main> --branch main`: `worktree_not_found` ("worktree checkout changed during discovery; retry the request").
- `git rev-parse --absolute-git-dir` equals `--git-common-dir` in the submodule main checkout.

## Not observed

- TUI rendering of the group (no client attached).
- A running process in the child workspace during `worktree remove` or `workspace close --group`.
- herdr-app behaviour. The bundled herdr-app 0.1.10 `menu` and `worktree` mods were read, not run: `menu` `checkout()` returns `"linked"` when the common dir differs from `<top>/.git` (wrong for a submodule main checkout) and then offers `Remove worktree`; `worktree` `remove()` closes the space, then runs `git worktree remove` with one `--force` when the checkout is dirty or has submodules.

## Second session (`planner-spike2-e919`, stopped and deleted): lock and create-then-open

Scratch repository `.tmp/planner-spike2-e919/r`, checkouts locked with `git worktree lock --reason`.

- `workspace create --cwd <checkout> --label oh-aaaa --env ORCH_PROBE=yes --no-focus`, then `worktree open --workspace <src> --path <checkout> --no-focus`: `already_open: true`, same workspace, label kept, `is_linked_worktree: true`, same `repo_key` as the source workspace (which shows `is_linked_worktree: false`).
- The root pane's shell has `ORCH_PROBE=yes`.
- `worktree remove --workspace <child>` on the locked checkout: exit 1, `worktree_remove_failed`, message carries the lock reason. With `--force`: the same refusal. Checkout and workspace remain.
- `pane close` on the workspace's only pane: exit 0, workspace gone, checkout kept.
- Git 2.56.0: `git worktree remove` and `remove --force` both exit 128 on a locked entry; `git worktree prune` skips a locked entry whose directory is gone; `unlock` then `prune` clears it.
