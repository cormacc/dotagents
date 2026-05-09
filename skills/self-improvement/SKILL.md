---
name: self-improvement
description: |
  Capture friction with agent / pi configuration (AGENTS.md, skills, prompts,
  pi extensions, project conventions) at the moment it arises in any session
  and route it as actionable TODO work to the right tier — project-local
  config goes into the current project's TASKS.org; global config splits
  between two repos via pi-intercom: dotagents (skills, pi extensions,
  prompts, pi-side AGENTS.md) and dotfiles (Home Manager / NixOS /
  nix-darwin wiring, agents.nix, pi settings.json, dotfiles-side
  AGENTS.md). Use whenever the user (or you yourself) notices something
  durable that should change about how the agent works.
---

# Self-improvement skill

This skill turns observed friction into durable work items. It does *not* edit
`AGENTS.md`, skill files, prompts, or extensions directly — every change goes
through a normal TODO entry, optionally promoted into a change-record via the
[`org-plan`](../org-plan/SKILL.md) flow, so the team and the project history
stay in the loop.

The skill is repo-agnostic. It is invoked from whichever session notices the
friction, and routes the resulting TODO to the tier that owns the affected
artefact. "Global" splits across two sibling repos (see *Routing* below); both
are reached via `pi-intercom`.

## When to use

### User-invoked

Whenever the user says things like "let's capture that as a self-improvement",
"feed this back into the skill", "remember this for next time", or otherwise
asks to log a durable lesson about agent behaviour or config.

### Agent-invoked (self-proposal)

Invoke this skill *proactively* when one of these is observed:

- The user corrects an action that traces back to an explicit `AGENTS.md` or
  skill guideline — the rule clearly didn't land.
- A tool was misused in a way rooted in unclear or missing documentation, and
  the same mistake is plausibly repeatable.
- A protocol gap is discovered (a workflow the agent had to improvise because
  nothing in `AGENTS.md` / skills covered it).
- The agent had to ask the user for information that *should* have been answered
  by existing config.

Keep the heuristic narrow. Do *not* self-propose for one-off mistakes, taste
disagreements, or ordinary user direction changes. When unsure, ask the user
once whether to log it; their answer is itself a useful signal.

### Trigger gate after every user correction

After any user correction of agent behaviour, *before* moving on, briefly
evaluate the four self-proposal triggers above. If any fit, choose a loop (next
section) and act. Skipping this gate is how durable lessons get lost.

## Two improvement loops

Not every observation deserves a `TASKS.org` entry. The skill has two loops;
pick the right one before doing anything else.

### Tight loop — incremental correction

For small, obvious, doc-only fixes the agent proposes a minimal diff inline, the
user approves, and the change is committed immediately. No `TASKS.org` entry, no
change-record, no planning. The git history *is* the record.

Use the tight loop when **all** of these hold:

- The affected artefact lives in the *current session's repo* (no cross-repo
  round-trip).
- The fix is a small, self-contained edit to a *doc / guideline* artefact:
  `AGENTS.md`, a `SKILL.md` prose section, a README, an inline comment. One- to
  a few-line additions, a typo, a missing example, a clarifying sentence.
- The location *and* the exact wording are obvious enough to draft in one pass
  without design choices.
- No code change in extensions, scripts, or executables.
- No cross-cutting implications, no scope debate, no alternatives worth
  weighing.

Tight-loop flow:

1. Draft the proposed edit as an exact before/after diff.
2. Show it to the user with a one-line rationale.
3. On approval: apply the edit and commit immediately with a message that
   follows the *target repo's* commit style (inspect `git log --oneline` for the
   dominant pattern).
4. On any pushback ("can we discuss", "not sure that's the right wording", "this
   affects more than I thought"): fall back to the slow loop.

### Slow loop — larger-impact changes

Default for everything else. Files an entry under `* Agent feedback` so the work
can be triaged, deduped, and optionally promoted to a planned change-record.

Use the slow loop when **any** of these hold:

- Code change to skills, pi extensions, scripts.
- A new skill, file, restructure, or rename.
- The fix has multiple plausible designs.
- The fix touches another repo (cross-tier hand-off via pi-intercom).
- The user wants to think about it before committing.
- You're not certain — when in doubt, slow loop.

The slow-loop pipeline (routing, transport, triage routine, entry conventions)
is documented in the rest of this file.

## Routing: three tiers

Before doing anything else, classify the affected artefact's *tier*. The TODO
must end up in the `TASKS.org` of the repo that owns the fix. There are three
tiers:

- **project-local** — the current session's repo (anything that isn't dotfiles
  or dotagents).
