# `ot` — org-tasks command-line interface

`ot` is the Babashka-powered protocol engine for the
[`org-tasks`](../SKILL.md) memory protocol. It owns parsing,
serialization, status lifecycle writes, archive mechanics, selection,
doctor checks, section reads, summary scans, and change-record
scaffolding. Other coding agents (pi, Emacs companions, CI scripts)
shell out to `ot` rather than reimplementing the protocol.

The original cutover from the TypeScript helpers is recorded in
[`design/log/2026-05-18-tasks-extension-ot-cli.org`](../../../design/log/2026-05-18-tasks-extension-ot-cli.org);
the machine-output contract lives in [`docs/contract.md`](docs/contract.md).

## Install

### Third parties (recommended)

```sh
# Requires babashka + bbin on PATH.
bbin install io.github.cormacc/dotagents --as ot --latest-sha
```

This drops a self-contained shim at `~/.local/bin/ot` that fetches
deps and invokes `bb` with the right classpath.

### Local development

```sh
# In a fresh checkout of cormacc/dotagents (or this submodule).
bbin install ./. --local/root . --as ot

# Or run the in-tree shim directly without installing:
./skills/org-tasks/scripts/ot --help

# Or run via babashka task:
bb run ot --help
```

The local shim resolves the dotagents repo root from its own path and
invokes `bb --config <repo>/bb.edn --deps-root <repo> -m org-tasks.cli`,
so it works regardless of the caller's working directory.

## Usage

```
ot [global-options] <command> [command-options]
```

Common subcommands:

| Command              | Purpose                                                       |
| -------------------- | ------------------------------------------------------------- |
| `ot list`            | List the task graph                                           |
| `ot show <id>`       | Show one task plus plan summary                               |
| `ot create <text>`   | Create a task under `--section` (default: Improvements)       |
| `ot status <id> X`   | Cycle status (TODO/STARTED/WAITING/DONE/CANCELLED)            |
| `ot select <id>`     | Mark a task selected (`--clear` to deselect)                  |
| `ot archive <id>`    | Archive a closed top-level task                               |
| `ot doctor`          | Run protocol health checks                                    |
| `ot section <file>`  | Read one `* section` of an org file                           |
| `ot scan`            | Walk the graph for prior-art change-record summaries          |
| `ot record create`   | Scaffold a change-record and attach `#+IMPORT:`               |
| `ot issue …`         | Manage `:LINKED_ISSUES:`                                      |
| `ot blocker …`       | Manage `:BLOCKED-BY:`                                         |
| `ot handoff …`       | Manage `:HANDOFF:`                                            |

Global options (every subcommand):

- `--root <dir>` (default: `git rev-parse --show-toplevel`, else cwd)
- `--format text|json|edn` (default: `text`)
- `--tasks`, `--local`, `--archive` (override protocol-file paths)
- `--dry-run`
- `--yes` / `-y`
- `--no-color`
- `--help` / `-h`

Run `ot --help` for the full command index.

## Machine output

Every command supports `--format json` (and `--format edn`). The
envelope contract is documented in
[`docs/contract.md`](docs/contract.md):

```json
{
  "ok": true,
  "schema": "org-tasks/v1",
  "result": { ... },
  "warnings": []
}
```

Errors flow over stderr with the same envelope, `"ok": false`, and an
exit code of 1 (or 2 for option-parse failures).

## Project layout

```
deps.edn                      # tools.deps root for the dotagents repo
bb.edn                        # bbin + bb tasks (declares :bbin/bin {ot ...})
skills/org-tasks/
  SKILL.md                    # protocol skill (file format, lifecycle)
  scripts/
    ot                        # in-tree shell shim
    src/org_tasks/
      cli.clj                 # dispatch table + global options
      output.clj              # envelope + format renderer
      root.clj                # project-root resolution
      parser.clj              # org parser/serializer
    test/org_tasks/
      test_runner.clj         # clojure.test discovery + runner
      cli_test.clj            # CLI smoke tests
    fixtures/round-trip/      # byte-identical org fixtures
    docs/
      boundary.md             # what `ot` owns vs the pi extension
      contract.md             # JSON/EDN envelope contract
      test-map.md             # TypeScript → Clojure test mapping
```

## Testing

```sh
bb test
```

Discovers every `*_test.clj` namespace under
`skills/org-tasks/scripts/test/` and runs them via `clojure.test`. Exit
code is 0 on success, 1 on any failure.

## See also

- `../SKILL.md` — the org-tasks protocol (file format, lifecycle).
- `../../org-plan/SKILL.md` — the change-record section contract.
- `pi/extensions/tasks/README.md` — the pi UI/event glue layer.
