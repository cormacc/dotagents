# Code Review: `ot` org-tasks CLI/TUI (openai-codex/gpt-5.5)

**Reviewed:** `skills/org-tasks/scripts/src/org_tasks/`, tests, CLI/TUI entry points
**Tests:** `bb test` — 193 tests, 724 assertions, 0 failures

## Summary

The codebase is well organized, with good separation between parsing, graph loading, command handlers, lifecycle logic, output shaping, and TUI state. The existing test suite is substantial and passes cleanly. The main risks are around file rewrite safety and a few import/link edge cases that can cause surprising behavior or data loss in real use.

## Findings

### [P1] Mutations can overwrite concurrent/manual edits with stale source content

**File:** `skills/org-tasks/scripts/src/org_tasks/loader.clj:187-210`

`save-source-roots` prefers the task's captured `:source-content` over the current file contents. Most mutating commands load the graph, mutate an in-memory task, then save using this snapshot. If the file changes between load and save — another `ot` process, editor save, git checkout, etc. — the later write will silently discard those changes.

**Recommendation:** Before writing, re-read the current file and compare it to the loaded snapshot via content hash or mtime+size. If it changed, fail with a conflict error instead of writing. Consider a project/file lock around read-modify-write for mutators.

### [P1] Archiving file-level imported tasks does not remove them from their source file

**Files:** `loader.clj:48-64`, `124-128`; `commands/archive_publish.clj:185-197`

File-level `#+IMPORT:` tasks are appended into the top-level task list as normal shared tasks. `archive-cmd` allows archiving any top-level non-local closed task, then removes it only from `TASKS.org` via `remaining-shared`/`save-file-block!`. For a task sourced from a file-level import: the task is copied into `TASKS.archive.org`, the actual imported source file is not modified, and the task still appears active on the next `ot list`.

**Recommendation:** Either disallow archive/publish/unpublish for file-level imported roots, or persist removal to the task's actual `:source-path` using the same source-root save path used by status mutations.

### [P2] Malformed drawers can swallow the rest of the file and be rewritten as task metadata

**File:** `parser.clj:235-250`, `269-273`

`consume-drawer!` reads until `:END:` or EOF. If a `:PROPERTIES:` or `:LOGBOOK:` drawer is missing `:END:`, the parser consumes all following lines, including later headings, as drawer lines. A later mutating command can then serialize that malformed parse back out, effectively hiding or corrupting subsequent tasks.

**Recommendation:** Detect EOF before `:END:` and surface a parse error/diagnostic. Mutating commands should fail fast on malformed drawers instead of rewriting.

### [P2] TUI "plan" action opens raw typed import targets instead of resolved files

**Files:** `parser.clj:281-288`; `tui/dispatch.clj:185-191`

Parser preserves typed imports such as `[[plan:foo.org]]` as `:import-path "plan:foo.org"`. The TUI plan action passes `importPath` directly to the editor. For typed links this attempts to open a literal path like `plan:foo.org`, not the resolved `design/log/foo.org`.

**Recommendation:** Resolve `importPath` before opening, or include a resolved plan path in the wire task output and use that in the TUI.

## Test Coverage Gaps

- No direct loader tests for `load-graph` file-level import behavior, imported-root mutation/archive behavior, or nested file-level import chains.
- No conflict/concurrent-edit tests for read-modify-write commands.
- Parser tests cover import forms well, but malformed drawer handling should be tested once fail-fast behavior is added.

## What's Good

- Clear namespace boundaries and good command/module organization.
- Lifecycle logic is pure and well isolated.
- CLI JSON/text output envelope is consistently routed through shared output helpers.
- Existing tests are broad and fast; the suite passed cleanly.