- **dotagents** — `~/dotfiles/agents-src/` (a git submodule pointing at
  `cormacc/dotagents`). Owns reusable agent assets: skills, pi extensions,
  prompts, the pi-side `AGENTS.md`, and dotagents package contents / manifests
  (`agent-org-memory.nix`, `package.json`). These are symlinked into
  `~/.agents/skills` and `~/.pi/agent/{AGENTS.md,prompts,extensions,skills}` by
  `agents.nix`.
- **dotfiles** — `~/dotfiles/` itself. Owns Home Manager / NixOS / nix-darwin
  configuration, `agents.nix` (the wiring that installs dotagents),
  `agents-config/pi/settings.json` (user-local pi settings), and the
  dotfiles-side `AGENTS.md`.

### Decision rules

Resolve the affected file (follow symlinks — `realpath` or `readlink -f`) and
apply the first matching row:

| Signal | Tier |
|--------|------|
| File resolves under `$HOME/dotfiles/agents-src/` | **dotagents** |
| File is a skill (`~/.agents/skills/<name>/`) or lives under `~/.pi/agent/{skills,extensions,prompts}/` | **dotagents** (these are symlinks into `agents-src/`) |
| File is the pi-side `AGENTS.md` (`~/.pi/agent/AGENTS.md`, resolves to `agents-src/AGENTS.md`) | **dotagents** |
| File resolves under `$HOME/dotfiles/` but **not** under `agents-src/` (e.g. `agents.nix`, `agents-config/pi/settings.json`, `home*.nix`, `hosts/`, `darwin-configuration.nix`, the dotfiles `AGENTS.md`) | **dotfiles** |
| Project-only `AGENTS.md`, project-scoped script, project-specific convention, or project tooling | **project-local** |
| Artefact lives in a sibling repo unrelated to the three tiers above | project-local *to that repo* — but routing to a third repo is out of scope; ask the user |

Quick heuristic for the dotagents-vs-dotfiles split when the artefact is
conceptual rather than file-bound:

- *"How the agent behaves / what a skill says / how an extension works / what a
  dotagents package contains"* → **dotagents**.
- *"How dotagents gets installed / which local package inputs are enabled / Nix
  wiring / shell environment"* → **dotfiles**.

### Ambiguous cases

If the classification isn't clear from the signals above, ask the user once:
*"is this a fix in this project, in dotagents (skills / extensions / prompts),
or in dotfiles (Nix wiring)?"* If they decline to disambiguate, default to the
**current project** (least disruptive) and add the tag `:tier-unknown:` to the
entry so it can be re-routed later.

## Slow-loop transport: two flows

In the slow loop, the routing decision selects one of two transport flows.
Triage logic (classify affected target, dedupe, draft entry, confirm, insert,
prompt) is identical between them — only the *transport* differs. The tight loop
bypasses both: it edits the file in the current session's repo and commits.

### Flow A: project-local (no transport)

1. Collect a free-form description of the friction. If you are self-proposing,
   write it yourself and mark sender as `agent`. If the user is invoking, take
   their description and mark sender as `human`.
2. Auto-detect metadata:
   - Origin session name (via `intercom action: status` or equivalent).
   - `cwd`.
   - Git remote and current branch (`git remote get-url origin`,
     `git rev-parse --abbrev-ref HEAD`).
   - Timestamp via `date +'%Y-%m-%d %a %H:%M'`.
   - Optional transcript snippet showing the trigger, if useful.
3. Triage (see "Triage routine" below).
4. Insert into the *current project's* `TASKS.org` via `tasks_insert_task` with
   section `Agent feedback` and `allowCreateSection: true`.
5. Tell the user: *"Filed as <UUID> under * Agent feedback. Plan it now
   (org-plan) or leave on the backlog?"* and act on their answer.

### Flow B: global (pi-intercom hand-off to dotagents *or* dotfiles)

The originating session does *not* triage; it hands a structured envelope to a
session running in the *target* repo. The repo selected by the routing rules is
the **target repo**: either `$HOME/dotfiles/agents-src` (dotagents) or
`$HOME/dotfiles` (dotfiles).

1. Collect description + auto-detect metadata (as in Flow A, step 2).
2. Discover a live target session:
   ```
   intercom action: list
   ```
   Filter for a session whose `cwd` matches the target repo:
   - **dotagents** target: `cwd` is under `$HOME/dotfiles/agents-src`.
   - **dotfiles** target: `cwd` is under `$HOME/dotfiles` **but not** under
     `agents-src/`.

   Be careful with the dotfiles match: a session whose `cwd` is
   `~/dotfiles/agents-src` is **not** a dotfiles session — it's a dotagents
   session that happens to sit inside the parent checkout.
