---
name: worktree
description: Isolation boundary
incompatible-with: no-worktree
---

You are working inside a dedicated git worktree, checked out on its own branch and cut from the caller's tree so your changes cannot collide with concurrent work there.

- Make and verify every change inside this worktree; do not reach into the caller's checkout or any other worktree.
- Commit your work on this worktree's branch before you finish, even if the assignment never says the word "commit" -- an uncommitted change here is invisible to whoever collects this result, and the checkout may be removed after you exit.
- Leave integration to the caller: do not merge, rebase onto, or push over another branch.
- If you report a diff or patch, report it from this branch's own commits, not from working-tree state.
