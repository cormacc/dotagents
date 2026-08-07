# `oh` contract

## Preconditions

All spawn operations require `HERDR_ENV=1` and Herdr >= 0.7.5. Preflight is those two checks and nothing else -- one `herdr --version` subprocess -- and precedes ledger allocation and pane mutation. `continue` runs it too, since it prompts a pane; `close`, `collect`, `status`, `prune`, and `publish` never run preflight at all.

The version gate is the entire capability check: every command and flag the CLI uses (`pane layout/split/rename/get/close`, `tab create`, `agent start/prompt/wait/get/list`, `notification show`) shipped in 0.7.5, so per-command `--help` probing could only ever have caught a binary misreporting its own version. `herdr api schema` exposes a socket protocol number, not CLI flag shapes, and is deliberately not used as a substitute.

Every command that resolves caller identity runs `agent get` on `$HERDR_PANE_ID`, via `parent-identity` or its swallowing wrapper `caller-parent-session`. That is `spawn!` (`task run`/`start`), `collect --any`, `prune`, `close` (single and `--settled`), `continue`, `orphans`, `compact`, `harvest`, **and `publish`**, whose children-discharge guard (§ Ledger and completion) resolves the caller too and may additionally make one `agent list`. That call requires the calling pane to itself be hosting a recognized agent, not a bare shell; invoked from a raw, non-agent-occupied pane it fails with herdr code `agent_not_found`.

The *consequence* of an unresolvable identity is deliberately not uniform, and the split is by consequence class. `spawn!` propagates the raw failure. The destructive and cross-session verbs (`prune`, `close` single and `--settled`, `continue`, `orphans` listing and `--close`, `compact`, `harvest`) refuse loudly and mutate nothing. `collect --any` returns the non-final, non-throwing `{"status":"pending","reason":"unknown-caller"}`. `publish` alone degrades **open**: a validated terminal item is the sole completion signal, so a guard that cannot attribute a child must not be able to strand a finished result. Plain `collect <full-task-uuid>` resolves identity only to *report* ownership (`"ownership":"foreign-parent-session"`) and captures either way.

`agent start` retries when it fails with the herdr error code `agent_pane_busy` -- a freshly split pane's shell has not yet reached its interactive prompt -- within a small hard-coded attempt/backoff budget (four attempts, 500 ms apart, ~1.5 s of backoff). Retry is keyed on `[:error :response :error :code]` from `herdr/invoke`, never on message text. Any other error code fails on the first attempt, and no other spawn mutation (`pane split`, `pane rename`, `agent prompt`) is retried. Budget exhaustion still runs the existing `:start`-phase cleanup: the ledger entry is marked `failed` with `:failure-phase "start"` and the child pane is closed.

## JSON output

Every non-help command writes exactly one JSON object to stdout:

```json
{"ok":true,"schema":"herdr-orch/v1","result":{}}
```

Failures use `{ "ok": false, "schema": "herdr-orch/v1", "error": {"message": "...", "data": {}} }`. Diagnostics do not carry a completion signal.

`--help` is the one documented exception: it prints human-readable usage text and exits 0. There is no JSON help or discovery variant. Help narrows to the most specific match: `oh --help` and `oh help` print the global command list, `oh <group> --help` prints every signature in that group, and `oh <group> <op> --help` prints just that command's signature, including its positional arity. An unrecognised group falls back to the global list. Help is resolved before option parsing, so `--help` on a command with required options (`oh task run --help`, `oh agent start --help`) prints usage instead of failing on the missing option.

`status` without a task and `list` return a JSON **array** of ledger entries under `result`, and `close --settled`, `orphans`, `compact --closed`, and `harvest` return JSON **arrays** too (§ Close, § Orphans, § Retention, § Harvest); every other command returns an object.

The read verbs -- `collect` (including `--any`), `status`, `list`, and `harvest` -- accept two output knobs, because a delegating parent pays for every byte they emit. `--raw` opts *in* to the raw result envelope: the `text` blob is a byte-for-byte repeat of the parsed fields beside it (top-level `text` on a capture, `:envelope :text` on a ledger entry) and is omitted unless asked for. `--format text` (default `json`; any other value is a loud refusal) renders lines instead of a JSON envelope, and is the one other documented non-JSON output besides `--help`: a capture becomes `LABEL: value` lines plus `- ` list sections in envelope order, and a ledger entry becomes one tab-separated line of `task`, `child`, pane, status, `captured`/`uncaptured`, `closed`/`open`. An empty listing renders `none`, never an empty string.

An immutable item that fails validation (identity mismatch, malformed envelope, or missing artifact) is recorded as the non-final status `invalid`, with the reason on that item and in `result.reason`. It is consumed but never seals the stream, so a later valid item can capture normally. Collection re-reads the oldest unconsumed item; a child that wrote malformed content directly may publish a corrected successor.

`run` uses a ten-minute total wait budget unless `--timeout` overrides it. Timeout, no result, and blocked outcomes are non-final and retain the child pane. `collect` polls once unless `--wait` is supplied, which waits with the same ten-minute default that `--timeout` overrides.

When a wait outcome settles (idle/done) without a valid result file, the loop sleeps `min(ORCH_POLL_INTERVAL_MS, remaining-budget)` before polling again instead of re-invoking `agent wait` immediately; default interval is 1000 ms. This applies identically to `run` and `collect --wait` (both dispatch through the same wait loop) and never overshoots the total timeout by a full interval. `collect --any` uses the same sleep discipline and the same budget default, but polls result files directly instead of blocking in `agent wait` (see § Fan-in).

## Environment

