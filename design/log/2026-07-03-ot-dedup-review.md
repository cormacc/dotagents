# Code Review — ot dedup/simplification pass (cd44044..98c9eea)

**Reviewed:** 12 commits implementing the DONE tasks in
`design/log/2026-07-03-ot-deduplication-and-simplification-pass.org`
(wrapper deps, positional-arg helper, derived dispatch-coerce, dead-option
removal, atomic-write share, link-path helper, tree traversal, generated
property handlers, perf memoization).
**Verdict:** NEEDS CHANGES

## Summary
The refactors are, with one exception, faithful mechanical extractions:
path resolution, tree traversal, dispatch-coerce derivation, atomic-write,
and the perf work all preserve prior semantics and `bb test` is green at
186 tests / 651 assertions (matches the acceptance baseline). The exception
is the generated property-command handlers (commit `f19a556`), which silently
change the JSON/EDN output shape of `ot issue add` / `ot issue remove` — a
breach of the change-record's Must-not anti-criterion on machine output.

## Findings

### [P1] `ot issue add` / `ot issue remove` machine output shape changed
**File:** `skills/org-tasks/scripts/src/org_tasks/commands/links.clj`
(`add-remove-property-cmd`, commit `f19a556`)
**Issue:** The generated handler emits the `:tokens` field from the wired
issue list when a `:wire` fn is present:
```clojure
(or (:mut-key cfg) (:list-key cfg))
(if (:wire cfg)
  ((:wire cfg) ((:list-fn cfg) updated))   ; issue path
  ...)
```
For the `:issue` config `:wire` = `#(mapv linked-issue->wire %)`, so `:tokens`
is now a vector of maps `[{:rawToken .. :label .. :url ..}]`. The pre-refactor
handlers (`3192b1f:.../links.clj` lines 222, 246) emitted
`:tokens (existing-issue-tokens updated)` — a vector of raw token **strings**.

Verified live against `bb.edn`:
- `issue add … [[jira:ABC-1]]` → `"tokens": [ { "rawToken": …, "label": …, "url": … } ]`
  (was `["[[jira:ABC-1]]"]`).
- `issue remove` similarly emits `:tokens` as a (now empty) map vector.

This violates the change-record anti-criterion "Must not: change any key,
field, or value in org-tasks/v1 machine output" and the task-18a51246
acceptance "CLI output and JSON envelopes byte-identical for all nine
commands." The existing test (`commands_test.clj:1240 issue-add-list-remove-urls`)
only asserts on `issue list` (the `:issues` key), so the change slipped through.
Any agent/automation that added an issue and read `:tokens` as strings will
break.
**Suggested Fix:** For the mutation path, keep `:tokens` as the raw string
vector (`(existing-issue-tokens updated)` / `((:tokens-fn cfg) updated)`);
reserve the `:wire` mapping for the `list`/`get` path only. Add an assertion
on `issue add`/`remove` `:tokens` shape to lock it.

### [P2] `ot blocker remove` now normalises the token (behaviour + text drift)
**File:** `skills/org-tasks/scripts/src/org_tasks/commands/links.clj`
(`add-remove-property-cmd`, commit `f19a556`)
**Issue:** Removal now applies `normalise-blocker-token` before both matching
and display:
```clojure
token' ((:normalise cfg identity) token)   ; "foo" -> "human: foo"
... (filterv #(not= % token') existing) ...
:text/lines [((cfg :remove-line) token')]
```
The original `blocker-remove-cmd` (`3192b1f:.../links.clj:141,146`) filtered
against the **raw** arg and printed the raw arg. For an unprefixed token this
changes behaviour: previously `blocker remove <id> foo` matched nothing and
printed `Removed blocker: foo`; now it matches the stored `human: foo`, removes
it, and prints `Removed blocker: human: foo`. This is arguably a bug fix, but
it is a CLI-text / behaviour change not covered by the "removed ignored option"
exception. Prefixed tokens (the documented path, since `add` stores normalised)
are unaffected.
**Suggested Fix:** If the fix is intended, note it in the change-record
Implementation and add a test; otherwise filter/print the raw `token` for
removal to preserve prior output.

### [P3] `link-template-cache` grows unbounded in long-lived TUI sessions
**File:** `skills/org-tasks/scripts/src/org_tasks/task.clj`
(`link-template-cache`, commit `98c9eea`)
**Issue:** The module-level atom is keyed by `[source-path effective-content]`.
Correctness is fine — a content edit yields a new key, so no stale/cross-file
leakage — but every distinct content version accumulates an entry for the life
of the process. Irrelevant for one-shot CLI; a slow leak in a long TUI session.
**Suggested Fix:** None required for CLI. If TUI longevity matters, key by
`source-path` and invalidate on reload, or bound the cache.

## What's Good
- **Path-resolution extraction (`546f8af`)** is exact: all five call sites
  (loader ×2, list_show, maintenance, archive_publish, insert ×2) now route
  through `links/resolve-link-target` / `resolve-task-link-target` with
  identical base-dir selection (`:from-project-root` vs `fs/parent`) and
  unchanged `paths/resolve-project-path` sandbox delegation. Added unit tests
  cover project-root, file-relative, and sandbox-escape cases.
- **Tree unification (`3192b1f`)** preserves pre-order (`t`, children subtree,
  then import-children subtree), so doctor finding order is unchanged; insert's
  children-only lookup is correctly preserved via `{:imports? false}`.
- **dispatch-coerce derivation (`0aedc84`)** and `enum-opt` cleanly reproduce
  the hand-maintained table (incl. `:coerce []` repeated flags) from spec
  `:coerce` entries; empty `section-spec` removed.
- **Perf (`98c9eea`)**: `row-depths` single pass is equivalent to the old
  O(n²) parent-chain climb for pre-order rows, including the filtered-ancestor
  edge case (depth 0 either way); memo key includes content so no stale reads.
- Dead options `--scope`/`--selected` cleanly removed with the always-true
  branch; atomic-write/safe-slurp consolidated. Commit hygiene is good — one
  concern per commit, all reference the change-record.

## Gate for the deferred refactors
The `issue add/remove` regression shows the nine-command "byte-identical
envelope" claim was asserted but not test-covered. Before the deferred
**doctor pure-check rewrite** (finding-order sensitive) and **parser facade
split** (round-trip sensitive) land, add golden/fixture output assertions for
the mutation commands and for `ot doctor`, so ordering/shape drift is caught
mechanically rather than by inspection.
