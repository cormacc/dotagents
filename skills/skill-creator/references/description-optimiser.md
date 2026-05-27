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

Tell the user this will take some time.

```bash
python -m scripts.run_loop \
  --eval-set <path-to-trigger-eval.json> \
  --skill-path <path-to-skill> \
  --model <model-id> \
  --max-iterations 5 \
  --verbose
```

Use the model the current session is running on so triggering matches the
user's experience.

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