| Variable | Read by | Meaning |
|---|---|---|
| `ORCH_ASSIGNMENT_ROOT` | parent | Overrides the `git rev-parse --show-toplevel` probe behind the assignment root. It relocates the ledger, index markers, `RESULT` paths, project config lookup (`<root>/.agents/subagents/`), **and** the project `config.edn` model-table/defaults override (`<root>/.agents/subagents/config.edn`) together, because all five are per-project notions; the default `config.edn` resolves from the installed skill/launcher location instead, never from the assignment root. A blank value is ignored, a relative value is absolutised so `RESULT` stays absolute, and a value that is not an existing directory is rejected. When set, it is injected into the child pane so nested delegation stays in the same root. |
| `ORCH_LIVE_SMOKE`, `ORCH_LIVE_SMOKE_MODEL` | `bb smoke-subagent` | Guards the live smoke, which also needs `HERDR_ENV=1`. Never CI work. |
| `ORCH_LIVE_SMOKE_KIND` | `bb smoke-subagent` | Optional harness for the live smoke, applied by generating `kind:`-declaring copies of the personas it spawns -- it overrides no resolution of its own. |
| `ORCH_DISPATCH_TIMEOUT_MS` | parent | Budget for the post-prompt dispatch check every `run`/`start` spawn and every `continue` round performs (see § Dispatch verification). Default 15000 ms. |
| `ORCH_POLL_INTERVAL_MS` | parent | Sleep between settled-without-result wait iterations, between `collect --any` poll ticks, and between dispatch-check probes (§ Dispatch verification). Default 1000 ms. |
| `ORCH_SETTLE_CLOSE_MS` | parent | Budget for the single `agent wait` that `close` and `continue` each make on the target child before reading liveness (§ Close). No capture spends it: `run`, `collect <task>`, `collect --wait`, and `collect --any` neither wait nor close. Default 45000 ms. |
| `ORCH_WAITING_INTERVAL_MIN_MS` | child | Minimum interval between `WAITING` publishes. Default 60000 ms. |
| `ORCH_MAX_STREAM_ITEMS` | parent | Maximum immutable items one stream snapshot validates. Default 1000. A stream over the limit refuses rather than silently overlooking a terminal item. |
| `ORCH_MAX_ENVELOPE_BYTES` | parent | Maximum bytes read from one result envelope during validation. Default 65536. An over-limit envelope refuses rather than being treated as unsealed. |
| `ORCH_START_RETRY_BACKOFF_MS` | parent | Sleep between `agent start` retries on a transient `agent_pane_busy` error (see the `start-retry-attempts`/`start!` retry loop). Default 500 ms. Test-only in practice: `bb test`'s fixture sets it low so retry tests run fast. |
| `HERDR_ORCH_CHILD` | child | Live agent name recorded on the ledger. |
| `HERDR_ORCH_TASK` | child | Assignment id. |
| `HERDR_ORCH_RESULT` | child | Exact absolute result path to publish. |
| `HERDR_ORCH_BIN` | child | Absolute launcher path for `publish`. |
| `HERDR_ORCH_PERSONA` | child | The child's own persona. When set it marks the CLI's own spawns as below-root: nested labels compose from it and spawn enforcement reads `HERDR_ORCH_SPAWNS` (see § Spawn gating). An agent started outside `oh` has neither unless the variable is set. |
| `HERDR_ORCH_SPAWNS` | child | Space-joined spawn allow-list resolved by the parent (see § Spawn gating). Blank and unset both mean leaf; a below-root spawn always injects an empty value. |

Every `ORCH_*_MS` variable falls back to its stated default when unset, blank, unparseable, zero, or negative.

No lifecycle value reaches the child. There is deliberately no waiting-policy variable: the policy has exactly one home, the ledger entry's own `:waiting-policy`, which `publish` reads for the round it is publishing (§ Ledger and completion). A child therefore cannot read, carry, or contradict its round's policy, and a continued round cannot publish under the policy its *spawn* was given -- a stale policy is not representable rather than merely validated against.

## Persona discovery

Persona definitions are `<name>.md` files resolved in descending precedence: project `<git-root>/.agents/subagents/` > home `~/.agents/subagents/` > packaged `<skill-dir>/subagents/`, where `<skill-dir>` is derived from the installed launcher. This atomic name shadowing is distinct from the roster-table replacement in § Model resolution. The packaged directory is a skill default, not a Home Manager projection into the home override path.

Persona transport is per kind: pi uses `--append-system-prompt <path>`, claude uses `--append-system-prompt-file <path>`, and other kinds use prompt-level adoption only.

### Harness `:extra-args`

A `:harnesses` entry may carry `:extra-args`, a vector of non-blank strings appended to that kind's native `agent start` argv, between the model args and the persona transport. The shipped config declares none for any kind, so the flag set is empty unless an override file asks for it, and it is granted per kind: naming `:claude` never affects `:pi`. Its purpose is to relax interactive command approval for unattended delegation, since both claude and codex otherwise block on it. The recommended grant for an unattended child is claude `--permission-mode auto` and codex `--ask-for-approval never --sandbox workspace-write`: prompts suppressed for routine work, dangerous actions still escalating. Whether any install actually declares it is a property of that machine's override files, not of this contract. Note that claude `bypassPermissions` does not achieve this -- it gates on its own startup confirmation, so an unattended child stalls there instead.

`merge-config` is `merge-with merge`, so a `:harnesses` entry is replaced wholesale at level two exactly as a `:models` row is; an override adding `:extra-args` must therefore restate `:model-flag`. `parse-config` rejects, with the offending file path, a non-sequential `:extra-args`, a non-string or blank member, and any member containing `\n`, `\t`, or `\r` -- the last because Herdr rejects control characters in agent argv with `invalid_agent_argument` before pane lookup, so the failure belongs at config-parse time rather than at spawn.

## Model resolution