3. If no session for the target repo is alive, **auto-spawn** one (see "Spawn
   recipe" below) and wait for it to register with intercom.
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
   The `[self-improvement]` prefix in the first line is what the target-side
   triage routine matches on to recognise the message as feedback.
5. Return immediately. Do **not** block on triage; the target session will reply
   asynchronously with the new task UUID and a "plan now or backlog?" prompt
   that lands in this session's inbox. When that prompt arrives, treat it like
   any other user-visible message.

### Spawn recipe (Flow B fallback)

When no session for the target repo is alive, spawn one. Prefer `cmux`, fall
back to `tmux`, mirroring the conventions in
[`pi-intercom`](../../../../.cache/npm/lib/node_modules/pi-intercom/skills/pi-intercom/SKILL.md).
Substitute the target's working directory:

- **dotagents** target → `cd $HOME/dotfiles/agents-src`.
- **dotfiles** target → `cd $HOME/dotfiles`.

```bash
# Pick the target cwd:
TARGET_CWD="$HOME/dotfiles/agents-src"   # or "$HOME/dotfiles"
TARGET_NAME="dotagents-feedback"          # or "dotfiles-feedback"

# cmux preferred — visible split:
cmux new-split right
sleep 0.5
cmux send --surface right "cd $TARGET_CWD && pi\n"

# tmux fallback:
SOCKET_DIR=${TMPDIR:-/tmp}/pi-tmux-sockets
mkdir -p "$SOCKET_DIR"
SOCKET="$SOCKET_DIR/pi.sock"
tmux -S "$SOCKET" new -d -s "$TARGET_NAME" -c "$TARGET_CWD" 'pi'
```

After spawn, poll `intercom action: list` (a few times, ~1 s apart) until the
new session registers, then send. If the session never registers within a small
retry budget, surface the failure clearly to the user — do *not* silently drop
the feedback.

## Triage routine

Same routine for project-local entries (run in the originating session) and for
global inbound messages (run in the target repo's session: dotagents or
dotfiles).

1. **Parse** the envelope (or local-call args) → body + metadata + sender type.
2. **Classify affected target.** Identify the artefact the feedback is about and
   produce a single org tag of the form:
   - `:skill_<name>:` — a specific skill (`:skill_org-tasks:`).
   - `:ext_<name>:` — a pi extension (`:ext_jira:`).
   - `:agents-md:` — root or project `AGENTS.md`.
   - `:prompt_<name>:` — a named prompt.
   - `:project-convention:` — a project-only convention with no dedicated
     artefact yet.
   - `:tier-unknown:` — only when routing was ambiguous (see above). If multiple
     targets apply, attach multiple tags.
3. **Dedupe.** Search open entries under `* Agent feedback` in the *target*
   `TASKS.org` for near-duplicates: same target tag *and* significant keyword
   overlap with the new summary. If a match exists, append the new evidence (a
   new dated bullet in that entry's body, plus any new attachments quoted
   verbatim) *instead* of creating a parallel TODO. Tell the user / sender which
   existing UUID was extended.
4. **Draft summary + body** using the entry conventions below.
5. **Confirm** wording with the user *only when sender is `human`*. Show the
   proposed summary, body, tags, and target `TASKS.org` path; ask for thumbs up
   / edits before insertion. When sender is `agent`, skip confirmation — the
   entry is the agent's own observation, no human is on the sender end to
   confirm wording.
6. **Insert** via `tasks_insert_task` with:
   ```
   file: <target>/TASKS.org
   section: "Agent feedback"
   allowCreateSection: true
   summary: <draft summary>
   labels: [<target tags from step 2>]
   body: <draft body — see entry conventions>
   ```
7. **Acknowledge.** Tell the sender:
   - For local triage: prompt the user inline ("Filed as <UUID>. Plan now or
     backlog?").
   - For cross-tier triage in the target repo session: send back via
     `intercom action: send` (or `action: reply` if the inbound was an `ask`,
     though the standard flow uses `send`) to the originating session:
     *"[self-improvement] Filed as <UUID> in <target-label>/TASKS.org. Plan now
     or backlog?"*

## `* Agent feedback` entry conventions

### Heading

```
** TODO [#?] <one-line summary> :<target-tag>:
```

Priority cookie is optional and usually omitted at filing time; add it during
planning if useful.

### Body template

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

When triage merges new evidence into an existing entry, append a fresh `Origin:`
+ `Evidence:` block (with its own timestamp) to the existing body rather than
overwriting.

### Promotion to a change-record

When the entry is ready to be planned, follow the standard `org-plan` flow: a
change-record under the target repo's `#+DEFAULT_PLAN_DIR` (defaults to
`[[file:./design/log]]`), linked from the task via `#+IMPORT:`. Nothing about
this skill short-circuits that flow.

## Worked examples

### Tight loop

The user corrects the agent's commit headline to follow Conventional Commits.
The pi-side AGENTS.md doesn't document the convention (trigger 2 + 3 fit). The
current session is in the dotagents repo; the fix is a one-line addition to
`agents-src/AGENTS.md`. All tight-loop preconditions hold:

1. Agent drafts the diff:
   ```
   + - Match the existing commit style of the target repo.
   +   Inspect `git log --oneline` for the dominant pattern
   +   (e.g. Conventional Commits with `type(scope): subject`).
   ```
2. Shows the user with rationale: "missing guideline; just corrected me on
   this."
3. User approves → agent applies edit and commits immediately with
   `docs(agents): note repo commit-style convention` (a message that itself
   respects the convention being added).
4. Done. No `TASKS.org` entry needed.

### Slow loop — dotagents target

A user in `~/code/some-project` corrects the agent's misuse of
`tasks_insert_task` (the agent forgot `allowCreateSection`). Tracing back, the
pi-side `AGENTS.md` guideline for `tasks_insert_task` is unclear. The agent
self-proposes:

1. Classify tier: pi-side `AGENTS.md` resolves to
   `$HOME/dotfiles/agents-src/AGENTS.md` → **dotagents**.
2. Discover dotagents session via `intercom action: list` (filter `cwd` under
   `~/dotfiles/agents-src`). None alive → spawn via `cmux` recipe with
   `TARGET_CWD=$HOME/dotfiles/agents-src`.
3. Send envelope:
   ```
   [self-improvement] AGENTS.md guidance on tasks_insert_task
   misses allowCreateSection requirement

   The current snippet shows tasks_insert_task usage but doesn't
   call out that section creation requires
   allowCreateSection: true. I just hit this in some-project and
   so did the user (they had to remind me).

   Origin:
   - session: some-project
   - cwd: /Users/cormacc/code/some-project
   - git: git@github.com:user/some-project.git @ main
   - timestamp: [2026-04-29 Wed 10:15]
   - sender: agent
   ```
4. Originating session returns immediately and continues the user's actual task.
5. Dotagents session receives, parses, classifies tag `:agents-md:`, finds no
   near-duplicate, drafts summary + body, sees sender is `agent` → skips
   confirmation, inserts into `~/dotfiles/agents-src/TASKS.org` under
   `* Agent feedback`.
6. Dotagents session replies via `intercom action: send` to the originating
   session: *"[self-improvement] Filed as 01234567-… in dotagents/TASKS.org.
   Plan now or backlog?"*
7. The originating-session agent surfaces that prompt to the user when
   convenient.

### Slow loop — dotfiles target

The user complains that `home-manager switch` keeps re-staging
`agents-config/pi/settings.json` whenever the default model changes, and the
README's note on the clean filter is buried. The affected artefact is the
dotfiles-side `README.org` and the `agents-config/install-git-filter.sh` wiring.

1. Classify tier: both files resolve under `$HOME/dotfiles/` but **not** under
   `agents-src/` → **dotfiles**.
2. Discover dotfiles session (filter `cwd` under `~/dotfiles`, excluding
   `agents-src/`). None alive → spawn via `cmux` recipe with
   `TARGET_CWD=$HOME/dotfiles`.
3. Send the `[self-improvement]` envelope as above, addressed to the dotfiles
   session.
4. Dotfiles session triages, inserts into `~/dotfiles/TASKS.org` under
   `* Agent feedback`, replies with the new UUID.

## See also

- [`../org-tasks/SKILL.md`](../org-tasks/SKILL.md) — `TASKS.org` protocol,
  `tasks_insert_task` insertion, idempotency rules.
- [`../org-plan/SKILL.md`](../org-plan/SKILL.md) — promoting an entry into a
  planned change-record.
- [`pi-intercom`](../../../../.cache/npm/lib/node_modules/pi-intercom/skills/pi-intercom/SKILL.md)
  — transport semantics (`send` / `ask` / `reply` / `list`), spawn recipes.
- `~/dotfiles/agents.nix` — the wiring that symlinks `~/dotfiles/agents-src/`
  into `~/.agents/skills` and
  `~/.pi/agent/{AGENTS.md,prompts,extensions,skills}`. Useful when verifying
  which tier a symlinked path belongs to.
