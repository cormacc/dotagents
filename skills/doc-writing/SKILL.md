---
name: doc-writing
description: Write, restructure and check documentation -- Markdown and Org documents (READMEs, design records, architecture and onboarding guides) and code comments and docstrings. Covers terminology, identifier checks, where each kind of text belongs, line wrapping, Org and Markdown structure, and Mermaid and PlantUML diagrams and their GitLab rendering, with scripts that unwrap hard-wrapped files and render diagram blocks. Use whenever you create or substantially edit a .md or .org document, add or change a diagram, unwrap text, or write, review or rewrite comments and docstrings as a task, even if formatting is not mentioned. Not for looking up or reading external documentation. Voice and spelling rules are in AGENTS.md.
compatibility: The scripts need Babashka. check-diagrams.bb also needs mmdc (@mermaid-js/mermaid-cli) for Mermaid and plantuml for PlantUML; Org table alignment needs Emacs.
---

# Doc writing

Write and check the documentation that people read alongside code: standalone documents, and the comments and docstrings in the code. Voice rules (Simplified Technical English, spelling, identifiers) are in `AGENTS.md` and apply to all of it; this skill does not repeat them.

Precedence: a project's own `AGENTS.md` conventions override this skill. Do not edit vendored or third-party text, because a re-sync loses every local change.

## All documentation

- **Terminology.** Use one term for each thing, in prose, comments, diagrams and tables. Prefer the upstream term: if a library calls something a "phase function", do not invent "hook" for it. Define a term once, near its first use. When you name or rename something, check that the name does not suggest a different, existing thing, for example a context key `::stack` beside a library's own `:stack` key.
- **Identifiers.** Verify each identifier, path and command against the source before you write it. After a rename, search comments, docstrings and documents for the old name too.
- **One home for each fact.** A docstring owns a contract, a design record owns a decision and its rationale, and a standalone document explains and links to both. Link instead of copying: a copy goes stale without anyone noticing.

## Comments and docstrings

The standalone-document rules below do not apply here. In particular, follow the code's line length: comments and docstrings are hard-wrapped like the code around them.

- Follow the language's docstring format. Language skills hold the detail, for example the `clojure` skill's `references/idioms.md`.
- A docstring states the contract: what the function takes, returns, throws and changes. It does not describe the implementation. Update it in the same change as the code.
- A comment says why: a constraint, a non-obvious decision, a trap. Do not restate what the next line does.
- Describe the current code, not its history. Do not write "now", "previously" or "was changed to"; version control holds the history.
- Mark a deliberately accepted limit with a `known-limit:` comment that names the limit and the trigger to revisit it.
- Keep long rationale in a design record, and link to it from the comment.

## Standalone documents

### Line wrapping

Do not hard-wrap prose in Org or Markdown documents. Write each paragraph and each list item as one line, and let the editor wrap it on screen. Hard-wrapped text makes every edit reflow its neighbours, so diffs and reviews show lines that did not change.

Keep real line breaks only where the format needs them: headings, keyword lines, drawers, tables, code and example blocks, quotes, and explicit line breaks (Org `\\`, Markdown two trailing spaces or `\`).

When you edit a hard-wrapped file, unwrap only the paragraphs you change, unless the user asks you to unwrap the whole file. To unwrap a whole file, use the script:

```bash
bb <skill-dir>/scripts/unwrap.bb <file.org|file.md> <candidate-file>
```

Write the candidate to a scratch file, read its diff, then replace the original. The script keeps every line that it is not sure is prose, and its guard refuses to write if the output's words differ from the input's (exit 1). Check that the file is not open with unsaved changes in an editor before you replace it.

### Org

- Align tables with Org, not by hand: `emacs --batch -Q FILE --eval "(progn (org-mode) (org-table-map-tables #'org-table-align t) (save-buffer))"`. If the file is open in a running Emacs, align it there, so that the buffer and the file stay the same.
- Mark code as `~code~` or `=verbatim=`. Use a description list (`- Term :: detail`) for definitions.
- Write source blocks as `#+BEGIN_SRC <language>` and `#+END_SRC`, with the language, so that renderers highlight it.
- Write relative file links: `[[../src/foo.clj][foo.clj]]`. GitLab and Emacs resolve them the same way.
- A link to a heading behaves differently on each renderer. GitLab generates heading anchors (lower case, punctuation removed, spaces as hyphens), so `[[#my-heading][My heading]]` works there; Emacs reads `#my-heading` as a `CUSTOM_ID`. If both must work, give the heading a matching `CUSTOM_ID` property. See https://docs.gitlab.com/user/org_mode/ for the anchor rules.

### Markdown

- Give every fenced code block a language.
- Write relative links to repository files, so that they work on every host and in a local clone.
- Do not align table columns by hand: renderers ignore the alignment, and it inflates diffs.

### Diagrams

Write diagrams as source blocks in the document, not as committed images, so that a reviewer can read the diff.

- Use Mermaid for flowcharts, trees and entity relationships.
- Use PlantUML for sequence and state diagrams. It supports activation bars, `== separators ==` and `...` delays, which show a timeline, and in tests it placed state-diagram notes more cleanly than Mermaid.
- GitLab renders `mermaid` and `plantuml` blocks in both Markdown and Org (https://docs.gitlab.com/user/org_mode/). Mermaid needs no set-up; PlantUML needs a PlantUML server, which a GitLab administrator enables on a self-managed instance. For another host, check its documentation before you rely on a diagram type.
- In PlantUML text, `[[...]]` is a hyperlink, so a nested vector such as `[[:a :b]]` renders as a link. Write it another way.
- In Mermaid, quote labels that contain punctuation (`A["[:x] or (y)"]`) and use `<br/>` for a line break.

Render every diagram that you add or change, then look at the images: a block can render without error and still be unreadable.

```bash
bb <skill-dir>/scripts/check-diagrams.bb --out <scratch-dir> FILE.org FILE.md ...
```

It prints PASS, FAIL or UNAVAILABLE (tool not installed) for each block, and WARN for known traps. Exit codes: 0 every block passed, 1 a block failed, 3 a tool was unavailable. Do not report a diagram as checked when its tool was unavailable.

### Technical documents

For a design, architecture or onboarding document:

- Say who the document is for and in what order to read it. Separate what a user of the code must know from how the code works, and from how to change it safely.
- State that the code is the authority, and link to it, to its docstrings and to design records.
- Give worked examples from real runs. Say what you ran them against (version, date) so that a reader can tell when they may be out of date.
- Record the invariants that a change must keep, and the known limits, with the trigger to revisit each one.

## Scripts

| Script | Use |
|--------|-----|
| `scripts/unwrap.bb` | Unwrap hard-wrapped Org or Markdown, with a word-preservation guard. |
| `scripts/check-diagrams.bb` | Render each Mermaid and PlantUML block in Org or Markdown files, and report traps. |

Each has a `test-*.bb` beside it. Run them after you change a script.
