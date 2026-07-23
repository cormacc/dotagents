# Review: herdr-subagents skill scripts

**Repo:** `/home/cormacc/dotfiles/agents` (dotagents)
**Range:** staged working tree — `git diff --cached` (18 files, +1212/−302), no unstaged or untracked implementation files. `TASKS.org`, `TASKS.archive.org`, `pi/settings.json` excluded per request.
**Change record:** `design/log/2026-07-24-herdr-subagent-skill-scripts.org` (read in full).

---

## Findings

### [P1] Documented/deployed launcher path silently takes the `-Sdeps` fallback and relocates both the child's cwd and the assignment root

- **Location:** `skills/herdr-subagents/scripts/subagent:5-22` (symlink loop, `repo_root` computation, `cd "$script_dir"` in the fallback branch)
- **Problem:** Two compounding defects in the launcher.
  1. The symlink loop only tests the *final* path component (`[ -L "$script_path" ]`). Home Manager deploys `~/.agents/skills` as a **directory** symlink (`agents.nix`), so the final component is a real file and the loop never fires. `pwd` (logical, not `-P`) then keeps the un-canonicalised path, and `repo_root="$(cd "$script_dir/../../.." && pwd)"` textually normalises to `/home/cormacc/.agents`, which has no `bb.edn`. The repo branch is skipped.
  2. The fallback branch runs `cd "$script_dir"` (`subagent:20`) before `exec bb`. That silently changes the CLI's cwd, which is load-bearing in two places:
     - `cli.clj:122` passes `:cwd (System/getProperty "user.dir")` into `herdr.clj:44` `split!` → the child pane is opened in the **skill's own scripts directory**, not the parent's project.
     - `ledger.clj:9` `assignment-root` runs `git rev-parse --show-toplevel` in that cwd → the ledger, index markers, and `RESULT` paths land in the **dotagents repo**, and the project-local roster `<root>/.agents/subagents/` is looked up in the wrong project (project personas become invisible; the global roster silently wins).
- **Impact:** The invocation form documented in both `SKILL.md:14` and `scripts/README.md:24` (`$HOME/.agents/skills/herdr-subagents/scripts/subagent`) is the broken one. Every child spawned this way starts in `…/dotagents/skills/herdr-subagents/scripts` regardless of the parent's project, writes its artifacts and result there, and cannot see project-local personas. This defeats the "preserving cwd" contract and cross-contaminates unrelated repositories with delegation state. Acceptance box *"Launcher works from the symlinked deployment … and from a bare skill-subtree install"* is checked `[X]` but is not satisfied in the deployed configuration and has no test.
- **Fix:** Two one-line changes.
  ```bash
  script_dir="$(cd -P "$(dirname "$script_path")" && pwd)"   # canonicalise through directory symlinks
  ...
  else
    exec bb -Sdeps "{:paths [\"$script_dir/src\"]}" -m herdr-subagents.cli -- "$@"   # no `cd`
  fi
  ```
  `cd -P` alone restores the repo branch for the deployed path (verified); dropping the `cd` fixes the bare-subtree install too.
- **Evidence:** Live reproduction (I am myself a child spawned through this path — `HERDR_SUBAGENT_BIN=/home/cormacc/.agents/skills/…/subagent`, and my cwd is `/home/cormacc/dotfiles/agents/skills/herdr-subagents/scripts`, not the repo root).

  Branch selection probe:
  ```
  deployed  ~/.agents/skills/…/subagent  → repo_root=/home/cormacc/.agents  → BRANCH=fallback
  repo path /home/cormacc/dotfiles/agents/…/subagent → BRANCH=repo
  with `cd -P`: repo_root=/home/cormacc/dotfiles/agents → BRANCH=repo   (fix verified)
  ```

  Fake-Herdr spawn with parent cwd `/tmp/probe-proj` (a real git repo):
  ```
  # via deployed path
  pane split --pane w:p --direction right \
    --cwd /home/cormacc/dotfiles/agents/skills/herdr-subagents/scripts \
    --env HERDR_SUBAGENT_RESULT=/home/cormacc/dotfiles/agents/.agents/tmp/herdr-subagents/…result

  # via repo path (correct)
  --cwd /tmp/probe-proj
  --env HERDR_SUBAGENT_RESULT=/tmp/probe-proj/.agents/tmp/herdr-subagents/…result
  ```

