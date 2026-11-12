# Repository development scripts

The scripts in this directory are repository-level development checks and runners. They are not shipped runtime surfaces.

## Isolated test run

The root `bb test` task loads `isolated-test-run.bb` and calls `run-suites` with the org-tasks and herdr-orch Clojure suites and the script test suites. It runs each suite as `bb -Djava.io.tmpdir=<root>` with `TMPDIR=<root>`, where `<root>` is one new `/tmp/dotagents-test-*` directory for the run. Babashka's `fs/create-temp-dir` reads `java.io.tmpdir` and ignores `TMPDIR`; Node's `os.tmpdir()` reads `TMPDIR`. The root is outside the checkout because several tests need a directory outside any git repository. The task deletes the root afterwards; set `DOTAGENTS_KEEP_TEST_TMP=1` to keep it for debugging. It also removes the caller's session variables (`HC_*`, `HERDR_*` and the Pi session variables listed in `caller-session-env`) from each suite's environment, because `oh` selects its runtime from them: without this, a run inside herdr-app, Herdr or Pi gives different results from a run in a plain terminal.

The task also fails when the run adds or removes a git worktree or a `refs/heads/orch/*` branch in this repository, and it prints the cleanup command. Such a change means that a test ran `oh` with this checkout as its working directory. A real orchestration that starts during the run can also cause this failure.

## Trait gate runner

Run a trait's adversarial gate in the host harness, with a Pi model ID (`provider/id`, optionally `:<thinking>`):

```sh
scripts/run-trait-gate.bb prune --model anthropic/claude-sonnet-5-5
```

`run-trait-gate.bb` reads `traits/<name>/gate.md`, derives both arm sources from its one scaffold, and asserts that removing one exact `%<name>` token line is their only source difference. It calls the existing `skills/herdr-orch/scripts/traits` CLI with the repository trait store (`traits/`) and the packaged skill store (`skills/herdr-orch/traits/`) as layers to interpolate the treated arm, so a gate-only fragment at repository root and a real packaged fragment resolve alike; it does not implement interpolation or extend that CLI. A probe measures a trait's marginal effect in the environment where the trait is used, so each arm runs in the host harness, Pi, with its complete deployed resource context: home `AGENTS.md`, every installed skill and extension, and MCP servers. Each arm is sent as a direct prompt to a separate `pi -p --no-session --mode json --model <id>` process, so no subagent persona or durable configuration fixture is created. Each arm starts in its own new empty directory outside the checkout, so no project `AGENTS.md` loads and no `gate.md` is in the working tree. The arm runs without the caller's `HC_*`, `HERDR_*`, `ORCH_*` and Pi session variables, so the child does not report itself to the caller's Herdr pane or session. Authentication is the caller's normal Pi authentication; the runner does not copy credentials.

Pi has no read-only sandbox. The scaffolds are recommend-only, and the runner reports a write rather than preventing one: it records each arm directory's contents after the run and warns when it is not empty, and warns when the checkout's `git status` changes during the run.

Run artifacts are transient under `.tmp/trait-gates/`. Each arm writes `<arm>.events.jsonl` (the Pi JSON event stream), `<arm>.output.md` (the final assistant text) and `<arm>.provenance.json`: provider, model and API of the final response, the context files and skill count from the session's system message, active tools, MCP servers, whether the session's user message is the exact arm prompt, and the arm directory's contents. The runner fails when an arm exits non-zero, records a different prompt, stops with an error, or has no final response. It prints a provenance line per arm and both final responses beside the pre-registered pass condition, and stops without a verdict; a human applies the condition.

### `gate.md` grammar

A runnable gate is UTF-8 Markdown with these elements in this order:

1. Exactly one single-line paragraph beginning `Pass condition, fixed before the run:` before the runnable sections.
2. Exactly one `## Scaffold` section containing the common prompt and exactly one line whose complete content is `%<name>`.
3. Exactly one following `## Assignment` heading; a suffix introduced by ` -- ` is allowed for retained evidence such as a void scenario.
4. The assignment extends to the next level-two heading or end of file. Later sections such as `## Observed` are records only and are never sent to either arm.

