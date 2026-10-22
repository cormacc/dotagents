# Root-owned workflow recipes

Use these recipes from the root orchestrator. The root chooses context, validates captures, keeps evidence, and closes or tears down only the children and checkouts it owns. The recipes compose existing `oh` lifecycle verbs; they do not add a workflow engine, persona, scheduler, or runner.

## Ordinary staged delegation

“Spawn a subagent” means a fresh context, which is the default. Use a named persona when its role helps the stage, or omit the persona for a fresh persona-less child. Capture each stage before preparing its successor, and give the successor the captured, existence-validated artefacts rather than relying on an earlier child's pane context.

```sh
OH="$HOME/.agents/skills/herdr-orch/scripts/oh"
"$OH" task start worker --worktree new --task-file .tmp/stage-1.md
# Record the complete UUID as STAGE_1 from the start envelope.
"$OH" task collect "$STAGE_1" --wait --timeout 1800000
# Inspect result.artifact-links and the checkout reconciliation, then prepare .tmp/stage-2.md with the retained artefact paths.
"$OH" task close "$STAGE_1"
"$OH" task start worker --worktree new --task-file .tmp/stage-2.md
```

The stage file names only captured artefacts and the next decision. It does not ask the next child to inspect a previous child's pane. A worktree `CHECKPOINT` is a reconciliation point, not integration: the root inspects it and integrates explicitly.

Use `continue` only when the follow-up needs the same child's retained context, role and checkout. It is not a shortcut for ordinary staging or independent review.

```sh
"$OH" task continue "$STAGE_2" --task-file .tmp/stage-2-follow-up.md --wait --timeout 1800000
# Capture the returned successor UUID, inspect its artefacts, then close that successor.
```

"Fork a subagent" means `--context fork`. The child inherits the caller's completed earlier turns but not the current turn that launches it, so its assignment is its only open request. Write the assignment for a reader who has not seen the current turn: state the task and include any findings from this turn that the child needs. A fork from the caller's first turn refuses; use fresh context there.

```sh
"$OH" task run --context fork --task-file .tmp/forked-follow-up.md
```

Independent validation is always a new fresh child, never a continuation of the implementing or reviewing child.

```sh
"$OH" task run reviewer --context fresh --worktree new --task-file .tmp/independent-validation.md --timeout 1200000
```

## Repeated measurements

A measurement is headless by default, so repeated samples do not allocate one pane per sample. Start or run one sample at a time, or use the root's own bounded fan-in policy with `collect --any --wait`; `oh` does not schedule batches. The measurement configuration controls its treatment, tools, hard limit and optional `expectedOutputs`.

```sh
"$OH" task start --context measurement --measurement-config experiments/sample.json --worktree new --task-file experiments/sample-assignment.txt
# Record the complete UUID as SAMPLE_1 from the start envelope.
"$OH" task collect "$SAMPLE_1" --wait --timeout 600000
# Copy required artefacts, execution evidence and expected outputs outside the disposable checkout.
# Inspect result.reconciliation; leave checkpointed files intact.
"$OH" task close "$SAMPLE_1"
# Inspect any residual untracked/ignored scratch before the normal managed-checkout guard.
"$OH" worktree remove "$SAMPLE_1"
```

Every sample that declares `expectedOutputs` uses its own `--worktree new` checkout. The target must be absent before launch. Publication checkpoints non-ignored outputs: deleting a checkpointed output creates tracked dirt and makes teardown refuse. Copy required evidence outside the checkout, inspect reconciliation, and leave checkpointed files intact. Rescue or remove only genuinely residual untracked/ignored scratch after inspection; never delete expected outputs as a blanket cleanup step. Keep the existing stopped-execution, ownership and clean-checkout guards. Do not use a shared checkout merely to avoid this cleanup.

Add `--tab` or `--split` only when a read-only measurement monitor is useful. The monitor shows recorded activity but cannot provide input to, stop, or publish for the measured subject.

