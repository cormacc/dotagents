---
name: retro
description: End-of-session retrospective. Reviews the conversation for corrections, friction, and wasted effort, then proposes durable improvements to skills and rules. Use at end of sessions or when user says "retro", "what did we learn", "session retro", "end of session". PROACTIVE - if the conversation has repeated corrections or mounting friction, suggest running /retro and restarting the session.
---

# Session retrospective

Review the session for repeatable correction signals and turn them into durable improvements in the current AGENTS/skills/task-memory hierarchy.

## Threshold

If the session had fewer than roughly five substantive exchanges, say that it is unlikely to contain durable lessons and skip unless the user insists.

## 1. Detect signals

Prioritize:

1. **Explicit correction** — “that is wrong”, repeated instructions, reverted work, “use X not Y”.
2. **Tool/API failure** — wrong command, flag, schema, field, path, or operation order. Record both the failed and verified invocation.
3. **Repeated friction** — avoidable back-and-forth, manual workaround, or undocumented convention.
4. **Validated approach** — an explicitly approved pattern that contradicts or fills a gap in current guidance.

Skip typos, one-off misunderstandings, taste, and behavior already covered by an effective rule.

## 2. Classify

| Category | Treatment |
|---|---|
| Behavioral | Candidate instruction change |
| Guardrail | Candidate hard rule with verified replacement |
| Tech debt | TODO/backlog, not an immediate rule |
| Backlog | TODO/backlog, not an immediate rule |

Tool/API failures are normally guardrails. Only behavioral and guardrail findings are candidates for direct instruction edits.

## 3. Choose the narrowest owner

Resolve symlinks and inspect the current hierarchy before proposing a destination:

| Scope | Destination |
|---|---|
| One skill workflow | That skill's `SKILL.md` or its reference file |
| One project | Project-root `AGENTS.md` |
| Agent behavior shared by this dotagents setup | dotagents `AGENTS.md` or the owning skill |
| Executable/non-trivial change | `TASKS.org` under `* Agent feedback` via `ot` |
| Cross-project installation/Nix behavior | Owning dotfiles repository task memory |

Do not invent Claude-specific `/rules`, `/skill`, `.claude/rules`, or `CLAUDE.md` destinations unless the current repository actually declares them as canonical. Read the target `AGENTS.md` and relevant skill before proposing edits.

Use [`self-improvement`](../self-improvement/SKILL.md) for tier routing and persistence. Its tight-loop exception applies only to an obvious, user-approved, current-repository documentation clarification; all executable, cross-project, uncertain, or multi-file work becomes a TODO through the guaranteed `ot` CLI.

## 4. Present findings

Present one review table:

| # | Signal | Category | Proposed reusable rule/outcome | Destination |
|---|---|---|---|---|

Synthesize; never store user input verbatim. State the underlying reusable construct, not merely the surface phrase. Include only findings worth keeping.

Wait for user approval before editing or filing tasks. The user may reject, rewrite, or reclassify each item.

## 5. Apply approved changes

For each approved item:

1. Read the target and parent instructions.
2. Check for duplication or contradiction.
3. For an eligible tight-loop documentation fix, make the smallest approved edit.
4. Otherwise create or deduplicate an `Agent feedback` task using `self-improvement` and `ot`.
5. Keep `AGENTS.md` concise and skill bodies under roughly 500 lines; move detailed material to references when needed.

Do not commit unless the user requested a commit or the active task protocol explicitly includes it.

## 6. Verify

- Re-read each modified instruction file.
- Run `ot show <id>` for each created task.
- Confirm no duplicate or contradictory guidance was introduced.
- Report changed paths, task UUIDs, and intentionally deferred tech debt/backlog.

## What not to capture

- Correct behavior that needs no rule.
- Session-specific facts.
- Speculative improvements unsupported by observed friction.
- Generic best practices.
- One-time fixes.
