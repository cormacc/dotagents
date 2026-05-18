# `ot` machine-output contract

This document pins the JSON / EDN contract every machine-readable `ot`
command emits when called with `--format json` or `--format edn`. It is the
shared surface between `ot` and any consumer (the pi extension, other coding
harnesses, CI scripts, future Emacs companions). Bump rules are in
`design/log/2026-05-18-tasks-extension-ot-cli.org` § Decisions.

## Schema version

```
"schema": "org-tasks/v1"
```

- Additive fields in `result` are non-breaking and remain `v1`.
- Renames, removals, or shape changes bump to `v2`.
- Consumers must accept and ignore unknown additive fields.

## Envelope

### Success

```json
{
  "ok": true,
  "schema": "org-tasks/v1",
  "result": { ... },
  "warnings": []
}
```

- `result` is the per-command payload documented below.
- `warnings` is an array of `{ code, message, location? }` for non-fatal
  conditions (e.g. unreadable change-record encountered during a scan).

### Failure

```json
{
  "ok": false,
  "schema": "org-tasks/v1",
  "error": {
    "code": "section-not-found",
    "message": "Section 'Improvements' not found in /repo/TASKS.org",
    "file": "/repo/TASKS.org",
    "line": null,
    "details": {}
  }
}
```

- `code` is a stable kebab-case identifier (`section-not-found`,
  `unknown-task`, `path-outside-project`, `duplicate-linked-issue`,
  `validation`, `invalid-status`, `out-of-root`, `unreadable`,
  `git-unavailable`, `empty-summary`).
- `message` is a single-line human-readable summary.
- `file` and `line` are populated when locatable; otherwise `null`.
- `details` may carry command-specific extra context.
- The process exit code is `0` on success and `1` on failure.

## Common task shape (`Task`)

Returned by `list` (`rows[]` and within `tree[]`), `show`, `create`,
`status`, and as the `task` field of related result payloads.

```jsonc
{
  "id":            "uuid",
  "status":        "TODO | STARTED | WAITING | DONE | CANCELLED",
  "priority":      "A | B | C | D | null",
  "summary":       "Heading text",
  "tags":          ["backend", "security"],
  "level":         2,
  "sourcePath":    "/repo/TASKS.org",
  "line":          12,
  "local":         false,
  "selected":      false,
  "importPath":    "[[plan:foo.org]] | null",
  "importExpandedPath": "/repo/design/log/foo.org | null",
  "closed":        "2026-05-18 Mon 09:00 | null",
  "started":       "2026-05-18 Mon 08:30 | null",
  "createdAt":     "2026-05-18 Mon 08:00 | null",
  "blockedBy":     ["task:uuid", "url:https://...", "human: text"],
  "handoff":       "Pick up at parser delimiter test | null",
  "linkedIssues":  [
    { "rawToken": "[[jira:ABC-1]]", "label": "ABC-1", "url": "https://..." }
  ],
  "parentId":      "uuid | null"
}
```

Tree form additionally carries `"children": [Task]` and an `"importChildren":
[Task]` array populated when an `#+IMPORT:` change-record is resolvable. Commands
that return a single task may include `sourceContent` and `effectiveSourceContent`
directly on that task. `ot list` deduplicates those large strings into its
`result.sources` map instead.

## Per-command results

### `ot list`

```json
"result": {
  "tree":         [Task],
  "rows":         [Task],
  "selectedId":   "uuid | null",
  "files": {
    "tasks":   "/repo/TASKS.org",
    "local":   "/repo/TASKS.local.org",
    "archive": "/repo/TASKS.archive.org | null"
  },
  "sources": {
    "/repo/TASKS.org": {
      "sourceContent": "raw TASKS.org content",
      "effectiveSourceContent": "content plus expanded setupfiles"
    }
  }
}
```

- `tree` is depth-first nested by `children` and `importChildren`.
- `rows` is the same tasks flattened in walker order, each carrying `parentId`.
- `selectedId` echoes `#+SELECTED:` from `TASKS.local.org`.
- `sources` is keyed by absolute source path and carries file content once per
  path so UI clients can resolve link templates or preserve org text without
  duplicating large strings on every task row.

### `ot show <id>`

```json
"result": {
  "task":            Task,
  "ancestors":       [Task],
  "children":        [Task],
  "importChildren":  [Task],
  "record": {
    "path":          "/repo/design/log/foo.org | null",
    "sections":      ["Summary", "Plan", "Implementation"],
    "hasContext":    true,
    "hasOpenQuestions": false
  }
}
```

`ancestors` is ordered root → parent.

### `ot create`

```json
"result": {
  "id":           "uuid",
  "file":         "/repo/TASKS.org",
  "line":         42,
  "sectionCreated": false
}
```

Options include `--section`, `--parent`, `--after`, `--priority`, repeated
`--tag`, `--body`, repeated `--linked-issue`, repeated `--also-scan`,
`--allow-create-section`, and compatibility/test overrides `--id` and
`--created-at`. `--parent` inserts a child under that task; `--after` inserts a
sibling after the anchor task. Errors: `section-not-found`, `duplicate-linked-issue`,
`path-outside-project`, `empty-summary`.

### `ot status <id> <new-status>`

```json
"result": {
  "task":         Task,
  "prevStatus":   "TODO",
  "status":       "STARTED",
  "closed":       null,
  "started":      "2026-05-18 Mon 13:18",
  "promoted":     [{ "id": "uuid", "prevStatus": "TODO", "status": "STARTED" }]
}
```

