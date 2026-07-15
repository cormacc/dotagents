---
name: retro
description: End-of-session retrospective. Reviews a substantive ending session for corrections, friction, and wasted effort, then proposes durable agent-process improvements. Use when the user says "retro", "what did we learn", "session retro", or "end of session"; proactively offer once after repeated corrections or mounting friction.
---

# Session retrospective

Review the session for repeatable correction signals and synthesize candidate agent-process improvements. This is a session-learning workflow, not the retrospective project change-record workflow owned by [`org-plan`](../org-plan/SKILL.md).

## Threshold

Proactively offer one retro only when a substantive session is ending and the scan below finds repeated corrections or mounting friction. Task completion and change-record closure are not signals by themselves. If the session had fewer than roughly five substantive exchanges, say that it is unlikely to contain durable lessons and skip unless the user insists.

## 1. Detect signals

Prioritize:

1. **Explicit correction** — “that is wrong”, repeated instructions, reverted work, “use X not Y”.
2. **Tool/API failure** — wrong command, flag, schema, field, path, or operation order. Record both the failed and verified invocation.
3. **Repeated friction** — avoidable back-and-forth, manual workaround, or undocumented convention.
4. **Validated approach** — an explicitly approved pattern that contradicts or fills a gap in current guidance.

Apply [`self-improvement`](../self-improvement/SKILL.md)'s canonical trigger gate to decide which scanned signals are durable enough to keep. It owns common eligibility, including what to skip.

## 2. Classify

| Category | Treatment |
|---|---|
| Behavioral | Candidate instruction change |
| Guardrail | Candidate hard rule with verified replacement |
| Tech debt | TODO/backlog, not an immediate rule |
| Backlog | TODO/backlog, not an immediate rule |

Tool/API failures are normally guardrails. Only behavioral and guardrail findings are candidates for direct instruction edits.

## 3. Choose the narrowest owner

For every retained finding, load [`self-improvement`](../self-improvement/SKILL.md) and follow its canonical ownership, routing, deduplication, and persistence rules. Resolve symlinks and inspect the target instructions before proposing a destination; do not invent destinations that the current repository does not declare as canonical.

## 4. Present findings

Present one review table:

| # | Signal | Category | Proposed reusable rule/outcome | Destination |
|---|---|---|---|---|

Synthesize; never store user input verbatim. State the underlying reusable construct, not merely the surface phrase. Include only findings worth keeping.

Wait for user approval before editing or filing tasks. The user may reject, rewrite, or reclassify each item.

## 5. Apply approved changes

For each approved item, apply `self-improvement`'s tight-loop or TODO-first workflow exactly. Keep `AGENTS.md` concise and skill bodies under roughly 500 lines; move detailed material to references when needed.

Do not commit unless the user requested a commit or the active task protocol explicitly includes it.

## 6. Verify

- Re-read each modified instruction file.
- Use `self-improvement`'s verification rules for persisted findings.
- Confirm no duplicate or contradictory guidance was introduced.
- Report changed paths, task UUIDs, and intentionally deferred tech debt/backlog.
