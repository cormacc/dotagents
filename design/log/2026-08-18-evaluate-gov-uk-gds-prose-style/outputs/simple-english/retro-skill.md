<!-- Sample source: skills/retro/SKILL.md -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Extracted: 2026-08-18. -->

# Session retrospective

Review the session for repeatable correction signals. Then synthesize candidate improvements to the agent process.

Note: This skill is a session-learning workflow. It is not the retrospective workflow for a project change record. [`org-plan`](../org-plan/SKILL.md) owns that workflow.

**Boundary.** This skill owns detection, classification, and presentation. [`self-improvement`](../self-improvement/SKILL.md) is the canonical owner of these five items: eligibility, ownership routing, deduplication, persistence, and the verification of persisted findings. When the findings are approved, load that skill. Obey it exactly. Do not derive those rules again here.

## Threshold

Offer one retro without a request only when both of these conditions are true: a substantive session is at its end, and the scan in § 1 finds repeated corrections or increased friction. Task completion is not a signal by itself. The closure of a change record is not a signal by itself.

If the session had fewer than approximately five substantive exchanges, tell the user that the session is unlikely to hold durable lessons. Then skip the retro, unless the user insists.

**Non-interactive equivalent.** A delegated session receives one assignment prompt. Any other non-interactive session also receives one assignment prompt. Therefore the count of exchanges does not measure such a session. Instead, a session is substantive in either of these two conditions:

- The session did multi-step tool work that needed diagnosis or correction.
- The session hit any signal in § 1 at all.

If the session meets neither condition, write nothing. Silence is the correct outcome. A failed retro is not the correct outcome.

## 1. Detect signals

If the session delegated work through [`herdr-orch`](../herdr-orch/SKILL.md), use the candidates of the children as scan input. Use them together with your own signals. Each captured result envelope can carry a `PROCESS:` list. Each item in that list has this form: `signal → category → proposed rule`. The ledger entry keeps the list under `:envelope`. The `:child-session` field of each entry records the location of the transcript of the child. If the candidates are thin, read that transcript manually.

A child does steps 1 and 2 only. You keep steps 3 to 6. Therefore you route, deduplicate, and persist the candidates of the child yourself.

A candidate is testimony from an agent that scans itself. Bound that verification with the Class B probe policy of [`herdr-orch`](../herdr-orch/SKILL.md) (§ Trusting a result). Do a maximum of 3 targeted checks for each candidate before it reaches the table in § 2. If a probe cannot settle a claim, attribute the claim to the child. Do not adopt the claim as correct. Let near-duplicate candidates from a fan-out collapse into single rows.

You keep the verification and the persistence. A child never routes its own candidate. A child never files its own candidate.

Apply two filters before a child candidate reaches the table. The ledger has a longer life than the session, and not every candidate describes reality:

- **Scope to this session.** Ledger entries persist across sessions. If you harvest the complete ledger, you collect candidates that earlier retros already routed.
- **Probe each harness-dependent claim against your own harness.** A candidate about tool behavior describes the harness of the child, and that harness can be different from yours. One measurement showed that "the Bash working directory persists between calls" was true for one harness and false for the harness that received the candidate. If you adopt such a claim without a probe, you write a wrong rule into a shared file.

Use this order of priority:

1. **Explicit correction** -- "that is wrong", a repeated instruction, reverted work, or "use X not Y".
2. **Tool/API failure** -- a wrong command, flag, schema, field, path, or order of operations. Record the invocation that failed and the invocation that you know is correct.
3. **Repeated friction** -- avoidable back-and-forth, a manual workaround, or an undocumented convention.
4. **Validated approach** -- a pattern that the user approved explicitly, and that contradicts current guidance or fills a gap in it.

Keep only the signals that pass the trigger gate of `self-improvement`.

## 2. Classify

| Category | Treatment |
|---|---|
| Behavioral | Candidate instruction change |
| Guardrail | Candidate hard rule with verified replacement |
| Tech debt | TODO/backlog, not an immediate rule |
| Backlog | TODO/backlog, not an immediate rule |

A Tool/API failure is normally a guardrail. Only behavioral findings and guardrail findings are candidates for a direct instruction edit.
