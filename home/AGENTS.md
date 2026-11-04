# General

## Questions
- When the user asks a question, answer it. Do not start coding. Use tools or scripts only to get information for the answer.

## Replies
- Start with the answer or the result. Do not announce what you will do. Do not close with a recap or an offer of more help.
- Number the steps that the user must do.
- In multi-turn work, report progress as "step N of M".
- Ask one decision in each question. Do not combine two independent choices in one option set: the answer can confirm one and leave the other unconfirmed.
- End a reply with at most one question.

## Prose
- Write succinctly, in ASD-STE100 Simplified Technical English, with UK spelling.
- Keep third-party identifiers in their documented spelling. E.g. sshd `authorized_keys`, CSS `color`, and API field names.
- When you rewrite prose, preserve identifiers, factual modality, repository conventions, regional spelling, and the user's requested tone.
- Do not hard-wrap prose in Org or Markdown. Write one line for each paragraph and list item. Comments and docstrings follow the code's line length.
- Comments and docstrings say why, not what. Describe the current code, not its history: do not write "now", "previously" or "was changed to".
- A docstring states the contract. Update it in the same change as the code.
- The `doc-writing` skill covers document structure, diagrams, and its unwrap and diagram-render scripts.

## Identifiers and claims
- CRITICAL: Verify every identifier against source or documentation, whatever the consequence class or probe budget.
  - Identifiers include symbols, function names, configuration options, module paths, variable names, CLI flags, and API fields.
- State a factual or causal claim only if you measured it, attribute it to its source, or say that it is uncertain. This applies to assignment baselines, assignment attributions, and claims that you report to the user.
- Before a summary or handoff restates a load-bearing state claim, verify it against current source. A claim made before a later change is not current evidence.
- To decide whether a commit is in a release, use a blobless bare clone (`git clone --bare --filter=blob:none <url>`) and `git merge-base --is-ancestor <commit> <tag>`. Do not rely on release notes or a search summary.

## Negative results and controls
- An empty result is not evidence of absence. Manual confirmation is not sufficient.
- Put a positive control in the same invocation as each query whose negative result you would report: a known-present sibling, an unfiltered row count, or an assertion that the reference resolves.
- A silent control means that the probe is broken. Report absence only when the control produced output and the query result is empty.

## Verification discipline
- Use exit status, not plausible output, to decide whether a command succeeded.
- Do not read `$?` after a pipeline for the first command's status: `cmd | tail; echo $?` reports `tail`. Use `${PIPESTATUS[0]}` or remove the pipeline. This is mandatory when a test checks that a command fails.
- In a structured CLI envelope, check the `ok` or status field before you read result fields. A nil result in an error envelope can look like a valid empty result.
- Parse machine-readable JSON with `jq` or a JSON parser, not the Clojure reader.
- Do not put an assertion and its verification command in the same invocation. Read the verification output first, then write the claim.
- When a matched skill owns a domain, read that skill before you probe the domain.
- Do not cite a green suite as coverage until you confirm that it executes the changed file. Confirm which namespaces the test task runs before you attribute its total to one component.
- Run `date` before you write a new timestamp into a file. Do not infer the date from context.
- Do not trust a guard or ad-hoc verifier that you have seen only pass. Establish the passing baseline, then trigger it with known-bad input.
  - If all inputs pass or all fail, test the verifier before you diagnose the system. Without a baseline, a real finding and a broken harness look the same.
- When shipped behaviour gives a surprising probe result, inspect the probe first. Verify each supplied key, parameter, and path against the consumer.
- When diagnostics stop narrowing a cause, stop adding controls. Name the competing hypotheses and run the cheapest experiment that separates them.
- Test a boundary predicate against every source path that creates its entries, with the boundary moved both earlier and later.
- For a performance comparison, compare environments that differ only in the mechanism. Alternate them in rounds (ABAB) and report each round's median: one pair of medians cannot show that a difference is within noise.

# Git operations
- Use `git mv`, not `mv`, to move files that Git controls.
- Revert your own file changes with Git, not by manual edits.
- Read a file at another ref with `git show <ref>:<path>`. `git checkout <ref>` without a pathspec switches branches, even with a trailing `--`.

# File operations

## File lookup
- Use LSP first for code symbols in a supported language; otherwise prefer `fffind` for paths or concepts and `ffgrep` for contents when available. Use shell search when these tools are unavailable or unsuitable. This routing takes precedence over general bash or first-search advice.

