# General

## Questions
- When the user asks a question, answer the question. Do not start coding. Use tools or scripts only when you need more information for the answer.

## Prose
- Write succinctly.
- Use ASD-STE100 Simplified Technical English.
- Use UK English spelling.
- Do not change 3rd party API identifiers to UK English spelling. E.g. sshd uses `authorized_keys`, CSS uses `color`, and APIs retain their documented field names.
- Prose and an identifier can use different spellings in the same sentence.
- When you rewrite prose, preserve identifiers, factual modality, repository conventions, regional spelling (UK/US), and the user's requested tone.

## Identifiers and claims
- CRITICAL: Always verify each identifier against source or documentation.
  - Identifiers include symbols, function names, configuration options, module paths, variable names, CLI flags, and API fields.
  - This identifier check always applies. It does not depend on consequence class or probe budget.
- State a factual or causal claim only when at least one of these conditions applies:
  - You measured the claim.
  - You attribute the claim to its source.
  - You explicitly state that the claim is uncertain.
  - Do not present confidence alone as proof.
  - Apply this rule to assignment baselines, assignment attributions, and claims that you report to the user.
- Before a summary or handoff restates a load-bearing state claim, verify the claim against current source.
  - A claim that was true earlier is not current-source evidence after a later change.
- To decide whether a commit is in a release, make a blobless bare clone with `git clone --bare --filter=blob:none <url>` and use `git merge-base --is-ancestor <commit> <tag>`. Do not rely on release notes or a search summary.

## Negative results and controls
- An empty result is not evidence of absence. Manual confirmation is not sufficient.
- Include a positive control in the same invocation as each query whose negative result you would report.
- Use a known-present sibling, an unfiltered row count, or an assertion that the reference resolves.
- Confirm that the positive control produced output. A silent control means that the probe is broken.
- Report absence only after the control succeeds and the queried result is empty.

## Verification discipline
- Use a command's exit status to determine whether the command succeeded. Plausible output is not sufficient.
- Do not read `$?` after a pipeline when you need the first command's status. E.g. `cmd | tail; echo $?` reports the status of `tail`.
  - Use `${PIPESTATUS[0]}` or remove the pipeline.
  - This method is mandatory when a test checks that a command fails.
- Check the `ok` or status field in a structured CLI envelope before you read result fields.
- Parse machine-readable JSON with `jq` or a JSON parser. Do not use the Clojure reader for machine-readable JSON.
- A nil result field in an error envelope can appear identical to a valid empty result.
- Do not compose an assertion and its verification command in the same invocation.
  - First, read the verification output. Then write the claim that depends on that output.
- Ask one decision in each question.
  - Do not combine two independent choices in one option set.
  - Such an answer can confirm one choice while the other choice remains unconfirmed.
- When a matched skill owns a domain, read that skill before you issue exploratory commands in that domain.
  - Do not run the skill read and the domain probes in parallel.
- Do not cite a green suite as coverage until you confirm that it executes the changed file. Confirm which namespaces the test task runs before you attribute its total to one component.
- Run `date` before you write a new timestamp into a file. Do not infer the current date from surrounding context.
- Do not trust a guard or ad-hoc verifier after you observe only passing results.
  - Establish the passing baseline first, then trigger the verifier deliberately with known-bad input.
  - If all inputs pass or all inputs fail, test the verifier before you diagnose the system. Without the baseline you cannot tell a real finding from a broken harness.
- When shipped behaviour produces a surprising probe result, inspect the probe before you diagnose the system.
  - First, verify each supplied key, parameter, and path against the consumer.

# Git operations
- Use `git mv` when you move files that Git controls. Do not use `mv` for those files.
- Use Git to revert file changes that you made. Do not manually edit those files to revert them.
- Read the `git-commit` skill before you write a commit message.

# File operations

