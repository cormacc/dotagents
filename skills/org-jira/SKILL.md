---
name: org-jira
description: "Jira semantics over org-tasks. Use for Jira keys (PROJ-123), /jira commands, :LINKED_ISSUES:, #+LINK: jira, #+JIRA_* keywords, Jira epic/issue planning, or Atlassian MCP questions."
---

# Jira integration for org-tasks

Extends [`org-tasks`](../org-tasks/SKILL.md) with Jira-specific authoring conventions and workflows. Use when a task references a Jira issue, or the user asks to get / clone / claim / comment / transition / create a Jira issue, or wants to know the Atlassian MCP connection state.

This skill owns Jira *semantics*. It works in any harness that has the Atlassian MCP server and the `ot` CLI; pi, `codemode`, and the pi `jira` extension are optional (see [Pi: the `jira` extension](#pi-the-jira-extension-optional-accelerator)). The underlying file format (`:LINKED_ISSUES:` drawer, `#+LINK:` declarations, badge rendering, browser open) is owned by `org-tasks` and, in pi, the [`tasks` extension](../../pi/extensions/tasks/README.md#linked-external-issues); it is tracker-agnostic. Issue-key format, cloudId resolution rules, and the exact Jira-token filter live in [`references/protocol.md`](references/protocol.md).

## Atlassian MCP connection

All Jira read/write goes through the `atlassian` MCP server. This skill names its tools by the server's tool name, for example `getJiraIssue`. Each harness adds its own prefix or namespace: pi registers `mcp__atlassian__getJiraIssue`. Look up the exact name in your harness's tool list.

The server must be authenticated before any workflow. If a call fails with an authentication error, tell the user to reconnect rather than retrying. In pi the reconnect command is `/mcp login atlassian` (or `pi mcp login atlassian` from a shell); in other harnesses use that harness's MCP login.

Dispatch a tool call however your harness does it. Request only the fields a flow needs and keep the raw tool result out of your answer. In pi, MCP tools are not declared to the model; they are called from a `codemode` script (`tools.mcp__atlassian__getJiraIssue({...})`), so only what the script returns enters the context.

## Workflows

Every workflow below needs only the Atlassian MCP server and `ot`. Two shared steps are defined once in [`references/protocol.md`](references/protocol.md):

- **cloudId**: `#+JIRA_CLOUDID`, else the `url` match from `getAccessibleAtlassianResources`.
- **Jira keys of a task**: run `ot issue list <id> --format json` (or take `linkedIssues` from `ot show <id> --format json`) and apply the Jira-token filter to each `rawToken`. The selected task is `ot selected --format json`; a `null` `selected` means refuse and ask the user to select a task.

### Reference (read-only)

No MCP call needed. The user adds `[[jira:KEY]]` to `:LINKED_ISSUES:` and sets `#+LINK: jira <base>/browse/%s` once. `ot issue urls <id>` resolves the URLs; the `tasks` extension in pi renders badges and `J` opens them. Fully offline-safe.

### Planning / resume context

When [`org-plan`](../org-plan/SKILL.md) is drafting/refining a change-record -- or when an agent resumes a task with Jira-shaped `:LINKED_ISSUES:` -- fetch current Jira scope before relying on stale local prose. Keep fetched data ephemeral: distil only plan-relevant facts into the change-record per `org-plan`'s section contract (`* Summary` first, promote `* Context` only when the Jira rationale/scope exceeds the summary, use `* Open questions` for gaps).

For each Jira-shaped token:

1. Ensure the server is connected; if not, ask the user to reconnect and proceed without blocking the plan.
2. Resolve the cloudId.
3. Fetch the parent with `getJiraIssue` (summary, status, issue type, priority, assignee, plain-text description, parent key, relevant issue links).
4. Walk children only while they materially shape scope. Epics use `"parent" = KEY` (or legacy `"Epic Link" = KEY`). Tasks/Stories/Bugs use `parent = KEY`. Stop at done/out-of-scope branches or beyond two levels below the linked issue.
5. Distil the walk. Name each linked parent and why it frames the work. Include in-scope children only when they affect the plan. Record blockers. Surface gaps in `* Open questions`. Do **not** mint Jira issues during this read-only walk. Jira keys are never org `:CUSTOM_ID:` values -- link Jira-derived local tasks via `:LINKED_ISSUES:`.

Re-fetch on later sessions rather than caching raw Jira JSON or ADF.

### Status

1. Call `atlassianUserInfo`. A result means connected.
2. An authentication error or a missing `atlassian` tool means disconnected: say so and give the reconnect instruction above.

### Get (`/jira get <KEY>`)

Read-only: print a compact per-key block. No file writes.

1. Validate each `KEY` against `^[A-Z][A-Z0-9_]+-\d+$`. A bare number gets `#+JIRA_PROJECT-` prepended; refuse it when the keyword is unset.
2. Resolve the cloudId.
3. Call `getJiraIssue` with `cloudId`, `issueIdOrKey`, `fields` set to `["summary","priority","labels","description","issuetype","parent","subtasks","status","assignee","reporter","issuelinks","updated","versions","fixVersions","components","comment"]` and `responseContentFormat: "markdown"`. Do not request `*all` or expand custom fields. `versions` is the Affects versions field. The description can still come back as an ADF document (for example an empty one, `{"type":"doc","version":1,"content":[]}`): use its text, and treat an empty one as no description.
4. Render one block per key, never raw JSON. Every field gets a line in this order, and an empty field reads `none`, so the reader can see the block is complete:
   - heading `KEY - summary [status]`;
   - `Priority: <priority>; type: <type>; labels: <labels>`;
   - `Components:`, `Affects versions:` and `Fix versions:` as comma-separated names;
   - `Assignee:` and `Reporter:` display names;
   - `Updated:` as `YYYY-MM-DD`;
   - `Parent: KEY - summary`;
   - `Subtasks (n):` and `Issue links (n):` with up to 5 `- ...` lines and a `+N more` line; an issue link reads `- <relation> KEY - summary`, where the relation is the link type's outward phrase for an `outwardIssue` and its inward phrase for an `inwardIssue`;
   - `Comments: <comment.total>`, never the comment bodies;
   - `Description:` the first paragraph capped at 300 characters, with `...` for truncation;
   - a footer link `<base>/browse/KEY` when the `#+LINK: jira` template is standard.
5. Separate keys with a blank line.

### Clone (`/jira clone <KEY>`)

Create a local task from a Jira issue. Never assemble the org heading or drawer by hand.

1. Validate each `KEY` as for Get.
2. Fetch the issue as in Get step 3, but with `fields` set to `["summary","priority","labels","description"]`; the clone needs no other field. Stop on any error.
3. Create the task with `ot`:
   ```
   ot create --section Improvements --linked-issue "[[jira:KEY]]" \
     --priority <Highest|High|Medium|Low|Lowest> --tag <label> ... --body="<description>" -- "<summary>"
   ```
   Keep this shape: Jira text can start with `-`, so pass the body as `--body=<text>` and put the summary last, after `--`. `ot create` indents description lines that would parse as Org headings (`* item`). Omit `--priority` when the issue has no priority or an unknown name. `--tag` takes `[A-Za-z0-9_]+` only: replace other characters in a label with `_` or skip the label. Use `--local` for local drafts or another `--section` when the user works elsewhere. `ot create` writes the drawer, `:CUSTOM_ID:`, `:CREATED:` and `:LINKED_ISSUES:`.
4. React to the `ot` result: success returns the new `id`, `file` and `line`; a `duplicate-linked-issue` error means the issue is already cloned (cite the existing task); `section-not-found` means ask whether to retry with `--allow-create-section` or correct the section; any other error, surface its message.
5. Summarise one bullet per key with the new heading and Jira URL.

Smoke test against `SAND` only.

### Claim (`/jira claim`)

1. Find the Jira keys of the selected task (shared steps above). Refuse when none.
2. Resolve the cloudId and call `atlassianUserInfo` once for `account_id`.
3. For each key call `editJiraIssue` with `fields: {"assignee": {"accountId": "<account_id>"}}`.
4. Report one line per key: success or the returned error. A failing key must not stop the others.

### Comment (`/jira comment <markdown>`)

1. Find the Jira keys of the selected task. Refuse when none.
2. Resolve the cloudId.
3. For each key call `addCommentToJiraIssue` with `commentBody` set to the markdown and `contentFormat: "markdown"` (the server converts to ADF).
4. Report one line per key.

### Create (`/jira create [PROJECT] [--type Task|Story|Bug|Epic]`)

1. Project defaults to `#+JIRA_PROJECT`; refuse when neither the argument nor the keyword provides one. Type defaults to `Task`.
2. Read the selected task with `ot show <id> --format json`: `summary` becomes the issue summary and `description` the issue description.
3. Resolve the cloudId. Call `getJiraProjectIssueTypesMetadata` and stop, listing the available types, if the type is not among the returned `issueTypes[].name`.
4. Call `createJiraIssue` with `cloudId`, `projectKey`, `issueTypeName`, `summary`, `description` and `contentFormat: "markdown"`. Stop if the result has no `key`.
5. Link the new issue back: `ot issue add <id> "[[jira:<key>]]"`. If this fails, report the new key and the manual fix; do not create the issue again.
6. Confirm with the new key and URL.

Smoke test against `SAND` only.

### Transition (auto, optional)

Reflect a local status change (`ot status <id> STARTED` or `DONE`) on every Jira-shaped token of that task:

1. Find the task's Jira keys. Do nothing when there are none.
2. Resolve the cloudId. For each key call `getTransitionsForJiraIssue`.
3. Match a transition by name, case-insensitive, in order (see `references/protocol.md` for the mapping): `STARTED` to `Start Progress`, `In Progress`; `DONE` to `Done`, `Closed`, `Resolved`.
4. Call `transitionJiraIssue` with `transition: {"id": "<id>"}` for the match. If no name matches, show the available transitions as a chooser instead of guessing; do nothing for that key.
5. Report one line per key.

Run this only when the user asks for it or has enabled auto-transition. Never replay historical `:LOGBOOK:` entries as queued transitions.

## Pi: the `jira` extension (optional accelerator)

In pi with the [`jira` extension](../../pi/extensions/jira/README.md) loaded, the `/jira` commands do not run the manual protocol above. They call deferred `jira_*` tools (callable from `codemode`) that perform the same flows deterministically in TypeScript and return only the fields each flow needs. The manual protocol above is the fallback: use it in any other harness, or in pi when the extension is not loaded.

- The command handler resolves the keys in TypeScript (arguments and `#+JIRA_PROJECT`, or the selected task through `ot`) and sends the model one hidden line that names one `codemode` call, for example `return await tools.jira_get({"keys":["SAND-77"]});`. Run that call exactly once and show the result; do nothing else and do not re-derive the steps.
- Tools: `jira_get`, `jira_clone`, `jira_claim`, `jira_comment`, `jira_create`, `jira_transition`. They have `deferred` exposure: they are not in your tool list and not listed in the `codemode` description, but a `codemode` script calls them as `tools.<name>(args)`.
- Without a `/jira` trigger (for example the user asks in prose to summarise or clone `SAND-75`): in pi, first check for the tools in a `codemode` script with `ALL_TOOLS.some((t) => t.name === "jira_get")`. If they exist, call the matching tool with the keys from the request (for example `return await tools.jira_get({"keys":["SAND-75"]});`) instead of the manual protocol, then show the result. Use `describeTool("jira_<name>")` if you need the argument schema. If they do not exist, use the manual protocol.
- `jira_clone` returns `[{key, status, ...}]` with `status` `inserted`, `duplicate` (cite `existingId`), `section_not_found` (ask whether to retry with `allowCreateSection: true`) or `error` (surface `message`).
- The write tools refuse any key or project outside `SAND` before calling the server. `jira_transition` returns `{key, choices}` for a key whose transition names do not match, and performs nothing for it; list the choices to the user.
- With `{"autoTransition": true}` in `<agent dir>/jira-ext.json`, a `tasks:status-changed` event to STARTED or DONE sends the `jira_transition` trigger for the task's Jira keys.

## Question-handling

Follow [../org-plan/SKILL.md#Executing from a change-record](../org-plan/SKILL.md#executing-from-a-change-record). Batch minor ambiguities into `* Open questions`. Raise design-affecting questions (extension API, data shape, cross-extension contract) immediately.

## Sandbox

All write-path development and smoke testing runs against project `SAND`. Never call `editJiraIssue`, `addCommentToJiraIssue`, `createJiraIssue`, or `transitionJiraIssue` against any other project until the relevant plan stage is signed off.

## Offline / disconnected behaviour

Reference display (badges, `J`, `ot issue urls`) needs no server. Anything else in this skill that needs the server must surface a clear notification ("Atlassian MCP not connected -- reconnect it; in pi run /mcp login atlassian") rather than failing silently.
