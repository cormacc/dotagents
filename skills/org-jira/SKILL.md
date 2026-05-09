---
name: org-jira
description: "Jira-specific authoring conventions on top of the org-tasks protocol. Use whenever the user mentions Jira keys (PROJ-123), Atlassian, /jira slash commands (clone/get/claim/comment/create/transition), :LINKED_ISSUES:, or #+JIRA_* keywords; wants to plan from a Jira Epic or issue tree; or asks about the Atlassian MCP connection. Owns Jira semantics; the underlying tracker-agnostic :LINKED_ISSUES: drawer and #+ISSUE_URL_BASE keyword belong to the tasks extension."
---

# Jira integration for org-tasks

This skill extends [`org-tasks`](../org-tasks/SKILL.md) with Jira-specific
authoring conventions and agent prompts. Use it when:

- A task references one or more Jira issues.
- The user asks to clone, claim, comment on, transition, or create a Jira issue.
- The user wants to know the Atlassian MCP connection state.

This skill owns Jira semantics. The underlying file format (`:LINKED_ISSUES:`
drawer property, `#+ISSUE_URL_BASE` keyword, badge rendering, browser-open) is
owned by the [`tasks`
extension](../../pi/extensions/tasks/README.md#linked-external-issues) and is
*tracker-agnostic* — the rules below apply only to Jira-shaped links.

## File-format conventions

### Issue keys

Jira keys are stored as **bare `PROJ-NNN` tokens** in the generic
`:LINKED_ISSUES:` drawer property defined by the `tasks` extension:

```org
* TODO Refactor stim driver
:PROPERTIES:
:CUSTOM_ID: 01234567-89ab-4def-8123-456789abcdef
:LINKED_ISSUES: MBFW-123 MBE-45
:END:
```

- Validation regex: `^[A-Z][A-Z0-9_]+-\d+$`.
- Whitespace-separated; `:LINKED_ISSUES:` is multi-valued.
- A single task may link to many Jira issues; mixing bare Jira keys with
  non-Jira org-link tokens (`[[https://github.com/.../issues/42][gh#42]]`) in
  the same drawer line is supported.
- The property is created on first link only — never auto-backfilled on existing
  tasks (mirrors `:STARTED:` behaviour).

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

The `tasks` extension reads only `#+ISSUE_URL_BASE`. The three `#+JIRA_*`
keywords are read only by the `jira` extension and this skill.

`TASKS.local.org` overrides any of these (last-write-wins, mirroring
`#+SELECTED:`). Useful for per-checkout overrides like a different default
project.

### Trust boundary

`#+ISSUE_URL_BASE` and `#+JIRA_*` keywords are project-local trusted
configuration. Values from `TASKS.local.org` are part of the user's
checkout-local trust boundary, not untrusted remote input. Non-HTTPS issue URL
bases are allowed; opener implementations must pass URLs as arguments rather
than shell-interpolated command strings.

### Identifying Jira tokens within `:LINKED_ISSUES:`

When `/jira *` commands need to operate only on Jira-shaped tokens (claim,
transition, comment), they apply this filter:

1. **Bare token** matches `/^[A-Z][A-Z0-9_]+-\d+$/` → Jira key.
2. **Org-link token** `[[url][label]]` whose target host matches
   `#+JIRA_BASE_URL` → Jira key. (Uncommon — Jira keys are usually stored bare
   so they can be reused across machines.)

Tokens that match neither are silently ignored by Jira workflows — a task
carrying `MBFW-123 [[https://github.com/foo/bar/issues/42][gh#42]]` exposes only
`MBFW-123` to `/jira claim`.

## Atlassian MCP connection

All Jira read/write goes through the `atlassian` MCP server, exclusively driven
by the agent. The `jira` extension itself is I/O-free and only detects
connection state by checking `pi.getAllTools()` for tools matching the
`atlassian_` prefix.

Before any Jira workflow, ensure the MCP is connected:

```
/mcp reconnect atlassian
```

If `/jira` (the status command) reports "disconnected", surface the reconnect
instruction to the user rather than retrying.

### cloudId resolution

When making MCP calls, prefer `#+JIRA_CLOUDID` from the file. If absent:

1. Call `atlassian_getAccessibleAtlassianResources`.
2. Pick the resource whose `url` field equals `#+JIRA_BASE_URL`.
3. Use its `id` as the `cloudId` for subsequent calls.

## Workflows

All workflows below are **agent-driven**: the slash command drafts a structured
prompt that *you*, the agent, dispatch to MCP tools. The extension code itself
never invokes MCP tools directly.

### Reference (read-only)

No prompt needed. The user types a Jira key into `:LINKED_ISSUES:` and sets
`#+ISSUE_URL_BASE` once; `tasks` renders badges and `J` opens URLs. Fully
offline-safe.

### Planning / resume context (tasks with `:LINKED_ISSUES:`)

When [`org-plan`](../org-plan/SKILL.md) is drafting/refining a change-record —
or when an agent resumes work — for a task with Jira-shaped `:LINKED_ISSUES:`,
fetch current Jira scope before relying on stale local prose. Keep fetched data
ephemeral: distil only plan-relevant facts into the change-record according to
`org-plan`'s section contract (`* Summary` first, promote `* Context` only when
the Jira rationale/scope exceeds the summary, use `* Open questions` for gaps).

