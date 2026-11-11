# Bundled checkout tests classify a submodule main checkout as a linked worktree

## Summary

The bundled `menu` mod's `checkout()` and `spaces` mod's `check` compare the common Git directory with `<top>/.git`. That comparison fails for an absorbed submodule's main checkout, where `.git` is a file and the common directory is in the superproject's metadata. Reading the source predicts that `Remove worktree` is offered on the main checkout and that its linked-worktree spaces are not nested under it. These UI effects were not run or observed: the user chose source inspection only.

## Environment

Inspected on 2026-11-11 on Linux with Git 2.56.0 (`git --version`). Installed herdr-app package: 0.1.15, identified without launching it by `readlink -f ~/.nix-profile/bin/herdr-app`, which resolves to `/nix/store/igp4q27ddh2yyjzrwz8l0c1fwazdidr1-herdr-app-0.1.15/bin/herdr-app`. That wrapper executes the package's `libexec/herdr-app/herdr-app` binary.

The installed bundled mods inspected were `~/.config/herdr-app/mods/menu/main.js`, `~/.config/herdr-app/mods/spaces/main.js` and `~/.config/herdr-app/mods/worktree/main.js`. The defective `menu` and `spaces` expressions, and the `space.close` call quoted below, were also found in strings embedded in that installed binary. No bundled mod was edited. No app version command or UI action was invoked.

## Reproduction

The following Bash setup and Git probes were run again from an empty directory. They create local repositories only; they do not control the app. Use an empty disposable directory, with Git installed.

```bash
set -eu
base=$PWD
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
git -C super/sub rev-parse --show-toplevel
git -C super/sub rev-parse --absolute-git-dir
git -C super/sub rev-parse --path-format=absolute --git-common-dir
git -C child rev-parse --show-toplevel
git -C child rev-parse --absolute-git-dir
git -C child rev-parse --path-format=absolute --git-common-dir
```

Suggested UI confirmation, not performed:

1. Open a space rooted at `<base>/super/sub` and another at `<base>/child`, using the bundled `spaces` mod rather than a replacement sidebar.
2. Open the main submodule space's context menu. Check whether it offers `Remove worktree`; it must not.
3. Check whether the child space nests under that main space.

Do not confirm removal to reproduce the classification problem. The removal ordering below is source reading, not an instruction to close a real space.

## Observed

Only the Git probes were executed. Paths below are normalised to `<base>`.

| Probe | Actual | Expected |
|---|---|---|
| Main checkout `--show-toplevel` | `<base>/super/sub` | Same |
| Main checkout `--absolute-git-dir` | `<base>/super/.git/modules/sub` | Same as its common dir |
| Main checkout absolute `--git-common-dir` | `<base>/super/.git/modules/sub` | Same as its Git dir, so not linked |
| Child `--absolute-git-dir` | `<base>/super/.git/modules/sub/worktrees/child` | Different from its common dir, so linked |
| Child absolute `--git-common-dir` | `<base>/super/.git/modules/sub` | Same repository identity as main |

## Read in source

All UI effects in this section are inferred from the installed bundled source, not observed in the app. Line numbers refer to the installed files named in Environment.

`~/.config/herdr-app/mods/menu/main.js:17-22`, `checkout(folder, on)`, reads the top and common dir and uses this defective test at line 21:

```javascript
return common.stdout.trim() === `${top.stdout.trim()}/.git` ? "main" : "linked";
```

Line 67 offers the removal item when that test returns `"linked"`:

```javascript
...(git === "linked" ? [["Remove worktree", "worktree", { space: tg.id, action: "remove" }]] : []),
```

`~/.config/herdr-app/mods/spaces/main.js:198-230`, `check(key, cx)`, reads the top and common dir at lines 206-208. Its `info` object at line 223 contains the same defective predicate:

```javascript
linked: repo !== `${top}/.git`
```

In `ordered()` at lines 263-264, nesting requires a non-linked main entry with the same repository identity:

```javascript
const mains = new Set(list.filter((r) => r.g && !r.g.linked).map((r) => r.g.repo));
const nested = (r) => r.g?.linked && mains.has(r.g.repo);
```

With the submodule main space incorrectly marked linked, it cannot supply that main entry. Line 270 also skips linked entries as parents.

`~/.config/herdr-app/mods/worktree/main.js:99-105`, `repoOf(folder, on)`, likewise derives `main` from the common dir and returns `linked: main !== top.out` at line 104. This also classifies the submodule main checkout as linked, so the dialog's main-checkout guard does not correct the menu's error.

In the same file, `remove()` at lines 211-228 issues `space.close` before it awaits Git removal, at lines 224-226:

```javascript
run("space.close", { id: s.id, confirmed: true });
const force = this.dirty || mods.length > 0;
const r = await git(this.repo.main, ["worktree", "remove", ...(force ? ["--force"] : []), this.repo.top], this.on, 600_000);
```

Thus the code requests closure of the space and its terminals before Git can refuse removal, including a refusal for a locked worktree or a main checkout. This report does not claim that the submodule main checkout was deleted, or that any live space was closed.

| Source-derived effect | Actual logic | Expected |
|---|---|---|
| Main submodule context menu | Offers `Remove worktree` | No removal item for the main checkout |
| Bundled spaces grouping | Both main and child are linked, so neither is the main parent | Child nests under the non-linked main space |
| Removal failure ordering | Requests space closure before Git can refuse | Refuse invalid targets before closing their space; preserve the space on refusal where possible |

## Proposed fix

Use Git's per-checkout directory, not `<top>/.git`, to determine linked status. Read `git rev-parse --absolute-git-dir` and `git rev-parse --path-format=absolute --git-common-dir`, handle command failure as the current code does, and compare their paths: equal means main; different means linked. Keep the common directory as the repository grouping key. Apply the test consistently to `menu.checkout()`, `spaces.check` and the worktree dialog's `repoOf()` guard.

Do not treat a submodule common dir as its checkout path. `git rev-parse --show-toplevel` supplies the checkout path when run there. Keep path discovery separate from linked classification. Validate a removal target before issuing `space.close`; consider how to retain the space on Git refusal while respecting the source comment about terminals holding folders busy on Windows.

Test a plain main checkout, a plain linked checkout, a submodule main checkout and a linked checkout of that submodule. The main entries must be non-linked and lack the removal item; each child must nest under its main. Include a refused removal to check space preservation. These are proposed regression checks, not tests run against the app.

## Context

The local Git probes establish why the predicate is wrong without running destructive app actions. A separate Herdr 0.9.5 CLI reproduction shows correct `workspace list` provenance for submodule main and child workspaces, while its `worktree list` and branch-open path have a separate defect. An earlier user-observed Herdr TUI session grouped that child correctly; this is not evidence that the bundled herdr-app sidebar does so.

This is an issue draft only. No report has been filed, no remote has been contacted, and no live app effects have been tested.