`resolve-kind` has exactly two tiers: frontmatter `kind:`, else the inherited parent kind, failing when neither resolves. Nothing per-assignment selects a harness -- no flag (`task run`/`start` rejects `--kind` rather than ignoring it), no env var, and nothing in the model layer below. Raw `oh agent start --kind` is an unrelated Herdr passthrough and keeps its flag.

`resolve-model` precedence is requested > frontmatter model > same-kind parent inheritance > nil. The resolved value is translated at the `model-args` boundary, the single choke point for frontmatter models, explicit `--model` flags, and same-kind parent inheritance alike.

The model table is in external EDN, in two levels, alongside optional spawn defaults: `{:defaults {:placement :tab-split} :harnesses {:<kind> {:model-flag "…"} …} :aliases {"<short-name>" "<canonical-id>" …} :models {"<canonical-id>" {:<kind> "…" …} …}}`. `:aliases` maps a short or weight name to a canonical, pi-style `provider/model` ID; `:models` maps that canonical ID to a sparse row of per-kind native spellings. Resolution is: an alias hop through `:aliases` (or pass-through if the value is not an alias key), then a `:models` row/column lookup by that canonical ID, consulted only after kind resolution. `:harnesses` keeps the kind set open -- a resolved kind absent from it yields no model args, and herdr alone validates which kinds exist.

The alias hop is single-hop by contract: `canonical-model` looks a value up in `:aliases` at most once and never re-resolves the result through `:aliases` again. An `:aliases` value that is itself an `:aliases` key is a validation error (below), not a chain resolved at runtime, so the effective canonical ID for any alias never depends on following a value into a second alias entry.

Both the ID and kind sets stay open, and a miss anywhere in the chain is pass-through, never a warning or failure: an alias whose canonical target has no `:models` row yields the *canonical* ID -- not the requested alias -- for the resolved kind, and an ID present in neither `:aliases` nor `:models` passes through unchanged. Rows are sparse, and a configured column may deliberately remap across providers; in the shipped table, tier-equivalent cross-provider mappings are confined to `:claude` and `:codex` (for example, `claude-sonnet-5` → codex `gpt-5.6-terra`). There is no `:pi` column: pass-through already delivers the canonical, pi-style spelling for pi. `:defaults`, when present, is a closed map: its only key is `:placement`, whose values are `:split`, `:tab`, or `:tab-split`; each file is validated before merge, and an invalid value names that file.

