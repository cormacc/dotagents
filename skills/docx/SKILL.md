---
name: docx
description: "Review/edit Word .docx files without Word: headers, footers, version history, paragraph-level tracked changes, anchored comments, tracked edits with reasons, verified clean copies for publication, redlines/comparisons, forms/templates, cells, checkboxes and footnotes. Use for Word revision review or programmatic edits, even without explicit .docx wording. For plain text reading use convert-documents-to-markdown."
compatibility: Needs Babashka. clean checks its result with pandoc when pandoc is installed. sections needs LibreOffice and pdftotext. compare needs LibreOffice; on Nix it finds the Python that LibreOffice links against.
---

# docx

Review and edit Word `.docx` packages as WordprocessingML. A `.docx` is a zip of XML parts; `word/document.xml` holds the body, `word/headerN.xml` and `word/footerN.xml` the headers and footers, `word/comments.xml` the comments.

## Commands

`scripts/docx.clj` prints JSON on success and a message on stderr on failure.

| Command | Output |
|---|---|
| `docx.clj inspect DOCX` | `headers`, `footers` (one string per part, paragraphs joined by ` \| `), `tracked_changes` (part name to `{ins, del}` counts), `version_history` (rows of cell text), `field_errors` (stored field error results) |
| `docx.clj changes DOCX` | One object per paragraph with tracked changes: `heading` (nearest heading above, by paragraph style), `before`, `after`; header and footer changes follow, with `part` |
| `docx.clj sections DOCX` | Changed paragraphs with `heading`, `before`, `after`, `section` (rendered decimal number or null) and `section_status`; header/footer changes include `part` |
| `docx.clj comments DOCX` | One object per comment: `id`, `author`, `date`, `text`, `heading`, and the anchored text `before` and `after` the tracked changes |
| `docx.clj edit IN SCRIPT OUT --author NAME [--initials XX] [--date ISO] [--no-tracking]` | Applies an edit script (below) as tracked changes; reports each edit |
| `docx.clj clean IN OUT [--keep-tracking] [--no-verify]` | Writes the clean copy: supported changes accepted, comments removed, tracking turned off; refuses deleted paragraph marks; reports the pandoc check |
| `docx.clj compare OLD NEW OUT` | Writes `OUT`, a `.docx` that shows OLD to NEW as tracked changes |

Exit codes: 0 success; 1 usage, input or unsupported-change error; 2 an edit did not match (nothing written); 3 the clean copy differs from pandoc's accept view.

- Text reads as if all tracked changes were accepted: inserted text is included, deleted text is not. `changes`, `sections` and `comments` give both sides.
- `version_history` is the last table with a header row that has a cell exactly `Version` and a cell that names a DCR or a description. Blank rows are dropped. A placeholder such as `<DD-Mmm-YYYY>` in the last row shows a field that the author has not completed.
- A large revision gives hundreds of `changes` entries. Redirect the output to a file and read it in slices.
- `changes` and `comments` report the nearest paragraph with a heading style. Use `sections` when headings have outline levels but no heading styles, as in QM0019. It recognises direct and inherited outline levels 0 to 8, or heading/title styles, without treating ordinary numbered list items as headings.
- `sections` renders a private copy with LibreOffice and reads its PDF with `pdftotext -layout`; it does not write the input or use a running LibreOffice profile. Its numbers describe LibreOffice's rendered review view, not a Word-verified clean copy. Repeated titles are matched between unique surrounding headings. A renderer failure exits 1; an unresolved number does not stop the report.
- Check `section_status`: `matched` has one decimal-number match; `unresolved` has none; `ambiguous` has several; `no-heading` precedes any recognised heading; `not-applicable` is a header/footer change. All except `matched` have `section: null`. Unnumbered headings, appendix letters and headings without style or outline metadata need manual review; no section number is guessed.

## Stored field errors

`inspect` reads cached field results from the body, headers, footers, footnotes and endnotes, without rendering or updating the fields. LibreOffice can recompute fields and hide a stored error, so check this report before rendering a draft.

`field_errors` is an array, empty when no matching error is found. Each entry has `part`, `paragraph` (the one-based XML paragraph where the field starts), `instruction` and `result`. Entries are sorted by part name and field-start order. For example:

```json
{"part": "word/document.xml", "paragraph": 2,
 "instruction": "REF Existing \\h", "result": "Error! Reference source not found."}
```

The check recognises simple and complex fields whose stored result starts with English `Error!` (case-insensitive, surrounding whitespace removed). It joins split runs, keeps nested fields separate, and excludes deleted or moved-from revisions. Ordinary prose and field instructions are not results. This is not reference-target validation: a stale error is reported even if its target exists; localised messages and other error formats need manual review.

## Edit scripts

A JSON (`.json`) or EDN (`.edn`) list of edits, applied in order:

