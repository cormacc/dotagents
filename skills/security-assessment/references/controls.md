# Security assessment controls

Use these questions only for boundaries in the assigned scope. A missing control is a candidate to trace, not a finding by itself. Verify stack-specific recommendations against source and current authoritative documentation.

## Input, identity and data access

- Trace validation to the point where untrusted data becomes SQL, shell input, HTML, a file path or a privileged API argument. Shape validation does not prove authorisation.
- Separate authentication from permission to act on the specific resource. Check tenant and object ownership, including batch operations and error paths.
- Inspect session lifetime, reset or invitation token reuse, browser request protections and output encoding when applicable. Do not infer a safe flow from one cookie flag or a framework's presence.
- For remote fetches, consider scheme, destination addresses, redirects, resolution and connection-time changes. A hostname check alone does not prove that a request cannot reach an internal service.

## Filesystem and destructive targets

- Identify who supplies each target, including filenames and another process's command line. Check containment in an allowed root, exclusion of the root itself, and independent ownership evidence before a destructive operation.
- Consider symlinks, replacement between check and use, and self-attested ownership markers. A resolved path or marker alone is not proof of authority.
- Use a disposable target for a reproduction. Do not read private files or execute deletion against a real resource to establish exploitability.

## Dependencies and installation

- Find the installation boundary, authoritative lockfile, manager version and CI policy. Resolve competing lockfiles before recommending a command.
- Inspect new packages, lockfile changes and install-script policy together. Prevent unreviewed dependency scripts from running during inspection; use the manager's documented mechanism rather than guessing flags.
- A native audit covers known advisories, not all malicious packages or supply-chain risk. Assess findings by reachability across runtime, build, test and deployment paths.
- Do not run forced remediation automatically. Record deferred reachable risk with a reason, owner and review date. Provenance or signature checks add evidence; their absence alone does not prove compromise.

## Secrets and privacy

- Check how secrets enter the process, who can read them, and where they can leak: logs, arguments, environment, generated files and model context. Use the project's approved secret store; do not prescribe environment variables as a universal safe location.
- Report secret type and location, never the value. For an exposed credential, recommend rotation through the authorised process before history cleanup.
- Identify personal-data purpose, retention, access, sharing and deletion paths, including derived stores. Keep legal-policy questions distinct from verified code behaviour; do not certify compliance from a checklist.

## Services and resource limits

- Check request, payload, concurrency, timeout and retry bounds against the deployment model. An in-memory limit is not necessarily shared across workers.
- Verify sensitive failure paths, logs and responses. Do not recommend hiding errors that operators need in order to recover.
- Prefer a bounded, observable failure to an unbounded retry or permissive fallback. Validate the claim against supported load, not imagined scale.

## LLM and tool boundaries

- Treat retrieved content and model output as untrusted. Trace them into tool arguments, generated code, paths, SQL and HTML.
- Check code-enforced permissions, argument validation, tenant separation, data minimisation, destructive-action approval, token or request bounds and recursion limits.
- A system prompt, a delimiter or a model's instruction-following is not an access-control boundary. Assess the enforcement code and the actual tool reach.

## Provenance

Adapted from the same source revision as [../SKILL.md#Provenance](../SKILL.md#provenance); the sibling [../LICENSE](../LICENSE) carries the MIT notice. These are assessment questions, not copied implementation examples or universal hardening requirements.
