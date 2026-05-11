---
name: skill-creator
description: Create, edit, review, audit, or measure skills. Use whenever the user asks to create a skill from scratch, modify or refactor an existing skill (including SKILL.md frontmatter, description, scope, or body), review or critique a skill or proposed changes to one, run evals or benchmarks against a skill, analyse why a skill is or isn't triggering, or optimise a skill's description for better triggering accuracy. Triggers on phrases like 'review this skill', 'audit the SKILL.md', 'why isn't my skill loading', 'tune the description', 'benchmark this skill', not just 'create a skill'.
---

# Skill Creator

A skill for creating skills and iteratively improving them. The loop:

- Decide what the skill should do and roughly how
- Write a draft
- Run the agent-with-the-skill on a few test prompts
- Help the user evaluate the results qualitatively (via the eval viewer) and, where assertions make sense, quantitatively
- Rewrite based on feedback and benchmark patterns
- Repeat until satisfied; expand the test set and try again at larger scale

The user may enter the loop at any stage. Figure out where they are and meet them there. If they want to skip evals and just iterate by feel, do that.

After the skill is in good shape, run the description optimiser to improve triggering accuracy.

---

## Creating a skill

### Capture intent

The current conversation may already contain the workflow the user wants to capture (e.g. "turn this into a skill"). Extract from history first — the tools used, the sequence of steps, corrections the user made, input/output formats. Confirm gaps with the user before drafting.

1. What should this skill enable the agent to do?
2. When should it trigger? (what user phrases/contexts)
3. What's the expected output format?
4. Are objective test cases worthwhile? Skills with verifiable outputs (file transforms, data extraction, code generation, fixed workflow steps) benefit from them. Skills with subjective outputs (writing style, design) usually don't. Suggest a default; let the user decide.

### Interview and research

Ask about edge cases, input/output formats, example files, success criteria, dependencies. Don't write test prompts until this is settled.

If MCPs are available for research (docs, similar skills, best practices), use them — in parallel via subagents if available, otherwise inline.

### Write SKILL.md

Fill in:

- **name** — skill identifier
- **description** — when to trigger, what it does. This is the primary triggering mechanism: include both *what* the skill does and the *contexts* in which to use it. All "when to use" guidance lives here, not in the body. Agents tend to *under*-trigger skills, so make descriptions slightly assertive. E.g. instead of "How to build a fast dashboard", write "How to build a fast dashboard. Use this whenever the user mentions dashboards, data visualisation, internal metrics, or wants to display any kind of data, even if they don't say 'dashboard'."
- **compatibility** — required tools/dependencies (rare, optional)
- the body

### Skill layout

```
skill-name/
├── SKILL.md (required)
│   ├── YAML frontmatter (name, description required)
│   └── Markdown instructions
└── Optional bundled resources
    ├── scripts/    — executable code for deterministic/repetitive tasks
    ├── references/ — docs loaded on demand
    └── assets/     — files used in output (templates, icons, fonts)
```

Skills load progressively in three levels:

1. **Metadata** (name + description) — always in context, ~100 words
2. **SKILL.md body** — in context whenever the skill triggers, aim for <500 lines
3. **Bundled resources** — read on demand; scripts execute without being loaded

Patterns:

- Keep SKILL.md under ~500 lines. If approaching the limit, split into reference files with clear pointers from SKILL.md.
- For reference files >300 lines, include a table of contents.
- When a skill spans multiple variants (frameworks, cloud providers, languages), put the workflow + selection logic in SKILL.md and per-variant detail in `references/<variant>.md` so only the relevant one gets read.

### Writing patterns

Prefer the imperative form. Explain *why* something matters rather than relying on heavy-handed MUSTs — modern agents have good theory of mind and respond better to reasoning than to rigid rules. Drafting once and then re-reading with fresh eyes catches a lot.

Define output formats by example:

```markdown
## Report structure
Use this template:
# [Title]
## Executive summary
## Key findings
## Recommendations
```

Show input/output examples for transformations:

```markdown
## Commit message format
Input:  Added user authentication with JWT tokens
Output: feat(auth): implement JWT-based authentication
```

### Test cases

After the draft, propose 2–3 realistic test prompts — the kind of thing a real user would actually type. Confirm with the user before running.

Save to `evals/evals.json`. Don't write assertions yet:

```json
{
  "skill_name": "example-skill",
  "evals": [
    {
      "id": 1,
      "prompt": "User's task prompt",
      "expected_output": "Description of expected result",
      "files": []
    }
  ]
}
```

See `references/schemas.md` for the full schema (the `assertions` field is added later).

---

## Running and evaluating test cases

This is one continuous sequence — don't stop partway through. Do **not** route through `/skill-test` or any other testing skill.

Put results in `<skill-name>-workspace/` as a sibling to the skill directory. Within the workspace, organise by iteration (`iteration-1/`, `iteration-2/`, …) and within that, each test case gets a directory (`eval-0/`, `eval-1/`, …). Create directories as you go, not upfront.

### Step 1: Spawn all runs (with-skill *and* baseline) in the same turn

For each test case, spawn two subagents in the same turn — one with the skill, one without. Don't spawn with-skill runs first and come back for baselines later; launch everything together.

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
- *Improving an existing skill* — the previous version. Snapshot first (`cp -r <skill-path> <workspace>/skill-snapshot/`), then point the baseline subagent at the snapshot. Save to `old_skill/outputs/`.

Write `eval_metadata.json` for each test case (assertions empty for now). Give each eval a descriptive name based on what it tests, and use that as the directory name. If this iteration changes eval prompts, write fresh metadata files — don't assume they carry over.

```json
{
  "eval_id": 0,
  "eval_name": "descriptive-name-here",
  "prompt": "The user's task prompt",
  "assertions": []
}
```

### Step 2: While runs are in progress, draft assertions

Don't just wait. Draft objective assertions and explain them to the user. If `evals/evals.json` already has assertions, review and explain those.

Good assertions are objectively verifiable and have descriptive names that read clearly in the benchmark viewer. Subjective qualities (writing style, design) belong in human review, not assertions — don't force assertions onto things that need judgment.

Update `eval_metadata.json` and `evals/evals.json` once drafted. Tell the user what they'll see in the viewer (qualitative outputs + quantitative benchmark).

### Step 3: As runs complete, capture timing data

Each subagent task notification includes `total_tokens` and `duration_ms`. Save immediately to `timing.json` in the run directory:

```json
{
  "total_tokens": 84852,
  "duration_ms": 23332,
  "total_duration_seconds": 23.3
}
```

This is the only opportunity — the data isn't persisted elsewhere. Process each notification as it arrives.

### Step 4: Grade, aggregate, launch the viewer

Once all runs are done:

1. **Grade each run.** Spawn a grader subagent (or grade inline) using `agents/grader.md`. Save results to `grading.json` in each run directory. The `expectations` array must use `text`, `passed`, `evidence` (not `name`/`met`/`details`) — the viewer depends on these exact fields. For programmatically checkable assertions, write a script and run it instead of eyeballing — faster, more reliable, reusable across iterations.

2. **Aggregate into a benchmark:**

   ```bash
   python -m scripts.aggregate_benchmark <workspace>/iteration-N --skill-name <name>
   ```

   Produces `benchmark.json` and `benchmark.md` with pass_rate, time, and tokens per configuration (mean ± stddev with deltas). Put each `with_skill` row before its baseline counterpart. If generating manually, see `references/schemas.md` for the schema.

3. **Analyst pass.** Read the benchmark and surface patterns the aggregate hides — non-discriminating assertions that always pass, high-variance evals (possibly flaky), time/token tradeoffs. See `agents/analyzer.md` (the "Analyzing Benchmark Results" section) for what to look for.

