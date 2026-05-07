---
name: org-jira
description: "Use when working with tasks that link to Jira issues. Covers Jira-specific authoring conventions on top of the org-tasks protocol — :LINKED_ISSUES: drawer key shape, #+JIRA_* keywords, agent prompts that drive the atlassian MCP for clone/claim/comment/create/transition workflows."
---

# Jira integration for org-tasks

This skill extends [`org-tasks`](../org-tasks/SKILL.md) with
Jira-specific authoring conventions and agent prompts. Use it when:

- A task references one or more Jira issues.
- The user asks to clone, claim, comment on, transition, or create a
  Jira issue.
- The user wants to know the Atlassian MCP connection state.

This skill owns Jira semantics. The underlying file format
(`:LINKED_ISSUES:` drawer property, `#+ISSUE_URL_BASE` keyword, badge
rendering, browser-open) is owned by the [`tasks`
extension](../../pi/extensions/tasks/README.md#linked-external-issues)
and is *tracker-agnostic* — the rules below apply only to Jira-shaped
links.

## File-format conventions

### Issue keys

Jira keys are stored as **bare `PROJ-NNN` tokens** in the generic
`:LINKED_ISSUES:` drawer property defined by the `tasks` extension:

```org
* TODO Refactor stim driver
:PROPERTIES:
:ID: 01234567-89ab-4def-8123-456789abcdef
:LINKED_ISSUES: MBFW-123 MBE-45
:END:
```

- Validation regex: `^[A-Z][A-Z0-9_]+-\d+$`.
- Whitespace-separated; `:LINKED_ISSUES:` is multi-valued.
- A single task may link to many Jira issues; mixing bare Jira keys
  with non-Jira org-link tokens (`[[https://github.com/.../issues/42][gh#42]]`)
  in the same drawer line is supported.
- The property is created on first link only — never auto-backfilled
  on existing tasks (mirrors `:STARTED:` behaviour).

### File-level keywords

`TASKS.org` (overridable in `TASKS.local.org`):

```org
#+ISSUE_URL_BASE: https://your-org.atlassian.net/browse/{ID}
#+JIRA_CLOUDID: 00000000-0000-4000-8000-000000000000
#+JIRA_PROJECT: MBFW
#+JIRA_BASE_URL: https://your-org.atlassian.net
```

| Keyword            | Owner          | Purpose                                                    |
| ------------------ | -------------- | ---------------------------------------------------------- |
| `#+ISSUE_URL_BASE` | `tasks`        | URL template for bare keys; rendered badges & `J` open.    |
| `#+JIRA_CLOUDID`   | `jira`         | MCP routing: skip `getAccessibleAtlassianResources`.       |
| `#+JIRA_PROJECT`   | `jira`         | Default project for `/jira create`; short-key disambiguation. |
| `#+JIRA_BASE_URL`  | `jira`         | Filter `:LINKED_ISSUES:` for Jira-shaped tokens.           |

The `tasks` extension reads only `#+ISSUE_URL_BASE`. The three
`#+JIRA_*` keywords are read only by the `jira` extension and this
skill.

`TASKS.local.org` overrides any of these (last-write-wins, mirroring
`#+SELECTED:`). Useful for per-checkout overrides like a different
default project.

### Trust boundary

`#+ISSUE_URL_BASE` and `#+JIRA_*` keywords are project-local trusted
configuration. Values from `TASKS.local.org` are part of the user's
checkout-local trust boundary, not untrusted remote input. Non-HTTPS
issue URL bases are allowed; opener implementations must pass URLs as
arguments rather than shell-interpolated command strings.

### Identifying Jira tokens within `:LINKED_ISSUES:`

When `/jira *` commands need to operate only on Jira-shaped tokens
(claim, transition, comment), they apply this filter:

1. **Bare token** matches `/^[A-Z][A-Z0-9_]+-\d+$/` → Jira key.
2. **Org-link token** `[[url][label]]` whose target host matches
   `#+JIRA_BASE_URL` → Jira key. (Uncommon — Jira keys are usually
   stored bare so they can be reused across machines.)

Tokens that match neither are silently ignored by Jira workflows — a
task carrying `MBFW-123 [[https://github.com/foo/bar/issues/42][gh#42]]`
exposes only `MBFW-123` to `/jira claim`.

## Atlassian MCP connection

All Jira read/write goes through the `atlassian` MCP server, exclusively
driven by the agent. The `jira` extension itself is I/O-free and only
detects connection state by checking `pi.getAllTools()` for tools
matching the `atlassian_` prefix.

Before any Jira workflow, ensure the MCP is connected:

```
/mcp reconnect atlassian
```

If `/jira` (the status command) reports "disconnected", surface the
reconnect instruction to the user rather than retrying.

### cloudId resolution

When making MCP calls, prefer `#+JIRA_CLOUDID` from the file. If absent:

1. Call `atlassian_getAccessibleAtlassianResources`.
2. Pick the resource whose `url` field equals `#+JIRA_BASE_URL`.
3. Use its `id` as the `cloudId` for subsequent calls.

## Workflows

All workflows below are **agent-driven**: the slash command drafts a
structured prompt that *you*, the agent, dispatch to MCP tools. The
extension code itself never invokes MCP tools directly.

### Reference (read-only)

No prompt needed. The user types a Jira key into `:LINKED_ISSUES:` and
sets `#+ISSUE_URL_BASE` once; `tasks` renders badges and `J` opens
URLs. Fully offline-safe.

### Planning / resume context (tasks with `:LINKED_ISSUES:`)

When the [`org-plan`](../org-plan/SKILL.md) skill is drafting or
refining a change-record — or when an agent is resuming work — for a
task that has Jira-shaped tokens in `:LINKED_ISSUES:`, fetch the issue
tree from Jira *before* writing or relying on `* Context` so the plan
reflects current upstream scope, decomposition, and language. This
applies whether the plan is proactive, retrospective, or resumed after
another session.

Procedure for each Jira-shaped token:

1. Ensure the Atlassian MCP is connected (see above). If not, surface
   the reconnect instruction and proceed without the Jira context
   rather than blocking the plan.
2. Resolve the cloudId per "cloudId resolution" above.
3. Call `atlassian_getJiraIssue` for the parent key. Capture:
   - `summary`, `status.name`, `issuetype.name`, `priority.name`,
     `assignee.displayName`.
   - `description` (plain-text rendering of the ADF body).
   - `issuelinks` — note `Blocks`, `is blocked by`, `relates to`
     relationships; their keys are candidates for follow-up fetches
     when relevant to scope.
   - `parent.key` if present (issue sits under an Epic / parent task).
4. Walk children one level at a time, depth-first only when a child's
   summary suggests it materially shapes the plan. Use
   `atlassian_searchJiraIssuesUsingJql` with the appropriate clause:
   - **Epic** → `"parent" = KEY` (covers stories/tasks under the Epic
     in modern Jira; legacy projects may need `"Epic Link" = KEY`).
   - **Task / Story / Bug** → `parent = KEY` (returns subtasks).
   - Project the same fields as step 3 (`summary,status,issuetype,
     priority,assignee,parent`) and request a generous `fields` list
     plus a sensible `maxResults` (50 is usually enough; raise if a
     page boundary is hit).
5. Stop descending when:
   - A subtree is `Done` / `Closed` and not load-bearing for the new
     work, or
   - The branch is clearly out-of-scope for the task at hand, or
   - Depth exceeds two levels below the linked issue (deeper trees
     are rare and almost always noise for planning).
6. Summarise the gathered tree into `* Context` of the change-record:
   - One short paragraph naming each linked parent issue (key,
     summary, status, type) and how it frames the task.
   - A bullet list of in-scope children with their key, summary, and
     status. Use this list to seed `** Design decisions` or to derive
     fresh level-2 `* Plan` headings when the user wants the plan to
     track Jira decomposition one-to-one. Jira keys are never org
     `:ID:` values; Jira-derived plan tasks get normal UUIDs and link
     back via `:LINKED_ISSUES:`.
   - Note any `Blocks` / `is blocked by` relationships in `* Context`
     so dependencies are visible at planning time.
7. Do **not** mint new Jira issues from this read-only walk. Surface
   gaps ("the linked Epic has no subtasks covering X") in
   `* Open questions` and let the user decide whether to
   `/jira create` them.

Keep the fetched data ephemeral — do not paste raw issue JSON or full
ADF descriptions into the change-record. Distil to plan-relevant
prose and bullets. Re-fetch on subsequent planning or resume sessions
rather than caching, since Jira state drifts.

Subtask migration from `TASKS.org` into a change-record (owned by
`org-tasks` / `org-plan`) is orthogonal to Jira fetching. A plan may
contain migrated local subtasks with their original UUIDs and separate
Jira-derived plan tasks with fresh UUIDs linked via `:LINKED_ISSUES:`.

### Clone (`/jira clone <KEY>`)

*Two-step dispatch:* the slash command builds a prompt that asks the
agent to call `atlassian_getJiraIssue`, then forward the parsed
fields to the registered `jira_clone_apply` tool. The agent never
assembles the org task heading, drawer, or body via the `edit` tool;
all org-mode rendering lives in the `tasks` extension's
`tasks_insert_task` primitive (priority cookie, UUID, `:CREATED:`
timestamp, `:LINKED_ISSUES:` drawer line, label tag suffix).

1. Validate `KEY` against the regex above. If the user passes a bare
   number (`123`), prepend `#+JIRA_PROJECT-` (or refuse if
   `#+JIRA_PROJECT` is absent). The slash-command code already does
   this via `resolveKey()`.
2. Call `atlassian_getJiraIssue` with the resolved cloudId, the issue
   key, and `fields="summary,priority,labels,description,issuetype,parent,subtasks"`
   to keep the response small. Do not request `*all` or expand
   customfields.
3. Render the issue description as plain text/markdown (Jira ADF →
   markdown-ish; never embed raw ADF JSON). Apply small cleanups
   inline (collapse broken `| --- |` table rows, trim noisy summary
   boilerplate). Keep the result short.
4. Call `jira_clone_apply` with structured args:
   - `key` — the issue key.
   - `summary` — `issue.fields.summary` verbatim (after any small
     surgery).
   - `priorityName` — the priority name string
     (`Highest`/`High`/`Medium`/`Low`/`Lowest`); omit when missing or
     unknown.
   - `body` — the rendered description from step 3.
   - `labels` — the issue's label list (may be empty).
   - `file` — default `TASKS.org`; pass `TASKS.local.org` only when
     the user is working on local drafts.
   - `section` — default `Improvements`; pass an explicit section if
     the user has been working in a different one.
5. Surface the tool's structured return verbatim:
   - `status: "inserted"` — confirm with the new heading and Jira URL.
   - `status: "duplicate"` — cite `details.existingId` and refuse to
     re-clone (idempotency: the same `:LINKED_ISSUES:` token already
     appears somewhere in TASKS.org / TASKS.local.org / their
     imports).
   - `status: "section_not_found"` — ask whether to retry with
     `allowCreateSection: true` or correct the section name.
   - `status: "error"` — surface the message verbatim.
6. Smoke test against `SAND` only.

### Get (`/jira get <KEY>`)

A standalone display affordance that prints a compact human-readable
block per key (heading, status, priority, labels, parent, subtask
count, description preview, footer URL). No file writes; no
`jira_clone_apply` involvement. Reuses the same field filter as the
clone path.

The slash command builds the prompt deterministically; the agent
simply executes:

1. Resolve cloudId per the standard rule.
2. Call `atlassian_getJiraIssue` with the field filter.
3. Render the per-key block (do not paste raw JSON).
4. Repeat for each remaining key, separated by a blank line.

Use this when the user wants to *inspect* an issue without committing
it to TASKS.org. It is the read-only counterpart of `/jira clone`.

### Claim (`/jira claim`)

1. Resolve the cursor task's `:LINKED_ISSUES:` and filter to Jira-shaped
   tokens.
2. Call `atlassian_atlassianUserInfo` once to get the current user's
   accountId.
3. For each Jira key, call `atlassian_editJiraIssue` setting
   `assignee.accountId`.
4. Surface a one-line summary per key (key, success / error message).

### Comment (`/jira comment <markdown>`)

1. Filter `:LINKED_ISSUES:` for Jira tokens.
2. For each, call `atlassian_addCommentToJiraIssue` with the markdown
   body. The MCP server handles markdown → ADF conversion.
3. Surface a one-line summary per key.

### Create (`/jira create [PROJECT] [--type Task|Story|Bug|Epic]`)

1. Project defaults to `#+JIRA_PROJECT`. Refuse if neither argument nor
   keyword provides one.
2. Issue type defaults to `Task`. Validate via
   `atlassian_getJiraProjectIssueTypesMetadata` before submitting.
3. Call `atlassian_createJiraIssue` with summary = task heading,
   description = task body.
4. On success, append the returned key to the task's `:LINKED_ISSUES:`
   via `setDrawerProperty`.
5. Smoke test against `SAND` only.

### Transition (auto, optional)

When the user toggles a task's status (`TODO → STARTED → DONE`), and
the `jira` extension's `autoTransition` setting is enabled, attempt to
reflect the live status-change event on every Jira-shaped token. Use
the current `tasks:status-changed` payload; do not replay historical
`:LOGBOOK:` entries as queued Jira transitions:

1. Call `atlassian_getTransitionsForJiraIssue`.
2. Pick a transition whose name matches the target state by convention:
   - `STARTED` → "Start Progress" or "In Progress".
   - `DONE` → "Done", "Closed", or "Resolved" (try in order).
3. If no match, surface a chooser to the user instead of guessing.

This requires `tasks` to publish status-change events on the pi event
bus. LOGBOOK is durable audit history for resume/review; live event
payloads are the trigger for Jira writes. If status-change events are
unavailable, the auto-transition flow blocks until the prerequisite
extension point lands.

## Question-handling

Default mode for this work: batch minor implementation ambiguities into
the parent change-record's `* Open questions`; raise immediately
anything that affects downstream design (extension API, data shape,
cross-extension contract). See the parent
`design/log/2026-04-28-jira-integration.org` for the latest list.

## Sandbox

All write-path development and smoke testing runs against project `SAND`.
Never call `editJiraIssue`, `addCommentToJiraIssue`, `createJiraIssue`,
or `transitionJiraIssue` against any other project until the relevant
plan stage is signed off.

## Offline / disconnected behaviour

The badge display and `J` browser-open work unconditionally because they
live in `tasks` and don't touch the MCP. Anything in this skill that
needs the MCP must surface a clear notification ("Atlassian MCP not
connected — run /mcp reconnect atlassian") rather than failing
silently.
