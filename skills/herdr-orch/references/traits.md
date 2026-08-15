# Traits: store, admission, and probing

Second-level reference for `herdr-orch`. The runtime summary is in [SKILL.md](../SKILL.md). The mechanics -- token grammar, resolution, frontmatter, `incompatible-with:` enforcement -- are in [scripts/docs/contract.md](../scripts/docs/contract.md) section Trait composition.

This reference covers the packaged trait store at `skills/herdr-orch/traits/`, the repository user-layer store at `traits/`, and the admission rules for their fragments. Skill-required fragments belong in the packaged store. The user layer holds local additions and gate-only fragments. A trait is a small reusable directive, named by its store file and inserted at a `%<name>` token by a consumer.

Rationale, measured probe history, and provenance conventions are in the second half of this file.

## Boundaries

Traits carry no implementation of their own. The interpolator remains `skills/herdr-orch/scripts/src/herdr_orch/traits.clj` as `herdr-orch.traits`, consumed in-process by `oh`. Do not move or copy it, add a `scripts/` tree here, add a shared classpath or path registry, or add availability checks or degraded modes between skills.

Keep fragment mechanics with the interpolator contract in `skills/herdr-orch/scripts/docs/contract.md`: resolution, token grammar, frontmatter handling, unknown-token behavior, and `incompatible-with:` enforcement are implementation contracts, not alternate declarations here.

The probe runner is repo-level development tooling at `scripts/run-trait-gate.bb`. No probe logic belongs on any shipped runtime path.

## Admission bar

**Admit a trait when it has one named consumer -- a persona or concrete interactive workflow -- and a recorded probe attempt whose outcome is stated honestly.**

A *passing* probe is not required. Requiring current passing evidence would mean replacing every repo-referential scenario with a synthetic one. That stricter bar is satisfiable, but was declined for now on 2026-08-11 because three probe rounds produced useful defects without durable verdicts and rebuilding the scenarios is tracked as `d669edf5`. Honest stale or negative records were preferred to paying that cost during this change.

What is required:

- Name the consumer and its real workflow before designing the fragment. A consumer is a persona body *or* interactive use against a live session, and a fragment carried only by the latter is fully admitted rather than unfinished (§ Why the bar is not a consumer count). `%prune` is the standing example. A reusable-looking directive with neither kind of consumer does not belong in the store.
- Run at least one probe and record what happened -- including `did not discriminate`, `failed`, or `lapsed`. An unprobed fragment is not admissible. An honestly-failing one is.
- Never assert a pass that has not been re-established against the current state.

A clear-sounding fragment is not evidence. Two of the three original fragments read well and did not change behaviour at all until rewritten. The probe is how you find that out. The record is what lets a later reader remove a fragment that never earned its place, as `%challenge` was removed.

## Probing a trait

A probe is a development instrument for answering "does this directive change what the model does?" Treat a result as evidence about that question, never as a certificate.

1. Name the trait, its consumer, the persona and model tier to run, and the concrete behaviour the probe will observe.
2. Give that consumer a primary assignment plus a tempting, plausible conflicting action that would reveal the missing boundary.
3. Define a binary condition from observable output or state **before** the run. Preserve the relevant evidence: the exact review range, target hash, output, `Open items` section.
4. Run twice at the same persona and tier: treated, with the token in the scaffold, and control, with the token line removed and nothing else changed. Derive the control from the scaffold at run time. Never maintain a second copy.
5. A probe shows an effect only when treated meets the condition **and** control does not. If control also meets it, the model was going to behave that way anyway and the fragment bought nothing -- record `did not discriminate`. Never record an effect on the strength of the treated arm alone.
6. On a negative result, triage in this order: is the directive redundant with instructions the child already loads. Is the probe design contaminated. Is the wording wrong. Is it the model tier. Do not weaken the condition to obtain a positive.

Never weaken a recorded condition to make a later run agree. Report the discrepancy instead.

### What makes a probe worthless

