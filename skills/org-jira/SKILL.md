---
name: org-jira
description: "Jira semantics over org-tasks. Use for Jira keys (PROJ-123), /jira commands, :LINKED_ISSUES:, #+LINK: jira, #+JIRA_* keywords, Jira epic/issue planning, or Atlassian MCP questions."
---

# Jira integration for org-tasks

Extends [`org-tasks`](../org-tasks/SKILL.md) with Jira-specific authoring conventions and agent prompts. Use when a task references a Jira issue, or the user asks to clone / claim / comment / transition / create a Jira issue, or wants to know the Atlassian MCP connection state.

This skill owns Jira *semantics*. The underlying file format (`:LINKED_ISSUES:` drawer, `#+LINK:` declarations, badge rendering, browser open) is owned by the [`tasks` extension](../../pi/extensions/tasks/README.md#linked-external-issues) and is tracker-agnostic. Issue-key format, cloudId resolution rules, and the exact Jira-token filter live in [`references/protocol.md`](references/protocol.md).

## Atlassian MCP connection

All Jira read/write goes through the `atlassian` MCP server, agent-driven. The `jira` extension is I/O-free and only detects connection state by checking `pi.getAllTools()` for tools matching the `atlassian_` prefix.

Before any Jira workflow, ensure the MCP is connected:

```
/mcp reconnect atlassian
```

If `/jira` (status) reports "disconnected", surface the reconnect instruction to the user rather than retrying.

## Workflows

All workflows are agent-driven: the slash command drafts a structured prompt that *you*, the agent, dispatch to MCP tools. The extension never invokes MCP tools directly. See `references/protocol.md` for cloudId resolution and the Jira-token filter applied across these workflows.

### Reference (read-only)

No prompt needed. The user adds `[[jira:KEY]]` to `:LINKED_ISSUES:` and sets `#+LINK: jira <base>/browse/%s` once. `tasks` renders badges and `J` opens URLs. Fully offline-safe.

### Planning / resume context

When [`org-plan`](../org-plan/SKILL.md) is drafting/refining a change-record -- or when an agent resumes a task with Jira-shaped `:LINKED_ISSUES:` -- fetch current Jira scope before relying on stale local prose. Keep fetched data ephemeral: distil only plan-relevant facts into the change-record per `org-plan`'s section contract (`* Summary` first, promote `* Context` only when the Jira rationale/scope exceeds the summary, use `* Open questions` for gaps).

For each Jira-shaped token:

1. Ensure the MCP is connected; if not, surface the reconnect instruction and proceed without blocking the plan.
2. Resolve cloudId.
3. Fetch the parent with `atlassian_getJiraIssue` (summary, status, issue type, priority, assignee, plain-text description, parent key, relevant issue links).
4. Walk children only while they materially shape scope. Epics use `"parent" = KEY` (or legacy `"Epic Link" = KEY`). Tasks/Stories/Bugs use `parent = KEY`. Stop at done/out-of-scope branches or beyond two levels below the linked issue.
5. Distil the walk. Name each linked parent and why it frames the work. Include in-scope children only when they affect the plan. Record blockers. Surface gaps in `* Open questions`. Do **not** mint Jira issues during this read-only walk. Jira keys are never org `:CUSTOM_ID:` values -- link Jira-derived local tasks via `:LINKED_ISSUES:`.

Re-fetch on later sessions rather than caching raw Jira JSON or ADF.

### Clone (`/jira clone <KEY>`)

Two-step dispatch -- the slash command asks the agent to call `atlassian_getJiraIssue`, then forward parsed fields to the registered `jira_clone_apply` tool. The agent never assembles the org heading / drawer / body via `edit`; all rendering lives in the `tasks` extension.

1. Validate `KEY` against the regex. Bare numeric input is prepended with `#+JIRA_PROJECT-` (or refused if absent). The slash command already does this via `resolveKey()`.
2. Call `atlassian_getJiraIssue` with the resolved cloudId and `fields="summary,priority,labels,description,issuetype,parent,subtasks"`. Do not request `*all` or expand customfields.
3. Render the description as plain text / markdown (Jira ADF → markdown-ish; never embed raw ADF JSON). Apply small cleanups inline (collapse broken `| --- |` table rows, trim noisy summary boilerplate). Keep it short.
4. Call `jira_clone_apply` with: `key`, `summary` (verbatim), `priorityName` (omit if missing/unknown), `body` (rendered), `labels`, `file` (default `TASKS.org`), `section` (default `Improvements`).
5. Surface the tool's structured return verbatim. See `references/protocol.md` for the `status` values and how to react.
6. Smoke test against `SAND` only.

### Get (`/jira get <KEY>`)

Standalone display affordance -- prints a compact per-key block (heading, status, priority, labels, parent, subtask count, description preview, URL). No file writes. Reuses the clone field filter.

1. Resolve cloudId.
2. Call `atlassian_getJiraIssue` with the field filter.
3. Render the per-key block (no raw JSON).
4. Repeat for each key, separated by a blank line.

Read-only counterpart of `/jira clone`.

### Claim (`/jira claim`)

Filter the cursor task's `:LINKED_ISSUES:` for Jira tokens, fetch the current user's accountId via `atlassian_atlassianUserInfo` (once), then call `atlassian_editJiraIssue` per key setting `assignee.accountId`. Surface a one-line per-key result.

### Comment (`/jira comment <markdown>`)

Filter `:LINKED_ISSUES:` for Jira tokens, call `atlassian_addCommentToJiraIssue` per key with the markdown body (the MCP server handles markdown → ADF). One-line per-key result.

### Create (`/jira create [PROJECT] [--type Task|Story|Bug|Epic]`)

Project defaults to `#+JIRA_PROJECT` (refuse if neither argument nor keyword provides one). Type defaults to `Task`; validate via `atlassian_getJiraProjectIssueTypesMetadata` before submitting. Call `atlassian_createJiraIssue` with summary = task heading and description = task body; on success append the returned key to the task's `:LINKED_ISSUES:` via `setDrawerProperty`. Smoke test against `SAND` only.

### Transition (auto, optional)

When the user toggles a task's status (`TODO → STARTED → DONE`) and the `jira` extension's `autoTransition` setting is enabled, reflect the live status-change event on every Jira-shaped token via `atlassian_getTransitionsForJiraIssue` + name-match (see `references/protocol.md` for the exact mapping). If no match, surface a chooser instead of guessing.

If `tasks:status-changed` events are unavailable, auto-transition blocks until that extension point lands.

## Question-handling

Follow `org-plan` § *Executing from a change-record*. Batch minor ambiguities into `* Open questions`. Raise design-affecting questions (extension API, data shape, cross-extension contract) immediately.

## Sandbox

All write-path development and smoke testing runs against project `SAND`. Never call `editJiraIssue`, `addCommentToJiraIssue`, `createJiraIssue`, or `transitionJiraIssue` against any other project until the relevant plan stage is signed off.

## Offline / disconnected behaviour

Badge display and `J` browser-open work unconditionally (live in `tasks`, don't touch the MCP). Anything in this skill that needs the MCP must surface a clear notification ("Atlassian MCP not connected -- run /mcp reconnect atlassian") rather than failing silently.
