# `ot` -- org-tasks command-line interface

`ot` is the Babashka-powered protocol engine for the [`org-tasks`](../SKILL.md) memory protocol. It owns parsing, serialization, status lifecycle and priority writes, archive/unarchive mechanics, selection, doctor checks, section reads, summary scans, change-record scaffolding, and a standalone terminal task browser. Other coding agents (pi, Emacs companions, CI scripts) shell out to `ot` rather than reimplementing the protocol.

This README covers install, testing, and the documentation map. It deliberately does not repeat CLI usage, contracts, or architecture -- each of those has exactly one home (see [Documentation map](#documentation-map)).

## Install

### Third parties (recommended)

```sh
# Requires babashka + bbin on PATH.
bbin install io.github.cormacc/dotagents --as ot --latest-sha
```

This drops a self-contained shim at `~/.local/bin/ot` that fetches deps and invokes `bb` with the right classpath.

### Local development

```sh
# In a fresh checkout of cormacc/dotagents (or this submodule).
bbin install ./. --local/root . --as ot

# Or run the in-tree shim directly without installing:
./skills/org-tasks/scripts/ot --help

# Or run via babashka task:
bb run ot --help
```

The local shim resolves the dotagents repo root from its own path and invokes `bb --config <repo>/bb.edn --deps-root <repo> -m org-tasks.cli`, so it works regardless of the caller's working directory. All launcher forms preserve that directory. Relative `--root` values therefore resolve from the caller. Its fallback `-Sdeps` (used when no repo `bb.edn` is present, e.g. standalone skill installs) must stay in sync with `bb.edn` -- `bb.edn` is the source of truth.

## Usage

Run `ot --help` for the command index and `ot <command> --help` for per-command options -- both are derived from the same command registry as dispatch itself, so they cannot drift. Full usage reference, including the interactive TUI, key map, root resolution, and id-prefix rules: [`../references/ot-cli.md`](../references/ot-cli.md).

## Testing

```sh
# from the dotagents repository root or this scripts directory
bb test

# from the repository root, run one discovered Clojure test namespace
bb test-clojure org-tasks.doctor-test
```

The local `bb.edn` delegates `bb test` to the repository-root task. The repository-root `bb test-clojure` command accepts only discovered `*_test.clj` namespaces. It rejects option-like and unknown arguments before namespace loading. Exit code is 0 on success, 1 on any test failure. Round-trip fixtures under `test/fixtures/round-trip/` must survive parse→serialize byte-identically.

## Documentation map

| Document | Owns |
| --- | --- |
| [`../SKILL.md`](../SKILL.md) | org-tasks protocol: file format, lifecycle, agent workflows |
| [`../references/ot-cli.md`](../references/ot-cli.md) | CLI + TUI usage: commands, key map, root resolution, machine-output overview |
| [`../references/protocol.md`](../references/protocol.md) | field-level org format reference |
| [`docs/contract.md`](docs/contract.md) | canonical `org-tasks/v1` JSON/EDN envelope schemas per command |
| [`docs/DESIGN.org`](docs/DESIGN.org) | architecture: namespaces, data flow, TUI design, project layout |
| [`docs/boundary.md`](docs/boundary.md) | what `ot` owns vs the pi tasks extension |
| [`docs/test-map.md`](docs/test-map.md) | test suite map (behaviour → namespace) |
| [`AGENTS.md`](AGENTS.md) | invariants and workflows for agents developing `ot` itself |
| `../../org-plan/SKILL.md` | change-record section contract |
| `pi/extensions/tasks/README.md` | the pi UI/event glue layer |

The original cutover from the TypeScript helpers is recorded in [`design/log/2026-05-18-tasks-extension-ot-cli.org`](../../../design/log/2026-05-18-tasks-extension-ot-cli.org).
