---
name: retro
description: End-of-session retrospective. Reviews the conversation for corrections, friction, and wasted effort, then proposes durable improvements to skills and rules. Use at end of sessions or when user says "retro", "what did we learn", "session retro", "end of session". PROACTIVE - if the conversation has repeated corrections or mounting friction, suggest running /retro and restarting the session.
---

# Session Retrospective

Review the full conversation to find moments where the user corrected or redirected the AI. Turn correction signals into durable system improvements.

The goal: **make AI collaboration compound over time**. Every retro should leave the system better for the next session. We are not just building software — we are building the machine that builds the software.

## Threshold Check

Before starting, assess the session. If it was a quick task (under ~5 substantive exchanges), say so and skip the retro unless the user insists. Not every session has lessons worth extracting.

## Phase 1: Detect Correction Signals

Read the entire conversation. Look for these signal types, ordered by value:

**HIGH confidence** — explicit corrections:
- "No", "that's wrong", "I said...", "don't do that", "not like that"
- User repeating an instruction the AI missed or ignored
- User undoing or reverting something the AI did
- "Use X not Y", "always do X", "never do Y"

**MEDIUM confidence** — approved approaches:
- "Perfect", "exactly", "that's right", "yes, like that"
- Approaches the user explicitly validated (worth noting if they contradict a current rule or reveal an undocumented preference)

**LOW confidence** — observed patterns:
- Workarounds (user doing something manually the AI should have done)
- Wasted effort (AI went down a wrong path before being corrected)
- Friction points (things that took more back-and-forth than necessary)

**TOOL ERRORS** — failed tool calls:
- CLI commands with wrong subcommands, flags, or argument names
- API/MCP tool calls with wrong parameter names or shapes
- Tools called in the wrong order (missing required predecessor)
- Repeated failures where the AI retried the same broken call instead of fixing it
- For each error: note the wrong invocation AND the correct one

Skip one-off misunderstandings, simple typos, and things that are already documented in existing rules.

## Phase 2: Classify Improvements

For each signal worth capturing, determine the category (from Robert Sahlin's AI Retrospective framework):

| Category | Description | Example |
|----------|-------------|---------|
| **Behavioural** | How the AI should work differently | "Ask one question at a time, don't batch" |
| **Guardrail** | Hard rule to prevent a specific failure | "Never use `gog drive list` — the command is `ls`" |
| **Tech debt** | Shortcut taken that should be fixed later | "Hardcoded path needs extracting to config" |
| **Backlog** | Good idea that emerged but wasn't the current task | "Could automate X workflow as a skill" |

Tool errors are almost always **Guardrail** items — they produce a concrete "use X not Y" rule. Only Behavioural and Guardrail items get written to rules/skills. Tech debt and Backlog items are reported for the user to action separately.

## Phase 3: Map to Destinations

For each Behavioural or Guardrail item, determine where it belongs. Load `/rules` for the full memory hierarchy. Summary:

| Pattern | Destination |
|---------|-------------|
| Applies to a specific skill | That skill's SKILL.md (load `/skill` for format reference) |
| Applies to certain file types only | `.claude/rules/*.md` with `paths:` frontmatter |
| Applies to one project | Project CLAUDE.md |
| Applies universally | Global `~/.claude/CLAUDE.md` |
| Personal override for shared project | `CLAUDE.local.md` |

**Specificity rule**: always store in the most specific location that applies. A lesson about the capture skill goes in `capture/SKILL.md`, not in global CLAUDE.md.

## Phase 4: Present Findings

Present all findings in a single table for review:

| # | Signal | Category | Proposed rule | Destination |
|---|--------|----------|--------------|-------------|
| 1 | AI used `list` instead of `ls` for gog drive | Guardrail | `gog drive` subcommand is `ls` not `list` | Global CLAUDE.md |
| 2 | Capture skill added unwanted analysis | Behavioural | Don't expand on ideas unless explicitly asked | capture/SKILL.md |
| 3 | Should extract auth helper to shared util | Tech debt | _(report only)_ | — |

**Golden rule: NEVER store user input verbatim. ALWAYS synthesise into an actionable, reusable rule.** Write the instruction the AI needs to follow, not a narrative of what happened.

**Depth rule: describe the underlying construct, not just surface examples.** Surface-level examples (e.g. "don't write 'Not X, but Y'") are easy to pattern-match around with minor rephrasing. Name the deeper grammatical or structural construct (e.g. "appositive negation: defining something by what it is not before stating what it is"). This makes the rule robust against novel phrasings of the same anti-pattern.

Bad: "User said to use gpt-5.1 for reasoning"
Good: "For reasoning tasks, use gpt-5.1 model"

Bad: "Chris got frustrated when I batched questions"
Good: "Ask questions ONE AT A TIME — wait for each response before asking the next"

Bad: "Don't write 'Not a curated demo, but the full picture'"
Good: "Avoid appositive negation — defining something by what it is not before stating what it is. Just state what it IS."

Wait for user approval before proceeding. The user may reject, modify, or re-categorise items.

## Phase 5: Apply Approved Changes

For each approved item:

1. Read the target file
2. Check the proposed rule doesn't already exist (in this file or a higher-priority location)
3. Find the right section or create one
4. Write the rule — concise, imperative, no narrative
5. Check file size: CLAUDE.md under 300 lines (500 ceiling), skills under 500 lines
6. If a file would exceed limits, flag it and suggest reorganisation (extract to rules/, split to skill, etc.)

## Phase 6: Verify

After all changes:
- Read each modified file to confirm no duplication or contradiction was introduced
- Report a summary of what was updated and where

## What NOT to Capture

- Things that already worked (no rule needed for correct behaviour)
- Session-specific facts ("user was working on newsletter today")
- Speculative improvements not backed by actual friction in THIS conversation
- Anything that duplicates existing instructions
- One-time fixes or typo corrections
- Generic best practices the AI should already know

## Integration

This skill references:
- **`/rules`** — for the memory hierarchy, size guidelines, audit process, and anti-patterns
- **`/skill`** — for skill file format, frontmatter fields, and editing conventions