---

### [P2] `collect` will close a pane created by a different parent session

- **Location:** `skills/herdr-subagents/scripts/src/herdr_subagents/cli.clj:78-80` (`maybe-close!`), reached from `cli.clj:164-167` (`collect!`)
- **Problem:** The ledger directory is shared repo-wide (`<git-root>/.agents/tmp/herdr-subagents/ledger/`) and `list`/`status` enumerate **every** entry regardless of `:parent-session`. `collect <task>` reads any entry by id and, on a `COMPLETE`/`FAILED` envelope with a settled agent, calls `herdr/close! (:pane-id entry)`. `collect!` never compares the entry's recorded `:parent-session` against the caller's identity, even though `spawn!` persists it (`cli.clj:116`).
- **Impact:** Directly violates the checked anti-criterion *"Must not: close panes the parent did not create."* Two agents delegating in the same repo (the normal case here — the live ledger already holds entries from 5 distinct parent sessions) can destroy each other's child panes. `subagent list` hands out the task ids needed to do it.
- **Fix:** In `collect!` (and `maybe-close!`), require `(= (:parent-session entry) (:parent-session (parent-identity)))` before closing; on mismatch capture the envelope but retain the pane.
- **Evidence:** `cli.clj:164-167` `(let [entry (ledger/read! task)] … (maybe-close! entry parsed))` — no session check on any path; `cli.clj:174-176` `status`/`list` enumerate `(ledger/entries)` unfiltered. Live ledger:
  ```
  $ ls .agents/tmp/herdr-subagents/ledger/*.json | wc -l   → 262
  # 5 distinct :parent-session values recorded across those entries
  ```

---

### [P2] `bb test` writes into the live delegation ledger and grows it without bound

- **Location:** `skills/herdr-subagents/scripts/test/herdr_subagents/cli_test.clj:21-25` (`fake-env` overrides `PATH`/`HERDR_*` but not the assignment root) with `src/herdr_subagents/ledger.clj:9-15`
- **Problem:** `ledger/assignment-root` resolves via `git rev-parse --show-toplevel`, so the fake-Herdr integration tests run against the developer's **real** `<repo>/.agents/tmp/herdr-subagents/` tree rather than the per-test temp dir the fixture already creates. `core_test.clj:53` correctly isolates with `with-redefs [ledger/assignment-root (constantly root)]`; `cli_test.clj` cannot (it shells out) and does not set an equivalent env override — none exists.
- **Impact:** Every `bb test` permanently adds ~11 ledger JSON files, ~11 index markers, and ~6 result files to gitignored-but-live state. `subagent list`/`status` — shipped commands — now return 262 entries / 240 KB of JSON dominated by fake `pane-id: "w:child"` records, making them useless for their stated purpose. `allocate-index!` for the fake parent session now performs 212 `Files.createFile` probes per allocation and degrades linearly with every test run.
- **Fix:** Have `ledger/assignment-root` honour an override env var (e.g. `SUBAGENT_ASSIGNMENT_ROOT`) before the git probe, and set it to `(:dir fake-env)` in `cli_test.clj`.
- **Evidence:** Measured delta across one `bb test`:
  ```
  ledger json:                    251 -> 262
  index markers (session/worker): 201 -> 212
  result files:                   154 -> 160
  $ time ./…/subagent list  → 2.35 s, 240 585 bytes
  ```

---

### [P2] Relative launcher invocation injects a relative `HERDR_SUBAGENT_BIN` into the child

