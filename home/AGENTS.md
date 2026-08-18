# General

## Identifiers and claims
- CRITICAL: Always verify each identifier against source or documentation.
- Identifiers include symbols, function names, configuration options, module paths, variable names, CLI flags, and API fields.
- This identifier check always applies. It does not depend on consequence class or probe budget.
- See `herdr-orch` section "Trusting a result" for the related rules.
- State a factual or causal claim only when at least one of these conditions applies:
  - You measured the claim.
  - You attribute the claim to its source.
  - You explicitly state that the claim is uncertain.
- Do not present confidence alone as proof.
- Apply this rule to assignment baselines, assignment attributions, and claims that you report to the user.
- Before a summary restates a load-bearing state claim, verify the claim against current source.
- Before a handoff restates a load-bearing state claim, verify the claim against current source.
- A claim that was true earlier is not current-source evidence after a later change.

## Questions and prose
- When the user asks a question, answer the question. Do not start coding.
- Use tools or scripts only when you need more information for the answer.
- Use British English in prose that you author.
- This rule applies to instructions, records, task bodies, commit messages, code comments, and replies.
- Prefer -ise, -isation, -our, -re, and doubled consonants. Examples include `organise`, `behaviour`, `centre`, and `labelled`.
- Do not change identifiers to British spelling.
- For example, sshd uses `authorized_keys`, CSS uses `color`, and APIs retain their documented field names.
- The CRITICAL identifier rule takes priority over the British English rule.
- Prose and an identifier can use different spellings in the same sentence.

## ASD-STE100
- Use the `asd-ste100` skill for almost all English prose that you draft or revise.
- Read `~/.agents/skills/asd-ste100/SKILL.md` before you produce covered prose in the current session.
- Do not infer the skill rules from this file or from memory.
- Prose that you write before reading the skill is invalid.
- Covered prose includes instructions, prompts, task text, record text, commit messages, and code comments.
- Covered prose also includes errors, status text, reports, technical documentation, and replies.
- Use Strict mode for machine-consumed or operational text.
- Use `STE-flavored` mode for explanatory prose.
- Do not use the skill for user documentation that intentionally requires an informal voice.
- Do not use the skill for creative or marketing copy.
- The required voice determines the exception. The audience alone does not determine it.
- Ordinary user-facing technical prose still requires the skill.
- Preserve identifiers, factual modality, repository conventions, British spelling, and the user's requested tone.

## Negative results and controls
- An empty result is not evidence of absence. Manual confirmation is not sufficient.
- Include a positive control in the same invocation as each query whose negative result you would report.
- Use a known-present sibling, an unfiltered row count, or an assertion that the reference resolves.
- Confirm that the positive control produced output. A silent control means that the probe is broken.
- Report absence only after the control succeeds and the queried result is empty.
- A recorded session misread five uncontrolled empty results as evidence of absence.
- A `jq` filter matched nothing, and the report stated "no tags".
- File probes used references that did not exist, and the report stated "file removed".
- A `LIKE 'sent_notification%'` pattern missed the `p_` prefix, and the report stated "no partitioned table".
- A paged tree listing was incomplete, and the report stated "no migrations".
- `git lfs ls-files` inspected a branch with no LFS objects, and the report stated "no LFS objects".
- In the same session, two controlled probes detected their own faults immediately.
- The user or a later check detected every uncontrolled fault.
- One control used a path that did not exist and printed nothing.
- Its silence was not detected, and the report incorrectly stated that the queried item was absent.

