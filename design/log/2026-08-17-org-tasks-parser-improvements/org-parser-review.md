# org-parser adoption review

Date: 2026-08-17

## Conclusion

Do not adopt `org-parser` as the production parser for `org-tasks`. It does not provide the protocol model, lossless writer, Babashka compatibility, or licence fit that `ot` requires. Keep the protocol-specific parser and improve its internal boundaries and source preservation.

## Reviewed surfaces

- Current implementation: `skills/org-tasks/scripts/src/org_tasks/parser.clj`, the focused `parser/` namespaces, `section.clj`, `doctor.clj`, `loader.clj`, and `commands/move.clj`.
- Current contracts: `skills/org-tasks/scripts/AGENTS.md` and `skills/org-tasks/scripts/docs/DESIGN.org`.
- Upstream release: `org-parser` 0.1.28 from Clojars and the 200ok-ch repository source.

## Findings

### Model mismatch

`org-tasks.parser/parse-tasks` produces a nested protocol task graph with lifecycle state, properties, LOGBOOK data, imports, source ownership, and line ranges. `org-parser/read-str` produces flat headlines with level values. Its high-level result does not provide the nested writable task model or structured property data that `ot` needs. An adapter would reconstruct much of the current scanner.

### Writer mismatch

The upstream README marks the project as work in progress and says that its AST can change. The source marks rendering as incomplete. Probes against 0.1.28 confirmed that only a minimal heading round-tripped. A normal `ot` task lost its TODO keyword, priority, and tags, while drawer and body AST values appeared in rendered output. Both sampled `org-tasks` round-trip fixtures failed.

### Runtime and packaging mismatch

The published release failed to load under Babashka because Instaparse 1.4.12 uses an unsupported `deftype`. This conflicts with the repository requirement that `ot` remain Babashka-compatible. The published JAR also failed from a normal tools.deps working directory because `org-parser.parser` refers to `resources/org.ebnf`, while the JAR contains `org.ebnf`. It loaded from the cloned upstream repository because that filesystem path existed there.

### Performance cost

A same-process, warmed JVM benchmark parsed the 44,801-character `TASKS.org` seven times with each implementation. `org-tasks/parse-tasks` had a 1.23 ms median. `org-parser/read-str` had a 68.71 ms median. The operations are not semantically equivalent because `org-parser` parses more syntax, but `ot` does not use that additional syntax.

### Licence difference

Dotagents is MIT licensed. `org-parser` is AGPL-3.0. A distributed runtime dependency would require a deliberate licensing decision.

### Existing parser defect found during review

The task scanner does not shield Org blocks. A probe with `* TODO Literal` inside `#+BEGIN_EXAMPLE` and `#+END_EXAMPLE` produced a separate task named `Literal`. `org-tasks.section/read-section` already tracks matched block boundaries, while `org-tasks.parser/parse-tasks` does not. A shared block-aware line classifier can remove this inconsistency without a general Org AST.

## Recommendations

1. Keep the protocol-specific parser and do not add `org-parser`, Instaparse, or a JVM parser subprocess.
2. Introduce one lossless line classifier for headings, block boundaries, drawer boundaries, and file keywords. Reuse its block state in task parsing, section extraction, and block-aware doctor checks.
3. Split task scanning and task rendering into focused namespaces while retaining `org-tasks.parser` as the stable facade.
4. Move readiness evaluation out of the parser namespace because it is graph logic.
5. Derive accepted task statuses from `org-tasks.lifecycle/status-cycle` instead of duplicating the status list in the heading expression.
6. Preserve canonical serializers for creation and transfer flows. Use targeted source patches for in-place mutations only where regression tests prove that whole-root serialisation changes unrelated bytes.
7. Retain byte-identical round-trip fixtures and add mutation-locality fixtures for status, priority, property, and link changes.

## Verification performed

- `bb test-clojure org-tasks.parser-test` completed 24 tests and 100 assertions with no failures.
- A Babashka load probe for `org-parser` 0.1.28 failed in `instaparse/auto_flatten_seq.clj`.
- JVM parse and render probes covered a minimal heading, a normal task with a property drawer, nested headings, and two existing round-trip fixtures.
- The block probe confirmed that the current task scanner treats a task-shaped line inside an example block as a task.
- The warmed JVM benchmark used seven measured runs per parser against the same `TASKS.org` content.

## Upstream sources

- Repository and project status: https://github.com/200ok-ch/org-parser
- Parser entry point: https://github.com/200ok-ch/org-parser/blob/master/src/org_parser/parser.clj
- Transform model: https://github.com/200ok-ch/org-parser/blob/master/src/org_parser/transform.cljc
- Renderer: https://github.com/200ok-ch/org-parser/blob/master/src/org_parser/render.cljc
- Release metadata and dependencies: https://clojars.org/org-parser
- Licence: https://github.com/200ok-ch/org-parser/blob/master/LICENSE