```sh
"$OH" task start --context measurement --measurement-config experiments/sample.json --worktree new --tab --task-file experiments/sample-assignment.txt
```

A measurement result proves only the configured execution and request audit. It is not an answer-quality score. `continue` and `poke` do not apply to a measurement.

## Guarded live context smoke -- approval required

Do not run this smoke until the root explicitly approves a live provider request. It uses the existing guarded harness and requires a root Pi Herdr pane. The exact proposed invocation is:

```sh
HERDR_ENV=1 ORCH_LIVE_SMOKE=1 ORCH_LIVE_SMOKE_MODEL=openai-codex/gpt-6-luna bb smoke-subagent context-modes
```

The `context-modes` path exercises two fresh children (named and persona-less), then a fresh worker whose synthetic-only session is the source for both an inherited worker snapshot fork and a `scout` overlay fork, then two isolated measurements (headless and `--tab` monitor). The worker's first round only records a generated code word and publishes; a `continue` round then launches both forks. A fork inherits only completed turns, so both forks see that first round and nothing of the round that launched them. Each must report the code word, and the harness checks that the fork's own launch prompt has the synthetic task without the word. Seven model sessions is the intended count, not an enforced maximum; the worker session receives two assignments and the others one each: the worker can launch extra rounds before the post-hoc shape/count check rejects them. The fork source is the fresh worker session, not the root's live conversation; all assignments and the generated measurement treatment are synthetic. Both nested commands explicitly select the same approved model and require a matching `--print-prompt` preview before launch. The harness checks recorded `model`, `model-canonical` and native `model-args`, both fork treatments, source identity, captured terminal COMPLETE results and owner closure. Offline resolver checks confirm the proposed OpenAI model spelling and routing, not provider availability, authentication or live compliance. The reusable harness accepts its explicitly selected model; this proposal does not add a global provider restriction.

Interactive waits are non-destructive budgets, not execution deadlines. With the current resolved roles, the fresh scout, persona-less child and scout overlay each have a 600000 ms wait budget; the bridge worker and inherited worker snapshot fork each have 1800000 ms. Each measurement collect has a 600000 ms wait budget, while only the two measurements have the generated 120000 ms hard execution limit (no tools or expected outputs). Direct children are started and recorded before one bounded collect for each round using their previewed budget. WAITING or pending returns end the smoke with outstanding recovery evidence; no automatic retry, extra collect, stop or force-close occurs. The bridge is instructed to retain pending fork IDs rather than continue spawning. Its own wait can expire before nested work finishes. These waits do not bound total session time or model consumption, and underlying CLI lifecycle calls do not have an overall deadline.

The 120-character summary check is post-hoc validation of captured summaries, not a generation, output-token or monetary cap. There is no enforced token, money or total-session bound. Approval must accept these actual limits; no such approval is recorded here.

Before each validation or cleanup, the harness accumulates task IDs, start/capture data, ledger provenance, measurement evidence paths and lifecycle dispositions in `<assignment-root>/.tmp/herdr-orch/context-smoke/<run-uuid>/manifest.json`. It retains the generated measurement configuration beside the manifest. A manifest flush failure stops the smoke before another leg, but retains in-memory recovery evidence and still attempts guarded cleanup for captured terminals; its structured failure names the manifest, persistence errors and accumulated evidence even when the file is stale. The success envelope and failure data name this recovery manifest. Earlier legs remain recorded after a later failure. Captured terminal failures and successes use the existing guarded close; only stopped, clean, owned measurement checkouts can be removed. Refusals are retained as recovery-required, not overridden. All descendants proved by the synthetic bridge's recorded session links are included even on bridge failure; they remain owned by their recorded parent session, so the root cannot directly close them. Use the recorded IDs and existing owner/orphan policy for recovery; do not bulk-close root/user peers. Copy required evidence before later scratch cleanup.

This smoke is not CI and must not be run against private assignments, real project artefacts, secrets or a real parent conversation. No live consent is granted by this recipe.
