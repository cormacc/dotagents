# Code Review — `ot` (org-tasks) CLI/TUI

**Reviewed:** Babashka/Clojure `ot` CLI under `skills/org-tasks/scripts/` (parser, loader, lifecycle, insert, archive/publish, commands, TUI) on branch `org-tasks-tui`.
**Verdict:** APPROVED (with recommendations)

## Summary
This is a mature, well-factored codebase: clear namespace separation (pure core vs. side-effecting commands), atomic writes, sandboxed path resolution, and strong test coverage (193 tests / 724 assertions, all green). The round-trip serializer that preserves non-task org material is the standout — carefully handles trailing blanks and bottom-up edits. No P0/P1 defects found. The findings below are robustness/consistency improvements around concurrent access and read-error handling, plus a couple of minor nits.

Test suite: `bb test` → **0 failures, 0 errors** (193 tests, 724 assertions).

## Findings

### [P2] No concurrency guard between load and write — lost updates under concurrent edits
**Files:** `src/org_tasks/loader.clj:144` (`atomic-write`), `:196` (`save-source-roots`); `src/org_tasks/commands/status.clj`, `create.clj`, `archive_publish.clj`
**Issue:** Every mutating command follows load → mutate in memory → serialize-preserving-file → atomic write, with no mtime/lock check against the on-disk file. The tool *itself* opens `TASKS.org` in Emacs from the TUI (`editor.clj`), so concurrent human edits are an expected part of the workflow. If the file is saved in the editor between an `ot` load and its write, `ot` silently overwrites those edits. `serialize-tasks-preserving-file` matches by `:line-number` captured at load time, so a changed on-disk layout can also mis-splice.
**Suggested Fix:** Capture the source file's mtime (or a content hash) at load and re-check immediately before `atomic-write`; abort with a `stale-file` / `concurrent-modification` error envelope (fail-fast) rather than clobbering. At minimum, document the single-writer assumption.

### [P2] `save-file-block!` re-slurps instead of using the load-time snapshot
**File:** `src/org_tasks/commands/archive_publish.clj:34-42`
**Issue:** `save-file-block!` re-reads the target with `safe-slurp` and serializes the in-memory tasks (which carry load-time `:line-number`s) against that *freshly-read* content. `loader/save-source-roots:204` deliberately prefers the captured `:source-content` for exactly this reason. The re-slurp here widens the TOCTOU window relative to `save-source-roots` and can mis-align line numbers if the file changed since load.
**Suggested Fix:** Serialize against the load-time content (thread the parsed `:source-content` through, as `save-source-roots` does) rather than re-reading.

### [P2] `safe-slurp` masks read failures on files about to be overwritten → potential data loss
**Files:** `src/org_tasks/loader.clj:24` (`(catch Throwable _ nil)`); consumers at `archive_publish.clj:174`, `:38`
**Issue:** `safe-slurp` cannot distinguish "file absent" from "file present but unreadable" (permissions, transient I/O). In `archive-cmd`, `existing-archive (or (safe-slurp archive-path) "")` — if a *populated* `TASKS.archive.org` momentarily fails to read, it is treated as blank, the default preamble is regenerated, and only the newly-archived task is written, **discarding all previously-archived entries**. The same "nil → empty original" path in `save-file-block!` / `serialize-tasks-preserving-file` can collapse `TASKS.org` to a single newline. Low probability, but high blast radius and contrary to the fail-fast preference in the review guidelines.
**Suggested Fix:** Distinguish `not-exists` from `read-error`. Only treat genuinely-absent files as empty; surface real read errors as an error envelope and refuse to write.

### [P3] `.tmp` sidecar name collides under concurrent `ot` invocations
**File:** `src/org_tasks/loader.clj:145` — `tmp (str path ".tmp")`
**Issue:** Two concurrent `ot` writes to the same file share the identical `path.tmp` staging name and can corrupt each other's temp file before the rename. Rare, but trivially avoided.
**Suggested Fix:** Use a unique temp name (pid/random/`fs/create-temp-file` in the same directory) before `fs/move`.

### [P3] `create-cmd` calls `load-context` up to twice per invocation
**File:** `src/org_tasks/commands/create.clj:14,23`
**Issue:** `resolve-context` runs at line 14, then `(:tasks (load-context opts))` re-runs full graph loading at line 23 (only when `--relative-to` is set). Loads the whole task graph (and all `#+IMPORT:` chains) a second time. Correctness is fine; it's redundant I/O.
**Suggested Fix:** Bind `load-context` once and reuse its `:tasks`/`:files`/`:project-root`.

## What's Good
- **Round-trip serializer** (`parser/serialize-tasks-preserving-file`) is genuinely careful: bottom-up edits keep offsets stable, trailing-blank preservation avoids spacing erosion, and the trailing-newline handling is correct. Well covered by round-trip fixtures.
- **Path sandboxing** (`paths.clj`) resolves symlinks via realpath and rejects `..`/absolute escapes on both the target and every `--also-scan` candidate — no traversal hole found.
- **Atomic writes** (temp + `fs/move :replace-existing`) prevent partial-write corruption on the happy path.
- **Pure/side-effect separation** is disciplined: `lifecycle`, `parser`, `scan`, `insert/build-task-block` are pure and independently tested; I/O is isolated in `loader` and command handlers.
- **ID resolution** (`task/resolve-id-candidate`) enforces a 4-char minimum prefix and reports ambiguity explicitly — sensible guard against accidental binds.
- **Errors use stable `:code` identifiers** in the output envelope, not message strings — matches the guideline for code-based error checks.
- No secrets/state-broadcast exposure surface; no SQL; no un-parametrised untrusted input. New deps (`bling`, `charm.clj`, `nexus`, `babashka/cli`) are all TUI/CLI-appropriate.

## Notes / non-issues checked
- Import-cycle protection in `loader/attach-import-children` (visited set) is correct and keeps the graph finite.
- `random-uuid` for `:CUSTOM_ID:` has no existing-ID collision check, but UUIDv4 collision risk is negligible — not worth flagging.
- Parent auto-promotion across `#+IMPORT:` boundaries (`status.clj/find-path-to-id`) correctly walks both `:children` and `:import-children`.