`promoted` lists ancestor auto-promotions performed by the same call. Errors:
`unknown-task`, `invalid-status`, `validation`.

### `ot select [<id>]`

```json
"result": {
  "selectedId":   "uuid | null",
  "previousId":   "uuid | null",
  "file":         "/repo/TASKS.local.org"
}
```

Pass `--clear` (or omit the id) to deselect.

### `ot selected`

Same payload as `ot show <selectedId>` (or `{"selected": null}` when nothing
is selected).

### `ot archive <id>`

```json
"result": {
  "task":         Task,
  "archivePath":  "/repo/TASKS.archive.org",
  "archivedAt":   "2026-05-18 Mon 13:30",
  "planRewrite": {
    "file":       "/repo/design/log/foo.org",
    "from":       "task:uuid",
    "to":         "archive:uuid"
  }
}
```

Errors: `unknown-task`, `validation` (e.g. not closed), `path-outside-project`.

### `ot publish <id>` / `ot unpublish <id>`

```json
"result": {
  "task":         Task,
  "from":         "/repo/TASKS.local.org",
  "to":           "/repo/TASKS.org"
}
```

### `ot doctor`

```json
"result": {
  "findings": [
    {
      "code":     "duplicate-id",
      "severity": "error | warn",
      "message":  "Duplicate :CUSTOM_ID: …",
      "location": { "file": "/repo/TASKS.org", "line": 7, "heading": "Foo" }
    }
  ],
  "counts": { "error": 0, "warn": 0 }
}
```

### `ot section <file> [<section>]`

```json
"result": {
  "file":     "/repo/design/log/foo.org",
  "section":  "Summary",
  "found":    true,
  "heading":  "* Summary",
  "body":     "…"
}
```

Errors: `out-of-root`, `unreadable`. Not-found is a non-error result with
`"found": false`.

### `ot scan`

```json
"result": {
  "rows": [
    {
      "id":            "uuid",
      "summary":       "…",
      "status":        "TODO",
      "priority":      "A | B | C | D | null",
      "tags":          ["…"],
      "sourcePath":    "/repo/TASKS.org",
      "importPath":    "design/log/foo.org",
      "recordSummary": { "found": true, "body": "…" },
      "hasContext":    true
    }
  ],
  "scope":  "active | archived | all",
  "count":  42
}
```

`recordSummary` may also be `{ "found": false }` (record missing/unreadable
or lacking `* Summary`) or `null` (task has no `#+IMPORT:` at all).

### `ot record create <id>`

```json
"result": {
  "taskId":           "uuid",
  "recordPath":       "/repo/design/log/foo.org",
  "importRaw":        "[[plan:foo.org]]",
  "created":          true,
  "absorbedSubtasks": false,
  "scope": {
    "since":          "2026-05-18 Mon 08:30 | null",
    "until":          "2026-05-18 Mon 13:30 | null",
    "commits":        ["abcdef1", "1234567"]
  }
}
```

`scope.commits` is populated only for `--mode retrospective` (via `git log`).
Errors: `unknown-task`, `path-outside-project`, `git-unavailable`.

### `ot record path <id>`

```json
"result": {
  "taskId":    "uuid",
  "suggested": "design/log/2026-05-18-short-task-name.org"
}
```

### `ot issue list <id>`

```json
"result": {
  "taskId":  "uuid",
  "issues":  [
    {
      "rawToken": "[[jira:ABC-1]]",
      "label":    "ABC-1",
      "url":      "https://example.atlassian.net/browse/ABC-1",
      "error":    null
    }
  ]
}
```

### `ot blocker list <id>`

```json
"result": {
  "taskId":  "uuid",
  "blockers": [
    { "raw": "task:uuid", "kind": "task", "ref": "uuid" },
    { "raw": "url:https://...", "kind": "url", "ref": "https://..." }
  ]
}
```

### `ot ready <id>`

```json
"result": {
  "taskId":  "uuid",
  "ready":   true,
  "gating":  [
    {
      "blocker": { "raw": "task:other", "kind": "task", "ref": "other" },
      "reason":  "unresolved-task | missing-task | opaque"
    }
  ]
}
```

### `ot handoff get <id>`

```json
"result": {
  "taskId":   "uuid",
  "handoff":  "Pick up at … | null"
}
```

## Global options

All commands accept:

| Flag             | Default                                                 | Notes                                              |
| ---------------- | ------------------------------------------------------- | -------------------------------------------------- |
| `--root <dir>`   | git rev-parse --show-toplevel, else cwd                 | Anchors all relative paths.                        |
| `--format <fmt>` | `text` for TTY, `json` for non-TTY (configurable)       | `text`, `json`, `edn`.                             |
| `--tasks <path>` | `<root>/TASKS.org`                                      |                                                    |
| `--local <path>` | `<root>/TASKS.local.org`                                |                                                    |
| `--archive <path>` | `<root>/TASKS.archive.org`                            |                                                    |
| `--dry-run`      | false                                                   | Mutation commands print the proposed result without writing. |
| `--yes`, `-y`    | false                                                   | Skips confirmation prompts on destructive commands. |
| `--no-color`     | false                                                   | Disables ANSI styling in `--format text`.          |
| `--help`, `-h`   | n/a                                                     | Renders dispatch + spec-derived help.              |

## Exit codes

| Code | Meaning                                  |
| ---- | ---------------------------------------- |
| 0    | Success (`ok: true`).                    |
| 1    | Domain error (`ok: false`).              |
| 2    | Argument or option parse failure.        |
| 64   | Reserved for `--dry-run` would-modify.   |