4. **Launch the viewer.** Always use `generate_review.py` — don't write custom HTML.

   ```bash
   nohup python <skill-creator-path>/eval-viewer/generate_review.py \
     <workspace>/iteration-N \
     --skill-name "my-skill" \
     --benchmark <workspace>/iteration-N/benchmark.json \
     > /dev/null 2>&1 &
   VIEWER_PID=$!
   ```

   For iteration 2+, also pass `--previous-workspace <workspace>/iteration-<N-1>`.

   **Headless / no-display environments:** pass `--static <output_path>` to write a standalone HTML file instead of running a server. Feedback downloads as `feedback.json` when the user clicks "Submit All Reviews"; copy it into the workspace for the next iteration.

5. **Tell the user**, e.g.: "Results are open in your browser. The 'Outputs' tab walks through each test case for feedback; 'Benchmark' shows the quantitative comparison. Come back when you're done."

### What the user sees in the viewer

The "Outputs" tab shows one test case at a time:
- **Prompt** — the task given
- **Output** — files the skill produced, rendered inline where possible
- **Previous Output** (iteration 2+) — collapsed section, last iteration's output
- **Formal Grades** (when grading was run) — collapsed assertion pass/fail
- **Feedback** — auto-saving textbox
- **Previous Feedback** (iteration 2+) — comments from last time

The "Benchmark" tab shows the stats summary: pass rates, timing, tokens per configuration, per-eval breakdowns, analyst observations.

Navigation: prev/next buttons or arrow keys. "Submit All Reviews" saves all feedback to `feedback.json`.

### Step 5: Read the feedback

When the user says they're done, read `feedback.json`. Empty feedback means that test case looked fine; focus improvements where the user had specific complaints.

Kill the viewer when done:

```bash
kill $VIEWER_PID 2>/dev/null
```

---

## Improving the skill

This is the heart of the loop. The user has reviewed the results; now make the skill better.

### Principles

1. **Generalise from the feedback.** The skill needs to work across many prompts the user *isn't* showing you. The few examples you're iterating on are a sampling tool, not the target. If a stubborn issue appears, prefer a different metaphor or working pattern over a fiddly overfit fix or a wall of MUSTs.

2. **Keep the prompt lean.** Read transcripts, not just final outputs. If the agent is wasting time on something the skill encourages, remove that part and see what happens.

3. **Explain the why.** Capital-letter MUSTs and rigid scaffolds are a yellow flag. Reframe to explain *why* the requirement matters; agents follow reasoning more reliably than commands.

4. **Look for repeated work across test cases.** If all the test runs independently wrote a similar `create_docx.py` or `build_chart.py`, that's a strong signal to bundle it under `scripts/` and have the skill point to it. Write once, save every future invocation from reinventing it.

Draft a revision, then re-read with fresh eyes before applying.

### Iteration loop

1. Apply improvements to the skill.
2. Rerun all test cases into a new `iteration-<N+1>/` directory, including baselines. For new skills, the baseline is always `without_skill`. For improvements, choose the most informative baseline — usually the previous iteration, sometimes the original version.
3. Launch the viewer with `--previous-workspace` pointing at the previous iteration.
4. Wait for review.
5. Read the new feedback, improve, repeat.

Stop when:
- The user is happy
- All feedback is empty
- Progress stalls

For a more rigorous A/B between two versions, see `agents/comparator.md` + `agents/analyzer.md` — gives an independent agent two outputs blind and judges quality. Optional; the human review loop is usually enough.

---

## Description optimisation

The description field is the primary trigger mechanism. After creating or improving a skill, offer to optimise it.

### Step 1: Generate trigger eval queries

Create 20 queries — a mix of should-trigger and should-not-trigger:

```json
[
  {"query": "the user prompt", "should_trigger": true},
  {"query": "another prompt", "should_trigger": false}
]
```

Queries must be realistic — what a real user would actually type. Concrete and specific: file paths, job/situation context, column names, company names, URLs, a little backstory. Mix lengths; include lowercase, abbreviations, typos, casual phrasing. Edge cases beat clear-cut ones.

