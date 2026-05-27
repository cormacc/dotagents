---
name: self-improvement
description: |
  Capture durable friction with agent/pi configuration, skills, prompts,
  extensions, or project conventions and route it as TODO work to the right
  project: local TASKS.org, dotagents, or dotfiles. Use whenever the user or
  agent notices a durable improvement to how the agent works.
---

# Self-improvement skill

Turn observed friction into durable work items. This skill does *not* edit
`AGENTS.md`, skill files, prompts, or extensions directly — every change
goes through a TODO entry, optionally promoted into a change-record via the
[`org-plan`](../org-plan/SKILL.md) flow.

Repo-agnostic: invoked from whichever session notices the friction, routed
to the tier that owns the affected artefact. "Global" splits across two
sibling repos (see *Routing*); both are reached via `pi-intercom`.

> **Relationship to `retro`.** `retro` is for end-of-session synthesis of
> corrections into rule changes that get edited directly. This skill is for
> *mid-session* capture of durable friction into work items that get
> triaged and (optionally) planned. If you're at the end of a session, use
> `retro`; if you noticed something now but the user wants to keep moving,
> use this.

## When to use

### User-invoked

"Let's capture that as a self-improvement", "feed this back into the
skill", "remember this for next time", or any ask to log a durable lesson
about agent behaviour or config.

### Agent-invoked (self-proposal)

Invoke *proactively* when one of these is observed:

- The user corrects an action traceable to an explicit `AGENTS.md` or skill
  guideline — the rule clearly didn't land.
- A tool was misused in a way rooted in unclear or missing documentation,
  plausibly repeatable.
- A protocol gap is discovered (a workflow the agent improvised because
  nothing covered it).
- The agent had to ask the user for information that *should* have been
  answered by existing config.

Keep this narrow. Do *not* self-propose for one-off mistakes, taste
disagreements, or ordinary direction changes. When unsure, ask the user
once; their answer is itself a useful signal.

### Trigger gate

After any user correction of agent behaviour, *before* moving on, briefly
check the four triggers above. If any fit, pick a loop and act. Skipping
this gate is how durable lessons get lost.

## Two improvement loops

### Tight loop — incremental correction

For small, obvious, doc-only fixes: agent proposes a minimal diff inline,
user approves, change is committed immediately. No `TASKS.org` entry, no
change-record. The git history *is* the record.

Use the tight loop when **all** hold:

- Affected artefact lives in the *current session's repo* (no cross-repo
  hop).
- Fix is a small, self-contained edit to a doc/guideline artefact
  (`AGENTS.md`, `SKILL.md` prose, a README, a comment) — typo, missing
  example, clarifying sentence.
- Location and exact wording are obvious in one pass; no design choices.
- No code change to extensions / scripts / executables.
- No cross-cutting implications.

Flow: draft diff → show with one-line rationale → on approval, apply +
commit using the target repo's commit style (inspect `git log --oneline`
for the dominant pattern). On any pushback, fall back to the slow loop.

### Slow loop — larger-impact changes

Default for everything else. Files an entry under `* Agent feedback` so it
can be triaged, deduped, and optionally promoted to a planned
change-record.

Use the slow loop when **any** hold:

- Code change to skills, pi extensions, scripts.
- New skill / file / restructure / rename.
- Multiple plausible designs.
- Cross-repo hand-off needed.
- User wants to think before committing.
- You're not certain — when in doubt, slow loop.

## Routing: three tiers

Classify the affected artefact's tier (resolve symlinks with `realpath` /
`readlink -f`) and apply the first match:

| Signal | Tier |
|---|---|
| Resolves under `$HOME/dotfiles/agents/` (incl. anything reached via `~/.agents/skills`, `~/.pi/agent/{skills,extensions,prompts,settings.json,AGENTS.md}` — these are symlinks) | **dotagents** |
| Resolves under `$HOME/dotfiles/` but **not** under `agents/` (`agents.nix`, `home*.nix`, `hosts/`, dotfiles-side `AGENTS.md`) | **dotfiles** |
| Anything else in the current session's repo (project `AGENTS.md`, project-scoped script/convention) | **project-local** |

Quick heuristic for the dotagents-vs-dotfiles split when the artefact is
conceptual rather than file-bound:

- *How the agent behaves / what a skill says / how an extension works /
  what a dotagents package contains* → **dotagents**.
- *How dotagents gets installed / which local package inputs are enabled /
  Nix wiring / shell environment* → **dotfiles**.

If ambiguous, ask once. If the user declines to disambiguate, default to
the **current project** and add `:tier-unknown:` so it can be re-routed
later.

## Slow-loop transport

Triage logic is identical for both flows; only transport differs.

### Flow A: project-local (no transport)

1. Collect description + auto-detect metadata: origin session name (`intercom
   action: status`), `cwd`, git remote / branch, timestamp
   (`date +'%Y-%m-%d %a %H:%M'`), optional transcript snippet.
2. Triage (see below).
3. Insert into the current project's `TASKS.org` via `tasks_insert_task`
   with `section: "Agent feedback"`, `allowCreateSection: true`.
4. Prompt: *"Filed as <UUID> under * Agent feedback. Plan it now (org-plan)
   or leave on the backlog?"*

### Flow B: global (pi-intercom hand-off)

The originating session does *not* triage; it hands a structured envelope
to a session in the *target* repo (dotagents = `$HOME/dotfiles/agents`,
dotfiles = `$HOME/dotfiles`).

