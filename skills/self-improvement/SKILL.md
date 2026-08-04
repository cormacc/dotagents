---
name: self-improvement
description: |
  Capture durable friction with agent/pi configuration, skills, prompts,
  extensions, or project conventions and route it as TODO work to the right
  project: local TASKS.org, dotagents, or dotfiles. Use whenever the user or
  agent notices a durable improvement to how the agent works.
---

# Self-improvement

Turn repeatable agent friction into durable work. This skill is the canonical contract for durable-friction eligibility, ownership routing, deduplication, and persistence. The default is **TODO first**: file an `Agent feedback` task through the guaranteed `ot` CLI, then optionally promote it through [`org-plan`](../org-plan/SKILL.md). Do not require harness-specific task tools or session-to-session messaging.

## Trigger gate

Use this skill when:

- the user corrects behavior rooted in an unclear or missing rule;
- a tool/API was misused in a plausibly repeatable way;
- a workflow had to be improvised because no current contract covered it;
- existing configuration should have answered a question but did not.

Skip one-off mistakes, taste disagreements, ordinary direction changes, correct behavior that needs no rule, session-specific facts, speculative or generic best practices, and duplicates of existing guidance.

## Two loops

### Slow loop -- default, TODO first

Use for code changes, scripts, extensions, new files, restructures, multiple plausible designs, cross-project routing (except the home-config carve-out in the tight loop below), or any uncertain change.

1. Identify the owning tier.
2. Resolve its repository root.
3. Inspect open feedback with `ot --root <root> list --format json` and avoid duplicates.
4. Create the work item:

   ```bash
   ot --root <root> create "<actionable summary>" \
     --section "Agent feedback" \
     --tag '<target-tag>' \
     --body '<description and evidence>'
   ```

5. Report the returned UUID and ask whether to plan it now with `org-plan` or leave it on the backlog.

If `ot` is missing, stop with: `Install the org-tasks CLI (skills/org-tasks/scripts/ot or bbin), then rerun this capture.` If the target has no `TASKS.org`, stop with: `Run ot --root <root> init before filing cross-project feedback.` Never silently edit another repository as a transport fallback.

### Tight loop -- explicit exception

This is the sole exception to TODO-first. Use it only when all conditions hold:

- the artifact is in the current repository, or in a home-config tier (dotagents / dotfiles) whose working tree is clean;
- the change is a tiny, self-contained documentation clarification;
- exact wording and location are obvious;
- no executable code, script, extension, restructure, or cross-cutting behavior changes;
- the user explicitly approves the proposed diff.

Flow: show the minimal diff and rationale → obtain approval → edit and commit. Any uncertainty or pushback returns to the slow loop.

Outside the current repository the exception is narrow: it reaches the home-config tiers (dotagents, dotfiles) and nothing else, because those hold this agent's own instructions and a session in any project may be the only one that sees the friction. It requires a clean target working tree -- a dirty tree means the edit lands inside someone else's uncommitted change set, so check first and fall back to the slow loop when it is dirty. Leave the cross-repo edit uncommitted unless the user asks otherwise, and report the touched path so it is not mistaken for local work. Unrelated project repositories remain slow-loop only.

## Routing

Resolve symlinks before classifying (`realpath` on macOS/Linux where available):

| Resolved owner | Tier | Root |
|---|---|---|
| Under `$HOME/dotfiles/agents/` | dotagents | `$HOME/dotfiles/agents` |
| Under `$HOME/dotfiles/` but not `agents/` | dotfiles | `$HOME/dotfiles` |
| Anything else owned by the current project | project-local | nearest root from `ot root` |

Conceptual split:

- agent behavior, skills, prompts, pi extensions, dotagents package contents → dotagents;
- installation wiring, Nix/Home Manager integration, shell environment → dotfiles;
- project-only conventions → project-local.

Within that tier, choose the narrowest canonical owner: a skill workflow belongs in that skill or its reference; a project convention belongs in the project `AGENTS.md`; executable or non-trivial work belongs in an `Agent feedback` TODO. Read the target instructions before proposing changes, and do not invent harness-specific destinations that the repository does not declare.

Ask once when ownership is ambiguous. If unresolved, use the current project and tag `tier-unknown`.

Cross-project routing is a direct `ot --root <target> ...` operation. It does not depend on a live target agent session, `pi-intercom`, or optional pi task tools.

## Entry conventions

Use one or more target tags:

- `skill_<name>`
- `ext_<name>`
- `agents-md`
- `prompt_<name>`
- `project-convention`
- `tier-unknown`

Body shape:

```text
<reusable problem statement and desired outcome>

Origin:
- cwd: <cwd>
- git: <remote> @ <branch>
- timestamp: [YYYY-MM-DD Day HH:MM]
- sender: <human|agent>

Evidence:
<minimal useful evidence; synthesise user input rather than copying it verbatim>
```

When a matching open task exists, do not create a parallel item. Use `ot show <id>` to inspect it, then update it through the supported task workflow or report the duplicate for manual consolidation if no mutation command fits.

## Verification

Re-read any modified instruction file and run `ot show <id>` for each created or reused task. Confirm that no duplicate or contradictory guidance was introduced, then report changed paths, task UUIDs, and deferred work.

## Relationship to other skills

- [`org-tasks`](../org-tasks/SKILL.md) owns the task protocol and guaranteed `ot` commands.
- [`org-plan`](../org-plan/SKILL.md) owns promotion into a change-record.
- [`retro`](../retro/SKILL.md) scans and synthesizes end-of-session lessons, then delegates eligibility, routing, deduplication, and persistence here.
- A retrospective change-record in `org-plan` documents project work after the fact; it is not a `retro` session-learning workflow. Task or record closure alone does not trigger retro.