- **Location:** `skills/herdr-subagents/scripts/subagent:15` (`export HERDR_SUBAGENT_BIN="$script_path"`) and `src/herdr_subagents/cli.clj:44-48` (`launcher-bin` prefers the env value verbatim)
- **Problem:** `script_path` is only made absolute when the final component is a symlink. Invoked as `./skills/herdr-subagents/scripts/subagent`, it stays relative and is exported and then injected into the child pane's environment unchanged.
- **Impact:** Violates the checked acceptance criterion *"`_BIN` with the launcher's absolute path"*. The child's prompt instructs it to run `` `$HERDR_SUBAGENT_BIN publish …` ``; that resolves only while the child's shell remains in the pane's initial cwd. Any `cd` by the child (routine for coding agents) makes publication fail, and since the result file is the sole completion signal, the parent then blocks for the full 10-minute budget and reports `pending/timeout` for work that actually completed.
- **Fix:** Absolutise once after the symlink loop: `case "$script_path" in /*) ;; *) script_path="$script_dir/$(basename "$script_path")" ;; esac` (after `script_dir` is computed with `cd -P`).
- **Evidence:**
  ```
  $ cd /home/cormacc/dotfiles/agents && ./skills/herdr-subagents/scripts/subagent run worker … (fake herdr)
  injected env: HERDR_SUBAGENT_BIN=./skills/herdr-subagents/scripts/subagent
  ```

---

### [P3] A negative `SUBAGENT_POLL_INTERVAL_MS` aborts the run and orphans the child; `0` silently restores the busy-wait

- **Location:** `skills/herdr-subagents/scripts/src/herdr_subagents/cli.clj:14-15` (`poll-interval-ms`) and `cli.clj:93`
- **Problem:** `parse-long` accepts negative values, and `0` is truthy in Clojure so `(or 0 1000)` yields `0`.
  - `-5` → `(Thread/sleep -5)` → `IllegalArgumentException: timeout value is negative`, escaping `wait-and-capture!` mid-flight.
  - `0` → `(min 0 remaining*)` → the exact hot-spin the remediation removed.
- **Impact:** With a negative value the blocking `run` exits 1 with `{"message":"timeout value is negative","data":null}` — no task id, no pane id — while the ledger sits at `prompted` and a real agent pane is left running and unfindable from the error. With `0` the fix is silently reverted.
- **Fix:** `(let [n (some-> (System/getenv "SUBAGENT_POLL_INTERVAL_MS") parse-long)] (if (and n (pos? n)) n 1000))`.
- **Evidence:**
  ```
  SUBAGENT_POLL_INTERVAL_MS='-5'  -> exit=1 {"ok":false,…"message":"timeout value is negative","data":null}
  SUBAGENT_POLL_INTERVAL_MS='0'   -> 23 `agent wait` calls in 200 ms (vs 7 with the 20 ms default path)
  SUBAGENT_POLL_INTERVAL_MS='abc' -> falls back to 1000 ms (correct)
  SUBAGENT_POLL_INTERVAL_MS=''    -> falls back to 1000 ms (correct)
  ```

---

### [P3] Two of the three new bounded-poll tests cannot fail if the sleep is deleted, and the third's margin is ~15 %

- **Location:** `skills/herdr-subagents/scripts/test/herdr_subagents/cli_test.clj:107-128`
- **Problem:** `bounded-poll-eventual-publication` and `bounded-poll-covers-collect-wait` use the fixture's `idle-then-publish` mode, which publishes after a fixed **call count** (`FAKE_WAIT_PUBLISH_AFTER`), not after elapsed time. The `agent wait` count is therefore exactly 4 and exactly 3 respectively whether or not `Thread/sleep` runs; the `<= … 200` upper bounds are unreachable. Only `bounded-poll-timeout-without-result` is time-derived, and its bound (`<= 20`) sits close to the measured no-sleep count.
- **Impact:** The acceptance criterion *"A deterministic fake-Herdr test proves the `agent wait` call count stays within an interval-derived bound"* is carried by a single assertion whose safety margin is 23 vs. 20 measured calls on this machine — a slower host would let a full regression pass. The other two tests prove capture/parity (worthwhile) but not bounding.
- **Fix:** Tighten `bounded-poll-timeout-without-result` to an interval-derived bound with headroom (e.g. `(<= (wait-call-count log) 12)` for 200 ms / 20 ms), and drop the misleading `200` ceilings from the other two so their intent (capture correctness) is unambiguous.
- **Evidence:** measured with the real fixture, `--timeout 200`:
  ```
  SUBAGENT_POLL_INTERVAL_MS=20 (sleep active) →  7 `agent wait` calls
  SUBAGENT_POLL_INTERVAL_MS=0  (sleep absent) → 23 `agent wait` calls   # test bound is <= 20
  ```