Procedure for each Jira-shaped token:

1. Ensure the Atlassian MCP is connected (see above). If not, surface the
   reconnect instruction and proceed without blocking the plan.
2. Resolve the cloudId per "cloudId resolution" above.
3. Fetch the parent with `atlassian_getJiraIssue`, capturing summary, status,
   issue type, priority, assignee, plain-text description, parent key, and
   relevant issue links (`Blocks`, `is blocked by`, `relates to`).
4. Walk children only while they materially shape scope. For Epics use
   `"parent" = KEY` (or legacy `"Epic Link" = KEY` if needed); for
   Tasks/Stories/Bugs use `parent = KEY`. Stop at done/out-of-scope branches or
   beyond two levels below the linked issue.
5. Distil results:
   - Mention each linked parent issue (key, summary, status/type) and why it
     frames the work.
   - Include in-scope children only when they affect the plan; create local plan
     tasks with normal UUIDs only when tracking Jira decomposition one-to-one is
     useful. Jira keys are never org `:CUSTOM_ID:` values; link Jira-derived
     local tasks via `:LINKED_ISSUES:`.
   - Record blocking relationships where they affect planning.
   - Surface gaps in `* Open questions`; do **not** mint Jira issues during this
     read-only walk.

Re-fetch on later planning/resume sessions rather than caching raw Jira JSON or
full ADF descriptions. Subtask migration from `TASKS.org` into `* Plan` is owned
by `org-plan` and remains orthogonal to Jira-derived plan tasks.

### Clone (`/jira clone <KEY>`)

*Two-step dispatch:* the slash command builds a prompt that asks the agent to
call `atlassian_getJiraIssue`, then forward the parsed fields to the registered
`jira_clone_apply` tool. The agent never assembles the org task heading, drawer,
or body via the `edit` tool; all org-mode rendering lives in the `tasks`
extension's `tasks_insert_task` primitive (priority cookie, UUID, `:CREATED:`
timestamp, `:LINKED_ISSUES:` drawer line, label tag suffix).

1. Validate `KEY` against the regex above. If the user passes a bare number
   (`123`), prepend `#+JIRA_PROJECT-` (or refuse if `#+JIRA_PROJECT` is absent).
   The slash-command code already does this via `resolveKey()`.
2. Call `atlassian_getJiraIssue` with the resolved cloudId, the issue key, and
   `fields="summary,priority,labels,description,issuetype,parent,subtasks"` to
   keep the response small. Do not request `*all` or expand customfields.
3. Render the issue description as plain text/markdown (Jira ADF → markdown-ish;
   never embed raw ADF JSON). Apply small cleanups inline (collapse broken
   `| --- |` table rows, trim noisy summary boilerplate). Keep the result short.
4. Call `jira_clone_apply` with structured args:
   - `key` — the issue key.
   - `summary` — `issue.fields.summary` verbatim (after any small surgery).
   - `priorityName` — the priority name string
     (`Highest`/`High`/`Medium`/`Low`/`Lowest`); omit when missing or unknown.
   - `body` — the rendered description from step 3.
   - `labels` — the issue's label list (may be empty).
   - `file` — default `TASKS.org`; pass `TASKS.local.org` only when the user is
     working on local drafts.
   - `section` — default `Improvements`; pass an explicit section if the user
     has been working in a different one.
