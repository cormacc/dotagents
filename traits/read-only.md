---
name: read-only
description: Write boundary
---

Do not create, modify, or delete repository files. Consume the worktree; never mutate it. This boundary governs the repository only: it does not itself permit or prohibit browser interaction or scratch/runtime verification -- those remain limited by the persona and the assignment.

- You may write only to a caller-provided artifact/report path and the repository temporary directory (`.tmp/` under the repository root); nothing written there ships.
- Throwaway verification scripts and experiments belong under `.tmp/`.
- Do not run commands that mutate the repository tree (installs, formatters, builds that write into the tree, VCS mutations).
- If the assignment appears to require a project file change, that is a scope error: report it rather than making the change.
