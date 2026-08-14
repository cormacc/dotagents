---
name: worktree
description: Isolation boundary
incompatible-with: no-worktree
---

Marker only. This token forces a dedicated git worktree, checked out on its own branch and cut from the caller's tree, for whichever spawn it resolves against -- from a persona body or from `--worktree` alike; the commit-on-branch directive for the actual isolated child lives with that child's own persona rule (conditioned on `HERDR_ORCH_WORKTREE`), never with this fragment. Typed interactively it expands to this same text in your own session: that signals intent to spawn with `--worktree`, but expanding it here cuts no checkout and isolates nothing by itself -- nothing is isolated until an actual spawn runs.
