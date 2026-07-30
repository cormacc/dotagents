# `subagent` contract

## Preconditions

All spawn operations require `HERDR_ENV=1`, Herdr >= 0.7.5, and installed non-mutating help shapes for `pane layout/split/rename/get/close`, `tab create`, `agent start/prompt/wait/get/list`, and `notification show`. Preflight precedes ledger allocation and pane mutation. `tab create` is probed unconditionally, whether or not the spawn uses `--tab`.

`agent wait` is probed for both `--timeout` and `--until`. `--until` is needed only by the advisory parent push (§ Parent push), and `publish` never runs preflight at all, so that path relies on the >= 0.7.5 version gate rather than on a probe; the spawn-side row is widened anyway so the declared capability set stays honest about what the CLI uses.

Every command that resolves caller identity (`spawn!`, `collect --any`, `prune`, via `parent-identity`/`caller-parent-session`) runs `agent get` on `$HERDR_PANE_ID`. That call requires the calling pane to itself be hosting a recognized agent, not a bare shell; invoked from a raw, non-agent-occupied pane it fails with herdr code `agent_not_found`.

`agent start` retries when it fails with the herdr error code `agent_pane_busy` — a freshly split pane's shell has not yet reached its interactive prompt — within a small hard-coded attempt/backoff budget (four attempts, 500 ms apart, ~1.5 s of backoff). Retry is keyed on `[:error :response :error :code]` from `herdr/invoke`, never on message text. Any other error code fails on the first attempt, and no other spawn mutation (`pane split`, `pane rename`, `agent prompt`) is retried. Budget exhaustion still runs the existing `:start`-phase cleanup: the ledger entry is marked `failed` with `:failure-phase "start"` and the child pane is closed.

## JSON output

Every non-help command writes exactly one JSON object to stdout:

```json
{"ok":true,"schema":"herdr-subagents/v1","result":{}}
```

Failures use `{ "ok": false, "schema": "herdr-subagents/v1", "error": {"message": "...", "data": {}} }`. Diagnostics do not carry a completion signal.

`--help` is the one documented exception: `subagent --help`, `subagent help`, and `<command> --help` print human-readable usage text and exit 0. There is no JSON help or discovery variant.

`status` without a task and `list` return a JSON **array** of ledger entries under `result`; every other command returns an object.

A result that is published but fails validation (identity mismatch, malformed envelope, missing or relative artifact path) is recorded as the non-final status `invalid`, with the reason on the ledger entry and in `result.reason`. `invalid` is terminal for the child (publication is immutable and cannot be retried) and needs manual intervention; the pane is retained and re-collecting returns the same outcome instead of failing.

`run` uses a ten-minute total wait budget unless `--timeout` overrides it. Timeout, no result, and blocked outcomes are non-final and retain the child pane. `collect` polls once unless `--wait --timeout` is supplied.

When a wait outcome settles (idle/done) without a valid result file, the loop sleeps `min(SUBAGENT_POLL_INTERVAL_MS, remaining-budget)` before polling again instead of re-invoking `agent wait` immediately; default interval is 1000 ms. This applies identically to `run` and `collect --wait` (both dispatch through the same wait loop) and never overshoots the total timeout by a full interval. `collect --any` uses the same sleep discipline and the same budget default, but polls result files directly instead of blocking in `agent wait` (see § Fan-in).

## Environment