Missing, duplicated, empty, or out-of-order required sections fail with the full gate path before either agent runs. The grammar belongs to this development runner only: trait resolution still considers only `<layer>/<name>.md` and `<layer>/<name>/prompt.md`, so sibling `gate.md` files remain inert.

The repository-wide `bb test` task runs these checks after the org-tasks and herdr-orch Clojure suites. Run only the focused runner checks with:

```sh
scripts/test-run-trait-gate.bb
```

## Anthropic prompt-cache TTL analysis

Compare recent five-minute Anthropic prompt-cache costs with one-hour bounds:

```sh
bb prompt-cache-ttl --sessions 20 --scan-limit 200
```

The task scans `~/.pi/agent/sessions` by default. Use `--root DIR` for another Pi session root. `--scan-limit` limits the recent JSONL files inspected before the task selects the requested number of files that contain Anthropic assistant responses.

The JSON output reports the logged five-minute total and two one-hour estimates. The no-rescue estimate prices every logged cache write at the one-hour rate. The optimistic estimate also treats a prior cacheable prefix as reusable after a 5--60 minute same-model gap when the next response reported no cache read, rewrote at least that prefix, and had no intervening compaction. Session logs do not store prompt-prefix identities, so the optimistic estimate is a bound rather than a measured counterfactual.

The task extracts only timestamps, model/provider identifiers, usage and cost fields, and compaction events. It does not emit prompt or response content. Sessions with non-zero `cacheWrite1h` are reported separately and excluded from the five-minute baseline comparison.

Run the focused tests with:

```sh
bb scripts/test-analyse-anthropic-cache-ttl.bb
```

## Vendored gitlab-cli-skills sync

Re-sync `skills/gitlab-cli-skills/` from upstream:

```sh
scripts/sync-gitlab-cli-skills.bb            # dry run: build a candidate and print the diff
scripts/sync-gitlab-cli-skills.bb --apply    # replace the vendor tree
scripts/sync-gitlab-cli-skills.bb --ref v2 --repo owner/name
```

Upstream ships a flat collection: a `gitlab-cli-skills/` router directory beside 47 standalone `glab-*/` directories at the repository root. `npx skills add` installs all of them as siblings, which puts every description into discovery. This repository nests them under the router instead, so the harness discovers one skill. The script reproduces that layout deterministically: it clones the requested ref into `.tmp/`, hoists the router directory's contents to the vendor root, copies the `glab-*/` directories in beside it, rewrites the router's `](../glab-` links to child-relative form, re-applies the local router `description`, and records the upstream commit in `skills/gitlab-cli-skills/.vendor.edn`.

The dry run is the default because it builds the candidate under `.tmp/` and prints the added/removed skill list plus a `git diff --stat` against the current tree. `--apply` replaces the vendor tree only after that report.

It aborts rather than guessing when upstream changes shape: a missing router `SKILL.md`, no root-level `glab-*` directories, a router link style that no longer needs rewriting, a remaining parent-relative link, or a router entry that collides with a root entry whose shared files are not byte-identical.

## Section reference check

```sh
scripts/check-section-links.bb --exclude skills/herdr/ --exclude skills/gitlab-cli-skills/ --exclude skills/convert-documents-to-markdown/
agents/scripts/check-section-links.bb    # from the dotfiles checkout, against that repository
```