---

### [P3] `maybe-close!` turns a successfully captured result into an `ok:false` run

- **Location:** `skills/herdr-subagents/scripts/src/herdr_subagents/cli.clj:78-81`
- **Problem:** `herdr/agent!` is wrapped in `try/catch`, but `herdr/close!` is not. `close!` goes through `value!`, which throws on any nonzero Herdr exit — e.g. when the child's pane has already gone away.
- **Impact:** `capture!` has already validated the envelope and written `:status COMPLETE` to the ledger, but `run` exits 1 and the caller never sees the summary or artifact list. Recoverable only if the parent knows to re-run `collect`.
- **Fix:** `(try (herdr/close! (:pane-id entry)) (catch Exception _ nil))`, mirroring the `agent!` guard on the line above.
- **Evidence:** `cli.clj:81` — `(herdr/close! (:pane-id entry))` is the sole unguarded mutating call on the success path; `herdr.clj:50` `close!` → `herdr.clj:21` `value!` → `throw`.

---

### [P3] A bad artifact path permanently poisons an assignment with no notification

- **Location:** `skills/herdr-subagents/scripts/src/herdr_subagents/cli.clj:69-77` (`capture!`) with `core.clj:82-85` (`artifact-path`)
- **Problem:** If a child publishes a relative artifact path, or an artifact it later deleted, `capture!` throws from inside `wait-and-capture!`'s loop head. The result file is already published and immutable (no-overwrite by design), so every subsequent `collect <task>` throws identically. The ledger is never moved to a terminal status and the pane is never released.
- **Impact:** The assignment becomes uncollectable and unclosable without hand-editing gitignored state. `publish` performs no artifact validation, so the child gets no feedback either. The superseded `SKILL.md` required malformed results to be *"ignored for orchestration and surfaced with a human notification"*; the rewrite dropped that and the implementation hard-fails instead.
- **Fix:** In `capture!`, catch validation/artifact failures, record `:status "invalid"` plus the reason on the ledger entry, and return that as a non-final outcome (pane retained) rather than propagating.
- **Evidence:** `cli.clj:75` `(when-not (fs/exists? path) (fail "result artifact does not exist" …))` inside `capture!`, which is called at `cli.clj:84` and `cli.clj:88` with no handler; `publish!` (`cli.clj:149-162`) never touches `core/artifact-path`.

---

### [P3] Documentation / implementation drift

- **Location:** `skills/herdr-subagents/scripts/docs/contract.md:9`, `cli.clj:175`, `cli.clj:121`
- **Problem:** three small mismatches.
  1. contract.md states *"Every command writes exactly one JSON object to stdout"*, but `--help`/`help` print bare usage text (`cli.clj:175` returns the `usage` string, `-main` at `cli.clj:177-179` prints it verbatim). `subagent run --help` is worse: it is parsed as an option needing a value and returns `{"ok":false,…"option requires a value"}` with exit 1.
  2. `status`/`list` (`cli.clj:174-175`) return JSON **arrays** under `result`, not objects.
  3. `HERDR_SUBAGENT_PERSONA` is injected at `cli.clj:121` and is load-bearing (it gates the nested `planner-n/…` label at `cli.clj:112`), but it is documented in neither `contract.md` ("The child receives `HERDR_SUBAGENT_CHILD`, `_TASK`, `_RESULT`, `_BIN`, and `_WAITING_POLICY`") nor `SKILL.md`. A planner started outside `subagent` therefore silently loses nested labelling, contrary to `SKILL.md`'s label rule.