## Command and verifier results
- Use a command's exit status to determine whether the command succeeded. Plausible output is not sufficient.
- During one host migration, `rsync` exited 23 on all six volumes.
- The byte counts and destination sizes matched the source, but the output contained 86,150 attribute errors.
- The output appeared successful even though the command failed.
- Do not read `$?` after a pipeline when you need the first command's status.
- For example, `cmd | tail; echo $?` reports the status of `tail`.
- This error marked three guards as passing even though each guard exited 1.
- Use `${PIPESTATUS[0]}` or remove the pipeline.
- This method is mandatory when a test checks that a command fails.
- Check the `ok` or status field in a structured CLI envelope before you read result fields.
- Parse machine-readable JSON with `jq` or a JSON parser. Do not use the Clojure reader for machine-readable JSON.
- A nil result field in an error envelope can appear identical to a valid empty result.
- One `oh` response contained `{"ok":false}` because of a version mismatch.
- The nil result was incorrectly reported as a trait-resolution failure.
- Do not compose an assertion and its verification command in the same invocation.
- First, read the verification output. Then write the claim that depends on that output.
- One commit message claimed that no unresolved acceptance criteria remained.
- The same invocation produced output that listed three unresolved criteria.
- The verification ran, but the claim was already in the heredoc.
- Ask one decision in each question.
- Do not combine two independent choices in one option set.
- Such an answer can confirm one choice while the other choice remains unconfirmed.
- Adversarially test each behavioural directive before you ship it in an instruction file.
- Confirm that the directive changes behaviour.
- If the directive fails, change the incentive that causes the unwanted behaviour.
- Do not only restate the prohibition.
- In one recorded test, two of three shipped trait directives failed after their first rewrite.
- The original directives failed the same test. The original text did not change the behaviour.
- When a matched skill owns a domain, read that skill before you issue exploratory commands in that domain.
- Do not run the skill read and the domain probes in parallel.
- Do not cite a green suite as coverage until you confirm that it executes the changed file.
- One `bb test` run reported 556 passing tests but did not execute the broken standalone script.
- The green result was incorrectly cited as coverage for that script.
- Do not trust a guard or ad-hoc verifier after you observe only passing results.
- Trigger the verifier deliberately with known-bad input before you trust it.
- If all inputs pass or all inputs fail, test the verifier before you diagnose the system.
- When shipped behaviour produces a surprising probe result, inspect the probe before you diagnose the system.
- First, verify each supplied key, parameter, and path against the consumer.
- One render probe supplied `:locale`, but the code read `:language`.
- The code silently used one locale as a fallback, which appeared to be a localisation defect.
- An incorrect defect report is worse than no probe.

# Git operations
- Use `git mv` when you move files that Git controls. Do not use `mv` for those files.
- This requirement preserves file history.
- Use Git to revert file changes that you made. Do not manually edit those files to revert them.
- Read the `git-commit` skill before you write a commit message.
- In a commit body, refer to the associated design change record. Do not repeat the record's detail.

# File operations

## Command-line arguments
- Verify each flag's meaning before you trust its output.
- Work around the vendored `skills/gitlab-cli-skills/glab-runner/SKILL.md` use of `glab runner list --all`. Installed glab rejects `--all`.
- Use `glab runner list --instance` for instance scope when installed `glab runner list --help` documents it. Keep the vendored skill unchanged until upstream merges the correction.
- A flag rejection in one argument position does not prove rejection in another position.
- A removed `--focus` flag produced `unknown task option` before other arguments.
- In the final position, the same flag produced `option requires a value`.
- The parser entered its missing-value branch because no token followed the flag.
- The first message was reported as the behaviour. Two reviewers later found the second message.
- Also test a rejected flag in the final argument position.

## State outside version control
- Version control cannot restore state outside the repository.
- Remote hosts, cloud resources, databases, and registries do not produce a Git diff or Git revert.
- `git status` can remain clean after an action destroys external state.
- Before you replace or delete external state, record its previous state explicitly.
- First test a new destructive tool in dry-run mode or against a disposable target.
- This rule is especially important when you wrote the dry-run flag but did not use it.
- One authorised-keys installer test used a live host and silently reduced three keys to one.
- Recovery was possible only because an unrelated earlier step saved the old keys in a scratch file.

## Privilege and live CLI probes
- `sudo` elevates the command. It does not elevate the shell that prepares the command arguments.
- The calling user still performs glob expansion, redirection, and other shell expansion.
- `sudo ls /var/lib/docker/volumes/*/_data/*.tar` printed nothing because the calling user could not read the root-only path.
- The empty output was read as "no archives".
- Three seconds later, `sudo sh -c 'ls ...'` listed the archives.
- Use `sudo sh -c '...'` when shell expansion accesses a path that only root can read.
- A CLI argument probe runs the command whenever argument parsing succeeds.
- A probe of `oh tab create --focus` created a real tab.
- An invented flag on `oh task run` was accepted silently and started a billable subagent.
- The operator then had to close the subagent manually.
- Test argument parsing against a fake target or dry-run harness.
- If neither is possible, use an invocation whose parsing succeeds but whose operation cannot start.
- If a live probe is unavoidable, record each resource that it creates.
- Remove each created resource in the same step. Do not postpone the removal.