1. Collect description + metadata (as in Flow A).
2. Discover a live target session via `intercom action: list`, filtering
   `cwd`:
   - **dotagents**: `cwd` under `$HOME/dotfiles/agents`.
   - **dotfiles**: `cwd` under `$HOME/dotfiles` **but not** under
     `agents/`.
3. If none alive, spawn one (per `pi-intercom` conventions) with
   `TARGET_CWD` set to the appropriate path, then poll
   `intercom action: list` until it registers.
4. Send the envelope (fire-and-forget, *not* `ask`):
   ```
   intercom({
     action: "send",
     to: "<target session name>",
     message: "[self-improvement] <one-line summary>\n\n" +
              "<free-form body>\n\n" +
              "Origin:\n" +
              "- session: <name>\n" +
              "- cwd: <cwd>\n" +
              "- git: <remote> @ <branch>\n" +
              "- timestamp: <YYYY-MM-DD Day HH:MM>\n" +
              "- sender: <human|agent>\n",
     attachments: [/* optional transcript snippets */]
   })
   ```
   The `[self-improvement]` prefix is what the target-side triage routine
   matches on.
5. Return immediately. The target session will reply asynchronously with
   the new UUID + "plan or backlog?" prompt; surface that when it arrives.

## Triage routine

Same routine for project-local (originating session) and global inbound
(target repo's session).

1. **Parse** envelope (or local args) → body + metadata + sender.
2. **Classify the affected target** with a single org tag:
   - `:skill_<name>:` — specific skill.
   - `:ext_<name>:` — pi extension.
   - `:agents-md:` — root or project `AGENTS.md`.
   - `:prompt_<name>:` — named prompt.
   - `:project-convention:` — project-only convention with no dedicated
     artefact yet.
   - `:tier-unknown:` — only when routing was ambiguous.
   - Multiple tags allowed when multiple targets apply.
3. **Dedupe.** Search open `* Agent feedback` entries in the target
   `TASKS.org` for near-duplicates: same target tag + significant keyword
   overlap. If found, append a new dated bullet (plus attachments quoted
   verbatim) to that entry's body rather than creating a parallel TODO.
   Report which existing UUID was extended.
4. **Draft** summary + body (see entry conventions).
5. **Confirm wording with the user *only when sender is `human`*.** Show
   summary, body, tags, target path; wait for thumbs up. When sender is
   `agent`, skip confirmation — it's the agent's own observation.
6. **Insert** via `tasks_insert_task`:
   ```
   file: <target>/TASKS.org
   section: "Agent feedback"
   allowCreateSection: true
   summary: <draft summary>
   labels: [<target tags>]
   body: <draft body>
   ```
7. **Acknowledge.** Local: prompt inline ("Filed as <UUID>. Plan now or
   backlog?"). Cross-tier: `intercom action: send` to originating session:
   *"[self-improvement] Filed as <UUID> in <target-label>/TASKS.org. Plan
   now or backlog?"*

## `* Agent feedback` entry conventions

```
** TODO [#?] <one-line summary> :<target-tag>:
```

Priority cookie optional; usually added during planning.

Body:

```
<free-form description from sender>

Origin:
- session: <name>
- cwd: <cwd>
- git: <remote> @ <branch>
- timestamp: [YYYY-MM-DD Day HH:MM]
- sender: <human|agent>

Evidence:
<optional transcript snippet, quoted verbatim in a src block,
omitted if not useful>
```

When merging new evidence into an existing entry, append a fresh `Origin:`
+ `Evidence:` block (with its own timestamp) rather than overwriting.

Promotion to a planned change-record follows the standard `org-plan` flow
(`#+IMPORT: [[plan:<file.org>]]`); nothing here short-circuits it.

## Worked example (one is enough)

The user corrects misuse of `tasks_insert_task` (the agent forgot
`allowCreateSection`). The pi-side `AGENTS.md` guideline is unclear —
trigger 2 + 3 fit. Current session is `~/code/some-project`.

1. Classify tier: pi-side `AGENTS.md` resolves to
   `$HOME/dotfiles/agents/AGENTS.md` → **dotagents**.
2. Discover dotagents session via `intercom action: list`. None alive →
   spawn per `pi-intercom` recipe with
   `TARGET_CWD=$HOME/dotfiles/agents`.
3. Send `[self-improvement]` envelope.
4. Originating session returns immediately and continues the user's actual
   task.
5. Dotagents session receives, classifies tag `:agents-md:`, no
   near-duplicate, sender is `agent` → skips confirmation, inserts into
   `~/dotfiles/agents/TASKS.org` under `* Agent feedback`.
6. Dotagents session replies via `intercom action: send`:
   *"[self-improvement] Filed as 01234567-… in dotagents/TASKS.org. Plan
   now or backlog?"*
7. The originating-session agent surfaces that prompt to the user.

## See also

- [`../org-tasks/SKILL.md`](../org-tasks/SKILL.md) — `TASKS.org` protocol,
  `tasks_insert_task`, idempotency.
- [`../org-plan/SKILL.md`](../org-plan/SKILL.md) — promoting an entry to a
  planned change-record.
- [`../retro/SKILL.md`](../retro/SKILL.md) — end-of-session synthesis
  (sibling skill; complementary scope).
- `pi-intercom` skill — transport semantics (`send` / `ask` / `reply` /
  `list`) and spawn recipes.
- `~/dotfiles/agents.nix` — the wiring that symlinks
  `~/dotfiles/agents/` into `~/.agents/skills` and `~/.pi/agent/*`.