Checks that every section reference in tracked and non-ignored untracked `.org` and `.md` files of the git repository containing `--root` (default: the working directory) resolves. It covers the forms in [../AGENTS.md#Section references](../AGENTS.md#section-references) and custom-ID links:

- Org `[[file:path::*Heading]]` and `[[*Heading]]`: the target heading exists. Matching is exact after the TODO keyword, priority cookie, `COMMENT` and tags are removed, as `org-get-heading` does.
- Org `[[file:path::#id]]` and `[[#id]]`: a `CUSTOM_ID` matches, or a GitHub-style heading anchor matches. toc-org `_gh` tables of contents use the anchor form, and `toc-org-mode` translates it when the link is followed.
- Org `[[file:path.md]] section 'Heading'`: the Markdown heading exists. Org cannot search a Markdown file for a heading, so a `::*` search into a Markdown file is itself reported.
- Markdown `[text](path#anchor)` and `[text](#anchor)` to `.md` or `.org` files: the GitHub heading anchor exists. Whether GitHub renders anchors for `.org` headings is unverified; the check assumes it uses the same slug as Markdown.

Paths must be relative to the linking file. Links inside code spans, org verbatim, and code blocks are examples and are skipped. `--exclude` drops a path prefix, such as a vendored skill. Each problem prints as `path:line: message`. The script exits 1 when any reference fails to resolve and 2 on a usage error.

`scripts/check.sh` runs it with the vendored skills excluded. `bb test` runs the focused tests in `scripts/test-check-section-links.bb`.

## Change-record identifier check

```sh
scripts/check-record-identifiers.bb --control known-identifier design/log/record.org
```

Checks backticked non-path identifiers in the specified change-records against text in Git-tracked `.clj`, `.cljc`, `.cljs`, `.edn` and `.bb` files. It supports keywords, `::` keywords, qualified and simple symbols, and namespaces. It uses exact token boundaries, reads source without evaluating it, and treats comments and strings as source evidence. It does not resolve bindings.

The required `--control` is a known non-path identifier. The check tests it with the same matcher on every run and prints the accepted control. A missing control, unreadable record or tracked source, no tracked source, or Git failure exits non-zero. Unresolved record identifiers remain advisory and exit 0, because removed names, rejected alternatives and library internals can be valid record content.

An auto-resolved `::keyword` is reported as uncertain rather than resolved or unresolved: without namespace context, its target cannot be determined. Paths are excluded from this check; `ot doctor` owns inline path validation through `inline-path-dangling`.

Run the focused tests with:

```sh
bb scripts/test-check-record-identifiers.bb
```

`bb test` runs the focused tests in `scripts/test-check-record-identifiers.bb`.

## Skill frontmatter validation

```sh
scripts/validate-skills.bb --python /path/to/venv/bin/python
scripts/validate-skills.bb --python python3 --skills-dir .tmp/fixture
```

Runs `skills/skill-creator/scripts/quick_validate.py` over every `skills/*/SKILL.md` in two tiers. First-party and adopted skills gate the exit code; the vendored set from [../skills/README.org#Vendored](../skills/README.org#vendored) only warns, because those bodies follow upstream rather than this repository. `--skills-dir` exists so the gate itself can be tested against a fixture. An explicit `--python` path, including a relative path such as `.tmp/venv/bin/python`, resolves against the caller's directory before the validator changes directories. A bare command such as `python3` is looked up through `PATH`.

Run the interpreter-path and failure-control fixtures with `bb scripts/test-validate-skills.bb`.

`scripts/check.sh` calls it in place of the previous single-skill validation, which checked only `skills/code-review` and so proved the tooling ran without covering the skill set.

The Agent Skills specification defines exactly six frontmatter fields -- `name`, `description`, `license`, `compatibility`, `metadata` and `allowed-tools` -- and nominates `metadata` as the extension slot for client-specific data. Upstream `gitlab-cli-skills` adds top-level `openclaw:` and `requirements:` keys, so it warns on every run; that is upstream's non-conformance to fix, and the warning is the record of it. A hard error is the wrong severity for keys we do not own: Anthropic's own validator rejects the extension fields Claude Code documents (see anthropics/skills issue #394).

## Bundled herdr-app mod recovery

Recover an older herdr-app version's bundled mod source as the diff base for an `orch-spaces` review:

```sh
bb scripts/herdr-app-bundled-mod.bb <executable> '<first line of main.js>' <sha256> .tmp/spaces-old.js
```

The app embeds each bundled mod as plain text, and the installed `~/.config/herdr-app/mods/` holds only the current version's copy. The script starts at the first match of the marker and ends where the SHA-256 equals the hash recorded in `herdr-app/mods/orch-spaces/UPSTREAM.md`. It writes nothing and exits 1 when the marker is absent or no candidate matches the hash. The previous executable is available only while its Nix store path survives garbage collection. [herdr-app/README.md#Provenance and updates](../herdr-app/README.md#provenance-and-updates) gives the review procedure.