| Variable | Read by | Meaning |
|---|---|---|
| `SUBAGENT_ASSIGNMENT_ROOT` | parent | Overrides the `git rev-parse --show-toplevel` probe behind the assignment root. It relocates the ledger, index markers, `RESULT` paths, project-roster lookup (`<root>/.agents/subagents/`), **and** the project roster.edn model-table override (`<root>/.agents/subagents/roster.edn`) together, because all five are per-project notions; the default roster.edn resolves from the installed skill/launcher location instead, never from the assignment root. A blank value is ignored, a relative value is absolutised so `RESULT` stays absolute, and a value that is not an existing directory is rejected. When set, it is injected into the child pane so nested delegation stays in the same root. |
| `SUBAGENT_LIVE_SMOKE`, `SUBAGENT_LIVE_SMOKE_MODEL` | `bb smoke-subagent` | Guards the live smoke, which also needs `HERDR_ENV=1`. Never CI work. |
| `SUBAGENT_POLL_INTERVAL_MS` | parent | Sleep between settled-without-result wait iterations, and between `collect --any` poll ticks. Unset, blank, unparseable, zero, and negative values all fall back to 1000 ms. |
| `SUBAGENT_SETTLE_CLOSE_MS` | parent | Budget for the single `agent wait` a `collect --any` capture makes on the captured child before its one-shot pane close (see § Fan-in). Unset, blank, unparseable, zero, and negative values all fall back to 45000 ms — same discipline as `SUBAGENT_POLL_INTERVAL_MS`. |
| `SUBAGENT_PROGRESS_INTERVAL_MS` | child | Minimum interval between `progress --summary` snapshots that actually rewrite the ledger's `:progress` (see § Progress). Unset, blank, unparseable, zero, and negative values all fall back to 60000 ms — same discipline as `SUBAGENT_POLL_INTERVAL_MS`. |
| `HERDR_SUBAGENT_CHILD` | child | Live agent name recorded on the ledger. |
| `HERDR_SUBAGENT_TASK` | child | Assignment id. |
| `HERDR_SUBAGENT_RESULT` | child | Exact absolute result path to publish. |
| `HERDR_SUBAGENT_BIN` | child | Absolute launcher path for `publish`. |
| `HERDR_SUBAGENT_WAITING_POLICY` | child | `blocking` or `non-blocking`; the latter makes a successful publish emit an operator notification **and** attempt one advisory parent push (§ Parent push). |
| `HERDR_SUBAGENT_PERSONA` | child | The child's own persona. When set it marks the CLI's own spawns as below-root: nested labels compose from it and spawn enforcement reads `HERDR_SUBAGENT_SPAWNS` (see § Spawn gating). An agent started outside `subagent` has neither unless the variable is set. |
| `HERDR_SUBAGENT_SPAWNS` | child | Space-joined spawn allow-list resolved by the parent (see § Spawn gating). Blank and unset both mean leaf; a below-root spawn always injects an empty value. |

## Persona discovery

Persona definitions are `<name>.md` files resolved in descending precedence: project `<git-root>/.agents/subagents/` > home `~/.agents/subagents/` > packaged `<skill-dir>/subagents/`, where `<skill-dir>` is derived from the installed launcher. This atomic name shadowing is distinct from the roster-table replacement in § Model resolution. The packaged directory is a skill default, not a Home Manager projection into the home override path.

## Model resolution

`resolve-model` precedence is requested > frontmatter model > same-kind parent inheritance > nil. The resolved value is translated at the `model-args` boundary, the single choke point for frontmatter models, explicit `--model` flags, and same-kind parent inheritance alike.

The canonical model-ID table is external EDN: `{:harnesses {:<kind> {:model-flag "…"} …} :models {"<canonical-id>" {:<kind> "…" …} …}}`. `:harnesses` keeps the kind set open — a resolved kind absent from it yields no model args, and herdr alone validates which kinds exist. A model never selects a kind; `:models` is consulted only after kind resolution. Rows are sparse, and a configured column may deliberately remap across providers. In the shipped table, `:pi` supplies the explicit provider-qualified model Pi runs, while tier-equivalent cross-provider mappings are confined to `:claude` and `:codex` (for example, `claude-sonnet-5` → codex `gpt-5.6-terra`). A model ID absent from the table passes through to the resolved kind unchanged.

