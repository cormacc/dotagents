# Root-owned workflow recipes

Use these recipes from the root orchestrator. The root chooses context, validates captures, keeps evidence, and closes or tears down only the children and checkouts it owns. The recipes compose existing `oh` lifecycle verbs and add no persona, scheduler, or runner. The root keeps dependency knowledge and routing decisions. A workflow engine is out of scope until a demonstrated need that these verbs cannot meet.

For delegated delivery of an accepted Org change-record, use [org-execute](../../org-execute/SKILL.md). It composes these recipes with `org-plan` and `org-tasks`; the recipes below also apply to work without an Org plan.

## Isolated Herdr investigations

Use this recipe for an authorised reproduction that needs a disposable server, not for ordinary delegation. Keep the vendored [Herdr skill](../../herdr/SKILL.md) unchanged.

1. Run CLI discovery groups separately: a bare group prints usage and exits 2, so chaining groups with `&&` stops discovery.
2. Start a uniquely named disposable server with `herdr --session NAME server`. Create its workspace with `workspace create --cwd PATH --no-focus`; pass the returned `.result.workspace.workspace_id` to `tab create --workspace`. Prefix every server-control command with the same `herdr --session NAME`; inherited caller IDs belong to the original session.
3. Check exit status and the error envelope before reading result fields. Raw Herdr controls return `{id,result}` on success or `{id,error}` on failure, not `oh`'s `ok` envelope. `status --json` has its own top-level `client` and `server` fields.
4. Establish one successful trial and one expected refusal before batching. Bound the whole probe and each external call; attach owned-session cleanup to the outer timeout. Stop and delete only the disposable session, then verify the session inventory.

## Ordinary staged delegation

“Spawn a subagent” means a fresh context, which is the default. Use a named persona when its role helps the stage, or omit the persona for a fresh persona-less child. Capture each stage before preparing its successor, and give the successor the captured, existence-validated artefacts rather than relying on an earlier child's pane context.

```sh
OH="<skill-dir>/scripts/oh"   # <skill-dir> as in SKILL.md
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

## Independent regression-test authorship

Use this recipe when a complex bug warrants separating the reproduction from the proposed fix. Ordinary fixes stay inline. It uses the existing `worker` persona, not a new test-writing role.

1. Pin the baseline to an immutable commit and establish that the existing relevant checks run. Give a fresh worker the bug report, required behaviour, baseline, test conventions and allowed test files. Withhold the proposed fix and its rationale. Ask for a test-only change, a failure for the reported behaviour, and a safe control that confirms the harness works. Do not authorise a production-code edit.
2. Capture and inspect the test artefact and checkpoint before closing the author. Confirm the diff is test-only and trace assertions to the bug contract. Independently run those exact tests against the pinned production baseline; an import error, unavailable runner or unrelated baseline failure is not a reproduction. Retain the failing output and the passing control.
3. Give a different fresh worker the captured tests and a scoped implementation assignment. The worker may implement the fix, but must not weaken the approved assertions. Capture its result and inspect checkout reconciliation; do not assume a publication integrated anything.
4. Verify the same test passes with the fix and fails without it for the intended reason. Run the relevant existing suite and account for changed test coverage by assertion and behaviour, not by total count. Reject a fix that only bypasses the test setup.
5. Retain baseline, test and fix identifiers, exact commands, evidence and remaining limitations in the existing task record. Close only owned children after capture. Integration, commits and parent closure retain their existing authority rules.

If the test cannot distinguish the bug from the intended behaviour, return it for correction before assigning a fix. Do not let either child choose a new contract to make the test pass. A fresh author reduces exposure to the proposed implementation; it does not prove that a test is independent or correct.

## Bounded decision review

Use this recipe for a materially uncertain or high-impact decision while correction is still cheap. It is root-owned and opt-in, not routine worker consultation or a review of every branch in the code.

1. Name the decision and why an error matters. Prepare the smallest reviewable artefact, its required contract, and the caller, guard or test evidence needed to assess it. Omit the author's persuasive narrative. If the assignment is to verify a specific proposition, include that proposition; do not make the reviewer guess the question.
2. Start a fresh read-only `reviewer` for a code or design range, or `base-analyst` for another explicitly scoped assessment. Ask it to look for contract violations and unstated assumptions, and to return an explicit no-issues result if none meets the evidence bar. Do not ask it to manufacture an objection or broaden the range.
3. Capture the result and check each finding against source before accepting it. For a P0--P2 finding, find its quoted line at the cited location; reject the finding when the quote is absent. For a P3 one-line finding, which carries no quote, read the cited line. Reject any finding whose code contradicts it. Demote by one severity level a finding that holds only in a narrower case than it states. Classify each remaining finding as a contract misunderstanding, an actionable defect, an accepted trade-off or noise. Correct an incomplete contract before another review. A reviewer is evidence, not authority to relax the user's requirements.
4. Change the artefact only within approved scope. Re-review only when the changed artefact or clarified contract warrants it. A re-review assignment includes the prior findings and asks for a ruling on each, fixed or still open, with the current quoted line for a P0--P2 finding, as well as a fresh review of the changed range. Stop when no substantive unresolved issue remains. After at most three cycles, report unresolved issues to the owner rather than looping. Do not interpret the cycle limit as approval to ship a known defect.
5. Retain the decision, evidence, classification and disposition in the existing record. Close each captured child. Cross-model review is a separate user choice, not a mandatory question each cycle or permission to launch another CLI.

For a substantial spec-backed change, the root may split fresh `reviewer` assignments into requirement conformance and correctness, using the same pinned range. Keep each assignment narrow, then reconcile both under `code-review`'s evidence and severity rules. This is optional, not a mandatory standards panel or a replacement for `simplifier`.

The `advisor` remains a focused consult for a stuck worker; this recipe does not introduce pre-publish reviews. See [../SKILL.md#Executor tier and advisor strategy](../SKILL.md#executor-tier-and-advisor-strategy).

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

## Process retrospectives

Read before overriding retro policy or consuming `PROCESS:` candidates. The [core](../SKILL.md#process-retrospectives) owns the parent/child persistence boundary.

Use `--retro` or `--no-retro` only when overriding the persona policy for this spawn. Otherwise the CLI resolves persona frontmatter `retro:` and then its enabled default. `scout`, `researcher`, and `advisor` currently opt out. If no `retro` skill is installed, default/frontmatter enablement degrades to disabled. An explicit `--retro` fails fast.

A gated-in child applies steps 1--2 of [`retro`](../../retro/SKILL.md), using that skill's threshold. Surviving one-line candidates arrive in the result's optional `PROCESS:` section and the ledger `:envelope`. No candidates is a valid result. The ledger's best-effort `:child-session` is the transcript reference for any manual follow-up after pane closure. Exact precedence, fields, limits, and section grammar belong to the [mechanical contract](../scripts/docs/contract.md).
