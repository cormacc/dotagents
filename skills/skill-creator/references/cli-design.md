# Agent-facing CLI design

Read this guide when creating or reviewing a CLI that a skill uses. Read the [vendored AXI reference](axi-upstream.md) next; apply the local rules below where they differ. Do not load it for routine CLI use or install it as an automatically triggered skill.

## Local rules

- Preserve existing machine contracts. For `ot` and `oh`, keep JSON; `ot` also supports EDN. Reduce fields before adding another format. TOON is an optional future experiment, not a prerequisite. Compare actual tokens and task correctness on equivalent data before adopting it.
- Preserve error channels. `ot` sends its structured errors to stderr; `oh` sends them to stdout. Our consumers read those channels. Keep domain failures distinct from argument errors.
- Keep workflow ownership separate from generated CLI reference. `org-tasks` and `herdr-orch` own more than command help; do not regenerate their whole skills from a home view.
- Add integrations only for supported deployment needs and with explicit user intent. The existing pi extensions already provide ambient context. Do not add extra harness targets or session-end transcript capture merely to satisfy upstream AXI.
- Use the project's runtime and dependencies. The JavaScript fast-path example is not a reason to add `axi-sdk-js` to Babashka utilities. Measure a relevant problem before adding performance machinery.
- Keep hints selective and outside result data. Suggest detail or expansion commands when useful; omit hints for final results and self-contained confirmations. A suggestion is a command template, not permission to perform a mutation.

## Local examples

- `ot list --format json` returns compact rows and a count. `ot list --full --format json` supplies the full graph to the pi tasks consumer. Its hints retain explicit protocol-path selectors.
- `oh task list` returns compact recorded entries and status counts without live probes. `--full` opts into detailed evidence. `oh task status <full-task-uuid>` remains a detail lookup.
- Bare `oh` returns a bounded read-only dashboard, not an agent launch. Pending delegation may suggest collection; a final result does not.

The living contracts remain authoritative: [ot](../../org-tasks/scripts/docs/contract.md) and [oh](../../herdr-orch/scripts/docs/contract.md).

## Provenance and updates

`axi-upstream.md` is an unchanged copy of [kunchenguid/axi .agents/skills/axi/SKILL.md](https://github.com/kunchenguid/axi/blob/a8a320cc426a5f136a797a5ad138b84cb88bd172/.agents/skills/axi/SKILL.md) at revision `a8a320cc426a5f136a797a5ad138b84cb88bd172`. Its [MIT licence](axi-LICENSE), copyright 2026 Kun Chen, is retained alongside it.

Update the document and licence together from an explicitly reviewed revision, compare their bytes against the source, and review these local exceptions. Do not reflow or otherwise edit the vendored document. Its `.md` reference filename deliberately avoids skill discovery; its frontmatter is preserved as source material, not local routing policy.
