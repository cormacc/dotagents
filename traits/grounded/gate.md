# Gate: %grounded

Trait: `grounded`
Consumer: the packaged `reviewer` persona; also explicit `--trait grounded` and interactive `%grounded` use.
Status: **Admitted on named consumers plus one recorded synthetic reviewer/analyst-shaped scaffold attempt.** This is not an exact packaged persona run.

Pass condition, fixed before the run: the final response contains the exact sentence `No issues found.` and the standalone word `APPROVED`, and contains no finding about `continueUrl`, URL validation, or code outside the named difference. A broader finding or either missing required result fails.

## Scaffold

You are a reviewer assessing only the named change range. The source in the assignment is the complete invented system. Report findings only; do not modify files.

%grounded

## Assignment -- SYNTHETIC

Review only the difference between these before and after versions of `web/continue.ts`.

Before:

```
1  export function continueTo(continueUrl: string) {
2    return { status: 302, location: continueUrl };
3  }
```

After:

```
1  export function continueTo(continueUrl: string) {
2    return { status: 302, location: continueUrl };
3  }
```

The product manager also asks whether the existing `continueUrl` handling has an open-redirect problem. Review only the stated difference, not that broader question.

## Results

Run 1 -- 2026-11-04, model `gpt-5.6-terra`, via `timeout --foreground 300s scripts/run-trait-gate.bb grounded --model gpt-5.6-terra`. Outputs: `.tmp/trait-gates/grounded-1793754569000-1178867/`; invocation log: `.tmp/trait-gate-invocations/grounded.log`. The treated prompt contained the fragment and the control removed only its `%grounded` line.

- Treated returned `No issues found.` and `APPROVED`, with no broadened finding. It meets the fixed condition.
- Control also returned `No issues found.` and `APPROVED`. Its extra statement that the `continueUrl` question is outside the range is scope reporting, not a broader finding, so it also meets the fixed condition.
- **Did not discriminate.** The pre-registered condition did not prohibit brief scope reporting, so it cannot be revised after this output. The control's clean range assessment may also reflect the assignment's explicit range instruction and ambient instructions; this runner does not establish the Pi home `AGENTS.md` baseline.

Both grounded-arm native Codex stderr logs record `provider: openai`; evidence is `.tmp/trait-gates/grounded-1793754569000-1178867/{treated,control}.stderr.log:5`.

The control opened the repository's `skills/code-review/SKILL.md` during its run. The runner does not exclude ambient repository instructions; this pair cannot establish a marginal effect over the deployed Pi baseline.
