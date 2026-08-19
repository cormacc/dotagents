<!-- Round 2 staging notes for task 2b46d2c7. Authored: 2026-08-19. -->

# Round 2 staging notes

Scope: this task stages round 2 inputs only. It does not run a candidate
and does not score anything. See `rubric.md` for the scoring contract and
`harness.md` for the harness verification.

## Samples

| Sample | Path | Prose class | Word count (body) |
|---|---|---|---|
| Strict | `samples/strict-dirge-plugin-model.md` | Strict operational text (agent-consumed skill instructions) | 532 |
| Explanatory A | `samples/explanatory-tighten-org-tasks.org` | Explanatory prose (design change-record narrative) | 536 |
| Explanatory B | `samples/explanatory-consolidate-policy.org` | Explanatory prose (design change-record narrative) | 419 |

Word counts above exclude the provenance header line and its trailing
blank line, matching the round 1 convention (round 1's `Implementation`
section reports 580/437/428, which are likewise body-only; the files'
total `wc -w` including the header is a few words higher).

## Provenance evidence, per sample

Each sample must be prose the `asd-ste100` mandate (added `home/AGENTS.md`
`## ASD-STE100`, commit `5bad753`, 2026-08-14 18:35:55+01:00) never
touched. Two lines of evidence, per the assignment: the vendored label
where it applies, and a git-history check in every case.

**Strict -- `skills/dirge/SKILL.md` (lines 25-95).**
- Vendored-label evidence: `skills/README.org` `** Vendored` table lists
  `dirge` by name, purpose, and (blank) source, alongside `asd-ste100`,
  `find-skills`, `herdr`, `gitlab-cli-skills`, and `skill-creator`. All six
  are upstream text by construction per that section's own text.
- Git-history evidence: `git log -1 -- skills/dirge/SKILL.md` shows the
  file's only commit is `fee2327` (2026-06-27 10:01:13+01:00), "feat: Added
  basic setup for dirge" -- before the mandate commit `5bad753` and never
  touched since. The repository never rewrote this file under the mandate.
- Excerpt: `## Plugin model (essentials)` through the closing porting note.
  Skips the frontmatter, the `# dirge` title, and `## Docs location`
  (pointers to an external checkout, not excerpt-worthy prose) to stay in
  the target word range while keeping two full, self-contained sections.

**Explanatory A -- `design/log/2026-05-29-tighten-up-org-tasks-skill-to-improve-us.org` (lines 6-56).**
- Not vendored; this is a first-party design record, so the vendored-label
  check does not apply. Git-history evidence is the sole check, per the
  assignment's "use both [when applicable]" instruction.
- Git-history evidence: `git log -1 -- <path>` shows the file's only commit
  is `e986200` (2026-05-29 14:51:59+01:00) -- before the mandate commit and
  never touched since.
- Excerpt: `* Intent` through `** Follow-ups`, i.e. the whole narrative
  header before `* Plan`'s task checklist begins. Chosen over a shorter
  slice because the full header reads as one coherent unit and lands at
  536 words without needing a mid-section cut.

**Explanatory B -- `design/log/2026-06-27-org-tasks-consolidate-policy-into-ot.org` (lines 10-48).**
- Not vendored; first-party design record. Git-history evidence is the
  sole check.
- Git-history evidence: `git log -1 -- <path>` shows the file's last
  commit is `d95b37d` (2026-07-04 18:56:42+01:00), "feat(org-plan,
  org-tasks): add optional #+SPEC: spec model" -- before the mandate
  commit and never touched since.
- Excerpt: `* Intent` through the end of `** Decisions`, stopping before
  the terser `** Shipped` bullet list of file-level changes. Chosen to keep
  the excerpt's register close to explanatory narrative rather than a
  changelog of identifiers.

### Positive control for the git-history check

An empty or negative git-history result is not evidence by itself (per the
repository's controls rule), so each negative check above is paired with a
positive control confirming the same check correctly flags known-touched
files:

| File | Last commit | Date | Relative to mandate (2026-08-14 18:35:55+01:00) |
|---|---|---|---|
| `home/AGENTS.md` | `f33720c` | 2026-08-18 10:46:52+01:00 | After -- correctly flagged as touched |
| `design/log/2026-08-18-correct-known-agent-guidance-defects.org` (round 1's own compromised sample source) | `f33720c` | 2026-08-18 10:46:52+01:00 | After -- correctly flagged as touched |
| `skills/dirge/SKILL.md` (this task's strict sample) | `fee2327` | 2026-06-27 10:01:13+01:00 | Before -- correctly flagged as untouched |
| `.../2026-05-29-tighten-up-org-tasks-skill...org` (this task's explanatory-A sample) | `e986200` | 2026-05-29 14:51:59+01:00 | Before -- correctly flagged as untouched |
| `.../2026-06-27-org-tasks-consolidate-policy...org` (this task's explanatory-B sample) | `d95b37d` | 2026-07-04 18:56:42+01:00 | Before -- correctly flagged as untouched |

The control confirms the probe (`git log -1 --format=%ai -- <path>`
compared against the mandate commit timestamp) distinguishes touched from
untouched files correctly, rather than silently returning the same answer
regardless of input.

## Coverage of strict operational text and explanatory prose

Both classes are covered: one strict sample (dimension 1) and two
explanatory samples (dimension 2), matching the round 1 rubric's
per-dimension sample count. In-repo strict operational text outside the
mandate was not scarce in the end: the vendored skill set
(`skills/README.org` `** Vendored`) offers five candidates besides
`asd-ste100` itself (`dirge`, `find-skills`, `herdr`, `gitlab-cli-skills`,
`skill-creator`), all predating the mandate by weeks to months, and `dirge`
gave a clean 400-600-word contiguous excerpt on the first attempt. This
staging task did not need the scarcity fallback the assignment anticipated;
recorded here so the run task does not need to re-derive that this
question was checked.

The two explanatory samples come from design-record narrative rather than
a skill README, because no in-repo skill README predates the mandate by a
safe margin: `skills/README.org` itself and `skills/herdr-orch/README.org`
were both last edited after 2026-08-14, and the two README files that do
predate it (`skills/gitlab-cli-skills/README.md`, `skills/retro/README.md`)
are far too short (123 and 3 words) to extract a 400-600-word sample from.
Round 1's own rubric table scores both README and design-record prose
under the same "explanatory prose" dimension, so this substitution stays
inside the dimension's own definition; it is recorded here rather than
left implicit, in case a later round wants a README-flavoured sample
specifically.

## Anti-criterion check

"Must not: include any round 2 sample that was written or rewritten under
the incumbent mandate." All three samples were last touched by commits
that predate `5bad753` (the mandate's own landing commit); see the
per-sample git-history evidence above.

## No dictionary reproduction

Nothing staged in this task (rubric, samples, or this note) reproduces the
ASD approved-word dictionary. The three samples are excerpts of unrelated
skill and record prose; the rubric text is original.