## Search patterns and renames
- A search pattern from data can start with `-`, which a command can consume as an option.
- For example, `grep -qxF "$line"` fails for a line that starts with `---` or `- `.
- It prints a usage error for each such line, while the loop treats the line as a mismatch.
- Pass the pattern with `grep -e "$pat"` or after `--`.
- This defect affects only some inputs. A successful spot-check does not prove that the command is correct.
- The remaining misreads can appear to be valid findings.
- Verify every rename in both directions before you declare it complete.
- BSD `sed` on macOS does not support `\b`, and it can silently match nothing.
- A pattern that prefixes another token can also rewrite that token.
- One rename missed two call sites and changed an unrelated `%focusx` token.
- Prefer Babashka because its Java regular expressions support `\b` on every platform.
- First count matches with `(re-seq #"%focus\b" text)`.
- Then rewrite with `str/replace`.
- Finally, search again for the old name.
- A word boundary after a non-word character never matches.
- For example, `\bname!\b` finds nothing because the boundary after `!` requires a following word character.
- For identifiers that end in `!`, `?`, or `-`, omit the final `\b`.
- Search again for the old identifier before you run tests.
- Otherwise, a bulk rename can silently miss most call sites.

## Prompts, credentials, and shell quoting
- Set a non-interactive option when you script a tool that can request credentials.
- This option must make a missing credential fail immediately instead of blocking the user's terminal.
- Use `GIT_TERMINAL_PROMPT=0` for Git over HTTPS.
- Use `ssh -o BatchMode=yes` for SSH.
- In one session, `glab repo clone` blocked twice on a credential prompt.
- The host used `git_protocol: https`, while the user authenticated with an SSH key.
- The script configured `BatchMode=yes` only for the SSH path.
- Inspect files that contain credentials with narrow token matches.
- Use `grep -o` with explicit patterns. Do not use context flags or an unfiltered `cat`.
- One `grep -A6` command printed a live HTTP secret from a registry configuration into the session transcript.
- The query did not need the surrounding lines.
- Single-quote shell search patterns that contain backticks or `$`.
- Markdown-derived text commonly contains these characters. Double quotes permit command substitution.
- Never pass text that you did not author as the format argument to `printf`.
- Use `printf '%s' "$text"`, a quoted heredoc, or `echo`.
- The command `printf "$text"` can silently corrupt `%` sequences and still exit 0.
- For example, `"85% coverage"` prints `85overage`.
- `%focused` prints `0.000000ocused`, and `%no-bullshit` prints `o-bullshit`.
- Of these examples, only `%read-only` and `%prune` fail with an error.
- Percentages occur in prose more often than trait tokens. This rule is not specific to traits.
- Write CLI prose arguments, such as task bodies and assignments, to a file with a quoted heredoc.
- Pass the file contents as `"$(cat file)"`.
- An apostrophe terminates a single-quoted argument. Bash can then execute the remaining text.
- This error truncated one `ot create --body` argument, and Bash executed its remaining text.
- Shell command substitution does not scan its output again. Backticks inside the file are therefore safe.

## Output and structured edits
- Limit test-failure output with `head -c` when an asserted value can contain a file or captured log.
- One `grep -A` assertion printed approximately 10,000 tokens to report one Boolean value.
- The assertion line alone identified the failure.
- Prefer available structured read and edit tools for routine file inspection and modification.
- Build a structured edit's `oldText` from a contiguous file read.
- Do not build `oldText` from `rg` or `grep` output.
- Filtered output can make separate source lines appear consecutive.
- One `rg '^- \['` view displayed criteria lines as one block, although a paragraph separated them.
- The edit built from that view was rejected.
- A multi-entry edit is all-or-nothing. One non-matching entry rejects every entry in the call.
- Check the target path of every edit entry.
- One entry that targets the wrong file causes the complete edit call to fail.
- Never add keys that are absent from a structured tool's declared schema.
- A tool can silently accept an unknown field and change the payload instead of reporting an error.
- One extra key in an `edits[]` entry truncated replacement text and wrote an incomplete form.

