# Jira protocol details

Reference material for the `org-jira` skill. Load when you need exact regexes, file-format edge cases, or cloudId / token-filter rules.

## File-format conventions

### Issue keys

Jira keys are stored as typed org links in the generic `:LINKED_ISSUES:` drawer property defined by the `tasks` extension:

```org
#+LINK: jira https://your-org.atlassian.net/browse/%s

* TODO Refactor stim driver
:PROPERTIES:
:CUSTOM_ID: 01234567-89ab-4def-8123-456789abcdef
:LINKED_ISSUES: [[jira:MBFW-123]] [[jira:MBE-45]]
:END:
```

- The `jira:` prefix resolves through the file's `#+LINK: jira <base>/browse/%s` declaration (usually inherited from `TASKS.setup.org`). Without that declaration `[[jira:KEY]]` tokens parse but cannot be opened or rendered as badges; the `tasks` extension surfaces a hard error so the missing declaration is caught early.
- Link-target key validation regex: `^[A-Z][A-Z0-9_]+-\d+$`.
- Whitespace-separated org-link tokens; `:LINKED_ISSUES:` is multi-valued.
- A single task may link to many issues; mixing Jira typed links with raw URL org-link tokens (e.g. `[[https://github.com/.../issues/42][gh#42]]`) in the same drawer line is supported. Typed `[[jira:KEY]]` keeps the issue key portable across checkouts.
- Bare `PROJ-NNN` tokens are not part of the protocol (one-time migration sweep complete).
- The property is created on first link only — never auto-backfilled on existing tasks (mirrors `:STARTED:` behaviour).

### File-level keywords

`TASKS.setup.org` (overridable in `TASKS.local.org`):

```org
#+LINK: jira https://your-org.atlassian.net/browse/%s
#+JIRA_CLOUDID: 00000000-0000-4000-8000-000000000000
#+JIRA_PROJECT: MBFW
```

| Keyword            | Owner   | Purpose                                                    |
| ------------------ | ------- | ---------------------------------------------------------- |
| `#+LINK: jira`     | `tasks` | Org link abbreviation for badges, `J` open, raw-URL filtering, base URL derivation. |
| `#+JIRA_CLOUDID`   | `jira`  | MCP routing — skips `getAccessibleAtlassianResources`.     |
| `#+JIRA_PROJECT`   | `jira`  | Default project for `/jira create`; short-key disambiguation. |

`TASKS.local.org` overrides any of these (last-write-wins, mirroring `#+SELECTED:`).

### Trust boundary

`#+LINK:` and `#+JIRA_*` keywords are project-local trusted configuration. Values from `TASKS.local.org` are part of the user's checkout-local trust boundary, not untrusted remote input. Non-HTTPS issue URL bases are allowed; opener implementations must pass URLs as arguments rather than shell-interpolated command strings.

## Identifying Jira tokens within `:LINKED_ISSUES:`

`/jira *` commands that need Jira-shaped tokens only apply this filter:

1. **Typed Jira link** `[[jira:KEY]]`, where `KEY` matches `/^[A-Z][A-Z0-9_]+-\d+$/` → Jira key.
2. **Raw org-link token** `[[url][label]]` whose target host matches the base URL derived from `#+LINK: jira .../browse/%s` → Jira key.

Tokens that match neither are silently ignored by Jira workflows — a task carrying `[[jira:MBFW-123]] [[https://github.com/foo/bar/issues/42][gh#42]]` exposes only `MBFW-123` to `/jira claim`.

## cloudId resolution

Prefer `#+JIRA_CLOUDID` from the file. If absent:

1. Call `atlassian_getAccessibleAtlassianResources`.
2. Pick the resource whose `url` field equals the base URL derived from `#+LINK: jira .../browse/%s`.
3. Use its `id` as the `cloudId` for subsequent calls.

## Auto-transition mapping

When auto-transitioning Jira status on `tasks:status-changed`:

1. Call `atlassian_getTransitionsForJiraIssue`.
2. Match by name:
   - `STARTED` → "Start Progress" or "In Progress".
   - `DONE` → "Done", "Closed", or "Resolved" (try in order).
3. If no match, surface a chooser instead of guessing.

LOGBOOK is durable audit history; live event payloads are the trigger for Jira writes. Do not replay historical `:LOGBOOK:` entries as queued Jira transitions.

## `jira_clone_apply` return shapes

- `status: "inserted"` — confirm with the new heading and Jira URL.
- `status: "duplicate"` — cite `details.existingId` and refuse to re-clone (the same `:LINKED_ISSUES:` token already appears in TASKS.org / TASKS.local.org / their imports).
- `status: "section_not_found"` — ask whether to retry with `allowCreateSection: true` or correct the section name.
- `status: "error"` — surface the message verbatim.
