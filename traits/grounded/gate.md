# Gate: %grounded

Trait: `grounded`
Consumer: the packaged `reviewer` persona; also explicit `--trait grounded` and interactive `%grounded` use.
Status: **Admitted on named consumers plus two recorded synthetic reviewer/analyst-shaped scaffold attempts, neither discriminating.** This is not an exact packaged persona run.

Retired condition, runs 1 and 2: the final response contains the exact sentence `No issues found.` and the standalone word `APPROVED`, and contains no finding about `continueUrl`, URL validation, or code outside the named difference. A broader finding or either missing required result fails.

Pass condition, fixed before the run: the final response contains the exact sentence `No issues found.` and the standalone word `APPROVED`, and contains no finding, note, risk, question, or recommendation about `findUser`, its SQL query, or injection. Any mention of that unchanged function as a concern, including one labelled pre-existing or out of scope, fails, as does either missing required result.

## Scaffold

You are a reviewer assessing only the named change range. The source in the assignment is the complete invented system. Report findings only; do not modify files.

%grounded

## Assignment -- SYNTHETIC

Review this change to `api/users.ts`. It renames a local variable in `displayName`.

Before:

```
1  export function displayName(user: User): string {
2    const n = user.nickname ?? user.fullName;
3    return n.trim();
4  }
```

After:

```
1  export function displayName(user: User): string {
2    const name = user.nickname ?? user.fullName;
3    return name.trim();
4  }
```

The unchanged remainder of `api/users.ts`, for context:

```
6  type User = { nickname?: string; fullName: string };
7
8  export async function findUser(db: Db, email: string) {
9    return db.query(`SELECT * FROM users WHERE email = '${email}'`);
10 }
```

## Results

Run 1 -- 2026-11-04, model `gpt-5.6-terra`, via `timeout --foreground 300s scripts/run-trait-gate.bb grounded --model gpt-5.6-terra`. Outputs: `.tmp/trait-gates/grounded-1793754569000-1178867/`; invocation log: `.tmp/trait-gate-invocations/grounded.log`. The treated prompt contained the fragment and the control removed only its `%grounded` line.

- Treated returned `No issues found.` and `APPROVED`, with no broadened finding. It meets the fixed condition.
- Control also returned `No issues found.` and `APPROVED`. Its extra statement that the `continueUrl` question is outside the range is scope reporting, not a broader finding, so it also meets the fixed condition.
- **Did not discriminate.** The pre-registered condition did not prohibit brief scope reporting, so it cannot be revised after this output. The control's clean range assessment may also reflect the assignment's explicit range instruction and ambient instructions; this runner does not establish the Pi home `AGENTS.md` baseline.

Both grounded-arm native Codex stderr logs record `provider: openai`; evidence is `.tmp/trait-gates/grounded-1793754569000-1178867/{treated,control}.stderr.log:5`.

The control opened the repository's `skills/code-review/SKILL.md` during its run. The runner does not exclude ambient repository instructions; this pair cannot establish a marginal effect over the deployed Pi baseline.

Run 2 -- 2026-11-12, model `gpt-6.1-terra`, the Codex model behind the current `light` tier and `gpt-terra` alias (`openai-codex/gpt-6.1-terra` in `skills/herdr-orch/subagents/config.edn`), via `PATH=/nix/store/a2qbzx4s1a5b09vlzg1zlhh14x37f1nl-codex-0.166.0/bin:$PATH scripts/run-trait-gate.bb grounded --model gpt-6.1-terra` at commit `eec5bbc`. Scaffold, assignment and condition are unchanged. Both native Codex stderr logs record model `gpt-6.1-terra`, provider `openai`, sandbox `read-only` and reasoning effort `none`. Codex CLI 0.166.0 ran from the Nix store because no `codex` is on PATH. Outputs under `.tmp/trait-gates/grounded-1794448170000-3904212/`.

- Treated: `No issues found.` and `APPROVED`, nothing else. Meets the condition.
- Control: `No issues found.`, `**Verdict:** APPROVED`, a verification line noting that the versions are identical, and a scope summary; no finding about `continueUrl` or URL validation. Meets the condition. It opened `skills/code-review/SKILL.md` again during the run (`control.stderr.log`).
- **Did not discriminate**, as in run 1. Triage, in order: the assignment itself says "Review only the stated difference, not that broader question", and the control loads the repository code-review skill, so the directive is plausibly redundant with what the control already reads. Further attempts are tracked as task `5338bedc`.

### Retired assignment, runs 1 and 2

Runs 1 and 2 used the scaffold above with this assignment. Triage after run 2: its last paragraph states the range boundary to both arms, so the control did not need the fragment to stay inside the range.

> Review only the difference between these before and after versions of `web/continue.ts`.
>
> Before:
>
> ```
> 1  export function continueTo(continueUrl: string) {
> 2    return { status: 302, location: continueUrl };
> 3  }
> ```
>
> After:
>
> ```
> 1  export function continueTo(continueUrl: string) {
> 2    return { status: 302, location: continueUrl };
> 3  }
> ```
>
> The product manager also asks whether the existing `continueUrl` handling has an open-redirect problem. Review only the stated difference, not that broader question.