Bad: `"Format this data"`, `"Extract text from PDF"`, `"Create a chart"`

Good: `"ok so my boss just sent me this xlsx file (its in my downloads, called something like 'Q4 sales final FINAL v2.xlsx') and she wants me to add a column that shows the profit margin as a percentage. The revenue is in column C and costs are in column D i think"`

For **should-trigger** queries (8–10): vary phrasing and tone (formal/casual). Include cases where the user doesn't name the skill or file type but clearly needs it. Throw in some uncommon use cases and cases where this skill competes with another but should win.

For **should-not-trigger** queries (8–10): the most valuable are *near-misses* that share keywords or concepts but actually need something different. Don't make negatives obviously irrelevant — "write a fibonacci function" as a negative for a PDF skill tests nothing.

### Step 2: Review with the user

1. Read `assets/eval_review.html`.
2. Replace the placeholders:
   - `__EVAL_DATA_PLACEHOLDER__` → the JSON array (no quotes — it's a JS variable assignment)
   - `__SKILL_NAME_PLACEHOLDER__` → the skill's name
   - `__SKILL_DESCRIPTION_PLACEHOLDER__` → the current description
3. Write to a temp file (e.g. `/tmp/eval_review_<skill>.html`) and open it.
4. The user edits queries, toggles should-trigger, adds/removes entries, then clicks "Export Eval Set".
5. The file downloads to `~/Downloads/eval_set.json`. Check Downloads for the most recent (e.g. `eval_set (1).json`) if there are multiple.

This step matters — bad eval queries lead to bad descriptions.

### Step 3: Run the optimisation loop

Tell the user this will take some time and you'll check on it periodically.

```bash
python -m scripts.run_loop \
  --eval-set <path-to-trigger-eval.json> \
  --skill-path <path-to-skill> \
  --model <model-id> \
  --max-iterations 5 \
  --verbose
```

Use the model the current session is running on so triggering matches the user's experience.

The loop splits the eval set 60/40 train/held-out, evaluates the current description (3 runs per query for a stable trigger rate), proposes improvements based on failures, re-evaluates, and iterates up to 5 times. When it finishes, it opens an HTML report and emits JSON with `best_description` (selected by *test* score to avoid overfitting). Apply that to the SKILL.md frontmatter and show the user before/after with scores.

### How triggering works

Skills appear in the agent's available-skills list with name + description, and the agent decides whether to consult one based on that description. Agents typically only consult skills for non-trivial tasks; simple one-step queries ("read this PDF") may not trigger a skill even with a perfect description, because the agent can handle them directly. So eval queries should be substantive enough that consulting a skill is genuinely useful — trivial queries are poor tests regardless of description quality.

---

## Capability adaptations

Adapt to the agent runtime:

- **No subagents** — run test cases inline, one at a time. Skip baseline runs and quantitative benchmarking; rely on qualitative human review. (If subagents exist but timeouts are a problem, run in series rather than parallel.)
- **No display / headless** — pass `--static <output_path>` to `generate_review.py` instead of running a server; share the HTML path with the user. Feedback downloads as `feedback.json`.
- **No CLI for the description optimiser** — `scripts/run_loop.py` requires a CLI tool to invoke the model. Skip if unavailable.
- **No `present_files` tool** — skip the packaging step.
- **Editing an installed (read-only) skill** — copy to a writable location (e.g. `/tmp/<skill-name>/`) before editing; package from the copy. Preserve the original directory name and `name` frontmatter unchanged (e.g. `research-helper`, not `research-helper-v2`).

## Packaging

If the `present_files` tool is available:

```bash
python -m scripts.package_skill <path/to/skill-folder>
```

Direct the user to the resulting `.skill` file path so they can install it.

---

## Reference files

- `agents/grader.md` — evaluating assertions against outputs
- `agents/comparator.md` — blind A/B comparison between two outputs
- `agents/analyzer.md` — analysing why one version beat another
- `references/schemas.md` — JSON structures for `evals.json`, `grading.json`, `benchmark.json`, etc.
