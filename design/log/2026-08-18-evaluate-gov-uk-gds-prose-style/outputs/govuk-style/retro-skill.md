<!-- Sample source: skills/retro/SKILL.md -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Extracted: 2026-08-18. -->

# Session retrospective

Review the session for repeatable correction signals. Synthesise candidate improvements to the agent process from them. This is a session-learning workflow. It is not the retrospective project change-record workflow, which [`org-plan`](../org-plan/SKILL.md) owns.

This skill owns detection, classification and presentation. [`self-improvement`](../self-improvement/SKILL.md) owns eligibility, ownership routing, deduplication, persistence and verification of persisted findings. Load that skill once the user approves the findings, and follow it exactly. Do not work out those rules again here.

## Threshold

Offer one retro only when a substantive session is ending and the scan below finds repeated corrections or mounting friction. Task completion and change-record closure are not signals on their own. If the session had fewer than roughly 5 substantive exchanges, say that it is unlikely to hold durable lessons, and skip the retro unless the user insists.

A delegated or otherwise non-interactive session receives one assignment prompt, so exchange count does not measure it. Such a session is substantive when it did multi-step tool work that you had to diagnose or correct, or when it hit any signal in section 1 at all. Below that, write nothing. Silence is the correct outcome, not a failed retro.

## 1. Detect signals

When the session delegated work through [`herdr-orch`](../herdr-orch/SKILL.md), use the children's own candidates as scan input alongside your own signals. Each captured result envelope may carry a `PROCESS:` list of `signal → category → proposed rule` items. The ledger entry holds that list under `:envelope`. Each entry's `:child-session` records where the child's transcript lives, so you can read it by hand when the candidates are thin.

A child performs steps 1 and 2 only. You still own steps 3 to 6, so route, dedup and persist its candidates yourself.

Candidates are testimony from an agent scanning itself. Bound that verification by the Class B probe policy in [`herdr-orch`](../herdr-orch/SKILL.md), section "Trusting a result": up to 3 targeted checks for each candidate before it reaches the table below. Attribute anything a probe cannot settle to the child, rather than adopting it as verified. Let near-duplicates from a fan-out collapse into single rows. You still own verification and persistence. A child never routes or files its own candidate.

Two filters apply before a child candidate reaches the table, because the ledger outlives the session and not every candidate describes reality:

- Scope to this session. Ledger entries persist across sessions, so harvesting the whole ledger sweeps in candidates that earlier retros already routed.
- Probe harness-dependent claims against your own harness. A candidate about tool behaviour describes the child's harness, which may not be yours. One measurement found "the Bash working directory persists between calls" true for one harness and false for the harness that received the candidate. Adopting such a claim without a probe writes a wrong rule into a shared file.

Prioritise these signals:

1. Explicit correction: "that is wrong", repeated instructions, reverted work, "use X not Y".
2. Tool or API failure: a wrong command, flag, schema, field, path or operation order. Record both the failed invocation and the verified one.
3. Repeated friction: avoidable back-and-forth, a manual workaround, or an undocumented convention.
4. Validated approach: an explicitly approved pattern that contradicts current guidance or fills a gap in it.

Keep only the signals that pass the trigger gate in `self-improvement`.

## 2. Classify

| Category | Treatment |
|---|---|
| Behavioral | Candidate instruction change |
| Guardrail | Candidate hard rule with verified replacement |
| Tech debt | TODO or backlog item, not an immediate rule |
| Backlog | TODO or backlog item, not an immediate rule |

Tool and API failures are normally guardrails. Only behavioral and guardrail findings are candidates for direct instruction edits.
