# Submodule main checkout is misclassified by worktree list and cannot be opened by branch

## Summary

In a submodule repository, `worktree list` reports the main checkout as linked and fails to associate it with its open workspace. `worktree open --branch main` then fails. The row's metadata-directory `path` comes from Git itself; the defects are Herdr's classification, workspace association and branch-open behaviour, not the provenance of that path.

## Environment

Reproduced on 2026-11-11 with Herdr 0.9.5 (`herdr --version`; disposable server and client both report 0.9.5 in `status --json`) and Git 2.56.0 (`git --version`), on Linux. No remote repository is needed.

## Reproduction

Run these Bash commands from an empty directory. Use a fresh session name; never use `default`. Dependencies: Git, Herdr, `timeout` and `jq`. Each Herdr call is bounded. Successful API envelopes are checked for `error` before their `result` fields are read.

```bash
set -eu
base=$PWD
session=upstream-submodule-6abee-draft
export GIT_TERMINAL_PROMPT=0
export GIT_AUTHOR_NAME=Repro GIT_AUTHOR_EMAIL=repro@example.invalid
export GIT_COMMITTER_NAME=Repro GIT_COMMITTER_EMAIL=repro@example.invalid

git init -b main sub-origin
printf '%s\n' reproduction > sub-origin/readme
git -C sub-origin add readme
git -C sub-origin -c commit.gpgsign=false commit -m Initial
git init -b main super
git -C super -c protocol.file.allow=always submodule add "$base/sub-origin" sub
git -C super -c commit.gpgsign=false commit -am 'Add submodule'
git -C super/sub worktree add -b child "$base/child"
git -C super/sub worktree list --porcelain
git -C super/sub rev-parse --show-toplevel

timeout 10 herdr --session "$session" session list --json > sessions-before.json
cleanup() {
  timeout 15 herdr --session "$session" session stop "$session" --json > stop.json
  jq -e '.error == null and .stopped == true' stop.json
  timeout 15 herdr --session "$session" session delete "$session" --json > delete.json
  jq -e '.error == null and .deleted == true' delete.json
  timeout 10 herdr --session "$session" session list --json > sessions-after.json
  diff sessions-before.json sessions-after.json
}
trap cleanup EXIT
# Clear herdr-app routing variables, so this starts the named TUI server.
timeout 90 env -u HC_TERMINAL_ID -u HC_SCOPE -u HC_TERMINAL_SOCKET \
  herdr --session "$session" server > server.log 2>&1 &
sleep 2
timeout 15 herdr --session "$session" workspace create \
  --cwd "$base/super/sub" --label sub-main --no-focus > create.json
jq -e '.error == null' create.json
workspace=$(jq -er '.result.workspace.workspace_id' create.json)
timeout 15 herdr --session "$session" worktree open \
  --workspace "$workspace" --path "$base/child" --no-focus > open.json
jq -e '.error == null' open.json
timeout 15 herdr --session "$session" worktree list --workspace "$workspace" > list.json
jq -e '.error == null' list.json
jq '.result.worktrees' list.json
timeout 15 herdr --session "$session" workspace list > workspaces.json
jq -e '.error == null' workspaces.json
jq '.result.workspaces' workspaces.json
set +e
timeout 15 herdr --session "$session" worktree open \
  --workspace "$workspace" --branch main --no-focus > branch.json 2>&1
code=$?
set -e
test "$code" -eq 1
jq -e '.error.code == "worktree_not_found"' branch.json
```

The trap stops and deletes only the disposable session and compares the session inventory with its starting state. The reproduction was also run inside a bounded investigation with cleanup outside the overall timeout.

## Observed

These are CLI observations from a fresh local superproject, submodule and child checkout. In this run the main workspace was `w1` and the child was `w2`; IDs are not fixed API values.

| Item | Actual | Expected |
|---|---|---|
| Main row `is_linked_worktree` | `true` | `false` |
| Main row `open_workspace_id` | `null` when queried with jq; the key is omitted in raw JSON | Main workspace ID, `w1` in this run |
| Main row `path` | `<base>/super/.git/modules/sub`, also Git's first porcelain entry | Git's raw path can be retained, but resolve the checkout as `<base>/super/sub` for classification, matching and opening |
| Child row | `is_linked_worktree: true`, `open_workspace_id: "w2"`, path `<base>/child` | Same |
| `worktree open --branch main` | Exit 1, `worktree_not_found`, message `worktree checkout changed during discovery; retry the request` | Select the already-open main workspace |
| `workspace list` provenance | Main `checkout_path` is `<base>/super/sub`, linked `false`; child linked `true`; both use `repo_key` `<base>/super/.git/modules/sub` | Same |

Git's `worktree list --porcelain` prints `<base>/super/.git/modules/sub` as the first (main) worktree entry, followed by the linked child. `git -C super/sub rev-parse --show-toplevel` prints the actual checkout, `<base>/super/sub`. The same Git path behaviour was rechecked in a real submodule checkout, not only in the disposable fixture.

## Proposed fix

Resolve the main checkout independently of the raw porcelain path. In the main checkout, `git rev-parse --show-toplevel` supplies its path. Use the resolved checkout path when deriving `is_linked_worktree`, matching `open_workspace_id` and validating a branch-open target. Do not treat a metadata-directory path inherited from Git as proof that the entry is linked. Preserve linked-child handling and repository grouping.

A regression test should create an absorbed submodule plus a linked checkout, open both workspaces, and check the main row and `worktree open --branch main`. Include a plain repository as a control.

## Context

This does not appear to be a workspace-provenance or TUI-grouping defect: the fresh CLI run gives correct `workspace list` provenance for both workspaces. Separately, in an earlier user-observed Herdr 0.9.5 TUI session in a real submodule repository, the child space was grouped correctly under its main space. That visual observation was not repeated in this headless reproduction.

The bundled herdr-app checkout tests have a separate submodule problem. No Herdr implementation source was inspected for this report; the proposed fix is a behavioural suggestion, not a claim about its internal cause. This is an issue draft only; no report has been filed.