```json
[
  {"op": "replace", "old": "ISO/IEC 62304", "new": "IEC 62304:2006+A1:2015",
   "comment": "Cite the current amended edition (clause 1)."},
  {"op": "replace", "in": {"starts": "The software system"}, "old": "shall", "new": "should"},
  {"op": "insert-after", "after": {"starts": "The SDP shall identify"}, "text": "New requirement text.",
   "comment": "Reason: clause 5.2.4."},
  {"op": "delete", "paragraph": {"equals": "Obsolete sentence."}},
  {"op": "replace", "part": "headers", "all": true, "old": "11.0 (CR-123)", "new": "12.0 (CR-456)"},
  {"op": "insert-row-after", "row": {"starts": "11.0 | 123"}, "cells": ["12.0", "456", "Summary of changes.", "<DD-Mmm-YYYY>"],
   "comment": "Version history row for the change record."},
  {"op": "delete-row", "row": {"contains": "Obsolete row"}}
]
```

- `replace` changes the only occurrence of `old` in one paragraph: by default the paragraph that contains `old`; with `in`, the paragraph that the selector picks.
- A selector is a string (the paragraph contains it) or `{"starts" | "contains" | "equals": text}`. It must pick exactly one paragraph. Text is matched in the accepted view (insertions in, deletions out). Automatic section numbers are not paragraph text: select by the paragraph's words.
- `insert-after` copies the anchor's paragraph properties (or `"style": "StyleId"`) and the run properties of its first text run.
- `insert-row-after` adds a table row with one value for each cell (a blank line in a value starts a new paragraph); it copies the anchor row's row, cell, paragraph and run properties, without vertical merges. `delete-row` removes a row. A row selector matches the row text: the cell texts joined by ` | `.
- `"part": "headers"` or `"footers"` edits the header or footer parts instead of the body (default `"body"`); selection runs over all of them. A document often has the same header in several parts (first page, default): `"all": true` applies the edit to every match, where otherwise exactly one must match.
- Word has no comments in headers or footers, so an edit there cannot carry a `comment`; neither can an edit with `all`, because one comment anchors one place. Give those reasons in the check-in comment or the change record.
- `comment` is the reason for review and for change records; a blank line in it starts a new comment paragraph.
- A match must lie in plain text runs. Text that crosses a field (a citation, a cross-reference), an existing tracked change or other non-text content is refused, so that other authors' changes are never split. Edit around it.
- The whole script runs in memory first. The first edit that does not match aborts with its index, and no output file is written.
- Changes carry `--author` and the run time (or `--date`). `edit` also turns on tracking in the document, so later edits in Word are tracked too.

## Editing a SharePoint document

Use the `ms365` skill's Graph client (`references/graph-client.md` there) for the SharePoint steps:

1. `graph.clj checkout URL`, then `graph.clj download URL in.docx`.
2. `docx.clj edit in.docx script.json out.docx --author "Name"`; check the report and `docx.clj changes out.docx`.
3. `graph.clj upload URL out.docx`. Repeat steps 1 to 3 (without a new check-out) as often as the session needs.
4. `graph.clj checkin URL --comment "..."`: one minor version for the session.

For one small change, `graph.clj transaction URL --comment "..." --exec '~/.agents/skills/docx/scripts/docx.clj edit {in} script.json {out} --author "Name"'` does all four steps and discards the check-out if any step fails. `--exec` runs in a shell from the current directory: give the script by its path.

Reviewers see the tracked changes and comments in desktop Word without a check-out; in a library that requires check-out, Word Online shows them only after a check-out.

## Clean copy

`docx.clj clean IN OUT` accepts insertions, run deletions, moves, property changes, and table-row and cell changes. It removes comment anchors, references and comment parts with their relationships and content types, and removes `trackRevisions` from the settings unless `--keep-tracking`.

A deleted paragraph mark in the body, a header, footer, footnote or endnote stops `clean` with exit 1 before it writes output. This includes marks inside table cells and marks made by the script's `delete` operation. `--no-verify` and `--keep-tracking` do not bypass this refusal. Use Word's **Accept All Changes**, save the copy, then run `clean` on that copy. Word's formatting rules for joined paragraphs are not reproduced by this tool.

The check compares the clean copy with pandoc's accept view of the input, paragraph by paragraph, after it removes list markers and whitespace. It checks text, not formatting. Before publication, open the clean copy in Word and check lists and paragraph formatting. If the command fails, do not upload its output or use `--no-verify` to bypass the publication check; investigate the difference first.

