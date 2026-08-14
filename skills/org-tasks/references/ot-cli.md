# `ot` CLI reference

`ot` is the Babashka org-tasks protocol engine under `skills/org-tasks/scripts/`. It owns durable graph reads and writes: parsing, serialization, setupfile/link resolution, lifecycle writes, archive mechanics, selection, linked issues, blockers, handoff notes, section reads, summary scans, change-record scaffolding, and a standalone terminal task browser.

Bare `ot` launches the standalone TUI when an interactive terminal is available. `ot --help`, `ot -h`, and `ot help` render the command index; `ot <command> --help` (or `ot help <command>`) renders that command's specific options plus the globals. Bare non-TTY `ot` and bare `ot --format json` emit the persisted selected-task JSON envelope without launching the TUI.

The pi tasks extension remains a pi-specific UI/event wrapper around `ot`: overlay rendering, keybindings, prompts, confirmations, file watching, compact widgets, and agent follow-up prompts stay in pi; durable protocol mutations go through `ot`.

## Install

```shell
bbin install io.github.cormacc/dotagents --as ot --latest-sha
```

In-tree development options and testing: [`../scripts/README.md`](../scripts/README.md).

## Machine output

Use `--format json` for agents and scripts. JSON/EDN commands use schema `org-tasks/v1`:

```json
{"ok": true, "schema": "org-tasks/v1", "result": {}, "warnings": []}
```

Errors use:

```json
{"ok": false, "schema": "org-tasks/v1", "error": {"code": "...", "message": "..."}}
```

Field-level examples live in `scripts/docs/contract.md`.

The envelope is shared. The `result` payload is per command, so never infer one command's shape from another's. `show`/`selected`/`status` return a task object under `result.task`, while `create` returns a *locator* -- `{"id", "file", "line", "sectionCreated"}` -- with no task object at all. Two consequences for scripted callers: reading `result.task` from a `create` response raises rather than returning nil, and that raised parse error says nothing about whether the mutation happened. It did. Re-running the command on a parse failure creates a duplicate, and a duplicate top-level task cannot be withdrawn with `ot remove` (which refuses protocol roots) -- it needs `ot status <id> CANCELLED` plus `ot archive <id> --yes`.

## Common commands

```shell
ot                    # TUI on TTY; selected JSON on non-TTY
ot --format json     # selected-task envelope, non-interactive
ot init
ot root
ot list --format json
ot list --levels 0
ot selected --format json  # the selection query: {"selected": null} + exit 0 when nothing
                        # is selected, so it is the safe resume probe
ot show <id-or-selected>  # text mode appends a non-empty task body after metadata
                        # JSON/EDN retain the Task.description payload
                        # strict: `show selected` exits 1 when nothing is selected
ot create "New task" --section Improvements --linked-issue '[[jira:ABC-1]]'
ot create "New sibling" --relative-to <id> --as sibling   # after <id>, same level
ot create "New child"   --relative-to <id> --as child     # nested under <id>
                                                          # (derives local/source from the anchor)
ot move <id> --parent <dest-id>            # reparent an existing subtree (last child)
ot move <id> --section Improvements        # lift an existing subtree back to top level
ot status <id> STARTED
ot status <id> --cycle forward   # or: --cycle back (order owned by ot)
ot priority <id> B               # set the priority cookie (A|B|C|D)
ot priority <id> --cycle forward # unset → A → B → C → D → unset; back reverses (unset → D)
ot priority <id> --clear
ot select <id>        # or: ot select --clear / ot select --clear-stale
ot remove <id> --yes  # non-top-level subtree only; preview with --dry-run
                        # pass --prune-blockers only to remove reported inbound task blockers
ot archive <id> --yes
ot unarchive <id> [--section <name>]
ot publish <id>       # TASKS.local.org -> TASKS.org
ot unpublish <id>     # TASKS.org -> TASKS.local.org
ot doctor --format json # includes spec warnings for #+SPEC: declarations and records that declare #+SPEC:
ot spec list --format json   # report-only: discovered spec set + root provenance (alias: spec discover)
ot backfill            # add :CUSTOM_ID: / :CREATED: to hand-authored tasks missing IDs
ot section design/log/foo.org Summary --format json
ot scan --scope all --max-body-chars 500 --format json
ot record path <id>
ot record create <id>
ot record create <id> --mode retrospective
ot issue list|add|remove|urls <id> [...]
ot blocker list|add|remove <id> [...]
ot blocker prune --dry-run  # actual pruning requires --yes
ot tag add|remove <id> <tag>
ot ready <id>
ot handoff get|set|clear <id> [...]
ot uuid --count 3
```

