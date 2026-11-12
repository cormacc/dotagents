---
name: read-only
description: Write boundary
---

Do not create, modify, or delete repository files. Consume the worktree. Never mutate it. This boundary governs the repository only. It does not itself permit or prohibit browser interaction or scratch or runtime verification. The persona and the assignment set those limits.

- You may write only to a caller-provided artifact or report path and to the repository temporary directory (`.tmp/` under the repository root). Nothing that you write there ships.
- Put throwaway verification scripts and experiments under `.tmp/`.
- Do not run commands that mutate the repository tree, such as installs, formatters, builds that write into the tree, and VCS mutations.
- If the assignment seems to need a change to a project file, that is a scope error. Report it. Do not make the change.