- **Impact:** Machine consumers that assume a JSON object per invocation break on `--help`; the undocumented env var makes the nested-label behaviour non-obvious to maintainers and to manual-fallback users.
- **Fix:** Emit `--help` through `json-envelope` (or narrow the contract sentence to non-help commands), and add `_PERSONA` to the contract's env list.
- **Evidence:**
  ```
  $ ./skills/herdr-subagents/scripts/subagent --help      → plain text, exit 0
  $ ./skills/herdr-subagents/scripts/subagent run --help  → {"ok":false,…"option requires a value"} exit 1
  ```

---

## Things checked and found correct

- **Wait-loop shape** (`cli.clj:82-94`): `recur` is in genuine tail position — no stack growth. `capture!` runs at the loop head **and** immediately after every `herdr/wait!` return, so settled, blocked, timeout, and structured-error outcomes all get a result check before *and* after. The timeout branch is reached only after a fresh `capture!`. The sleep is `(min interval remaining*)` and is skipped when `remaining* <= 0`, so the deadline is never overshot by a full interval. `run` and `collect --wait` both dispatch through the same function (`cli.clj:172`, `cli.clj:166`) — parity is real, not documentary.
- **Publish atomicity** (`cli.clj:149-162`): argument order to `Files/createLink(link=target, existing=temp)` is correct; `FileAlreadyExistsException` is the no-overwrite guarantee; the temp file is removed on the exception path, the `FileAlreadyExistsException` path, and the success path; envelope rendering happens before any file is created, so a malformed publication leaves nothing behind. Notification failure is caught and never rolls back a committed publication. Second `publish` correctly exits 1 with the target intact (`cli_test.clj:89-95`). No leftover `*.tmp` files exist in the live tree after 262 assignments.
- **Index allocation** (`ledger.clj:35-40`): `O_CREAT|O_EXCL` loop is genuinely lock-free and monotonic; `stable-id` (`UUID/nameUUIDFromBytes`) neutralises `/` and `..` in session identifiers, and `safe-id` sanitises the persona segment. Concurrency proven by `core_test.clj:51-58` (`pmap` of 5 → `#{1 2 3 4 5}`) and independence across sessions.
- **Injection safety:** every Herdr call in `herdr.clj` builds a `babashka.process` argv **vector**; there is no `sh`/string splicing anywhere in `src/`. `--env` composition (`herdr.clj:44-46`) is `["--env" "K=V"]` pairs. `option-map` (`cli.clj:16-24`) does not reject values beginning with `-`, so leading-dash task text is safe. Verified end-to-end with a `--task` payload containing `$(id)`, backtick-`id`-backtick, embedded double quotes and a leading `-`: it round-trips unexpanded into the composed prompt, and `cli_test.clj:29-38` pins the same behaviour through `agent prompt`.
- **Pane-close eligibility:** `maybe-close!` fires only for `COMPLETE`/`FAILED` and only when Herdr reports the child `idle`/`done`; `BLOCKED` envelopes and `blocked` lifecycle outcomes retain the pane (`cli_test.clj:79-81` asserts no `pane close`). Capture (ledger write + artifact check) always precedes the close.
- **Preflight before mutation:** `herdr/preflight!` (`herdr.clj:31-40`) is the first statement of both `preview!` (`cli.clj:63`) and the non-preview `spawn!` branch (`cli.clj:102`), ahead of ledger allocation; `cli_test.clj:51-55` proves no mutating argv is emitted for an old version or a missing `pane split` capability, and `cli_test.clj:57-67` proves `--print-prompt` is side-effect-free.
- **Failure recovery ordering:** the ledger entry is written before the first pane mutation (`cli.clj:118`), and `safe-cleanup!` (`cli.clj:95-97`) closes the pane for `split`/`rename`/`start` phase failures (`cli_test.clj:99-102`).
- **Envelope validation** (`core.clj:64-89`): markers are anchored to the first and last lines; `field!` rejects repeated or missing scalars; `single-line!` rejects blank/multi-line values; list items must be `- `-prefixed; `CHILD`/`TASK`/`RESULT` are compared by exact string equality against the ledger. The `FINDINGS:`→`NEXT: <value>` section terminator is reconstructed from the parsed field so it cannot be spoofed by a `- `-prefixed item. Trailing or leading extra text fails the marker check. A partially written result cannot be observed because `publish` links an already-complete file into place.
- **Test discovery hardening** (`skills/org-tasks/scripts/test/org_tasks/test_runner.clj:37-47`): the explicit-namespace path is unaffected (`explicit` is non-empty when args are given), and `bb test` at the repo root passes and names both new namespaces. `bb.edn`'s `test` task now calls exactly one runner with no code after the `System/exit`. `deps.edn` and `bb.edn` paths stay aligned. No absolute checkout path remains in the test sources.

