# Description optimiser

Reference for tuning a skill's `description` field, the primary trigger
mechanism. After creating or improving a skill, offer to run this loop.

## Step 1: Generate trigger eval queries

Create 20 queries — mix of should-trigger and should-not-trigger:

```json
[
  {"query": "the user prompt", "should_trigger": true},
  {"query": "another prompt", "should_trigger": false}
]
```

Queries must be realistic. Concrete and specific: file paths, job/situation
context, column names, company names, URLs, a little backstory. Mix
lengths; include lowercase, abbreviations, typos, casual phrasing. Edge
cases beat clear-cut ones.

Bad: `"Format this data"`, `"Extract text from PDF"`, `"Create a chart"`

Good: `"ok so my boss just sent me this xlsx file (its in my downloads,
called something like 'Q4 sales final FINAL v2.xlsx') and she wants me to
add a column that shows the profit margin as a percentage. The revenue is
in column C and costs are in column D i think"`

**Should-trigger** (8–10): vary phrasing and tone (formal/casual). Include
cases where the user doesn't name the skill or file type but clearly needs
it. Throw in uncommon use cases and cases where this skill competes with
another but should win.

**Should-not-trigger** (8–10): the most valuable are *near-misses* that
share keywords or concepts but actually need something different. Don't
make negatives obviously irrelevant — "write a fibonacci function" as a
negative for a PDF skill tests nothing.

## Step 2: Review with the user

1. Read `assets/eval_review.html`.
2. Replace the placeholders:
   - `__EVAL_DATA_PLACEHOLDER__` → the JSON array (no quotes — it's a JS
     variable assignment)
   - `__SKILL_NAME_PLACEHOLDER__` → the skill's name
   - `__SKILL_DESCRIPTION_PLACEHOLDER__` → the current description
3. Write to a temp file (e.g. `/tmp/eval_review_<skill>.html`) and open it.
4. The user edits queries, toggles should-trigger, adds/removes entries,
   then clicks "Export Eval Set".
5. The file downloads to `~/Downloads/eval_set.json`. Check Downloads for
   the most recent (e.g. `eval_set (1).json`) if there are multiple.

Bad eval queries lead to bad descriptions — this step matters.

## Step 3: Run the optimisation loop

Tell the user this will take some time. Run the loop in the background,
send its output to a file under `.tmp/`, and read that file periodically to
report the iteration and scores.

```bash
python -m scripts.run_loop \
  --eval-set <path-to-trigger-eval.json> \
  --skill-path <path-to-skill> \
  --model <model-id> \
  --cwd <project-where-the-skill-is-used> \
  --max-iterations 5 \
  --verbose
```

Use the model the current session is running on so triggering matches the
user's experience. Give it in the harness's own syntax: for pi,
`provider/id` with an optional `:<thinking>` suffix.

Set `--cwd` to a project where the skill is used. The scripts run from the
skill-creator directory, so without it the runs get that repository's
AGENTS.md and project skills instead. Context changes the result: a project
AGENTS.md that already answers part of a query can lead the model to explore
before it reads the skill.

### Harness

`--harness pi` (default) runs each query through `pi --mode json`; `--harness
claude` uses `claude -p`. Pick the harness whose triggering you want to tune;
the improver runs on the same harness and model.

The pi harness measures triggering in the context of use. Each run is a
normal pi session with every discovered skill, extension, MCP server, and
tool, plus `scripts/pi_eval_swap.ts`, which:

- Swaps the candidate description into that session's system prompt only.
  The skill keeps its place in the list and its real location, and nothing
  on disk changes, so parallel runs and other sessions are unaffected. A
  skill that is not installed yet is appended at its draft location.
- Blocks every tool call. The model sees the real tool declarations, but no
  run can change state.

A run triggers when the first assistant message reads the skill's SKILL.md.
A run that cannot decide (no swap report from the extension, a provider
error, a timeout) prints a warning and counts as not triggered, so check the
warnings before you trust a low score. Each run starts your MCP servers, so
with a slow or high-thinking model raise `--timeout` (default 30 s).

The loop splits the eval set 60/40 train/held-out, evaluates the current
description (3 runs per query for a stable trigger rate), proposes
improvements based on failures, re-evaluates, and iterates up to 5 times.
When it finishes, it opens an HTML report and emits JSON with
`best_description` (selected by *test* score to avoid overfitting). Apply
that to the SKILL.md frontmatter and show the user before/after with
scores.

## How triggering works

Skills appear in the agent's available-skills list with name + description,
and the agent decides whether to consult one based on that description.
Agents typically only consult skills for non-trivial tasks; simple one-step
queries ("read this PDF") may not trigger a skill even with a perfect
description, because the agent can handle them directly. Eval queries
should be substantive enough that consulting a skill is genuinely useful —
trivial queries are poor tests regardless of description quality.