5. Surface the tool's structured return verbatim:
   - `status: "inserted"` — confirm with the new heading and Jira URL.
   - `status: "duplicate"` — cite `details.existingId` and refuse to re-clone
     (idempotency: the same `:LINKED_ISSUES:` token already appears somewhere in
     TASKS.org / TASKS.local.org / their imports).
   - `status: "section_not_found"` — ask whether to retry with
     `allowCreateSection: true` or correct the section name.
   - `status: "error"` — surface the message verbatim.
6. Smoke test against `SAND` only.

### Get (`/jira get <KEY>`)

A standalone display affordance that prints a compact human-readable block per
key (heading, status, priority, labels, parent, subtask count, description
preview, footer URL). No file writes; no `jira_clone_apply` involvement. Reuses
the same field filter as the clone path.

The slash command builds the prompt deterministically; the agent simply
executes:

1. Resolve cloudId per the standard rule.
2. Call `atlassian_getJiraIssue` with the field filter.
3. Render the per-key block (do not paste raw JSON).
4. Repeat for each remaining key, separated by a blank line.

Use this when the user wants to *inspect* an issue without committing it to
TASKS.org. It is the read-only counterpart of `/jira clone`.

### Claim (`/jira claim`)

Filter the cursor task's `:LINKED_ISSUES:` for Jira tokens, fetch the current
user's accountId via `atlassian_atlassianUserInfo` (once), then call
`atlassian_editJiraIssue` per key setting `assignee.accountId`. Surface a
one-line per-key result.

### Comment (`/jira comment <markdown>`)

Filter `:LINKED_ISSUES:` for Jira tokens and call
`atlassian_addCommentToJiraIssue` per key with the markdown body (the MCP server
handles markdown → ADF). Surface a one-line per-key result.

### Create (`/jira create [PROJECT] [--type Task|Story|Bug|Epic]`)

Project defaults to `#+JIRA_PROJECT` (refuse if neither argument nor keyword
provides one). Issue type defaults to `Task`; validate via
`atlassian_getJiraProjectIssueTypesMetadata` before submitting. Call
`atlassian_createJiraIssue` with summary = task heading and description = task
body; on success append the returned key to the task's `:LINKED_ISSUES:` via
`setDrawerProperty`. Smoke test against `SAND` only.

### Transition (auto, optional)

When the user toggles a task's status (`TODO → STARTED → DONE`), and the `jira`
extension's `autoTransition` setting is enabled, attempt to reflect the live
status-change event on every Jira-shaped token. Use the current
`tasks:status-changed` payload; do not replay historical `:LOGBOOK:` entries as
queued Jira transitions:

1. Call `atlassian_getTransitionsForJiraIssue`.
2. Pick a transition whose name matches the target state by convention:
   - `STARTED` → "Start Progress" or "In Progress".
   - `DONE` → "Done", "Closed", or "Resolved" (try in order).
3. If no match, surface a chooser to the user instead of guessing.

This requires `tasks` to publish status-change events on the pi event bus.
LOGBOOK is durable audit history for resume/review; live event payloads are the
trigger for Jira writes. If status-change events are unavailable, the
auto-transition flow blocks until the prerequisite extension point lands.

## Question-handling

Follow `org-plan` § *Executing from a change-record*: batch minor ambiguities
into `* Open questions`; raise design-affecting questions (extension API, data
shape, cross-extension contract) immediately.

## Sandbox

All write-path development and smoke testing runs against project `SAND`. Never
call `editJiraIssue`, `addCommentToJiraIssue`, `createJiraIssue`, or
`transitionJiraIssue` against any other project until the relevant plan stage is
signed off.

## Offline / disconnected behaviour

The badge display and `J` browser-open work unconditionally because they live in
`tasks` and don't touch the MCP. Anything in this skill that needs the MCP must
surface a clear notification ("Atlassian MCP not connected — run /mcp reconnect
atlassian") rather than failing silently.