## Editing existing task tags

`ot tag add <id> <tag>` and `ot tag remove <id> <tag>` mutate the trailing Org heading tags of an existing task. IDs accept the standard full UUID or unique prefix, and the task is written back to its owning `TASKS.org`, `TASKS.local.org`, or imported plan file. `--dry-run` returns the proposed tag list without writing.

Tags are ASCII letters, digits, and underscores (`[A-Za-z0-9_]+`). Surrounding whitespace and one conventional `:tag:` wrapper are normalised. Whitespace inside a tag, colons, and other characters are rejected. Add is idempotent and preserves existing tag order. Remove is idempotent, and removing the final tag leaves no dangling colon suffix. These commands alter only the owning heading. The task UUID, status, priority, drawers, body, sibling order, and unrelated file content are preserved. Imported owners use the normal atomic optimistic-concurrency write and fail with `conflict` if changed on disk.

## Moving existing tasks

`ot move <id>` relocates an existing task subtree **inside its own file** and takes exactly one destination:

- `--parent <dest-id>` reparents the subtree as the destination task's last child, re-normalising heading depth for the whole subtree.
- `--section <name>` returns it to a level-2 heading at the end of that level-1 section (the depth `ot create` gives a top-level task).

The subtree's source lines move verbatim apart from heading stars, so `:CUSTOM_ID:`, `:CREATED:` / `:STARTED:`, `CLOSED:`, `:LOGBOOK:`, `#+IMPORT:`, unknown drawer properties, bodies, descendant order, and intra-subtree blank lines all survive byte-for-byte, and the rest of the file is left untouched. `:CUSTOM_ID:` never changes, so `#+IMPORT:` links and a change-record's `#+PARENT: [[task:<uuid>]]` keep resolving. Use it to group existing tasks under a new parent instead of hand-editing org.

`move` is in-file only. Locality changes belong to `ot publish` / `ot unpublish` and the archive to `ot archive` / `ot unarchive`, so a destination in another file is refused rather than silently creating a second writable node for a UUID. It also refuses an archived source, a destination that is the task itself or one of its descendants, an unknown `--section`, and supplying both or neither destination. `--dry-run` runs every preflight and reports the proposed move without writing.

## Removing a task subtree and pruning blockers

`ot remove <id>` is preview-first: `--dry-run` returns the complete subtree (ids, summaries, statuses), unchecked `- [ ]` criteria with source context, inbound task blockers outside the subtree, affected files, selection impact, and `dryRun: true` without writing. A non-dry-run call requires `--yes`; otherwise it returns a `confirmation-required` error carrying the same impact. Protocol top-level graph roots are refused (`top-level-root`) so use `CANCELLED`/`DONE` plus `ot archive` for history-preserving lifecycle closure.

Inbound task blockers prevent removal with `inbound-blockers`. Pass `--prune-blockers --yes` only after reviewing the projection: it removes exactly blockers that resolve into the target subtree in the same preflighted source-root write. A selected target or descendant is cleared. Imported and local nested tasks persist to their own owning file. Every known affected baseline is checked before the first write, then each file is atomically replaced. This is optimistic concurrency, not a filesystem transaction.

`ot blocker prune --dry-run` reports unresolved explicit `task:` and legacy bare-full-UUID blockers across the active graph. `ot blocker prune --yes` removes only those entries; valid or ambiguous task references and human, URL, Jira, and other blockers remain. Both commands use compact `org-tasks/v1` envelopes and the global `--dry-run`.

## Interactive TUI

Bare `ot` on an interactive terminal launches the standalone task browser. It is harness-agnostic: any human or agent with a TTY can use it -- no pi extension required. The pi tasks extension is a pi-specific overlay with the same currently-exposed key map; both surfaces dispatch their exposed mutations through the same `ot` commands (`status --cycle`, `priority --cycle`, `select`, `archive`, and others). Both expose removal on `D`: the standalone TUI previews the impact and requires a second `D` on the same cursor task, while pi uses its modal confirmation. Confirmed `D` removal requests inbound-blocker pruning; neither UI exposes a separate blocker-prune key.

Layout: task tree (status, id prefix, priority, summary) plus a details pane. The details pane renders beside the tree on landscape terminals and stacks below it when the terminal is narrow (< 80 columns) or portrait (width < height × 2, correcting for the ~1:2 cell aspect). Colours are shared with `ot list` via `styling/palette-256`.