Use Pandoc 3.13 or later when a field instruction has tracked changes. Older readers can join later paragraphs and lose list text in the reference view, even after Word Accept All leaves a tracked instruction in the saved file ([pandoc#11943](https://github.com/jgm/pandoc/issues/11943)). Upgrade Pandoc and repeat `clean`; do not bypass verification.

`docx.clj comments` before `clean` keeps the review record: the comments are gone after it.

## Editing from Babashka

Add the namespaces to the classpath, then edit the parsed tree and write the package back:

```clojure
(require '[babashka.classpath :as cp] '[babashka.fs :as fs])
(cp/add-classpath (str (fs/expand-home "~/.agents/skills/docx/scripts/src")))
(require '[docx.core :as d :refer [w]] '[clojure.data.xml :as xml])

(let [entries (d/read-entries "form.docx")
      doc (d/parse-entry entries "word/document.xml")
      body (first (d/children doc (w "body")))
      body (d/update-table body "Section 1"
             (fn [t] (d/update-row t "Requested by"
                       #(d/update-cell % 1 (fn [c] (d/set-cell-text c "A. Person"))))))
      doc (assoc doc :content (mapv #(if (d/tag= (w "body") %) body %) (:content doc)))]
  (d/write-docx "out.docx" (d/replace-entries entries {"word/document.xml" (xml/emit-str doc)})))
```

Main functions (each has a docstring):

| Namespace | Area | Functions |
|---|---|---|
| `docx.core` | Package | `read-entries`, `entry-str`, `parse-entry`, `part-names`, `replace-entries`, `write-docx` |
| `docx.core` | Tree | `w`, `w14`, `el?`, `tag=`, `children`, `descendants`, `text`, `xform`, `with-kids`, `paras`, `rows`, `cells` |
| `docx.core` | Text | `set-par-text`, `set-cell-text`, `label+value` (keep a label, replace its hint), `append-run`, `update-para` |
| `docx.core` | Tables | `heading`, `row-idx`, `update-row`, `update-cell`, `update-table` |
| `docx.core` | Checkboxes | `checkbox?`, `n-checkboxes`, `set-checkbox`, `tick` (with `:expect n`) |
| `docx.core` | Footnotes | `prune-footnotes` (after you remove footnote references) |
| `docx.edit` | Tracked editing | `tracked-replace`, `tracked-insert-after`, `tracked-delete`, `tracked-insert-row-after`, `tracked-delete-row`, `row-text`, `update-paragraphs`, `update-unique-paragraph`, `max-id`, `id-source`, `enable-tracking` |
| `docx.edit` | Comments, scripts | `ensure-comments-part`, `add-comment`, `selector`, `apply-script` |
| `docx.clean` | Clean copy | `clean-xml`, `clean-entries` |
| `docx.review` | Review | `inspect`, `changes`, `sections`, `comments`, `compare-docx`, `lo-python` |

- Find tables, rows and paragraphs by their text, not by index, so that a changed template fails instead of writing to the wrong place. Lookup functions throw `ex-info` with `{:docx/error :layout}`; matching in `docx.edit` throws `{:docx/error :match}`.

## Gotchas

- Namespace prefixes: `mc:Ignorable` lists prefixes by name, so a writer that renames prefixes produces a file that Word rejects. `clojure.data.xml` (in Babashka) keeps the original prefixes; keep to it.
- `python-docx` `cell.text` and `paragraph.text` skip runs inside `w:ins`, so they lose inserted text in a document with tracked changes. `docx.core/text` reads every `w:t`.
- Word writes a UTF-8 byte-order mark in some parts. `entry-str` removes it; a direct `xml/parse-str` on the raw bytes fails with "Content is not allowed in prolog".
- Word rejects `settings.xml` children out of schema order; `enable-tracking` inserts `w:trackRevisions` in its place.
- A paragraph whose text was all deleted keeps its place unless its paragraph mark is deleted too. Check deleted marks before publication: a deleted mark between two list items or references joins them into one paragraph.
- Word Online displays hand-written tracked changes and comments without `w14:paraId` or `commentsExtended` parts.
- LibreOffice's accept-all kept plain `w:del` text in a Word-authored draft; do not use it as the reference for a clean copy.
- After you delete a run that holds a `w:footnoteReference`, call `prune-footnotes`: LibreOffice otherwise shows the orphan footnote text against the remaining references.
- On Nix, the LibreOffice wrapper's `program/` directory holds symlinks to `libpyuno.so`. UNO started from the wrapper directory fails; `lo-python` resolves the real directory.
- LibreOffice renders some Word layouts differently (for example, an extra blank page). Compare with a render of the unmodified file before you treat a layout difference as a defect.

## Tests

`scripts/test-docx.bb` builds its fixtures from hand-written WordprocessingML at run time; no fixture file is committed. Tracked edits are checked against pandoc's accept and reject views (SKIP without pandoc) and, because pandoc drops empty paragraphs, against the paragraph marks directly. The compare test needs LibreOffice and prints SKIP without it. The sections CLI test needs `soffice` and `pdftotext`, and prints SKIP if either is missing; it checks automatic numbering, inherited outline levels, repeated titles, tracked heading edits, table cells, deletions and an unchanged input package. Focused tests also cover missing/ambiguous numbers and render failures. Stored field error tests check simple/complex fields, nested fields, paragraph boundaries, split runs, story parts, deleted revisions, false positives and an unchanged input package. `DOCX_TEST_SELFCHECK=1` adds one assertion that must fail.