## Temporary files
- Resolve the repository root with `git rev-parse --show-toplevel`.
- Use `<repository-root>/.tmp/` for scripts, data, experiments, tests, and other temporary work.
- Never store transient state under `.agents/`. That directory contains durable agent configuration.
  - Some harnesses deliberately mount `.agents/` as read-only so an agent cannot modify its own instructions.
- Scope a repository reference sweep to tracked files with `git ls-files -z | xargs -0 grep`. A bare recursive grep also matches scratch copies under `.tmp/`.

## Command-line arguments
- Verify each flag's meaning before you trust its output.
- A flag rejection in one argument position does not prove rejection in another position.
- Also test a rejected flag in the final argument position.

## State outside version control
- Version control cannot restore state outside the repository, and `git status` can stay clean after an action destroys it.
- Before you replace or delete external state, record its previous state explicitly.
- First test a new destructive tool in dry-run mode or against a disposable target.

## Privilege and live CLI probes
- `sudo` elevates the command. It does not elevate the shell that prepares the command arguments.
  - The calling user still performs glob expansion, redirection, and other shell expansion.
  - Use `sudo sh -c '...'` when shell expansion accesses a path that only root can read.
- A CLI argument probe runs the command whenever argument parsing succeeds.
  - Test argument parsing against a fake target or dry-run harness.
  - If neither is possible, use an invocation whose parsing succeeds but whose operation cannot start.
  - If a live probe is unavoidable, record each resource that it creates. Remove each created resource in the same step. Do not postpone the removal.
- Set a non-interactive option when you script a tool that can request credentials.
  - This option must make a missing credential fail immediately instead of blocking the user's terminal.
  - Use `GIT_TERMINAL_PROMPT=0` for Git over HTTPS.
  - Use `ssh -o BatchMode=yes` for SSH.
- Inspect files that contain credentials with narrow token matches. Use `grep -o` with explicit patterns, not context flags or an unfiltered `cat`.
- Wrap each destructive or concurrency probe in its own bounded process timeout. Attach cleanup to that outer bound, not to the probe's own exit path.

## Scripts and transformations
- When a script is necessary, prefer Babashka to shell and Python for repository-local automation.
  - Use Python only to invoke an existing Python tool or when its ecosystem is materially more suitable.
  - Use `bb` when a task needs a loop, conditional, string operation, or two or more `|` operators.
- Use shell for ordinary single commands and for passing one command's output to another command.
  - A command with `awk`, `sed -n`, or nested quoting requires a `.tmp/*.clj` script.
  - At that complexity, shell can hide failures instead of reporting them.
- Never pass text that you did not author as the format argument to `printf`. Use `printf '%s' "$text"`, because the corrupted output still exits 0.
- For a scripted transformation, write a candidate under `.tmp/`.
  - Inspect the candidate's diff before you replace the source.
  - Do not perform an unverified in-place transformation.
  - Use the same candidate-and-diff process to replace a long region. Do not replace a long region with one long inline replacement.
  - Check the end of each long edit after it completes. A truncated replacement can appear complete.
  - When an edit call containing several edits returns an error, treat the whole call as not applied. Verify with `grep` or `diff` before you reissue.
- To claim "verbatim except for listed edits", copy the source and apply targeted edits to the copy.
  - Inspect the diff before you make the claim.
- A human can edit the same file at the same time.
  - Before a complete replacement, check for an Emacs `.#<basename>` lock that names a running process ID.
  - Prefer targeted edits because stale text makes them fail explicitly.
  - A complete replacement can silently discard unsaved changes.
  - Re-read each file that the user touched in the current session immediately before you edit it.
- Redirect long-running or expensive command output to `.tmp/`.
  - Read slices from the saved file.
  - Do not pipe such output through `head` or `tail`. Those commands discard the remaining output and often require an expensive repeat execution.
- Confirm that a subprocess succeeded before you read a file that it should produce. Also confirm that the expected output markers are present.
- Never report a diagnostic tool's verdict from a truncated view. Read the finding-count summary or the complete report before you report health.