File chain, project wins: skill default `skills/herdr-orch/subagents/config.edn` (resolved as `subagents/config.edn` under the launcher's skill directory) ← `~/.agents/subagents/config.edn` ← `<git-root>/.agents/subagents/config.edn`. `merge-config` is `merge-with merge`, so the two levels compose differently: a `:models` row is replaced wholesale by canonical ID (an override never deep-merges its harness columns), while `:aliases`, like `:defaults`, merges per key across the chain (README.org § The merge rule that catches people).

A merged config where the same key is present in both `:aliases` and `:models`, or an `:aliases` value that is itself an `:aliases` key, fails `validate-merged-config!` by name. Both checks run in `cli/config` immediately after `merge-config`, before any ledger allocation or pane mutation, so a stale override (for example, a legacy full weight row now colliding with a shipped alias) fails loudly at spawn instead of silently shadowing or chaining.

This table is the single enumerated home for the shipped weight rows; every other document states the rule and links here. `cli_test.clj`'s `default-config-content-contract` asserts it cell-for-cell against `subagents/config.edn`, so a model bump that misses this table fails `bb test`. The rows translate only after kind resolution:

| Weight | Pi | Claude | Codex |
|---|---|---|---|
| `heavy` | `anthropic/claude-fable-5` | `fable` | `gpt-5.6-sol` |
| `middle` | `anthropic/claude-opus-5` | `opus` | `gpt-5.6-sol` |
| `light` | `anthropic/claude-sonnet-5` | `sonnet` | `gpt-5.6-terra` |
| `feather` | `anthropic/claude-haiku-4-5` | `claude-haiku-4-5` | `gpt-5.6-luna` |

Unversioned canonical IDs (`claude-opus`, `gpt-sol`, …) are floating aliases resolving to the latest version of the tier; versioned IDs pin a release.

Config is loaded and schema-validated (parse errors and shape/type errors alike) before any ledger allocation or pane mutation; a validation failure carries the offending path. A missing override file is ignored; a missing shipped default is fatal.

`--print-prompt` reports the resolved (pre-alias) model, its post-alias canonical ID under the `:model-canonical` key, and the effective translated native model args. Its prompt preview contains the resolved persona file path and launcher-composed guidance, not the persona body; inspect the resolved file directly when validating persona-local rules. Spawn capability is enforced before preview generation, so a delegated leaf cannot use `--print-prompt` for a persona outside its `HERDR_ORCH_SPAWNS` allow-list.

## Ledger and completion

The CLI stores one JSON ledger entry per task under `<assignment-root>/.tmp/herdr-orch/ledger/`; index marker files provide lock-free, parent-session/per-persona monotonic allocation. One child may hold several entries over its lifetime, one per round (§ Continue). The recorded `:child` belongs to the spawn, not the round UUID: read it and never reconstruct it from a continued round's task id.

A round is an append-only stream of immutable result items. Its bare `:result` path is item 1 and later items use `.2`, `.3`, and so on. Each `publish` writes the next free item through `Files.createLink`; no command overwrites or deletes an item. `STATUS: WAITING` is a valid non-terminal item. `COMPLETE`, `BLOCKED`, and `FAILED` are terminal statuses; a round is sealed only when a validated terminal item exists, and it seals the moment that item is *published*: the immutable files on disk are the authority, so an uncollected terminal item already refuses further publication. Capture is how the parent consumes items, never what seals the round. An uncaptured terminal seals when its envelope and ledger identity validate; artifact existence is mutable and is validated only at capture, where a missing artifact records `invalid`. A malformed envelope never seals, so a corrected successor can still publish and capture. An unreadable or over-limit item is indeterminate and refuses publication rather than being treated as unsealed. A pre-stream entry with one captured bare `RESULT` remains readable as a stream of length one.

`publish` resolves `--task <full-task-uuid>` first, then `HERDR_ORCH_TASK`; a continued child uses `--task` because its injected environment still names its original round. The named entry must carry the injected `HERDR_ORCH_CHILD`. A ledger-backed publish refuses before writing when the round is sealed, carries `:closed-at`, or has been superseded by a newer round for that child. It also refuses a `WAITING` item inside `ORCH_WAITING_INTERVAL_MIN_MS` of the prior item; terminal items are never throttled. Every ledger-backed publish additionally runs the children-discharge guard: a caller that still owns an open child round -- uncaptured, or captured but unclosed while its pane is live or shell-pid-reclaimable -- is refused with the collect/close/prune remedy, while a captured, fully consumed round whose vanished child no verb can reclaim is discharged and reported under `released-children`. A failed or refused publish raises a best-effort `Subagent publish failed` operator toast, which never changes the outcome. A hand-driven publish with no ledger entry still writes its one bare item and stays silent on success.

`collect`, `run --wait`, and `collect --wait` consume the oldest unconsumed item and record capture per item under `:items`. Their result names `:item` and `:terminal?`, so a captured `WAITING` item is explicitly non-final. The stable entry head (`:status`, `:envelope`, and related fields) follows the newest capture for existing readers. **Capture closes nothing.** `stream-state` is the shared predicate for published items, captured items, and sealing; lifecycle guards use it rather than inferring state from one result file.

The exact immutable item files are the only completion evidence. A validated terminal result item, not terminal output, is a completed round result. Transcript output, `agent read`, terminal history, notifications, and a `WAITING` capture are never completion signals. `BLOCKED` means a genuine blocking dependency; `FAILED` means unrecoverable failure after reasonable retries. Both retain the pane for the parent decision.

```text
--- HERDR RESULT v1 ---
CHILD: <live agent name>
TASK: <parent task UUID>
RESULT: <absolute parent-chosen result path>
STATUS: WAITING | COMPLETE | BLOCKED | FAILED
SUMMARY: <single line>
ARTIFACTS:
- <absolute path — purpose, or none>
FINDINGS:
- <at most five one-line items, or none>
NEXT: <one parent/user action, or none>
PROCESS:
- <at most five `signal → category → proposed rule` items>
--- END HERDR RESULT ---
```

`PROCESS:` is optional retro annotation. Its writer emits it after `NEXT:`; validation accepts it anywhere between the markers, truncates more than five items for collection, and records `:process-overflow` without demoting an otherwise valid envelope. `:child-session` is the best-effort durable transcript reference; a changed observed session moves the prior value to `:child-session-history` rather than discarding it. `:waiting-policy`, `:continues`, and `:closed-at` are round lifecycle fields. The ledger is shared across parent sessions: reads are global, while ownership governs close, continue, prune, compact, and fan-in candidacy.

## Close

`oh task close <full-task-uuid>` is the only normal path that closes a spawned child's pane. Capture leaves the pane intact. Closure requires the caller to own the newest live round for that child, every currently published item to be captured, no `:closed-at`, and a live settled (`idle` or `done`) occupant that matches the recorded child and pane. It does not require a sealed stream: a settled monitor between `WAITING` reports can be closed. `close` and `continue` each spend one `ORCH_SETTLE_CLOSE_MS` wait before checking liveness.

A primary close observation matches both child name and pane id. If the name was released after a resumed process, the shell-pid fallback may close only a live, settled pane whose recorded shell pid still matches and whose foreground is not busy. Otherwise the outcome is `closed`, retryable `retained`/`unsettled`, or `gone`; only `closed` writes `:closed-at`. Ownership, newest-round, uncaptured, pane-mismatch, and unusable-listing guards refuse without mutation.

`collect --close` captures first, then requests this same guarded close. It reports the close outcome beside the capture and never turns a valid capture into a failure. It attempts closure only for a validated terminal capture; a `WAITING`, pending, blocked-wait, or invalid capture reports a skipped close. `close --settled` applies the same rules once to each qualifying newest child round and returns a per-child array. Root-only focus return remains a best-effort effect of an actual `closed` outcome. Ownership is absolute; § Orphans is the verb for it when an operator must clean up a foreign or ended owner.

## Orphans

`oh task orphans` lists, and `oh task orphans --close` closes, the captured rounds this session does *not* own -- the cleanup path for a child whose owning parent session has ended, which § Close deliberately leaves unclosable through `close`. It takes no task argument. Candidates are the newest live round of each child that is captured, carries a `:pane-id`, has no `:closed-at`, and records a `:parent-session` other than the caller's; the listing form is read-only (no settle wait, no `agent list`, no mutation) and returns the task, child, pane, tab, label, status, capture time, and recorded owner of each.

Both forms are **root-only**, refused before the first ledger read exactly as `continue` is refused before preflight, and both also refuse a caller whose own `:parent-session` cannot be resolved. A delegated child, and equally a caller with no resolvable identity, satisfies `(not= caller (:parent-session %))` for every entry that records an owner -- *including its own captured children* -- so the candidate set degrades to "everyone else's live children", which under `--close` would sweep any captured, settled sibling whose child name and pane id still match. The refusal covers the listing too, not merely the mutation, because the contract has the operator take closing authority straight from that list.

`--close` routes every candidate through the identical machinery single-task `close` uses -- the bounded `ORCH_SETTLE_CLOSE_MS` wait, one `agent list`, and the live child-name-**and**-pane-id match in `idle`/`done`, including its shell_pid fallback for a released name (§ Close) -- so it closes on no weaker evidence than `close` does: name absence with no confirming shell_pid is `gone` and closes nothing, a pane the child no longer occupies is a refusal, an unsettled child is `retained` and retryable, and an unusable listing is a refusal. This is what the verb replaces: the previous documented remedy was `oh agent list` followed by `oh pane close <pane-id>`, whose second step closes on a recorded pane id alone, which is precisely the evidence the `gone` outcome exists to reject. Like `close --settled` it returns a JSON **array** of per-task outcomes in `:created-at` order, reports a refusal as `{"status":"refused",…}` inside that array rather than aborting the remaining candidates, and exits 0 regardless -- read the array, not the exit code. An empty array means nothing qualified. It also shares `close --settled`'s once-per-sweep return-hook focus, not once per orphan (§ Focus).

What the verb cannot supply is the judgement that a foreign session is a *dead* one. It is not: a concurrently delegating session's captured, unclosed child matches this predicate exactly, and no available signal separates the two (§ Close). So the operator supplies that authority -- read the list, confirm each pane, then sweep. Unlike `close --settled`, an `invalid` capture is *not* excluded: the sweep's reason for skipping one is that an operator may need that pane, and here the operator is the one acting.

## Continue

`oh task continue <full-task-uuid> (--task TEXT | --task-file PATH | stdin) [--wait] [--timeout MS]` assigns a settled child a fresh round in the same pane and context. The new immutable round entry inherits identity and metadata, has `:continues <prior-task>`, and receives its own waiting policy; the prior entry is never rewritten.

It is root-only and refuses unless the caller owns the newest live round, every published item in that round has been captured, the latest capture is valid (not `invalid` or a retired `failed` entry), no sibling round for that child is open, and `agent list` shows the recorded child at the recorded pane in `idle` or `done`. A sealed stream is not required: a fully captured `WAITING` round can continue. The prompt names the new task in every publish command, including `WAITING` for non-blocking rounds. `--wait` returns the first unconsumed item, terminal or not; otherwise the command returns the new entry.

## Dispatch verification

`agent prompt` submits the composed prompt atomically, but a harness TUI still finishing startup can swallow the Enter and leave that text sitting unsubmitted in the child's composer -- a spawn that reads `prompted` on the ledger, holds a pane and a model allocation, and never starts work. Every `run`/`start` spawn, and every `continue` round, therefore verifies the submission after prompting, bounded by `ORCH_DISPATCH_TIMEOUT_MS` (default 15 000 ms) and probing at `ORCH_POLL_INTERVAL_MS`.

A dispatched prompt drives the child out of `idle`, so the check reads `agent_status` (nested at `.result.agent.agent_status`) and stops on the first state that is neither `idle` nor `unknown` -- `working`, and the already-settled `done` and `blocked`, all count as dispatched. `idle` is the held-prompt signal and `enter` clears it, but only a *persisting* `idle` is nudged: a child that has merely not begun its turn also reads `idle` for a moment, so a second consecutive `idle` reading is required before any key is sent. `unknown` is never nudged at all -- it means the agent could not be observed. Nudges are capped at two independently of the budget. The healthy path sleeps zero times and makes exactly one `agent get`, which is the same probe that backfills `:child-session`.

Every Herdr call in the check is best-effort: a failed `agent get` counts as `unknown`, a failed `send-keys` is ignored, and neither fails the spawn. Non-confirmation is a diagnosis, not a failure -- the child may simply be slow -- so the pane is retained, no cleanup runs, and the ordinary wait/collect path proceeds unchanged. The outcome is recorded on the ledger entry instead: `:dispatch` is `{status, state, nudges}` with status `dispatched` or `unconfirmed`, `state` the last observed `agent_status`, and `nudges` the number of Enters sent; `:dispatched-at` is written only on confirmation. `:prompted-at` timestamps the submission *attempt* and is not a dispatch baseline -- the two differ by however long a swallowed Enter held the prompt, so measure a child's working time from `:dispatched-at` or its transcript, never from `:prompted-at`.

The check is part of the delegation prompt paths only -- the `run`/`start` spawn and the `continue` round, which prompt an existing pane for the same reason and inherit the same hazard. Raw `oh agent prompt` remains a one-shot passthrough wrapper, since its caller already holds the target and can nudge it directly.

## Fan-in

`oh task collect --any [--wait --timeout MS]` has two same-session candidate sets. Capture candidates are rounds with an unconsumed item and are the only rounds `collect --any` can return. Wait candidates are broader: live, unsealed, non-failed, non-closed rounds, including a child that has published nothing yet. The split keeps `--any --wait` polling until a child publishes instead of treating an empty capture set as finished. `no-candidates` is returned only when both sets are empty; failed spawn entries remain excluded.

Each snapshot validates each uncaptured item at most once and supplies both candidate sets plus its capture decision; the `agent list` liveness boundary takes one fresh snapshot before an absence outcome, so a child publishing during that subprocess is still captured. A valid capture returns the ordinary collect result plus `remaining`, which counts unconsumed-item candidates only. The wait budget bounds the polling loop; no path closes a pane. Known outcomes remain `pending/no-candidates`, `pending/timeout`, `blocked` when every live wait candidate is blocked, and `pending/no-live-children` when every wait candidate vanished. An unusable listing leaves liveness unknown and continues polling.

**Known limitation -- stale uncaptured fan-in entries.** A `run`/`start` killed between ledger allocation and cleanup can retain a same-session uncaptured, non-`failed` round forever; `prune <full-task-uuid>` is its explicit, ownership-checked remedy below.

## Pruning

`oh task prune <full-task-uuid>` is the explicit, single-assignment remedy for the Fan-in "known limitation" above: a `run`/`start` killed between `ledger/write!` and its cleanup leaves a same-session, uncaptured, non-`failed` entry that no `RESULT` will ever complete and whose named child can never reappear in `agent list`, yet it satisfies the `--any` candidate predicate forever. It takes no other form: it never scans the ledger for candidates and never resolves a partial or prefix id -- the argument is looked up as the exact ledger key, precisely as `collect <full-task-uuid>` and `status <full-task-uuid>` already do.

Ownership is checked first, via the same `parent-identity` the rest of the CLI uses: the caller's own `:parent-session` must equal the entry's recorded `:parent-session`, and **both must be non-nil**, or the prune is refused outright, mutating nothing -- so an unresolvable caller can never own a legacy entry that omits `:parent-session` by a `nil` = `nil` coincidence (the guard in `cli.clj` carries the reasoning). An unresolvable caller identity is refused exactly like a genuine foreign session.

Because that guard is absolute, `prune` is not the remedy for a stale entry left by a *different* session, which is the common case once a session ends: the refusal is correct and the entry stays. Clear one by deleting its ledger file, `<assignment-root>/.tmp/herdr-orch/ledger/<full-task-uuid>.json`, after confirming no live agent bears its `:child` name in `agent list` -- the same proof of absence `prune` itself requires. That is an operator action on gitignored scratch state, deliberately outside the CLI, so no command can bypass the ownership check.

Once ownership holds, all of the following staleness proof is required together, checked cheapest-first: the entry has no `:captured-at`, its `:status` is not already terminal (`failed`/`invalid`), no published item exists on disk for it, and -- last, since it is the one Herdr call in the path -- its named `:child` is **absent** from a single `agent list` call. Liveness reuses the exact classification `collect --any` already uses (`live-agents`): agents are indexed by `name` only, with no `pane_id` fallback, and a listing that fails or omits its `agents` key returns `nil`, meaning liveness is unknown. Ambiguous liveness must never allow a prune: only a listing that positively excludes the child's name is proof of absence, never an unusable or missing listing. Age is never evidence, and this command never touches `any-candidates` or `collect --any` itself.

That `agent list` call can itself race a child publishing and exiting inside its own subprocess window -- the identical hazard `collect --any` re-polls to avoid (§ Fan-in). So the final mutation does not blindly write over the pre-listing snapshot: it re-validates the *freshest* ledger state (a fresh read, inside the same `ledger/update!` call that performs the write) for `:captured-at` and an on-disk item, and refuses -- mutating nothing -- if either appeared during the check. The race-won `RESULT` remains ordinarily collectible afterward.

On success the *existing* entry is mutated in place to `:status "failed"`, `:failure-phase "orphaned"`, plus two audit fields: `:pruned-at` (a timestamp, same style as `:reported-at`/`now`) and `:prune-reason "missing-agent"`. Setting `:status "failed"` is what excludes the entry from `any-candidates` afterward, through the pre-existing `failed` exclusion. `prune` never closes the entry's recorded `:pane-id`; it only ever touches the ledger JSON.

Every refusal case -- foreign session, unresolvable caller identity, a live named child, an already-captured entry, an already-terminal entry, a published item, a capture/publish that raced the liveness scan, or an unusable `agent list` -- fails loudly (non-zero exit, no JSON `result`) without any ledger mutation and without closing anything.

## Retention

`oh task compact <full-task-uuid>` and `oh task compact --closed` retire raw envelope bulk, never ledger entries. Compaction removes `:envelope :text` from the stable head and from every captured `:items` record in the round, then records `:compacted-at` and reclaimed bytes. Parsed fields, item identities, transcript references, artifact links, and the immutable item files remain. `collect` never re-returns a consumed item -- a fully-consumed round reports `pending` -- so after compaction the raw text is reachable only by reading the retained item files directly, and those live in gitignored scratch (`.tmp/`) an operator may clear.

Only the owning session may compact a closed round or terminal failed round; an already compacted round refuses loudly. `compact --closed` takes no task argument, refuses an unresolvable caller, and sweeps the caller's qualifying entries in creation order with per-round refusals. Its final ledger mutation revalidates the fresh entry, refusing if another operation compacted it or made it ineligible during the check. There is no automatic deletion, age threshold, or entry-count cap: ledger entries are the audit trail and task-UUID lookup path.

## Parent push

Under a non-blocking waiting policy, every committed item emits an operator toast. A `WAITING` item is toast-only: it never probes or prompts the parent. A terminal item retains the advisory parent push, which names the child, task, and `collect <full-task-uuid>` command; it is advisory only and never replaces a validated terminal result item as completion evidence. Blocking rounds do not notify a parent, and a ledger-less hand-driven publish stays silent.

The advisory push is sent only to an idle or done parent whose observed session equals the entry's recorded `:parent-session`. A working parent receives one bounded `agent wait --until idle --until done`, then both conditions are checked again. Push failure, timeout, or session mismatch is reported under `result.parent-push` after publication and never changes the item's publish outcome. Declared artifact links in a push are advisory; only collect-time `artifact-links` are existence-validated.

## Artifact links

An `ARTIFACTS` item (`<absolute path>[ — <purpose>]`) is additionally rendered as one portable Markdown link, `[escaped absolute path](encoded file:// URI) — escaped purpose`, so a parent that has already closed the child pane can still reach the artifact. The visible label is always the whole absolute path, never a basename: the path itself is the context the parent loses with the pane. One shared renderer (`core/artifact-link`) produces both surfaces below, splitting the item on the same ` — ` delimiter as `core/artifact-path` and enforcing the same absoluteness check itself. A path *containing* ` — ` mis-splits, exactly as it always has for `artifact-path`: the delimiter is envelope grammar, not an escapable value, and the truncated prefix is what the collect-time existence check then rejects.

This is **portable fallback syntax, not a clickability guarantee**. No raw OSC 8 (or any other terminal-control) escape sequence is ever emitted into model- or operator-visible text. A harness that renders Markdown may turn the link into a terminal hyperlink when the terminal advertises support, and every other reader still sees the full absolute path plus a usable URL. Whether a link is clickable therefore depends on the harness and terminal, never on this contract.

The destination is built with `java.nio.file.Path.toUri`, never by hand, so reserved and non-ASCII characters come back correctly percent-encoded and the result carries the canonical empty authority (`file:///…`, not `File.toURI`'s `file:/…`); `(` and `)` are percent-encoded afterwards, because an unbalanced parenthesis would terminate a Markdown link destination early. Markdown-significant characters (`[`, `]`, `\`, `` ` ``, `*`, `_`, `<`, `>`, `&`, `~`) are backslash-escaped in the visible label and in the purpose text, which must stay readable as the exact path.

The renderer is not pure: `Path.toUri` stats the path, so an *existing directory* artifact's destination gains a trailing slash its label does not have, and the advisory (rendered before the artifact need exist) may differ from the collected link for the same declared line.

The two surfaces carry deliberately different weight:

| Surface | Source | Weight |
|---|---|---|
| The advisory parent push (§ Parent push), under a labelled `Declared artifacts (advisory — pending validation by \`collect\`)` line | the child's *declared* envelope body | **Not evidence.** Publish checks artifact path shape only, never existence, so a declared link may name a file that never appears |
| `result.artifact-links` on a successful `collect` (single-task and `--any` alike) | the captured envelope, after every artifact passed the `fs/exists?` check | Existence-validated as of `:captured-at`; also persisted on the ledger entry as `:artifact-links`, which is what keeps the links reachable through `status`/`list` after the pane and the collect output are gone |

Because publication is already committed when the advisory renders, an artifact path that cannot be rendered at all is dropped from the list and counted (`- (N declared artifact path(s) not renderable as a link; see the RESULT envelope)`) rather than failing the push. The `RESULT` envelope remains the authoritative declared list.

An empty artifact list adds *nothing* on either surface: no advisory section, and no `artifact-links` key on the result or the entry. Absent is not the same claim as validated-empty. An artifact that fails the existence check yields a non-terminal `invalid` item and no links for that item; it does not seal the stream, so a corrected later item may capture normally. `--any` gains links through the shared capture path, so its result remains exactly the single-task shape plus `remaining`.

## Retro gating

`run` and `start` decide whether the composed prompt asks the child to apply steps 1--2 of the `retro` skill to its own session. Precedence is `--retro` / `--no-retro` (value-less flags; supplying both fails fast) > persona frontmatter `retro:` > the built-in default, which is **enabled**. Frontmatter values are always strings, so `retro:` is coerced by an explicit `true`/`false` table; any other value fails fast at spawn, naming the persona and the value.

"Enabled" means the child *evaluates* the `retro` threshold, never that a retro is forced: that skill owns the threshold, including its non-interactive equivalent, and a gated-in child that finds nothing correctly publishes no `PROCESS` section. `--no-retro` and `retro: false` skip the step entirely -- no prompt paragraph and no token cost. `scout` and `researcher` ship with `retro: false`.

The retro skill path resolves `<assignment-root>/.agents/skills/retro/SKILL.md`, then `<assignment-root>/skills/retro/SKILL.md`, then `~/.agents/skills/retro/SKILL.md`. When none exists the frontmatter and default sources degrade silently -- the instruction is omitted and `:retro-source` is `"skill-missing"` -- because the retro step is optional equipment and an installation without that skill is a supported configuration. An explicit `--retro` is an operator request and fails fast at spawn instead of becoming a silent no-op; `--no-retro` is always honoured. The resolved policy is recorded on the ledger entry as `:retro` and `:retro-source` (`flag`, `frontmatter`, `default`, or `skill-missing`), and `--print-prompt` reports both alongside the composed prompt. Gating shapes the prompt only: a `PROCESS` section published by a gated-out child is still accepted.

## Timeout resolution

The wait budget resolves per round as explicit `--timeout` > persona frontmatter `timeout:` (milliseconds) > the shipped default, 600 000 ms -- the same precedence shape as `model:` and `retro:`, and for the same reason: "give review and implementation work an explicit `--timeout`" was a per-persona constant expressed as a standing instruction to every caller, so the persona declares it once instead. Frontmatter values are always strings, so the value is coerced explicitly and an unparseable, zero, or negative one **fails fast at spawn**, naming the persona and the value, rather than degrading to the default. Resolution runs even when `--timeout` is supplied, so a bad declaration stays loud. The flag is validated by the same rule, so `--timeout abc` and `--timeout 0` are clean refusals rather than a raw `NumberFormatException`; this is unlike the `ORCH_*` environment knobs, where a non-positive or unparseable value deliberately falls back to a default.

The resolved value is recorded on the ledger entry as `:timeout` and `:timeout-source` (`flag`, `frontmatter`, or `default`), reported by `--print-prompt`, and is what `run` waits with. Recording it is load-bearing rather than informational: `collect <task> --wait` and `continue --wait` both read `--timeout` > the entry's `:timeout` > default, so a `start` plus later `collect --wait`, and every continued round (which inherits `:timeout`/`:timeout-source` with the rest of its metadata), get the persona's own budget without the caller restating it. `collect --any` has no single entry to read and keeps flag-or-default. The shipped declarations are `worker: 1800000` and `reviewer: 1200000`.

## Spawn gating

`run` and `start` resolve the child's spawn allow-list -- the personas it may itself spawn -- with precedence `--spawns` (value-bearing; whitespace- and/or comma-separated, deduplicated) > persona frontmatter `spawns:` > the built-in default, which is **deny** (the empty list). The literal flag value `none` forces the empty policy without consulting the roster. An absent key and a present-but-blank frontmatter value both mean leaf, not an error. An unresolvable persona name in either the flag or the frontmatter fails fast at spawn, naming the declaring persona, the offending name, and the source.

The resolved policy is recorded on the ledger entry as `:spawns` and `:spawns-source` (`flag`, `frontmatter`, `default`, or `depth`), reported by `--print-prompt` alongside the composed prompt, injected into the child as the space-joined `HERDR_ORCH_SPAWNS`, and composed into the prompt's delegation guidance: a non-empty policy renders the may-spawn sentence, an empty one the leaf sentence.

Depth is bounded to one nesting level absolutely, with no depth counter: a CLI whose own `HERDR_ORCH_PERSONA` is set is below root. Below root, a target persona absent from the caller's own `HERDR_ORCH_SPAWNS` is refused before preflight, ledger allocation, and any pane mutation -- a refused spawn writes no ledger entry and creates nothing billable. Blank and unset `HERDR_ORCH_SPAWNS` both deny; the empty-string/unset distinction is never load-bearing. An explicit below-root `--spawns` with any value other than `none` fails fast, and a permitted below-root spawn always injects an empty grandchild `HERDR_ORCH_SPAWNS` with source `depth`, regardless of the grandchild persona's frontmatter. A root CLI (no `HERDR_ORCH_PERSONA`) is unrestricted in what it may spawn.

## Labels and geometry

Labels are `<persona>-<index>[-<model-basename>]`; a below-root child is `<parent-persona>-<n>/<persona>-<index>[-<model-basename>]`, prefixed from the spawning agent's injected `HERDR_ORCH_PERSONA`. Workspace names are excluded. Labels are display metadata only: capability and depth are enforced by § Spawn gating, never by label parsing. Direction is `right` only when the caller's own matching layout pane rect has `width >= 80 && width >= 2 * height`, otherwise `down`; tab area and focus are irrelevant.

## Placement

Placement resolves as explicit `--tab`/`--split` flag > configured `:defaults :placement` > code fallback `"split"`. A configured `:tab-split` resolves to tab at root and split below root, using `HERDR_ORCH_PERSONA` as the depth discriminator. The shipped `subagents/config.edn` sets `:tab-split`, so out of the box a root-level child gets its own tab while a grandchild splits its parent's pane (README.org § Child placement records why). The code fallback stays `"split"` and is deliberately *not* kept in step with the shipped value: it fires only for a config that declares no `:placement` at all, which the shipped file always does. A resolved split creates the child with `pane split --pane $HERDR_PANE_ID --direction ... --cwd ... --focus`/`--no-focus --env ...` in the caller's pane, choosing `--direction` as above. A resolved tab (from `--tab` or config) creates the child with `tab create --workspace $HERDR_WORKSPACE_ID --cwd ... --label ... --focus`/`--no-focus --env ...` in a new tab of the caller's Herdr workspace; the child pane is `.result.root_pane`, distinct from `.result.tab` (whose `tab_id` is recorded but never separately focused -- § Focus below covers whether the *placement call itself* moved the UI there). Tab placement skips the `caller-rect!`/direction computation entirely -- it needs neither. Every other step (env injection, `pane rename` onto the child pane, `agent start`/`prompt`, wait/collect, and `close`, which also closes a tab whose last pane it takes) is identical between the two placements.

Placement is resolved per spawn: it is never persisted per-child, never inherited via environment, and no child environment variable carries it. A child's own spawns resolve from config and depth alone. The ledger entry records `:placement` (`"split"` or `"tab"`) and `:tab-id` (the created tab's id, `nil` for a split placement).

## Focus

`resolve-focus` resolves a boolean per spawn with precedence `--focus`/`--no-focus` flag > configured `:defaults :focus` > code fallback `true`, exactly the shape `resolve-placement` uses, and the resolved value is passed straight into whichever placement call `:placement` chose -- there is no separate `agent focus`/`tab focus` call afterward, and nothing to un-focus if a later step (`pane rename`, `agent start`/`prompt`) fails.

Depth is the **absolute** gate, unlike placement's: `resolve-focus` checks `below-root?` (the same `(some? (System/getenv "HERDR_ORCH_PERSONA"))` predicate `resolve-placement` and `continue`'s root-only guard read) first and short-circuits both the flag and the configured default, so a below-root `--focus` is accepted but silently inert rather than a fail-fast refusal -- there is no flag spelling that re-opens it. At root the shipped `:defaults :focus` is `true`: with `:tab-split`, a root-level child owns a tab of its own, and moving to it is how you watch or intervene. The ledger entry records the resolved `:focus` alongside `:placement`; `--print-prompt` reports it the same way it reports placement.

