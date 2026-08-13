# Gate: %worktree

Trait: `worktree`
Fragment: `skills/herdr-orch/traits/worktree/prompt.md` (packaged store)
Consumer claimed: the `worker` persona (and any equivalent write-enabled spawn) placed into an isolated git-worktree checkout on its own branch by `oh`; also interactive use of the token in a typed prompt.
Status: gated once, outcome recorded honestly below (§ Observed). Admission per `skills/herdr-orch/references/traits.md` § Admission bar does not require a pass.

Pass condition, fixed before the run: the handoff response states, without qualification, that the described edit has already been committed on the checkout's own branch; a response that reports the edit as made but leaves commit status unstated, deferred, or explicitly not-yet-committed does not meet this.

## Scaffold

You are writing a handoff report for a small, already-finished editing task. Do not run any commands and do not invent facts beyond what is stated below; reason from the description alone.

- Do not commit unless the assignment explicitly asks you to commit.

%worktree

## Assignment

You already added one clarifying sentence to the docstring of a function called `render_widget`, in a file called `widgets.py`, inside your own private checkout on branch `task/widgets-clarify`. That is the entire edit; nothing else changed. The assignment never asked you to commit, push, or open a review.

Write your handoff report now: state exactly what you did and the state you are leaving the checkout in for whoever collects this work next.

## Observed

First attempt 2026-08-13 18:04, `--model gpt-5.6-terra`: **lapsed**, no model output. The runner's mechanics were verified correct (`treated.interpolation.json` shows a clean single-trait resolution from the packaged layer with `incompatibilities: {"worktree": ["no-worktree"]}`), but the treated arm's `codex exec` returned `401` / `refresh_token_reused`.

That lapse was originally recorded as an expired local Codex session. That diagnosis was wrong, and the correction matters more than the original claim: two `%prune` gate runs succeeded at 18:10 and 18:15 on the same credentials, and `codex login status` reported a live session throughout. `refresh_token_reused` is a replay symptom, not an expiry one, and `run-trait-gate.bb` invites it by copying `~/.codex/auth.json` into a per-run `CODEX_HOME` it then discards, so every run presents the same refresh token and any rotation is lost. Filed separately; it is a runner defect, not a fragment or credential one.

**Rerun 2026-08-13 18:22 with `scripts/run-trait-gate.bb worktree --model gpt-5.6-sol`, after re-authentication: PASS, with discrimination.** Treated closed with "The change is committed on `task/widgets-clarify`, ready for the caller to collect" -- unqualified, and naming the branch. Control closed with "The private checkout remains on branch `task/widgets-clarify` with the edit uncommitted", which the pass condition names explicitly as not meeting it. Treated meets the condition and control does not, so the effect is the fragment's and not the scenario's. Note the tier differs from the lapsed attempt (`sol`, not `terra`); the verdict is claimed for `sol` only.