## Untested surfaces

- The live Herdr command surface (`bb smoke-subagent`) — not run, as instructed. All Herdr behaviour below is fake-fixture evidence only.
- `--cwd` is never asserted in any test's argv expectations (`cli_test.clj:34` checks only `subvec … 0 6`), which is precisely why P1 is invisible to `bb test`.
- No test asserts the 600 000 ms default budget; every test passes an explicit `--timeout`.
- No test exercises a `BLOCKED`/`FAILED` **envelope** (the fixture only publishes `COMPLETE`), nor the nested `planner-n/…` label end-to-end (`core_test.clj:24` covers `child-label` as a pure function only).
- No test covers `--task-file`, stdin input, `--from-file` publication, or `--prompt-extra`.
- Launcher behaviour is entirely untested (no test invokes it through the deployed symlink or a bare subtree).

## Verification performed

| Command | Result |
|---|---|
| `git status --short`, `git diff --cached --stat`, `git diff --stat` | 18 staged files, no unstaged/untracked implementation changes |
| `bb test` (repo root, ×3) | 248 tests / 913 assertions, 0 failures, 0 errors; names `herdr-subagents.cli-test` and `herdr-subagents.core-test` |
| `./skills/herdr-subagents/scripts/subagent --help` / no-args / `run --help` | text usage exit 0 / JSON error exit 1 / JSON error exit 1 |
| `subagent run scout --task '<injection payload>' --print-prompt` (real herdr preflight, read-only) | exit 0; payload preserved verbatim, no mutating call |
| Launcher branch probe (`/tmp/probe-launcher.sh`) for deployed / repo / relative paths | fallback / repo / repo — P1 + P2 reproduced |
| Fake-Herdr spawn from `/tmp/probe-proj` via deployed vs. repo path | `--cwd` and `RESULT` root diverge — P1 reproduced |
| `SUBAGENT_POLL_INTERVAL_MS` ∈ {−5, 0, 20, abc, ""} against `FAKE_WAIT=idle-forever` | 7 vs 23 wait calls; negative aborts the run |
| Ledger delta across one `bb test` | +11 json, +11 index markers, +6 result files in the live tree |
| `time subagent list` | 2.35 s, 240 585 bytes, 262 entries across 5 parent sessions |

Not run: `bb smoke-subagent` / anything setting `SUBAGENT_LIVE_SMOKE=1`. No tracked file was modified; scratch files live in `/tmp` and `.agents/tmp/review-herdr-subagents/`.

## Verdict

**NEEDS CHANGES** — P1 alone (deployed launcher path relocates every child's working directory and assignment root) blocks; P2 pane-close cross-talk violates a checked anti-criterion, and P2 test-suite ledger pollution degrades a shipped command.

## Summary

The delegation protocol core is sound: the wait loop, result-file-as-sole-signal ordering, hard-link publish, `O_CREAT|O_EXCL` index allocation, vector argv, and envelope validation all match the change record's stated contract and hold up under probing. The defects are at the edges — a Bash launcher that mis-resolves the deployed symlink and `cd`s away from the caller's project, a missing parent-session guard on pane close, and fake-Herdr tests that write to live state and cannot detect a regression of the very sleep they were added for.
