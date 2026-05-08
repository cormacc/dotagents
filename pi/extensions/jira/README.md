# Jira Extension

Agent-driven Jira workflows backed by the [Atlassian
MCP](https://developer.atlassian.com/) server. Owns slash commands, MCP
routing, and Jira-specific authoring conventions; stays composable on top
of the generic `tasks` extension's tracker-agnostic linkage features
(`:LINKED_ISSUES:` drawer property + `#+ISSUE_URL_BASE` keyword).

## Status

Read and write workflows implemented (status / clone / get / claim /
comment / create). Optional `autoTransition` on live local status-change events is
implemented as an event listener on `tasks:status-changed`; off by
default, opt in via `~/.pi/agent/jira-ext.json`. Durable task LOGBOOK
history is audit evidence and is not replayed as a queue of Jira
transitions.

## Commands

| Command                                | Status      | Description                                            |
| -------------------------------------- | ----------- | ------------------------------------------------------ |
| `/jira`                                | Implemented | Print Atlassian MCP connection status.                 |
| `/jira status`                         | Implemented | Alias for `/jira`.                                     |
| `/jira clone KEY [KEY...]`             | Implemented | Pull issue(s) from Jira → create local task(s) via the `jira_clone_apply` tool. |
| `/jira get KEY [KEY...]`               | Implemented | Render a compact human-readable summary of one or more issues. No file writes. |
| `/jira claim`                          | Implemented | Set assignee on every Jira-shaped issue on the selected task. |
| `/jira comment <markdown>`             | Implemented | Add a comment to every Jira-shaped issue on the selected task. |
| `/jira create [PROJECT] [--type Type]` | Implemented | Promote the selected task to a new Jira issue.         |
| auto-transition (no command)           | Implemented | Reflect live local status changes on linked Jira issues. Off by default. |

## Tools

The extension also registers an LLM-callable tool that handles the
emission-side cost of `/jira clone` (so the rendered org body never
round-trips through the model):

- **`jira_clone_apply`** — takes structured Jira fields
  (`key`, `summary`, `priorityName?`, `body?`, `labels?`, `file?`,
  `section?`, `allowCreateSection?`) and delegates the org write to
  the `tasks` extension's `tasks_insert_task` primitive (see
  `pi/extensions/tasks/README.md#cross-extension-tools`). All
  org-mode string assembly (drawer, UUID, `:CREATED:`, priority
  cookie, tag suffix, `:LINKED_ISSUES:`) happens inside
  `tasks_insert_task` — not in this extension and not in the model.

The `/jira clone` slash command instructs the agent in a *two-step
dispatch*: call `atlassian_getJiraIssue` (with the existing field
filter), then `jira_clone_apply` with the parsed fields. The agent
never assembles drawer text via the `edit` tool.

All workflows are *agent-driven*: the slash command drafts a structured
prompt (using the conventions in the `org-jira` skill) and dispatches
it via `pi.sendUserMessage`. The agent then performs the actual MCP
calls and TASKS-file edits. The extension itself stays I/O-free for
write paths.

## Connection model

The extension itself is I/O-free. All Jira access is mediated by the
agent through the `atlassian` MCP server. To connect:

```
/mcp reconnect atlassian
```

After reconnect, `pi.getAllTools()` exposes a set of `atlassian_*` tools
(issue read/write, transitions, comments, JQL search, etc.). The
extension uses the presence of those tools as a connection-status proxy
without invoking them directly.

## Linkage to tasks

Jira keys live in the generic `:LINKED_ISSUES:` drawer property defined
by the `tasks` extension (see
`pi/extensions/tasks/README.md#linked-external-issues`). Jira
keys are stored as **bare `PROJ-NNN` tokens**, not full org links — the
`tasks` extension resolves them to clickable URLs via
`#+ISSUE_URL_BASE`.

```org
* TODO Refactor stim driver
:PROPERTIES:
:CUSTOM_ID: 01234567-…
:LINKED_ISSUES: MBFW-123 MBE-45
:END:
```

`tasks` renders these as cyan badges and opens them with `J`. URL bases
and `#+JIRA_*` keywords are project-local trusted configuration; see
the `org-jira` skill's trust-boundary section for details. This
extension's planned `/jira *` commands enumerate `:LINKED_ISSUES:`,
filter to Jira-shaped tokens (matching `^[A-Z][A-Z0-9_]+-\d+$` for
bare tokens or matching `#+JIRA_BASE_URL` host for org-link tokens),
and operate only on those. Tokens belonging to other trackers (GitHub,
Linear, Confluence pages) are ignored, so a single task can carry
multi-tracker references without confusing the Jira workflow.

## Configuration

Three optional `#+` keywords in `TASKS.org` (or override in
`TASKS.local.org`):

```org
#+JIRA_CLOUDID: 00000000-0000-4000-8000-000000000000
#+JIRA_PROJECT: MBFW
#+JIRA_BASE_URL: https://your-org.atlassian.net
```

| Keyword            | Purpose                                                       |
| ------------------ | ------------------------------------------------------------- |
| `#+JIRA_CLOUDID`   | Skip the `atlassian_getAccessibleAtlassianResources` round-trip on every call. |
| `#+JIRA_PROJECT`   | Default project for `/jira create`; disambiguates short keys. |
| `#+JIRA_BASE_URL`  | Identifies which `:LINKED_ISSUES:` org-link tokens are Jira links. |

When `#+JIRA_CLOUDID` is absent, the agent calls
`atlassian_getAccessibleAtlassianResources` and picks the resource whose
URL matches `#+JIRA_BASE_URL`. Values from `TASKS.local.org` override
shared configuration for the current checkout only.

## Skill

`skills/org-jira/SKILL.md` (extending `org-tasks`) documents the
authoring conventions and agent prompts. Load it when the user wants to
work with Jira-shaped tasks.

## Tests

```sh
./test.sh
```

Structural sanity check only. The workflow commands (when implemented)
will use `SAND` as their sandbox project for live smoke tests.
