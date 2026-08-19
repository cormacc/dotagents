<!-- Sample source: skills/retro/SKILL.md -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Extracted: 2026-08-18. -->

# Session retrospective

Review the session for repeatable correction signals and synthesize candidate agent-process improvements. This is a session-learning workflow, not the retrospective project change-record workflow owned by [`org-plan`](../org-plan/SKILL.md).

**Boundary.** Detection, classification, and presentation are this skill's. Eligibility, ownership routing, deduplication, persistence, and verification of persisted findings are canonically [`self-improvement`](../self-improvement/SKILL.md)'s -- load it once findings are approved and follow it exactly, rather than re-deriving those rules here.

## Threshold

Proactively offer one retro only when a substantive session is ending and the scan below finds repeated corrections or mounting friction. Task completion and change-record closure are not signals by themselves. If the session had fewer than roughly five substantive exchanges, say that it is unlikely to contain durable lessons and skip unless the user insists.

**Non-interactive equivalent.** A delegated or otherwise non-interactive session receives one assignment prompt, so exchange count does not measure it: a session is substantive when it performed multi-step tool work that had to be diagnosed or corrected, or when it hit any signal in § 1 at all. Below that, emit nothing -- silence is the correct outcome, not a failed retro.

## 1. Detect signals

When the session delegated work through [`herdr-orch`](../herdr-orch/SKILL.md), fold in the children's own candidates as scan input alongside your own signals: each captured result envelope may carry a `PROCESS:` list of `signal → category → proposed rule` items, persisted on the ledger entry under `:envelope`, and each entry's `:child-session` records where the child's transcript lives for a manual read when the candidates are thin. A child performs steps 1--2 only; you still own steps 3--6, so route, dedup, and persist its candidates yourself. Candidates are testimony from an agent scanning itself: bound that verification by the [`herdr-orch`](../herdr-orch/SKILL.md) Class B probe policy (§ Trusting a result) -- up to 3 targeted checks per candidate before it reaches the table below, attributing to the child rather than adopting as verified anything a probe can't settle -- and let near-duplicates from a fan-out collapse into single rows. You still own verification and persistence; a child never routes or files its own candidate.

Two filters apply before a child candidate reaches the table, because the ledger outlives the session and not every candidate describes reality:

- **Scope to this session.** Ledger entries persist across sessions; harvesting the whole ledger sweeps in candidates that earlier retros already routed.
- **Probe harness-dependent claims against your own harness.** A candidate about tool behaviour describes the child's harness, which may not be yours -- "the Bash working directory persists between calls" was measured true for one harness and false for the harness receiving the candidate. Adopting it unprobed writes a wrong rule into a shared file.

Prioritize:

1. **Explicit correction** -- “that is wrong”, repeated instructions, reverted work, “use X not Y”.
2. **Tool/API failure** -- wrong command, flag, schema, field, path, or operation order. Record both the failed and verified invocation.
3. **Repeated friction** -- avoidable back-and-forth, manual workaround, or undocumented convention.
4. **Validated approach** -- an explicitly approved pattern that contradicts or fills a gap in current guidance.

Keep only signals that pass `self-improvement`'s trigger gate.

## 2. Classify

| Category | Treatment |
|---|---|
| Behavioral | Candidate instruction change |
| Guardrail | Candidate hard rule with verified replacement |
| Tech debt | TODO/backlog, not an immediate rule |
| Backlog | TODO/backlog, not an immediate rule |

Tool/API failures are normally guardrails. Only behavioral and guardrail findings are candidates for direct instruction edits.