Key map:

| Keys | Action |
|------|--------|
| `↑↓` / `j k` | move cursor |
| `←→` / `h l` | cycle status back/forward |
| `⇧←` `⇧→` | cycle priority (unset→A forward, unset→D back) |
| `Enter` / `Space` / `Tab` | collapse/expand subtree |
| `s` | select / deselect task |
| `D` | preview removal; press `D` again on the same task to remove and prune reported inbound blockers |
| `n` / `N` | new sibling / child task |
| `e` / `p` | edit task / edit linked plan |
| `A` | archive (closed top-level tasks) |
| `P` / `U` | publish / unpublish |
| `J` | open linked-issue URLs |
| `Ctrl-d` / `Ctrl-u` | scroll details pane |
| `Esc` / `Alt-t` | quit |

Native Emacs `org-archive-subtree` may leave `#+SELECTED:` pointing at the archived active subtree because that local keyword is outside the archived file. Queries stay read-only: use `ot selected`/`ot doctor` to observe it, then `ot select --clear-stale` to atomically clear only an unresolved pointer. Normal `ot archive` already clears a selected archived subtree itself.

`ot show <id>` resolves only the active graph (`TASKS.org`, local tasks, and linked imports), so a successfully archived id returns `unknown-task`. Verify archive visibility with `ot scan --scope archived --format json`; use `ot unarchive` when the archived subtree itself must be restored for active-graph inspection or mutation.

`ot unarchive <id>` resolves exact IDs and unique prefixes from `TASKS.archive.org` only. It restores the archived subtree into an existing shared `TASKS.org` level-1 section, preferring explicit `--section` over the `:ARCHIVE_OLPATH:` stamped by `ot archive`; legacy entries and roots archived from a file-level `#+IMPORT:` record carry no `:ARCHIVE_OLPATH:`, so they require `--section` rather than being guessed. It removes `:ARCHIVED:` / `:ARCHIVE_OLPATH:`, reverses a matching linked record parent from `archive:` to `task:`, and does not change status, `CLOSED:`, or LOGBOOK. `--dry-run` reports the source, destination, section, and proposed parent rewrite without writing.

`n`/`N` mirror the pi overlay: with a cursor they create a sibling/child of the current task (inheriting its file/`--local` routing). With an empty list they create a top-level task under the default section.

Editor launches (`e`, and `p` on an existing plan) go through a configurable resolver: the `--editor` option, then `OT_EDITOR`, then `EDITOR`, defaulting to `emacsclient`. `emacsclient`/Vim use `+LINE file`; VS Code (`code`/`vscodium`/`cursor`) uses `--goto file:line`. For the Emacs editor the TUI ensures a server is reachable, starting `emacs --daemon` if needed (override binaries via `EMACSCLIENT_BINARY` / `EMACS_BINARY`).

Exit contract: the TUI reserves stdout for a final org-tasks/v1 selected-task envelope, so scripts can run it interactively and still consume machine output afterwards. Exiting without pressing `s` returns the persisted selection, not the cursor row.

## Root resolution

`ot` resolves the project root from explicit `--root` first. Without `--root`, it starts at the process current working directory and walks parent directories to the nearest `TASKS.org`, falling back to the current directory when none exists. The walk continues to the filesystem root. The nearest ancestor may be outside `$HOME`.

`ot root` prints the resolved absolute project root on one line. Only `--root` changes that value: `--tasks`, `--local`, and `--archive` override protocol file paths exposed by `ot list`, not project-root resolution.

`ot list --format json` returns both the resolved `root` and absolute protocol `files`:

```json
{
  "root": "/path/to/project",
  "files": {
    "tasks": "/path/to/project/TASKS.org",
    "local": "/path/to/project/TASKS.local.org",
    "archive": "/path/to/project/TASKS.archive.org"
  }
}
```

The pi tasks extension shares CLI root resolution by spawning `ot list` from the workspace cwd and using these returned `root` / `files` fields.

ID-accepting commands accept full `:CUSTOM_ID:` values or any unique prefix of at least four characters. `ot list` and `ot scan` print the first 8 characters of each id as an `id` column; that prefix can be pasted back into any id-accepting command. Ambiguous values fail with `ambiguous-id` and include matching candidates. For blockers, write `task:<UUID>`; existing bare full-UUID tokens also resolve as task dependencies without being rewritten, while all other bare text remains opaque.

## Change-record scaffolding