## Scripts and transformations
- New automation scripts in the repository-root `scripts/` directory must use Babashka by default.
- Existing non-Babashka scripts in that directory predate this rule. Do not use them as precedent for new scripts.
- A thin shell wrapper may use shell when shell is the suitable interface.
- A check that depends on the Node ecosystem may use TypeScript.
- When a script is necessary, prefer Babashka to shell and Python for repository-local automation.
- Use Python only to invoke an existing Python tool or when its ecosystem is materially more suitable.
- Use `bb` when a task needs a loop, conditional, string operation, or two or more `|` operators.
- Use shell for ordinary commands and for passing one command's output to another command.
- A command with `awk`, `sed -n`, or nested quoting requires a `.tmp/*.clj` script.
- At that complexity, shell can hide failures instead of reporting them.
- Use shell for ordinary single commands such as `git status`, `rg pattern`, and `ls`.
- For a scripted transformation, write a candidate under `<repository-root>/.tmp/`.
- Inspect the candidate's diff before you replace the source.
- Do not perform an unverified in-place transformation.
- Use the same candidate-and-diff process to replace a long region.
- Do not replace a long region with one long inline replacement.
- Long replacement text silently truncated three times in one recorded session.
- One truncation ended in the middle of a line.
- The original paragraph continued after it, so the file still appeared plausible.
- Check the end of each long edit after it completes.
- A truncated replacement can appear complete.
- To claim "verbatim except for listed edits", copy the source and apply targeted edits to the copy.
- Inspect the diff before you make the claim.
- One manually retyped persona changed two full stops.
- Six personas produced through copy-and-edit remained byte-identical outside the listed edits.
- The identity claim was made before any verification.
- A human can edit the same file at the same time.
- Before a complete replacement, check for an Emacs `.#<basename>` lock that names a running process ID.
- Prefer targeted edits because stale text makes them fail explicitly.
- A complete replacement can silently discard unsaved changes.
- Re-read each file that the user touched in the current session immediately before you edit it.
- Redirect long-running or expensive command output to `<repository-root>/.tmp/`.
- Read slices from the saved file.
- Do not pipe such output through `head` or `tail`.
- Those commands discard the remaining output and often require an expensive repeat execution.
- Confirm that a subprocess succeeded before you read a file that it should produce.
- Also confirm that the expected output markers are present.
- One test first read a derived path and reported `FileNotFoundException`.
- That error concealed the producer's parse error and directed the diagnosis to the wrong layer.
- Never report a diagnostic tool's verdict from a truncated view.
- `ot doctor | tail -3` displayed a clean tail twice while two ERROR entries were before the displayed section.
- The record was incorrectly reported as well-formed.
- Read the finding-count summary or the complete report before you report health.

# Temporary files
- Resolve the repository root with `git rev-parse --show-toplevel`.
- Use `<repository-root>/.tmp/` for scripts, data, experiments, tests, and other temporary work.
- Do not assume that `$PROJECT_ROOT` is defined.
- Never store transient state under `.agents/`. That directory contains durable agent configuration.
- Some harnesses deliberately mount `.agents/` as read-only so an agent cannot modify its own instructions.
- Codex under `--sandbox workspace-write` is one such harness.
- Child agents in those harnesses cannot write a scratch or result file under `.agents/`.

# Concurrency and destructive scratch probes

## Scratch-target containment
- Assert that a derived scratch target is non-nil, absolute, and inside the intended temporary root.
- Check this before the first write or delete against that target.
- A scratch probe once derived a nil checkout path.
- The probe wrote through that path into the caller's working directory before its own cleanup ran.

## Timeout units for concurrency probes
- Verify a tool's timeout unit before you supply a value. Treat the unit as an identifier under the identifier-verification rule above.
- Wrap each concurrency probe in its own bounded process timeout, independent of the probe's internal logic.
- Attach cleanup to that outer bound, not to the probe's own exit path.
- One timeout value of `120000` was supplied as milliseconds. The tool's schema read the value as seconds.
- The deadlocked probe ran for 4,173 seconds before a manual interrupt stopped it.

## Lock-test invariants
- Assert scheduler-independent one-winner/one-loser invariants in a lock test.
- Do not assume which waiter acquires the lock first.
- A POSIX or JVM file lock guarantees exclusion. It does not guarantee acquisition order.
- Never add a caller-controlled test barrier to a production lock path.
- One such barrier needed an external release file. The file was missing.
- Every later spawn and continue call blocked in review until the barrier was removed.
