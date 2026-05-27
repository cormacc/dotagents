# Eval workflow

Reference for the test/grade/review loop used by `skill-creator`. Load when
running the test cases or interpreting benchmark output.

## Layout

Put results in `<skill-name>-workspace/` as a sibling to the skill directory.
Within the workspace, organise by iteration (`iteration-1/`, `iteration-2/`,
…) and within that, each test case gets a directory (`eval-0/`, `eval-1/`,
…). Create directories as you go, not upfront.

## Step 1: Spawn all runs in the same turn

For each test case, spawn two subagents in the same turn — one with the
skill, one without. Don't spawn with-skill first and come back for baselines
later.

**With-skill run:**

```
Execute this task:
- Skill path: <path-to-skill>
- Task: <eval prompt>
- Input files: <eval files if any, or "none">
- Save outputs to: <workspace>/iteration-<N>/eval-<ID>/with_skill/outputs/
- Outputs to save: <what the user cares about — the .docx, the final CSV, etc.>
```

**Baseline run** (same prompt; baseline depends on context):
- *New skill* — no skill at all. Save to `without_skill/outputs/`.
- *Improving an existing skill* — the previous version. Snapshot first
  (`cp -r <skill-path> <workspace>/skill-snapshot/`), then point the
  baseline subagent at the snapshot. Save to `old_skill/outputs/`.

Write `eval_metadata.json` for each test case (assertions empty for now).
Give each eval a descriptive name and use it as the directory name. If this
iteration changes prompts, write fresh metadata files — don't assume they
carry over.

```json
{
  "eval_id": 0,
  "eval_name": "descriptive-name-here",
  "prompt": "The user's task prompt",
  "assertions": []
}
```

## Step 2: While runs are in progress, draft assertions

Don't just wait. Draft objective assertions and explain them to the user.
If `evals/evals.json` already has assertions, review and explain those.

Good assertions are objectively verifiable with descriptive names that read
clearly in the viewer. Subjective qualities (writing style, design) belong
in human review, not assertions.

Update `eval_metadata.json` and `evals/evals.json` once drafted. Tell the
user what they'll see in the viewer (qualitative outputs + quantitative
benchmark).

## Step 3: As runs complete, capture timing

Each subagent notification includes `total_tokens` and `duration_ms`. Save
immediately to `timing.json` in the run directory:

```json
{
  "total_tokens": 84852,
  "duration_ms": 23332,
  "total_duration_seconds": 23.3
}
```

This is the only opportunity — the data isn't persisted elsewhere.

## Step 4: Grade, aggregate, launch the viewer

1. **Grade.** Spawn a grader subagent (or grade inline) using
   `agents/grader.md`. Save results to `grading.json` per run. The
   `expectations` array must use `text`, `passed`, `evidence` (not
   `name`/`met`/`details`) — the viewer depends on these exact fields. For
   programmatic checks, write a script and run it.

2. **Aggregate:**
   ```bash
   python -m scripts.aggregate_benchmark <workspace>/iteration-N --skill-name <name>
   ```
   Produces `benchmark.json` and `benchmark.md`. Put each `with_skill` row
   before its baseline counterpart. See `references/schemas.md` for the
   schema if generating manually.

3. **Analyst pass.** Read the benchmark and surface patterns the aggregate
   hides — non-discriminating assertions, high-variance evals, time/token
   tradeoffs. See `agents/analyzer.md`.

4. **Launch the viewer.** Always `generate_review.py` — don't write custom
   HTML:
   ```bash
   nohup python <skill-creator-path>/eval-viewer/generate_review.py \
     <workspace>/iteration-N \
     --skill-name "my-skill" \
     --benchmark <workspace>/iteration-N/benchmark.json \
     > /dev/null 2>&1 &
   VIEWER_PID=$!
   ```
   For iteration 2+, also pass `--previous-workspace <workspace>/iteration-<N-1>`.

   **Headless:** pass `--static <output_path>` to write a standalone HTML
   file. Feedback downloads as `feedback.json`; copy it into the workspace
   for the next iteration.

5. **Tell the user** where to look (Outputs tab walks through cases for
   feedback; Benchmark tab shows quantitative comparison).

## Step 5: Read the feedback

When the user says they're done, read `feedback.json`. Empty feedback means
that test case looked fine; focus improvements where the user had specific
complaints. Kill the viewer:

```bash
kill $VIEWER_PID 2>/dev/null
```

## Iteration

1. Apply improvements to the skill.
2. Rerun all test cases into `iteration-<N+1>/`, including baselines. For
   new skills the baseline is `without_skill`; for improvements pick the
   most informative baseline (usually previous iteration, sometimes the
   original).
3. Launch the viewer with `--previous-workspace` pointing at the previous
   iteration.
4. Wait for review, read feedback, improve, repeat.

Stop when: user is happy, all feedback is empty, or progress stalls.

For rigorous A/B between two versions, see `agents/comparator.md` +
`agents/analyzer.md` — gives an independent agent two outputs blind and
judges quality. Optional; human review is usually enough.