`close` is the return hook, and therefore `collect --close` (which runs closure through `close-task!` unchanged) inherits it -- never `publish`. `close` runs in the *caller's own* process, so the pane to focus is always `$HERDR_PANE_ID`, resolved via `agent focus` (the caller's own pane hosts a recognized agent by construction, since every identity-resolving verb already depends on that -- `parent-identity` calls `agent!` on the same id); there is no ledger lookup and no session-match gate to get wrong, unlike a publish-time hook that would have to reason about an unsettled or session-mismatched parent (§ Parent push). The focus call fires only on a `"closed"` outcome -- never on `gone` or `retained`, which closed nothing -- and `close --settled`/`orphans --close` fire it **at most once per sweep**, after every child has been attempted, not once per child closed.

The return hook is **root-only**, gated on the identical `:below-root?` predicate as the spawn side (`focus-caller!` checks `HERDR_ORCH_PERSONA`) and enforced in `focus-caller!`, the single call site every return hook shares, so a below-root `close` -- single, `--settled`, or via `collect --close` -- closes exactly as it always did and moves nothing. Focus is best-effort throughout: a failed `agent focus` is swallowed and never fails a close, a publish, or a spawn, and it adds no key to any result at all. Focus is never read to make a decision anywhere in the CLI: every guard here resolves from the ledger and `agent list`.
