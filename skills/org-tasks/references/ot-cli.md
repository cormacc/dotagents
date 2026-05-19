# `ot` CLI reference

`ot` is the Babashka org-tasks protocol engine under `skills/org-tasks/scripts/`. It owns durable graph reads and writes: parsing, serialization, setupfile/link resolution, lifecycle writes, archive mechanics, selection, linked issues, blockers, handoff notes, section reads, summary scans, and change-record scaffolding.

The pi tasks extension is a UI/event wrapper around `ot`: overlay rendering, keybindings, prompts, confirmations, editor/browser opening, file watching, compact widgets, and agent follow-up prompts stay in pi; durable protocol mutations go through `ot`.

## Install

Third-party harnesses:

```shell
bbin install io.github.cormacc/dotagents --as ot --latest-sha
```

In-tree development:

```shell
bbin install ./. --local/root . --as ot
./skills/org-tasks/scripts/ot --help
bb run ot --help
```

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

## Common commands

```shell
ot init
ot list --format json
ot list --levels 0
ot show <id-or-selected>
ot create "New task" --section Improvements --linked-issue '[[jira:ABC-1]]'
ot status <id> STARTED
ot select <id>        # or: ot select --clear
ot archive <id> --yes
ot publish <id>       # TASKS.local.org -> TASKS.org
ot unpublish <id>     # TASKS.org -> TASKS.local.org
ot doctor --format json
ot section design/log/foo.org Summary --format json
ot scan --scope all --max-body-chars 500 --format json
ot record path <id>
ot record create <id>
ot record create <id> --mode retrospective
ot issue list|add|remove|urls <id> [...]
ot blocker list|add|remove <id> [...]
ot ready <id>
ot handoff get|set|clear <id> [...]
ot uuid --count 3
```

ID-accepting commands accept full `:CUSTOM_ID:` values, compact `prefix…suffix` aliases displayed by `ot list`/`ot scan` (unicode `…` and ASCII `...` both work), or legacy unique prefixes of at least four characters. Ambiguous short IDs fail with `ambiguous-id` and include matching candidates.

## Change-record scaffolding

`ot record create <id>` creates the plan file when missing, attaches `#+IMPORT:` to the parent task, and migrates existing child task trees from the parent into the new record's `* Plan` section. Existing record files are not modified for migration.

`--mode retrospective` also computes the `git log` scope from `:STARTED:`/`CLOSED:` and returns it in the JSON result for the prompting layer to use.