`ot record create <id>` creates the plan file when missing, attaches `#+IMPORT:` to the parent task, and migrates existing child task trees from the parent into the new record's `* Plan` section. Existing record files are not modified for migration. The scaffold emits the org-plan required sections (`* Intent`, `* Summary`, `* Plan`, `* Implementation`); optional sections such as `* Validation` are added by the author only when they earn their place. `ot doctor` checks spec-aware records for the required four and warns on a present-but-empty `* Validation`.

`--mode retrospective` also computes the `git log` scope from `:STARTED:`/`CLOSED:` and returns it in the JSON result for the prompting layer to use.

## Spec keyword and checks

`#+SPEC:` is a single keyword naming relevant specification documents, used in two contexts (disambiguated by file) and always carrying its value as a bare `[[proj:PATH]]` org link (repo-root relative, so the path is directly navigable in Emacs from TASKS.org and from records):

- In `TASKS.org` it declares where the project's living specs live -- the *discovery input*: repeatable, optional, each value a `[[proj:PATH]]` link to a spec file or folder. See the org-plan skill for the discovery model (default root, implicit specs, rooted/transitive traversal).
- In a change-record it lists the specs relevant to that change (cite individual sub-specs). Whether each was actually impacted is a closeout determination recorded in `** Shipped`. `ot doctor` reconciles the list against git only as an advisory nudge:

```org
#+SPEC: [[proj:skills/org-plan/SKILL.md]]
#+SPEC: [[proj:design/specs/data-model.org]]
```

Use `#+NO_SPEC: true` when the project has no durable contract layer, the task is below the spec threshold, or the contract is intentionally unaffected. Any truthy value (`true`/`t`/`yes`/`y`/`on`/`1`) is accepted. `#+NO_SPEC:` suppresses only the `spec-untouched` nudge -- a malformed `#+SPEC:` value in the same record is still reported.

## `ot spec list`

`ot spec list` (alias `ot spec discover`) is a read-only report of the org-plan discovery traversal (see org-plan SKILL.md § Spec discovery): `#+SPEC:` roots declared in TASKS.org, or the default root `./design/SPEC.org` when none are declared; implicit specs (root `README.*`, `AGENTS.md`, the skills directory); folder roots expanded recursively; org `[[file:...]]`/`[[proj:...]]` links, Markdown `[text](path)` links, and org `#+INCLUDE:` directives all followed transitively with a visited-set cycle guard (external `http`/`https` targets are never followed). Complete segments `.git`, `.direnv`, `.devenv`, `.cache`, `node_modules`, `target`, `build`, `dist`, and `.next` are excluded. Candidates with a NUL byte are omitted. Invalid control-data targets yield non-fatal `spec-link-invalid` warnings with source and raw target, and `ot doctor` emits the same ordered warning finding. Prints the discovered path set with root provenance (e.g. `#+SPEC: ...`, `default root: ...`, `implicit: ...`, `link from ...`).

`ot doctor` emits these change-record and spec findings:

- `spec-untouched` -- a `#+SPEC:` path declared in a record has not been touched in the current git working tree/index. A nudge, not a gate; it cannot infer omitted specs that were never declared.
- `spec-value-malformed` -- a `#+SPEC:` value is not a bare `[[proj:PATH]]` link: a plain path, the labelled `[[proj:PATH][label]]` form, or a path that is absolute, escapes the repo root (`..`), or is whitespace-padded.
- `spec-path-dangling` -- a `#+SPEC:` link in TASKS.org points at a path that does not resolve on disk (file or folder).
- `inline-path-dangling` -- a single-token Markdown-backtick or Org-verbatim repo-relative path citation in a change-record has an existing first path segment but does not resolve on disk. The check ignores URLs, absolute paths, globs/placeholders, basename-only tokens, source/example blocks, and illustrative roots; it is advisory and existence-only.
- `spec-citation-untested` -- an `** Acceptance` criterion cites `spec:` (see org-plan SKILL.md § Spec/test citation on acceptance criteria) but no `test:` evidence, and is not under `*** Anti-criteria` (which is its own evidence). A nudge only; a criterion with no citation at all produces no finding.
- `spec-stale` ("declared-but-stale") -- a `#+SPEC:` path declared in a record is unchanged in the current git working tree/index while code it transitively links to (per the `ot spec list` traversal) did change. A lightweight local echo of SOTA drift gates -- still advisory, never blocking; does not fire when the spec itself also changed, or when nothing it links to changed.
