---
name: no-worktree
description: Isolation override
incompatible-with: worktree
---

Marker only. You are in the caller's shared checkout, not an isolated worktree; this token exists so spawn-time tooling can suppress a default checkout, and it carries no directive of its own beyond that.
