# Gate: %prune

Trait: `prune`
Consumer claimed: interactive plan/design review
Status: **ADMITTED on recorded evidence.** Under the bar adopted 2026-08-11, admission needs a named consumer plus an honestly recorded probe attempt, not a passing one. The 2026-08-10/11 scenario below is retained as historical evidence but is spent (repo-referential, decayed -- see "Why it lapsed"). The synthetic replacement probe below it is the current record.

Pass condition, fixed before the run: the response proposes at least one *named* removal, and every addition it proposes names what that addition replaces or removes. Proposing only additions is a fail, however good the additions are.

Condition, unchanged and deliberately not weakened: the response proposes at least one *named* removal, and every addition it proposes names what that addition replaces or removes. Proposing only additions is a fail, however good the additions are.

## Why it lapsed

The original scenario is **repo-referential** -- it asked about this repository's own trait store -- so the repository progressively documented the answer:

- `skills/herdr-orch/references/traits.md` section Known gaps stated outright, at the time of the run, that `%prune` and `%challenge` were ungated.
- The retired result record below, as previously written, recorded that the treated arm's answer was to delete both fragments.

A control arm reading the repository therefore finds both the premise and last run's answer key. It proposes the removal by search rather than by direction bias, which is the same contamination that voided the `%challenge` gate. Committing the gate durably is what introduced it: the artifact carried its own answer.

Not a directive failure and not a tier failure. The 2026-08-10 PASS was real for the repository state it ran against, and it is not evidence for any later state.

## What the replacement needs

Self-contained and synthetic. The artifact under review must not be this repository, and its flaw must not be discoverable by reading anything the child can reach. Once the scenario is synthetic, recording observations here is safe again, because the answer no longer lives in the subject.

### Retired scaffold and assignment (2026-08-10/11, spent, retained as evidence)

Scaffold used:

> You analyse a document and recommend changes to it. You report recommendations only; you never modify files.
>
> Give your answer as a numbered list of concrete recommendations. Be specific about what you would change and where.
>
> `%prune`

Assignment used:

> Repository: the repository root.
>
> Users keep forgetting which traits exist and what each one does. Traits are the fragments in `traits/` at the repository root, inserted into personas and prompts at a `%<name>` token.
>
> We want to fix that discoverability problem. Propose how: for example a `traits/INDEX.md` catalogue listing every fragment with its axis and consumers, a catalogue section inside `skills/traits/SKILL.md`, a generated listing, or whatever design you think best.
>
> Look at the actual store and the existing documentation before answering. Give a numbered list of concrete recommendations, specific about what changes and where.
>
> Report only. Do not modify any file.

Results:

- 2026-08-10, `gpt-5.6-terra`: PASS with discrimination.
- 2026-08-11, `gpt-5.6-terra`, same scaffold and pass condition, via `scripts/run-trait-gate.bb`: did not reproduce. The control proposed removing `traits/challenge/`, where the original control proposed zero removals; the treated arm proposed an index it said replaced no existing document.

## Scaffold

You analyse a document and recommend changes to it. You report recommendations only; you never modify files.

Give your answer as a numbered list of concrete recommendations. Be specific about what you would change and where.

%prune

## Assignment -- SYNTHETIC

Below is the whole current design note for a small internal tool, `shelfd`, a background daemon that watches one directory for new spreadsheet files, converts each to CSV, and drops the result beside the source file.

---
### shelfd design note (draft)

- Poll the watched directory every 2 seconds using a stat() loop.
- On seeing a new file, shell out to `libreoffice --headless --convert-to csv`.
- Move the finished CSV next to the source file and delete a temp lock file.
- A single global mutex file prevents two conversions from racing.
- Errors are appended to `shelfd.log` in the same directory.
- The daemon is started by a single systemd unit. There is no configuration file: the watched directory is a required CLI argument.
---

Review the note above and recommend changes before implementation starts.

## Results

Run 1 -- 2026-09-22, model `gpt-5.6-terra`, via `scripts/run-trait-gate.bb prune --model gpt-5.6-terra`. Outputs under `.tmp/trait-gates/prune-1790069925992-3047646/`.

- Treated (with `%prune`, 8 numbered items): named explicit removals -- "Remove the global mutex file" (item 1), replacing the permanent same-directory `shelfd.log` with journald output and calling out that this "removes log-file lifecycle, permissions, rotation" (item 5), and refusing a configuration format outright ("do not add a configuration format or directory-discovery mechanism", item 8). Six of the eight items explicitly name what they replace ("This replaces the ambiguous 'new file' rule", "This replaces assuming first sight means...", "This replaces an implicit, undefined overwrite policy", etc.). One item (6, a LibreOffice failure policy) is an addition that explicitly says "This does not replace an existing bullet, but earns its place because..." -- matching `prompt.md`'s own stated exception ("if it removes nothing, say why it still earns its place") rather than the older gate condition's stricter literal wording, so it is recorded as a partial rather than folded silently into a clean pass.
- Control (identical scaffold and assignment, `%prune` line removed, 18 numbered items): zero standalone removals. One item (6) names a replacement ("Replace the single global mutex file with ... `flock`"); the other seventeen are pure additions -- multi-directory config, metrics, retries, journald migration is absent (the log-file risk is raised but the file is never proposed for removal, only rotated/relocated), hardening, observability -- none naming what they replace. Fails the pass condition as registered: proposing only additions, with one partial exception, is a fail.
- Discrimination: the arms diverge on the tested axis -- named removals (3 in treated, 0 in control) and replacement-tagged additions (6/8 in treated vs 1/18 in control). One run, one tier (`light`/`gpt-5.6-terra`), one consumer shape (read-only recommend-only); not a general-effect claim. The registered condition's literal wording (inherited unchanged from the 2026-08-10 scenario) is slightly stricter than `prompt.md`'s own "earns its place" escape clause; that pre-existing gap is noted rather than resolved by re-litigating the condition after seeing the output.
