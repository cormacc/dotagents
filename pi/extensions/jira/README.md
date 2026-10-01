# Jira Extension

Jira workflows backed by the [Atlassian
MCP](https://developer.atlassian.com/) server. Owns slash commands, deferred
`jira_*` tools (callable from `codemode`), and Jira-specific authoring conventions; stays composable on top
of the generic `tasks` extension's tracker-agnostic linkage features
(`:LINKED_ISSUES:` drawer property + org-native `#+LINK:` declarations).

This extension is an optional accelerator. The harness-independent protocol
(Atlassian MCP server plus `ot`, no pi) lives in `skills/org-jira/SKILL.md`; the
skill describes what the `/jira` commands do when this extension is not loaded.

## Status

Read and write workflows implemented (status / clone / get / claim /
comment / create). Optional `autoTransition` on live local status-change events is
implemented as an event listener on `tasks:status-changed`; off by default,
opt in via `<configured agent directory>/jira-ext.json` (default:
`~/.pi/agent/jira-ext.json`). Durable task LOGBOOK
history is audit evidence and is not replayed as a queue of Jira
transitions. The shared-event listener is released on `session_shutdown`, so
reloads and session replacement cannot multiply a single status transition.

## Commands

| Command                                | Status      | Description                                            |
| -------------------------------------- | ----------- | ------------------------------------------------------ |
| `/jira`                                | Implemented | Print Atlassian MCP connection status.                 |
| `/jira status`                         | Implemented | Alias for `/jira`.                                     |
| `/jira clone KEY [KEY...]`             | Implemented | Pull issue(s) from Jira → create local task(s) through the `jira_clone` tool. |
| `/jira get KEY [KEY...]`               | Implemented | Render a compact human-readable summary of one or more issues. No file writes. |
| `/jira claim`                          | Implemented | Set assignee on every Jira-shaped issue on the selected task. |
| `/jira comment <markdown>`             | Implemented | Add a comment to every Jira-shaped issue on the selected task. |
| `/jira create [PROJECT] [--type Type]` | Implemented | Promote the selected task to a new Jira issue.         |
| auto-transition (no command)           | Implemented | Reflect live local status changes on linked Jira issues. Off by default. |

## Tools

Six tools are registered with `exposure: "deferred"` in the `jira` namespace.
They are callable from a `codemode` script, are never declared to the model on
their own (none is in `pi.getActiveTools()`), and are not listed in the
`codemode` description, so they cost nothing per request. `codemode` exposure
would list them there on every request. A script addresses a
tool by its name: `tools.jira_get({...})`, and can find them with
`searchTools("jira")` or `ALL_TOOLS`. The namespace does not nest the `tools`
object (verified in `pi-codemode`'s `toCodemodeIdentifier` and sandbox prelude).

Each tool calls `mcp__atlassian__*` through `ctx.executeTool()` and returns only
the fields or text its flow needs, so no raw `CallToolResult` reaches the model.
A nested call that resolves with `isError: true` throws with the Jira message and
a `/mcp login atlassian` hint (per-key write calls report it on their key).

| Tool              | Input                              | Returns |
| ----------------- | ---------------------------------- | ------- |
| `jira_get`        | `keys`                             | Compact text block per issue, one line per field and `none` when empty: heading and status, priority/type/labels, components, affects and fix versions, assignee, reporter, updated date, parent, up to 5 subtasks, up to 5 issue links, comment count, 300-character description preview, footer link. |
| `jira_clone`      | `keys`, `file?`, `section?`, `allowCreateSection?` | `[{key, status, ...}]` with `status` of `inserted`, `duplicate`, `section_not_found` or `error`. The write goes through `insertTaskIntoFile()` (`ot create`); the issue body, summary and labels never enter model context. |
| `jira_claim`      | `keys`                             | `[{key, ok, error}]`; `atlassianUserInfo` once, then `editJiraIssue` per key. |
| `jira_comment`    | `keys`, `body`                     | `[{key, ok, error}]`; `addCommentToJiraIssue` with `contentFormat: "markdown"`. |
| `jira_create`     | `taskId`, `project`, `type?`       | `{key, url}`; reads the task heading and body through `ot show`, checks the type with `getJiraProjectIssueTypesMetadata`, creates, then runs `ot issue add`. |
| `jira_transition` | `keys`, `status` (`STARTED`/`DONE`) | `[{key, ok, transition}]`, or `{key, choices: [{id, name}]}` when no transition name matches (nothing is done for that key). |

Write tools (`jira_claim`, `jira_comment`, `jira_create`, `jira_transition`)
throw before any MCP call for a key or project outside `SAND`. `jira_transition`
matches names case-insensitively in order: `Start Progress`, `In Progress` for
`STARTED`; `Done`, `Closed`, `Resolved` for `DONE`. `jira_clone_apply` is
removed; `jira_clone` replaces it.

### Hidden one-line triggers

A slash-command handler cannot call tools (`executeTool` exists only on the
tool `execute()` context), so each flow sends the model one hidden line with
`pi.sendMessage({customType: "jira", display: false, ...}, {triggerTurn: true})`.
The line names a single `codemode` call with literal arguments, for example:

```
Call `codemode` once with exactly this code, then show the returned text verbatim and add nothing: return await tools.jira_get({"keys":["SAND-77"]});
```

`display: false` hides the message from the terminal only; pi still sends it to
the model, so a trigger carries no script, protocol steps or rendering rules.
The handlers resolve the keys in TypeScript: `/jira get` and `/jira clone` from
the arguments and `#+JIRA_PROJECT`; `/jira claim`, `/jira comment` and
`/jira create` from the selected task through `ot selected` (Jira keys come from
its `:LINKED_ISSUES:`); auto-transition from `ot show <id>`. The model reads no
task file. Auto-transition sends nothing for a task with no Jira key, and queues
its trigger as a follow-up when the event arrives mid-turn.

## Connection model

All Jira access goes through the `atlassian` server of pi's native MCP support.
To connect:

```
/mcp login atlassian
```

(or `pi mcp login atlassian` from a shell). The server entry lives in
`pi/mcp.json`, linked to `~/.pi/agent/mcp.json`. With the default
`codemode` exposure, the server's tools are not declared to the model; they
are registered as `mcp__atlassian__<tool>` and are callable only through
`ctx.executeTool()` or from a `codemode` script. `pi.getAllTools()` lists them,
and the extension uses the presence of any `mcp__atlassian__` tool as a
connection-status check without invoking it.

## Linkage to tasks

Jira keys live in the generic `:LINKED_ISSUES:` drawer property defined by the `tasks` extension (see `pi/extensions/tasks/README.md#linked-external-issues`). Jira keys are stored as typed org links, resolved by the org-native `#+LINK: jira` abbreviation.

```org
#+LINK: jira https://your-org.atlassian.net/browse/%s

* TODO Refactor stim driver
:PROPERTIES:
:CUSTOM_ID: 01234567-…
:LINKED_ISSUES: [[jira:MBFW-123]] [[jira:MBE-45]]
:END:
```

`tasks` renders these as cyan badges and opens them with `J`. Link templates and `#+JIRA_*` keywords are project-local trusted configuration; see the `org-jira` skill's trust-boundary section for details. This extension's `/jira *` commands enumerate `:LINKED_ISSUES:`, filter to typed Jira links (`[[jira:KEY]]`) or raw org links whose target host matches the base URL derived from `#+LINK: jira`, and operate only on those. Tokens belonging to other trackers (GitHub, Linear, Confluence pages) are ignored, so a single task can carry multi-tracker references without confusing the Jira workflow.

## Configuration

One `tasks`-owned link abbreviation plus two optional Jira keywords live in the effective `TASKS.org` configuration stream (usually `TASKS.setup.org`):

```org
#+LINK: jira https://your-org.atlassian.net/browse/%s
#+JIRA_CLOUDID: 00000000-0000-4000-8000-000000000000
#+JIRA_PROJECT: MBFW
```

| Keyword            | Purpose                                                       |
| ------------------ | ------------------------------------------------------------- |
| `#+LINK: jira`     | Org-native URL template for `[[jira:KEY]]` badges, `J` browser-open, raw-URL filtering, and base URL derivation. |
| `#+JIRA_CLOUDID`   | Skip the `mcp__atlassian__getAccessibleAtlassianResources` round-trip on every call. |
| `#+JIRA_PROJECT`   | Default project for `/jira create`; disambiguates short keys. |

When `#+JIRA_CLOUDID` is absent, each tool calls
`mcp__atlassian__getAccessibleAtlassianResources` once and picks the resource whose
URL matches the base URL derived from `#+LINK: jira .../browse/%s` (or the only
site, when there is no base URL; the same site is listed once per scope set, so
ids are de-duplicated). Jira uses the same recursively expanded, declaration-ordered `#+SETUPFILE:` stream as tasks: for each setting, the first non-empty effective declaration wins. Put a checkout-local declaration before its `#+SETUPFILE:` line when it must override shared configuration.

## Skill

`skills/org-jira/SKILL.md` (extending `org-tasks`) documents the
authoring conventions and the harness-independent protocol. Load it when the user wants to
work with Jira-shaped tasks.

## Tests

```sh
./test.sh
```

Runs `jira.test.ts`: the pure helpers, the trigger shape of every flow, and each
`jira_*` tool against a fake `ctx.executeTool` (arguments sent, filtered return,
`isError` throw, `SAND` guard, transition match and no-match), plus the command
handlers and the auto-transition listener against throwaway `ot` projects. No
test calls Jira. The `tasks:status-changed` listener's session-scoped cleanup is
covered by `pi/extensions/test/event-subscriptions.test.ts` (run from
`pi/extensions/emacsclient/test.sh`). Live smoke tests use `SAND` as their
sandbox project.
