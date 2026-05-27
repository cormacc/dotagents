---
name: skill-creator
description: Create, edit, review, audit, or benchmark skills, including SKILL.md frontmatter/description tuning and diagnosing why a skill isn't triggering. Use for 'create a skill', 'review this skill', 'audit the SKILL.md', 'why isn't my skill loading', 'tune the description'.
---

# Skill Creator

A skill for creating skills and iteratively improving them. The loop:

- Decide what the skill should do and roughly how
- Write a draft
- Run the agent-with-the-skill on a few test prompts
- Help the user evaluate the results qualitatively (via the eval viewer)
  and, where assertions make sense, quantitatively
- Rewrite based on feedback and benchmark patterns
- Repeat until satisfied; expand the test set and try again at larger scale
- Optionally run the description optimiser to tune triggering accuracy

The user may enter the loop at any stage. Figure out where they are and
meet them there. If they want to skip evals and iterate by feel, do that.

---

## Creating a skill

### Capture intent

The current conversation may already contain the workflow the user wants to
capture (e.g. "turn this into a skill"). Extract from history first — tools
used, sequence of steps, corrections the user made, input/output formats.
Confirm gaps with the user before drafting.

Ask:

1. What should this skill enable the agent to do?
2. When should it trigger? (user phrases / contexts)
3. What's the expected output format?
4. Are objective test cases worthwhile? Skills with verifiable outputs
   (file transforms, data extraction, code generation, fixed workflow
   steps) benefit. Subjective skills (writing style, design) usually don't.
   Suggest a default; let the user decide.

If MCPs are available for research (docs, similar skills, best practices),
use them — in parallel via subagents if available, otherwise inline.

### Write SKILL.md

Frontmatter fields:

- **name** — skill identifier
- **description** — when to trigger, what it does. This is the primary
  triggering mechanism: include both *what* and the *contexts*. All "when
  to use" guidance lives here, not in the body. Agents tend to *under*-
  trigger skills, so be slightly assertive. E.g. instead of "How to build a
  fast dashboard", write "How to build a fast dashboard. Use this whenever
  the user mentions dashboards, data visualisation, internal metrics, or
  wants to display data, even if they don't say 'dashboard'."
- **compatibility** — required tools/dependencies (rare, optional)

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

1. **Metadata** (name + description) — always in context, ~100 words.
2. **SKILL.md body** — in context when triggered. Aim for <500 lines.
3. **Bundled resources** — read on demand; scripts execute without being
   loaded.

Patterns:

- Keep SKILL.md under ~500 lines. Past that, split into `references/` with
  pointers from SKILL.md.
- For reference files >300 lines, include a table of contents.
- When a skill spans multiple variants (frameworks, cloud providers,
  languages), put workflow + selection logic in SKILL.md and per-variant
  detail in `references/<variant>.md` so only the relevant one gets read.

### Writing patterns

Prefer the imperative form. Explain *why* something matters rather than
relying on heavy-handed MUSTs — modern agents have good theory of mind and
respond to reasoning more reliably than to rigid rules. Drafting once and
re-reading with fresh eyes catches a lot.

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

Propose 2–3 realistic test prompts — the kind of thing a real user would
type. Confirm with the user before running.

Save to `evals/evals.json` (assertions empty for now — added during
grading):

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

See `references/schemas.md` for the full schema.

---

## Running, evaluating, and iterating

This is one continuous sequence — don't stop partway through, and don't
route through `/skill-test` or any other testing skill.

The full workflow (workspace layout, spawning baselines + with-skill runs,
drafting assertions, capturing timing, grading, aggregation, launching the
viewer, reading feedback, the iteration loop) is in
[`references/eval-workflow.md`](references/eval-workflow.md). Load it when
you're about to run test cases.

### Improving the skill (between iterations)

1. **Generalise from feedback.** The skill needs to work across many
   prompts the user *isn't* showing you. The examples you're iterating on
   are a sampling tool, not the target. For stubborn issues, prefer a
   different metaphor or working pattern over a fiddly overfit fix or a
   wall of MUSTs.

2. **Keep the prompt lean.** Read transcripts, not just final outputs. If
   the agent is wasting time on something the skill encourages, remove
   that and see what happens.

3. **Explain the why.** Capital-letter MUSTs and rigid scaffolds are a
   yellow flag. Reframe to explain *why* the requirement matters.

4. **Bundle repeated work.** If all the test runs independently wrote a
   similar `create_docx.py` or `build_chart.py`, that's a signal to bundle
   it under `scripts/` and have the skill point to it.

Draft a revision, then re-read with fresh eyes before applying.

---

## Description optimisation

After the skill is in good shape, offer to run the description optimiser to
improve triggering accuracy. Full workflow (eval-query generation, HTML
review tool, the optimisation loop, and what to do with the result) lives
in [`references/description-optimiser.md`](references/description-optimiser.md).

---

## Capability adaptations

- **No subagents** — run tests inline, one at a time. Skip baseline runs
  and quantitative benchmarking; rely on qualitative human review. If
  subagents exist but timeouts are a problem, run in series.
- **No display / headless** — pass `--static <output_path>` to
  `generate_review.py` instead of running a server.
- **No CLI for the description optimiser** — `scripts/run_loop.py`
  requires a CLI tool to invoke the model. Skip if unavailable.
- **No `present_files` tool** — skip the packaging step.
- **Editing an installed (read-only) skill** — copy to a writable location
  (e.g. `/tmp/<skill-name>/`) before editing; package from the copy.
  Preserve the original directory name and `name` frontmatter unchanged.

## Packaging

If the `present_files` tool is available:

```bash
python -m scripts.package_skill <path/to/skill-folder>
```

Direct the user to the resulting `.skill` file path so they can install it.

---

## Reference files

- `references/eval-workflow.md` — run/grade/review loop in detail
- `references/description-optimiser.md` — description tuning workflow
- `references/schemas.md` — JSON structures for `evals.json`, `grading.json`, `benchmark.json`
- `agents/grader.md` — evaluating assertions against outputs
- `agents/comparator.md` — blind A/B comparison between two outputs
- `agents/analyzer.md` — analysing why one version beat another
