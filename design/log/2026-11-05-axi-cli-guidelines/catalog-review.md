# AXI catalogue adoption review

## Recommendation

Pilot glab-axi first and gh-axi second as optional read-oriented complements. Keep existing backends and domain skills. Do not replace an established integration merely because its alternative appears in the AXI catalogue.

This is a functional fit review, not installation approval, a security assessment, or a measured performance comparison. No candidate was installed, authenticated, or run against live services.

## Evidence and scope

The [catalogue](https://github.com/kunchenguid/axi/blob/a8a320cc426a5f136a797a5ad138b84cb88bd172/catalog.yaml) has 4 official and 109 community entries. I screened the names, domains and descriptions, then inspected upstream documentation for the closest matches to the repository's skills. Detailed inspection was selective, not an audit of all 113 implementations.

Local comparisons used `skills/gitlab-cli-skills/SKILL.md`, `skills/org-jira/SKILL.md`, `skills/ms365/SKILL.md`, `skills/herdr/SKILL.md`, `skills/herdr-orch/scripts/docs/contract.md`, and the installed chromium skill at `~/.pi/agent/skills/chromium/SKILL.md`. The CLI contracts and local source are authoritative for existing behaviour; upstream descriptions below are attributed claims unless backed by the cited source inspection.

## Candidates

### glab-axi: first pilot, complement glab

[Repository](https://github.com/karotkriss/glab-axi). Inspected its README, complete agent skill, authentication implementation and subprocess-wrapper excerpts at revision `5731f8cb9927aad1bc7c7863ceba3e295f776e3c`.

- Documented benefits: compact MR views, pipeline status aggregates, stuck-job explanations and bounded CI logs with a full-log file for further queries. The README also documents JSON and jq escape hatches for MR list/view and raw API calls.
- It wraps the installed glab and reuses its authentication. Its auth source verifies the account through the user API rather than treating a stored credential as proof of authentication.
- Existing `gitlab-cli-skills` provides broad command coverage and explicit actor/host preflights before writes. Retain those rules and the underlying glab CLI; the wrapper is not an identity-policy replacement.
- Its upstream skill says to prefer the wrapper for any GitLab task and to follow contextual hints. Do not install that broad routing policy unchanged during a read-only pilot.
- Pilot: the same MR review preparation and failed-pipeline diagnosis through direct glab and the wrapper. Check host/account selection, job identity, omitted data, full-log retrieval, truncation signals and failure handling. Keep write operations out of the initial pilot.

### gh-axi: second pilot, complement gh

[Repository](https://github.com/kunchenguid/gh-axi). Inspected README excerpts, the complete discovery skill and CI-log implementation excerpts at revision `bb8c797be7d352335be41d45537a10381aa8fd55`.

- Documented benefits: compact issue/PR views, check-status aggregation and specialised CI investigation commands.
- The log implementation caps inline output at 20,000 characters and attempts to save the complete log in a private temporary file. Readback identity checks exist for explicitly selected historical attempts.
- Keep gh for programmatic JSON pipelines and commands outside the wrapper's scope. In particular, the existing find-skills workflow uses gh skill; wrapping GitHub issues does not replace the skill manager.
- The upstream discovery skill recommends unpinned npx execution and globally preferring the wrapper. Use an explicitly installed, reviewed version for a pilot instead.
- Pilot: PR readiness and failed-run investigation. Compare answer correctness and calls before judging token savings. Do not extrapolate upstream's benchmark figures to our existing workflows.

### chrome-devtools-axi: possible complement, not a chromium replacement

[Repository](https://github.com/kunchenguid/chrome-devtools-axi). Inspected README excerpts covering installation, snapshot references and bridge architecture.

- It documents combined navigation/action plus snapshot output, query filtering and generation-tagged stale-reference checks.
- It also adds a persistent bridge and chrome-devtools-mcp beneath the CLI. Our chromium extension connects directly to CDP and already provides bounded selector inspection.
- Keep the existing visible-browser path, stable tab/frame targeting, cross-origin frame support, interactive picking and screenshot verification. Equivalent behaviour in the candidate has not been established in this review.
- Consider an isolated pilot for accessibility-tree extraction or console/network tasks when a concrete gap is demonstrated. Do not switch an authenticated user browser or add ambient hooks merely to try it.

### quota-axi: already adopted

The current herdr-orch contract integrates `quota-axi@0.1.69` through `oh usage`, with explicit managed setup, fixed execution bounds and allowlisted evidence. There is no new adoption action here. Catalogue review does not justify an upgrade or relaxing credential-refresh restrictions.

### jira-axi and atl-axi: keep org-jira's current path

[Jira wrapper](https://github.com/emilchristensen/atlassian-axi/tree/main/packages/jira-axi), [direct REST alternative](https://github.com/cmosgh/atl-axi). Inspected package READMEs.

- jira-axi requires its own authenticated acli backend. Its README states that output has no JSON mode.
- atl-axi documents direct REST access, scoped API tokens, story-point field resolution, sprint commands, link-direction handling and an optional JSON mode. It describes itself as early v0.1.x.
- Our org-jira skill already combines compact deterministic jira_* actions with targeted MCP reads, Rovo/Teamwork Graph exploration and a SAND write boundary. A new CLI is not a demonstrated improvement to that integrated workflow.
- Revisit atl-axi for a concrete sprint/field operation or headless environment where the MCP route is inadequate. Do not replace exploration or the sandbox rules with a generic Jira skill.

### confluence-axi: useful future write-path candidate

[Package](https://github.com/emilchristensen/atlassian-axi/tree/main/packages/confluence-axi). Inspected its README.

- It documents page creation/update, full body retrieval, attachment listing and a macro-loss guard on full-body replacement.
- It needs a separate API token or a user-registered OAuth application. The README states there is no JSON output mode and Markdown is not converted to page storage format.
- Keep current MCP reads. Consider it if Confluence publishing becomes an explicit recurring requirement; first test macro preservation, version conflicts, auth and exact write scope on a disposable page.

### msgraph-axi: do not replace ms365

[Repository](https://github.com/jeffreyhaen/msgraph-axi). Inspected its README.

- It is Outlook-mail/calendar-first, wrapping the m365 CLI, with explicit execution gates for writes and a raw Graph escape hatch.
- Its documented setup adds an Entra application and mail/calendar write permissions. Our ms365 skill intentionally separates read-only MCP access from narrowly guided SharePoint/OneDrive/Excel writes through the Babashka Graph client.
- Compact output is not sufficient reason to replace that boundary. Teams, SharePoint version/draft semantics and Office-file workflows need parity evidence, not merely a generic Graph endpoint.
- Revisit only for an explicitly requested mail/calendar write workflow.

### cyber-mux: defer until cross-multiplexer support is required

[Package](https://github.com/cyberuni/cyber-mux/tree/2db9ca1dc00202eae0c0a4f076bddf5ea4dde733/packages/cyber-mux). Inspected its package README and complete mux skill.

- It documents shared pane control across several multiplexers, including Herdr, and agent-state inspection.
- Cross-multiplexer portability is a possible benefit, but it is not a substitute for oh's assignment ledger, publication, capture validation, ownership and safe-close protocol.
- Keep Herdr/oh. Revisit the adapter as a backend only when another multiplexer is an actual deployment requirement. The upstream mux skill's rule to use cyber-mux rather than native multiplexer CLIs conflicts with the current integration, so do not auto-load it beside Herdr.

### lavish-axi: optional human-review experiment

[Repository](https://github.com/kunchenguid/lavish-axi). Inspected the README introduction and setup sections.

It documents local HTML artefact review with element/text annotations and diagram feedback; hosted sharing is described as explicit and opt-in. This could complement design discussions, but it must not replace canonical Org change-records, Office tracked changes or skill-creator's eval results. Try it only for an actual HTML review need; do not add a second review surface by default.

### axi-axi: do not add as a project dependency or quality gate

[Repository](https://github.com/CodyEngel/axi-axi). Inspected its README.

It provides principle slices, a Node project scaffold and compliance probes against an embedded AXI snapshot. The accepted local plan deliberately differs from upstream AXI, and our CLIs are Babashka. The vendored reference plus local tests is a better fit. Its validator would need source inspection and disposable-target testing before running against live utilities; this review did not establish its safety or suitability as a gate.

## Adoption policy for any pilot

- Keep domain workflow skills authoritative; a CLI wrapper changes transport and presentation, not permission or identity rules.
- Review the exact package, dependencies, licence and install behaviour before installation. Do not execute unpinned npx packages or install hooks during a review.
- Start with read operations in disposable/public fixtures where possible. Prove that full data remains recoverable after truncation and that errors do not look like empty success.
- Compare equivalent requests with identical scopes and fields. Record correctness, calls and actual tokens; upstream aggregate benchmark figures are not measurements of this environment.
- If a pilot earns adoption, add a narrowly routed local guide rather than two competing skills that each claim the entire domain.