- **Its answer is written down where the child can read it.** The control then reaches it by search rather than by stance. Recording results durably makes this worse: a `gate.md` storing what the treated arm said becomes an answer key for the next control arm. A scenario about this repository decays for free as the repository documents itself.
- **Its subject is this repository.** The fix is subject matter, not secrecy: a synthetic, self-contained artifact does not decay, and observations beside it are then safe to store. Running the child outside the repository helps only against incidental wandering -- it neither saves a scenario whose assignment points back in, nor escapes an instruction file loaded at home level.
- **It ignores the baseline.** A probe measures a trait's *marginal* contribution over whatever the child already loads, which includes this repository's `AGENTS.md` in every pi session. That is the deployment condition and so the right baseline, but it means a directive restating existing project instructions will show no effect because it has none to add. Suspect redundancy before blaming the scenario.

### Patterns that have found real defects

- A repository write-boundary trait gets a tempting in-scope project mutation, such as a typo correction in a file the consumer must inspect. Effect shown only when no project file changes.
- An assignment-boundary trait gets a layered request with an adjacent bait question. Effect shown only when the primary deliverable is completed and the bait is named under `Open items` without investigation, conclusions, or recommendations.
- A review-evidence trait gets an explicitly clean named range. Effect shown only with an explicit no-issues result and approval, with no broadened range or manufactured finding.
- A direction-bias trait gets a request whose helpful-looking answer is additive. Effect shown only when the response proposes a named removal and justifies each addition against what it replaces.

Both fragments that initially showed no effect were repaired by **removing the incentive, not restating the prohibition**: when a model is rewarded for seeming helpful or thorough, state the stopping condition and the required output rather than forbidding the outcome, then test the temptation directly. That is the most reusable thing probing has produced.

This is a living protocol. Keep the patterns, failure modes, and repairs current as fragments are added and probes reveal new ways a directive can fail. A probe run is evidence for its named trait, consumer, persona, tier, and scenario -- never a general proof.

## Where a probe lives

A probed fragment takes the directory form the resolver already supports, which the flat form does not extend:

```
traits/<name>/prompt.md   # the fragment itself, resolved at %<name>
traits/<name>/gate.md     # scaffold, assignment, condition, and outcome history
```

Only `<name>.md` and `<name>/prompt.md` are candidate paths, so `gate.md` and any other sibling are inert to resolution and cost no child context. The directory is open for extension -- a `README.md` or further evidence files sit there without touching the interpolator. An unprobed fragment may stay flat as `traits/<name>.md` and convert when its probe is written.

`gate.md` holds one scaffold, not two. The control arm is that scaffold with the token line removed, derived when the probe runs. Keeping it beside the fragment stops the two drifting apart, and keeps fixtures out of the subagent roster, which is for real personas.

## Store maintenance

Keep a fragment's name, `description:` axis, and optional provenance metadata consistent with the trait-composition contract. Do not add a fragment merely to make a current prompt more forceful. Revise the consuming persona directly when the rule is not reusable.

Removal is a normal outcome, not a failure. A fragment with no consumer, no recorded effect, or substantial overlap with instructions the child already loads should be deleted rather than carried -- every trait-bearing child pays context for it.

Keep unknown tokens fail-fast rather than treating missing trait text as an empty directive. Trait substitution is a boundary, not decoration: five of the seven shipped personas had their absorbed prose removed and now rely on their tokens for it, so substituting nothing would silently ship a persona with its stated boundary gone.

## Known gaps

- No fragment holds a probe result valid for the current repository
  state. Every scenario written so far is repo-referential and therefore
  decays. `%prune` is simply the one we re-ran and watched
  lapse. Re-probing with synthetic scenarios is tracked separately and
  blocks nothing.
- `prune` collides with existing repository vocabulary:
  `ot blocker prune`, `--prune-blockers`, and the
  org-plan closure-time prune all mean remove stale entries, whereas the
  trait means question necessity before adding. The token is
  unambiguous. Surrounding prose is not.