File chain, project wins: skill default `skills/herdr-subagents/subagents/roster.edn` (resolved as `subagents/roster.edn` under the launcher's skill directory) ← `~/.agents/subagents/roster.edn` ← `<git-root>/.agents/subagents/roster.edn`. These roster files are model-table overrides, not persona-definition sources: an override replaces a complete row by model ID (including a weight row) and never deep-merges its harness columns. Thus a home or project `"heavy"` row replaces the shipped `"heavy"` row; it neither shadows a persona definition nor chooses the harness.

The shipped weight rows translate only after kind resolution: `heavy` → Pi `anthropic/claude-fable-5`, Claude `fable`, Codex `gpt-5.6-sol`; `middle` → Pi `anthropic/claude-opus-5`, Claude `opus`, Codex `gpt-5.6-sol`; `light` → Pi `anthropic/claude-sonnet-5`, Claude `sonnet`, Codex `gpt-5.6-terra`; `feather` → Pi `anthropic/claude-haiku-4-5`, Claude `haiku`, Codex `gpt-5.6-luna`. A weight alias is model data and never selects or changes kind. Unversioned canonical IDs (`claude-opus`, `gpt-sol`, …) are floating aliases resolving to the latest version of the tier; versioned IDs pin a release.

Config is loaded and schema-validated (parse errors and shape/type errors alike) before any ledger allocation or pane mutation; a validation failure carries the offending path. A missing override file is ignored; a missing shipped default is fatal.

`--print-prompt` reports both the canonical resolved model and the effective translated model / native model args.

## Ledger and completion

The CLI stores one JSON ledger entry per task under `<assignment-root>/.agents/tmp/herdr-subagents/ledger/`; index marker files provide lock-free, parent-session/per-persona monotonic allocation. The child receives `HERDR_SUBAGENT_CHILD`, `_TASK`, `_RESULT`, `_BIN`, `_WAITING_POLICY`, `_PERSONA`, and `_SPAWNS` (plus `SUBAGENT_ASSIGNMENT_ROOT` when overridden) through repeatable `--env` flags on the placement command (`pane split`, or `tab create` under `--tab`). `collect`, `status`, and `prune` all resolve their assignment argument as this exact ledger key: unlike `ot`'s `:CUSTOM_ID:` prefix matching, no partial or truncated id is ever resolved, and a shortened value fails with `unknown assignment task`. The ledger task id is a fresh `java.util.UUID/randomUUID`, an identifier space unrelated to any org `:CUSTOM_ID:`.

The exact `RESULT` file is the only completion signal. It must be atomically published once from a sibling temporary file using `Files.createLink(result, temp)` then unlinking `temp`; a pre-existing result is an error and is never overwritten. `publish` itself rejects a relative artifact path (from `--artifact` or `--from-file`, unmodified) before that write, so the one-shot file is never created for a mistake the child could still fix in-session; `collect`'s identical check (`core/artifact-path`) remains the backstop against any other route to a ledger entry. Artifacts named by the result must exist before collection captures it — publish time checks only the path shape, never existence.

```text
--- HERDR RESULT v1 ---
CHILD: <live agent name>
TASK: <parent task UUID>
RESULT: <absolute parent-chosen result path>
STATUS: COMPLETE | BLOCKED | FAILED
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

The marker lines and `CHILD`, `TASK`, and `RESULT` must exactly match the ledger, and `FINDINGS` holds at most five items. Transcript output, `agent read`, and terminal history are never collection inputs. A `COMPLETE`/`FAILED` result closes only a child-owned pane after capture and a settled child; `BLOCKED` never closes its pane. `BLOCKED` means a genuine blocking dependency stopped the assignment (resumable in the retained pane) and `FAILED` means unrecoverable failure after reasonable retries; the composed prompt instructs the child to publish either with a partial account of completed vs remaining work instead of stopping silently.

Section boundaries are structural, not content-derived: a list section (`ARTIFACTS:`, `FINDINGS:`, `PROCESS:`) runs from its exact header line to the next section header, the next scalar `LABEL: ` field line, or the end marker. Every list item carries a `- ` prefix, so no field value or item text can be mistaken for a boundary, and section order is not fixed — an optional section may be placed anywhere between the markers without corrupting a neighbour. A required section that is absent or repeated, and a non `- `-prefixed line inside one, remain errors.

`PROCESS:` is optional retro annotation. The writer renders it after the `NEXT:` line as the canonical serialization order; the reader accepts the section in any position. An empty list omits the whole section (never `- none`). The cap is five and is enforced asymmetrically: `publish` rejects a sixth item outright, while validation truncates to five and sets `:process-overflow` on the ledger entry, because a discardable annotation must never turn a valid result into the terminal status `invalid`. Non `- `-prefixed lines inside the section are ignored, and repeated `PROCESS:` headers merge their blocks in document order rather than dropping items. Emit items with repeatable `--process`, or a `"process"` array in `--from-file`.

Two further ledger fields sit outside the envelope. `:child-session` holds the child's whole Herdr `agent_session` map (`value` is meaningless without its `kind` discriminator: a transcript path or an opaque session id), giving a durable transcript reference after the pane is gone. It is best-effort and backfilled at every point the CLI already holds an `AgentInfo` — the `agent start` return, one `agent get` after the prompt lands, each wait-loop outcome (no extra Herdr call), `status`/`list`, and the pre-close refresh, which covers `BLOCKED` entries too. Failing to observe a session never fails a spawn or demotes a result. `:process-overflow` is `true` when validation truncated an over-length `PROCESS` section; the entry keeps the envelope's own status. `:artifact-links` holds the rendered Markdown link for each existence-validated artifact, added at capture and only when the envelope declared at least one (§ Artifact links).

Each entry also records the caller's identity at allocation: `:parent-session` (the caller's `agent_session` value, falling back to its `pane_id`) and `:parent-pane` (the caller's pane id). `:parent-session` scopes pane closure and fan-in candidacy; the pair together gates the advisory parent push (§ Parent push).

The ledger directory is shared by every parent in the project, so pane closure is scoped to the owning session: `collect` on an entry whose `:parent-session` differs from the caller's (or whose caller identity cannot be resolved) still captures and validates the envelope, but retains the pane and reports `"pane-retained": true`. Ledger *reads* are never scoped — `status` and `list` show every entry.

## Fan-in

`subagent collect --any [--wait --timeout MS]` takes no task argument (supplying one fails) and captures the *first* in-flight child of the caller's own `:parent-session` to publish a valid envelope, so a parent holding several children in flight learns which one finished instead of blocking on the slowest. It is a read/capture primitive only: it never spawns or schedules a child, and scheduling remains parent policy.

The candidate set is exactly: same `:parent-session` as the caller, no `:captured-at`, and `:status` not `failed`. The session scope is what makes a repo-wide shared ledger safe to fan in over — a foreign entry is never considered, captured, validated, or closed, even when it has a publishable result. The `failed` exclusion is load-bearing rather than tidiness: spawn-failure cleanup marks a dead entry `failed` *without* a `:captured-at`, so an uncaptured-only predicate would keep it a candidate forever and make both the `no-candidates` and all-blocked outcomes unreachable. `invalid` entries need no exclusion, because capture sets `:captured-at` on that branch too. Candidates are polled in `:created-at` order, so fairness is poll-order dependent when two children publish inside the same interval: one is returned and the other stays capturable.

Because `agent wait` takes a single target, N children cannot be awaited in one call. Each tick therefore (1) polls every candidate's `RESULT` file, (2) issues one `agent list` — not one `agent get` per child — to classify liveness by agent `name`, and (3) polls the same candidates again before any terminal outcome, because a child can publish and exit inside the listing window and a `blocked` or `no-live-children` classification must never discard an envelope already on disk. Liveness is keyed on `name` alone and never falls back to `pane_id`: a real listing contains nameless entries for manually started agents, so an unidentifiable entry counts as vanished rather than keeping an exited child live forever. A listing that fails, or that returns no `agents` key at all, leaves liveness *unknown*: no short-circuit may fire and the loop keeps polling to its budget (a genuinely empty list is a known-zero, and does short-circuit). Outcomes:

| Outcome | Result |
|---|---|
| A candidate published a result | the single-task `collect` result plus exactly one added field, `remaining` (candidates still in flight after this capture); nothing else differs |
| No candidates at all | `{"status":"pending","reason":"no-candidates"}` |
| Budget elapsed with nothing published (including the single poll without `--wait`) | `{"status":"pending","reason":"timeout"}` |
| Every live candidate is `blocked` | `{"status":"blocked","tasks":[…]}` with the affected task ids, without consuming the budget |
| Every candidate's agent has vanished from `agent list` | `{"status":"pending","reason":"no-live-children"}` |
| The caller's own identity cannot be resolved | `{"status":"pending","reason":"unknown-caller"}` — distinct from `no-candidates`, since nothing can be scoped without it; non-throwing, because `collect` never runs `preflight!` |

A capture reuses the single-task capture and closure path unchanged: candidates are same-session by construction, so a capture is always owned, and only the captured child's pane is closed under the existing `COMPLETE`/`FAILED` + settled-child rule. Sibling in-flight children are untouched — panes retained, ledger entries unmodified. A blocked or vanished outcome closes no pane and consumes no entry, so every candidate stays capturable. A child whose agent has already vanished is still captured, but its pane is retained: closure requires a settled `agent get`, which a vanished agent cannot supply.

Because that close attempt probes `agent_status` exactly once and never retries, a capture of a `COMPLETE`/`FAILED` envelope first makes one bounded `agent wait` on the captured child — and on no other child — before the unchanged close attempt runs. Never before the capture: the wait only buys the child time to reach `idle`/`done`, and a captured `BLOCKED` envelope (which never closes a pane) skips it entirely. The budget is `SUBAGENT_SETTLE_CLOSE_MS`, default 45 000 ms, deliberately larger than the 30 000 ms `--notify-timeout` default: a non-blocking child publishing while its parent sits inside `collect --any` reads `working` for that entire notify wait, since the parent mid-collect never settles `idle`/`done`, and that window is exactly the retention this wait exists to close. The wait passes **no** `--until`, unlike § Parent push's, so any settled state ends it — a child that settles `blocked` ends it immediately instead of burning the budget, and its pane is retained as always. Give-up is a non-event: budget expiry, a settled-but-unclosable state, or a failed `agent wait` all fall through to the same single close attempt, which simply finds the child unsettled and retains the pane. The result reports the captured envelope's own status either way, and every field of it — `remaining` included — is a function of capture and candidacy alone, never of the close outcome (`remaining` is computed from the candidate set observed at capture, before the wait). This budget is *additional* to `--timeout`, which bounds only the polling for a publication: a capture that lands just before the poll budget elapses can therefore return up to the settle budget later than `--timeout` alone suggests. `--timeout` is still never overshot by a full poll interval; the settle wait is a separate, post-capture bound.

Known limitation: a `subagent run`/`start` killed between `ledger/write!` and its cleanup leaves a same-session, uncaptured, non-`failed` entry, which stays a candidate indefinitely — inflating `remaining` by one and making `no-candidates` unreachable for that session. It can never appear in `agent list`, so it only ever pushes an otherwise-idle fan-in toward `no-live-children`. Clear it with the entry's ledger file rather than by widening the candidacy predicate — see § Pruning for the safe, auditable command that does exactly that.

## Pruning

`subagent prune <full-task-uuid>` is the explicit, single-assignment remedy for the Fan-in "known limitation" above: a `run`/`start` killed between `ledger/write!` and its cleanup leaves a same-session, uncaptured, non-`failed` entry that no `RESULT` will ever complete and whose named child can never reappear in `agent list`, yet it satisfies the `--any` candidate predicate forever. It takes no other form: it never scans the ledger for candidates and never resolves a partial or prefix id — the argument is looked up as the exact ledger key, precisely as `collect <full-task-uuid>` and `status <full-task-uuid>` already do.

Ownership is checked first, via the same `parent-identity` the rest of the CLI uses (`cli.clj`'s `parent-identity`): the caller's own `:parent-session` must equal the entry's recorded `:parent-session`, and **both must be non-nil**, or the prune is refused outright, mutating nothing. The non-nil requirement is deliberate, not redundant: a ledger entry this CLI wrote always carries a `:parent-session`, but a hand-edited or legacy-format ledger file could omit it, and a bare equality check would then let an unresolvable caller (itself `nil`) silently "own" that entry, since `nil` equals `nil`. Pruning is destructive, so that coincidence must never grant ownership — an unresolvable caller identity is refused exactly like a genuine foreign session, and ambiguity here can never default to permissive behaviour the way foreign-session `collect` does (§ Ledger and completion's `pane-retained` path).

Once ownership holds, all of the following staleness proof is required together, checked cheapest-first: the entry has no `:captured-at`, its `:status` is not already terminal (`failed`/`invalid`), no `RESULT` file exists yet on disk for it, and — last, since it is the one Herdr call in the path — its named `:child` is **absent** from a single `agent list` call. Liveness reuses the exact classification `collect --any` already uses (`live-agents`): agents are indexed by `name` only, with no `pane_id` fallback, and a listing that fails or omits its `agents` key returns `nil`, meaning liveness is unknown. Ambiguous liveness must never allow a prune: only a listing that positively excludes the child's name is proof of absence, never an unusable or missing listing. Age is never evidence, and this command never touches `any-candidates` or `collect --any` itself.

That `agent list` call can itself race a child publishing and exiting inside its own subprocess window — the identical hazard `collect --any` re-polls to avoid (§ Fan-in). So the final mutation does not blindly write over the pre-listing snapshot: it re-validates the *freshest* ledger state (a fresh read, inside the same `ledger/update!` call that performs the write) for `:captured-at` and an on-disk `RESULT`, and refuses — mutating nothing — if either appeared during the check. The race-won `RESULT` remains ordinarily collectible afterward.

On success the *existing* entry is mutated in place — never replaced or re-created — to `:status "failed"`, `:failure-phase "orphaned"`, plus two new audit fields: `:pruned-at` (a timestamp, same style as `:reported-at`/`now`) and `:prune-reason "missing-agent"`. Setting `:status "failed"` is what excludes the entry from `any-candidates` afterward, through the pre-existing `failed` exclusion — no change to that predicate is needed or made. `prune` never closes the entry's recorded `:pane-id`: that pane may be stale, reused by an unrelated agent, or already gone, so this command only ever touches the ledger JSON.

Every refusal case — foreign session, unresolvable caller identity, a live named child, an already-captured entry, an already-terminal entry, an existing `RESULT` file, a capture/publish that raced the liveness scan, or an unusable `agent list` — fails loudly (non-zero exit, no JSON `result`) without any ledger mutation and without closing anything.

## Progress

`subagent progress --summary TEXT` is a child-only, advisory heartbeat: it stores exactly **one latest** snapshot, `{:summary ... :reported-at ...}`, under the ledger entry's `:progress` key. There is no history array, ever — a new report overwrites the previous snapshot in place, it is not a second transcript, and it never competes with the `RESULT` file as the completion signal. It makes no herdr call at all: no `pane`/`agent`/`notification` mutation, so `fake-herdr` needs no new verb to cover it.

The command reads the same injected identity `publish!` reads — `HERDR_SUBAGENT_CHILD`, `_TASK`, `_RESULT` — and validates it against the ledger entry named by `_TASK` before doing anything else: the entry's `:child` must equal the injected `HERDR_SUBAGENT_CHILD`, so a mismatched or missing child env can never update another assignment's entry. Progress is then rejected, with a clear error, non-zero exit, and the ledger untouched, when the `RESULT` file already exists, the entry is already `:captured-at`, or the entry's `:status` is the terminal `failed` or `invalid`. None of these checks can be satisfied by a call that also changes assignment status — `progress!` never sets `:status` or `:captured-at`.

An update inside `SUBAGENT_PROGRESS_INTERVAL_MS` (default 60000 ms) of the entry's current `:progress :reported-at` is throttled: it returns the non-error `{"status":"throttled", ...}` outcome carrying the unchanged snapshot, and issues no `ledger/update!` call at all, so the stored snapshot stays byte-identical. An update outside the interval, or the entry's first-ever report (no prior `:progress`), rewrites the snapshot and returns `{"status":"recorded", ...}`.

`status` and `list` expose the stored `:progress` unchanged, because both already return the raw ledger entry (see `live`) — no additional plumbing was needed there. The composed prompt (`prompt-text`) appends one progress instruction, but only when `waiting-policy` is `"non-blocking"`: a `blocking` run's parent is already waiting inside its own wait loop and has nothing to gain from a mid-flight snapshot. The instruction asks for concise phase-boundary progress at most once per interval, with no draft findings and no result content.

Known limitation: `ledger/update!` is read-modify-write with no compare-and-swap, so a `progress` write racing a concurrent parent `capture!`/`record-session!` on the same entry can lose one side's update. The worst case is bounded and recoverable — a lost capture drops `:captured-at` and the entry briefly re-enters `collect --any` candidacy, where a re-capture re-parses the already-published `RESULT` file — so this is accepted rather than adding ledger locking.

## Parent push

Under the `non-blocking` waiting policy a parent has nothing to block on, so a *committed* publish additionally pushes one advisory `agent prompt` to the ledger entry's `:parent-pane`, naming the child, the task id, and the `collect <full-task-uuid>` command to run. The operator toast is retained; the push is additional, and it never replaces the `RESULT` file as the completion signal — it only tells the parent to go and validate one. Nothing is pushed under `blocking`, where the parent is already inside its own wait loop.

The push happens exactly once, and only when both gates hold on the last observed `AgentInfo`:

1. `agent_status` is `idle` or `done` — an active turn is never hijacked, and a `blocked` parent is never pushed to at all, because submitted text would land in its approval UI.
2. The probe's `agent_session` value equals the entry's `:parent-session`. This is not politeness: a pane outlives the agent that occupied it, so status alone would let a delayed child prompt an unrelated *replacement* agent in the reused pane. It is the same ownership boundary that scopes pane closure. A parent whose session cannot be observed fails the gate by construction (the recorded `:parent-session` is never nil), which is the safe direction.

A `working`, `unknown`, or otherwise unsettled parent is first waited for with one bounded `agent wait --until idle --until done` (default 30 000 ms, `--notify-timeout MS` on `publish` overrides it; non-positive and unparseable values fall back to the default). That `--until` pair is deliberately narrower than herdr's default match set, which also includes `blocked`. **Both** gates are then re-checked on the `AgentInfo` observed after the wait, since the wait may have been satisfied by a replacement agent settling in that pane. `agent prompt --wait` is never used: that path can return `agent_prompt_stalled`.

Publication is already committed when the push runs, so every outcome is reported under `result.parent-push` and none of them can change the publish status or exit code:

| `push` | When |
|---|---|
| `sent` | Both gates held |
| `skipped` | With `reason`: `parent-blocked`, `session-mismatch`, `parent-unsettled` (still unsettled after the wait), `parent-unobserved` (the wait returned no `AgentInfo`), `no-parent-pane` (entry predates `:parent-pane`), or `unknown-ledger-entry` (a publication with no ledger entry, so no parent to probe) |
| `timed-out` | The settle wait exhausted its budget (herdr error code `timeout`); reports `timeout-ms` and the `parent-status` waited from |
| `error` | A herdr failure on the push path, named by `reason`: `probe-failed`, `prompt-failed` (submission may have delivered text partially — not the same operator fact as a parent never contacted), the wait's own error `code`, or `push-failed` for anything else |

`waited` is `true` on any outcome that a settle wait preceded; it never asserts that the parent settled, since the post-wait re-check may still reject it.

## Artifact links

An `ARTIFACTS` item (`<absolute path>[ — <purpose>]`) is additionally rendered as one portable Markdown link, `[escaped absolute path](encoded file:// URI) — escaped purpose`, so a parent that has already closed the child pane can still reach the artifact. The visible label is always the whole absolute path, never a basename: the path itself is the context the parent loses with the pane. One shared renderer (`core/artifact-link`) produces both surfaces below, splitting the item on the same ` — ` delimiter as `core/artifact-path` and enforcing the same absoluteness check itself — `Paths/get` would otherwise resolve a relative path against the process cwd and yield a confident link to the wrong file. A path *containing* ` — ` mis-splits, exactly as it always has for `artifact-path`: the delimiter is envelope grammar, not an escapable value, and the truncated prefix is what the collect-time existence check then rejects.

This is **portable fallback syntax, not a clickability guarantee**. No raw OSC 8 (or any other terminal-control) escape sequence is ever emitted into model- or operator-visible text. A harness that renders Markdown may turn the link into a terminal hyperlink when the terminal advertises support — Pi's user messages render through pi-tui's Markdown component, which does exactly that — and every other reader still sees the full absolute path plus a usable URL. Whether a link is clickable therefore depends on the harness and terminal, never on this contract.

The destination is built with `java.nio.file.Path.toUri`, never by hand: spaces, `#`, `%`, `?`, `[`, `]`, and non-ASCII characters come back correctly percent-encoded, and the result carries the canonical empty authority (`file:///…`, not `File.toURI`'s `file:/…`). `(` and `)` are legal URI sub-delimiters that `toUri` leaves alone, so they are percent-encoded afterwards because an unbalanced parenthesis would terminate a Markdown link destination early; the URI is equivalent either way. `[`, `]`, `\`, `` ` ``, `*`, `_`, `<`, `>`, `&`, and `~` are backslash-escaped in the visible label and in the purpose text. The last four are not cosmetic: an unescaped entity reference would make a CommonMark renderer display `/tmp/amp&amp;.md` as `/tmp/amp&.md` — a different file — in the one field that must always be readable, and raw inline HTML can vanish outright.

The renderer is not pure: `Path.toUri` stats the path, so an *existing directory* artifact's destination gains a trailing slash its label does not have, and the advisory (rendered before the artifact need exist) may differ from the collected link for the same declared line.

The two surfaces carry deliberately different weight:

| Surface | Source | Weight |
|---|---|---|
| The advisory parent push (§ Parent push), under a labelled `Declared artifacts (advisory — pending validation by \`collect\`)` line | the child's *declared* envelope body | **Not evidence.** Publish checks artifact path shape only, never existence, so a declared link may name a file that never appears |
| `result.artifact-links` on a successful `collect` (single-task and `--any` alike) | the captured envelope, after every artifact passed the `fs/exists?` check | Existence-validated as of `:captured-at`; also persisted on the ledger entry as `:artifact-links`, which is what keeps the links reachable through `status`/`list` after the pane and the collect output are gone |

Because publication is already committed when the advisory renders, an artifact path that cannot be rendered at all is dropped from the list and counted (`- (N declared artifact path(s) not renderable as a link; see the RESULT envelope)`) rather than failing the push: `artifact-path` accepts a NUL byte that `Paths/get` rejects, and such a byte cannot even be degraded to raw text because it would then fail the `agent prompt` submission itself. The `RESULT` envelope remains the authoritative declared list.

An empty artifact list adds *nothing* on either surface: no advisory section, and no `artifact-links` key on the result or the entry. Absent is not the same claim as validated-empty. An artifact that fails the existence check yields the terminal `invalid` status and therefore no links at all — including on a re-collect whose artifact has since been deleted, which drops the `:artifact-links` an earlier capture stored. `--any` gains links through the shared capture path, so its result remains exactly the single-task shape plus `remaining`.

## Retro gating

`run` and `start` decide whether the composed prompt asks the child to apply steps 1–2 of the `retro` skill to its own session. Precedence is `--retro` / `--no-retro` (value-less flags; supplying both fails fast) > persona frontmatter `retro:` > the built-in default, which is **enabled**. Frontmatter values are always strings, so `retro:` is coerced by an explicit `true`/`false` table; any other value fails fast at spawn, naming the persona and the value.

"Enabled" means the child *evaluates* the `retro` threshold, never that a retro is forced: that skill owns the threshold, including its non-interactive equivalent, and a gated-in child that finds nothing correctly publishes no `PROCESS` section. `--no-retro` and `retro: false` skip the step entirely — no prompt paragraph and no token cost. `scout` and `researcher` ship with `retro: false`.

The retro skill path resolves `<assignment-root>/.agents/skills/retro/SKILL.md`, then `<assignment-root>/skills/retro/SKILL.md`, then `~/.agents/skills/retro/SKILL.md`. When none exists the frontmatter and default sources degrade silently — the instruction is omitted and `:retro-source` is `"skill-missing"` — because the retro step is optional equipment and an installation without that skill is a supported configuration. An explicit `--retro` is an operator request and fails fast at spawn instead of becoming a silent no-op; `--no-retro` is always honoured. The resolved policy is recorded on the ledger entry as `:retro` and `:retro-source` (`flag`, `frontmatter`, `default`, or `skill-missing`), and `--print-prompt` reports both alongside the composed prompt. Gating shapes the prompt only: a `PROCESS` section published by a gated-out child is still accepted.

## Spawn gating

`run` and `start` resolve the child's spawn allow-list — the personas it may itself spawn — with precedence `--spawns` (value-bearing; whitespace- and/or comma-separated, deduplicated) > persona frontmatter `spawns:` > the built-in default, which is **deny** (the empty list). The literal flag value `none` forces the empty policy without consulting the roster. An absent key and a present-but-blank frontmatter value both mean leaf, not an error. An unresolvable persona name in either the flag or the frontmatter fails fast at spawn, naming the declaring persona, the offending name, and the source.

The resolved policy is recorded on the ledger entry as `:spawns` and `:spawns-source` (`flag`, `frontmatter`, `default`, or `depth`), reported by `--print-prompt` alongside the composed prompt, injected into the child as the space-joined `HERDR_SUBAGENT_SPAWNS`, and composed into the prompt's delegation guidance: a non-empty policy renders the may-spawn sentence, an empty one the leaf sentence.

Depth is bounded to one nesting level absolutely, with no depth counter: a CLI whose own `HERDR_SUBAGENT_PERSONA` is set is below root. Below root, a target persona absent from the caller's own `HERDR_SUBAGENT_SPAWNS` is refused before preflight, ledger allocation, and any pane mutation — a refused spawn writes no ledger entry and creates nothing billable. Blank and unset `HERDR_SUBAGENT_SPAWNS` both deny; the empty-string/unset distinction is never load-bearing. An explicit below-root `--spawns` with any value other than `none` fails fast, and a permitted below-root spawn always injects an empty grandchild `HERDR_SUBAGENT_SPAWNS` with source `depth`, regardless of the grandchild persona's frontmatter. A root CLI (no `HERDR_SUBAGENT_PERSONA`) is unrestricted in what it may spawn.

## Labels and geometry

Labels are `<persona>-<index>[-<model-basename>]`; a below-root child is `<parent-persona>-<n>/<persona>-<index>[-<model-basename>]`, prefixed from the spawning agent's injected `HERDR_SUBAGENT_PERSONA`. Workspace names are excluded. Labels are display metadata only: capability and depth are enforced by § Spawn gating, never by label parsing. Direction is `right` only when the caller's own matching layout pane rect has `width >= 80 && width >= 2 * height`, otherwise `down`; tab area and focus are irrelevant.

## Placement

Default spawn creates the child with `pane split` in the caller's pane, choosing `--direction` as above. The value-less `--tab` flag instead creates the child with `tab create --workspace $HERDR_WORKSPACE_ID --cwd ... --label ... --no-focus --env ...` in a new, unfocused tab of the caller's Herdr workspace; the child pane is `.result.root_pane`, distinct from `.result.tab` (whose `tab_id` is recorded but never focused or otherwise acted on). `--tab` skips the `caller-rect!`/direction computation entirely — it needs neither. Every other step (env injection, `pane rename` onto the child pane, `agent start`/`prompt`, wait/collect, and pane-close-on-completion, which also closes a tab's last pane) is identical between the two placements.

Placement is spawn argv only: it is never persisted as a default, never inherited, and never carried through a new child environment variable, so a tab-placed child's own spawns split by default exactly as an unplaced one would. The ledger entry records `:placement` (`"split"` or `"tab"`) and `:tab-id` (the created tab's id, `nil` for a split placement).