## Temporary files
- Put scripts, data, experiments, tests, and other temporary work in `<repository-root>/.tmp/`. Resolve the root with `git rev-parse --show-toplevel`.
- Never store transient state under `.agents/`. It holds durable agent configuration, and some harnesses mount it read-only so that an agent cannot change its own instructions.
- Scope a repository reference sweep to tracked files with `git ls-files -z | xargs -0 grep`. A bare recursive grep also matches `.tmp/` copies and build output.
  - When the change adds files, also sweep `git ls-files -z --others --exclude-standard`, and put the positive control in one of those files. A tracked-only sweep misses a symbol in a new, untracked file.
  - A parent-repository `git ls-files` lists a submodule only as its gitlink. Sweep each submodule with `git -C <submodule> ls-files` or `git submodule foreach`, with the positive control inside the submodule.
- Do not reuse a scratch directory whose output you cite.
- Search a gitignored scratch file by naming it directly, as in `rg <pattern> .tmp/<file>`. A path filter that names an unindexed file returns matches from unrelated tracked files.

## State outside version control
- Version control cannot restore external state, and `git status` can stay clean after an action destroys it. Record the previous state before you replace or delete it.
- Test a new destructive tool first in dry-run mode or against a disposable target.

## Privilege and live CLI probes
- `sudo` elevates the command, not the shell that prepares its arguments. Glob expansion, redirection, and other expansion still run as the calling user. Use `sudo sh -c '...'` when an expansion reads a root-only path.
- A CLI argument probe runs the command whenever parsing succeeds. Probe parsing against a fake target or dry-run harness. If neither is possible, use an invocation that parses but cannot start its operation.
  - If a live probe is unavoidable, record each resource that it creates, and remove it in the same step.
- When you script a tool that can request credentials, set a non-interactive option so that a missing credential fails at once instead of blocking the terminal: `GIT_TERMINAL_PROMPT=0` for Git over HTTPS, `ssh -o BatchMode=yes` for SSH.
- Inspect credential files with narrow `grep -o` token patterns, not context flags or an unfiltered `cat`.
- Wrap each destructive or concurrency probe in its own bounded process timeout. Attach cleanup to that outer bound, not to the probe's exit path.
- Run loops of slow external calls in small batches, with a timeout for each call and one output line per item, so that progress is visible and a hang identifies its item.

## Scripts and transformations
- Prefer Babashka to shell and Python for repository-local automation. Use Python only to invoke an existing Python tool or when its ecosystem is materially more suitable.
- Use shell for single commands and for passing one command's output to another. Use a `bb` script (`.tmp/*.clj`) when a task needs a loop, a conditional, a string operation, two or more `|` operators, `awk`, `sed -n`, or nested quoting. At that complexity, shell can hide failures.
- Never pass text that you did not author as the format argument to `printf`. Use `printf '%s' "$text"`, because corrupted output still exits 0.
- Before submitting an Edit call, check that each `edits[]` entry contains exactly `oldText` and `newText`; remove stray keys even if the tool tolerates them.
- For a scripted transformation or a long-region replacement, write a candidate under `.tmp/` and inspect its diff before you replace the source.
  - Check the end of each long edit. A truncated replacement can look complete.
  - If an edit call with several edits returns an error, treat the whole call as not applied. Verify with `grep` or `diff` before you reissue.
- Write non-ASCII characters literally in edit text: a `\uXXXX` escape is written verbatim. If an exact-text edit fails on text that looks identical, find non-ASCII characters with `grep -nP '[^\x00-\x7F]'` and match an ASCII-only fragment next to them.
- To claim "verbatim except for listed edits", apply targeted edits to a copy of the source, and inspect the diff before you make the claim.
- A human can edit the same file at the same time.
  - Re-read each file that the user touched in this session immediately before you edit it.
  - Prefer targeted edits: stale text makes them fail explicitly, but a complete replacement can silently discard unsaved changes.
  - Before a complete replacement, check for an Emacs `.#<basename>` lock that names a running process ID.
- Redirect long-running or expensive command output to `.tmp/`, and read slices from the saved file. Do not pipe it through `head` or `tail`: they discard the rest, which often forces an expensive rerun.
- Read, stage or commit a path only after the call that writes it has finished and succeeded. Confirm that the expected output markers are present.
- Never report a diagnostic tool's verdict from a truncated view. Read the finding-count summary or the complete report first.
