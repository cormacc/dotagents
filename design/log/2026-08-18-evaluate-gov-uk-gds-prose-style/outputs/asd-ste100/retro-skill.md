<!-- Sample source: skills/retro/SKILL.md -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Extracted: 2026-08-18. -->

# Session retrospective

Review the session for repeatable correction signals. Synthesise candidate agent-process improvements. This skill is a session-learning workflow. It is not the retrospective project change-record workflow. [`org-plan`](../org-plan/SKILL.md) owns that workflow.

**Boundary.** This skill owns detection, classification, and presentation. [`self-improvement`](../self-improvement/SKILL.md) canonically owns eligibility, ownership routing, deduplication, persistence, and verification of persisted findings. When the findings are approved, load [`self-improvement`](../self-improvement/SKILL.md) and follow it exactly. Do not derive those rules again here.

## Threshold

Offer one retro without a request from the user only when both of these conditions are true:

1. A substantive session is ending.
2. The scan below finds repeated corrections or increased friction.

Task completion is not a signal by itself. Change-record closure is not a signal by itself.

A session can have fewer than approximately five substantive exchanges. Then say that the session is unlikely to contain durable lessons. Skip the retro, unless the user insists.

**Non-interactive equivalent.** A delegated session receives one assignment prompt. Any other non-interactive session also receives one assignment prompt. Therefore the exchange count does not measure such a session. A non-interactive session is substantive when one of these conditions is true:

- The session did multi-step tool work that had to be diagnosed or corrected.
- The session hit at least one signal in § 1.

For a session below that threshold, emit nothing. Silence is the correct outcome. It is not a failed retro.

## 1. Detect signals

The session can delegate work through [`herdr-orch`](../herdr-orch/SKILL.md). Then use the children's own candidates as scan input, together with your own signals. Three facts apply to those candidates:

- Each captured result envelope may carry a `PROCESS:` list. Each item in that list has the form `signal → category → proposed rule`.
- The ledger entry keeps that list under `:envelope`.
- Each entry's `:child-session` records the location of the child's transcript. Read that transcript manually when the candidates are thin.

A child does steps 1--2 only. You own steps 3--6. Therefore you route, deduplicate, and persist the child's candidates yourself.

Candidates are testimony from an agent that scans itself. Bound that verification by the [`herdr-orch`](../herdr-orch/SKILL.md) Class B probe policy (§ Trusting a result):

- Make a maximum of 3 targeted checks for each candidate before the candidate reaches the table below.
- A probe cannot settle every claim. Attribute such a claim to the child. Do not adopt such a claim as verified.
- Let near-duplicate candidates from one fan-out collapse into single rows.

You own verification and persistence. A child never routes its own candidate. A child never files its own candidate.

Two filters apply before a child candidate reaches the table. The ledger continues after the session ends, and not every candidate describes reality.

- **Scope to this session.** Ledger entries persist across sessions. If you harvest the whole ledger, you collect candidates that earlier retros already routed.
- **Probe harness-dependent claims against your own harness.** A candidate about tool behaviour describes the child's harness. That harness may not be your harness. For example, one measurement showed that "the Bash working directory persists between calls" is true for one harness. The same claim was false for the harness that received the candidate. If you adopt that claim without a probe, you write a wrong rule into a shared file.

Prioritise these signals:

1. **Explicit correction** -- “that is wrong”, repeated instructions, reverted work, “use X not Y”.
2. **Tool/API failure** -- a wrong command, flag, schema, field, path, or operation order. Record the failed invocation and the verified invocation.
3. **Repeated friction** -- avoidable back-and-forth, a manual workaround, or an undocumented convention.
4. **Validated approach** -- an explicitly approved pattern. The pattern contradicts current guidance, or it fills a gap in current guidance.

Keep only the signals that pass the `self-improvement` trigger gate.

## 2. Classify

| Category | Treatment |
|---|---|
| Behavioral | Candidate instruction change |
| Guardrail | Candidate hard rule with verified replacement |
| Tech debt | TODO/backlog, not an immediate rule |
| Backlog | TODO/backlog, not an immediate rule |

Tool/API failures are usually guardrails. Only Behavioral findings and Guardrail findings are candidates for direct instruction edits.
