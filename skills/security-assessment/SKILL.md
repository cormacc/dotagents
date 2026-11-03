---
name: security-assessment
description: Perform a scoped, threat-model-led security assessment of code, a design or a system. Use for threat modelling, abuse cases, security audits, trust-boundary analysis, dependency installation risk, or assessing LLM/tool permissions. Not a generic diff review, automatic hardening task, live penetration test or compliance certification.
license: MIT
---

# Security assessment

Assess the declared scope without changing it. Produce a threat model and evidence-backed findings; do not apply mitigations unless the user separately requests implementation.

## 1. Set the boundary

Read repository instructions and the assignment. Establish whether the range is a diff, named components or an explicitly scoped system audit. Resolve a diff baseline to an immutable commit and include relevant untracked files when the request covers the working tree. Do not expand a diff into a whole-system audit.

Record the deployment model, supported usage, assets and actors. Identify evidence you have, evidence you cannot obtain and any active checks authorised by the user. Use source inspection and local, non-destructive checks by default. Do not scan a live service, execute a dependency's scripts, fetch attacker-controlled targets or expose credentials to prove a finding.

## 2. Map assets and trust boundaries

Trace each important input from its writer through transformations to its privileged use. Trust follows who can write a value, not whether it arrived through HTTP, a local process, a filename, a queue, a configuration file or model output.

For each boundary, record the asset, entry point, actor capability, validation and authorisation checks, sensitive operation, and resulting exposure. Include indirect inputs such as redirects, symlinks, process metadata and generated tool arguments when they are in scope.

## 3. Derive abuse cases

Apply STRIDE to the relevant boundaries:

| Threat | Question |
|---|---|
| Spoofing | Can an actor assume another identity? |
| Tampering | Can an actor alter data or a target before use? |
| Repudiation | Is there evidence of the security-relevant action? |
| Information disclosure | Can an actor read data they must not receive? |
| Denial of service | Can input exhaust a bounded resource? |
| Elevation of privilege | Can an actor cross an authorisation boundary? |

Turn plausible threats into concrete abuse cases, not a checklist of missing products. Load only relevant sections of [references/controls.md](references/controls.md). Follow the detected stack's authoritative documentation before recommending a specific API, setting or control. Do not prescribe web headers to a CLI or assert that a general pattern guarantees legal compliance.

## 4. Verify the path, including safe controls

For each candidate, trace a reachable input through the actual checks to a sink. Inspect callers and ownership checks before asserting that a local absence is a vulnerability. A check can be upstream of the function under review.

Use a disposable reproduction when a source trace cannot settle the mechanism and the test is authorised. Pair an exploit input with a safe control, and confirm the probe exercises the intended boundary. Do not perform a destructive operation or read real secrets as a demonstration. If execution is not available, distinguish a source-proven path from a hypothesis requiring runtime evidence.

A theoretical missing best practice is not an actionable vulnerability. Keep unresolved questions separate from findings. Accept a safe control without manufacturing another issue to make the assessment useful.

## 5. Report and stop

Use the project's review severity and evidence rules; in this repository, [code-review](../code-review/SKILL.md) owns those rules. For a system audit, apply them within the explicitly named system scope rather than claiming every finding came from a diff. Every actionable finding needs:

- a precise location and affected asset;
- actor prerequisites and a reachable abuse path;
- existing controls and why they do not stop that path;
- concrete impact and the smallest compatible mitigation;
- source or reproduction evidence, with limits on what was tested.

Lead with the assessment verdict, then the threat-boundary summary, severity-ranked findings, checks and limitations. State `No issues found.` when no candidate meets the evidence bar; this is not a claim that the system is secure. Report missing evidence as an unknown, not an invented finding or a clean bill of health.

If delegation is explicitly requested and available, use `reviewer` for a diff and `base-analyst` for a scoped system assessment. Keep orchestration and permission decisions with the root; do not add a specialist persona merely to repeat this skill. Stop after the assessment. Do not change controls, install packages, commit or deploy.

## Provenance

Adapted from Addy Osmani's [security-and-hardening](https://github.com/addyosmani/agent-skills/blob/301b02b12bff98e4688e6984af6556802322bbde/skills/security-and-hardening/SKILL.md) and [security-auditor](https://github.com/addyosmani/agent-skills/blob/301b02b12bff98e4688e6984af6556802322bbde/agents/security-auditor.md), MIT License; see [LICENSE](LICENSE). Retains trust-boundary-led STRIDE, abuse cases, dependency and LLM risks, and exploitable-path evidence. Omits blanket web controls, storage prescriptions, numerical defaults, mandatory praise and a separate severity taxonomy. This adaptation is read-only by default and uses existing local personas.
