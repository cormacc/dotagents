---
name: docx
description: Review and edit Word .docx files without Word. Report header, footer and version history text; list tracked changes paragraph by paragraph; list comments with the text they anchor; apply an edit script as tracked changes with reason comments; produce a clean copy (all changes accepted, comments removed) checked against pandoc; compare two versions into a tracked-changes .docx (redline) with LibreOffice; and edit cells, checkboxes and footnotes from Babashka through docx.core and docx.edit. Use whenever a task needs what changed in a Word document, a redline or comparison, tracked edits or review comments in a .docx, a clean copy for publication, the version history or header of a .docx, or a programmatic edit of a .docx form or template. For plain reading of a document's text, use convert-documents-to-markdown instead.
compatibility: Needs Babashka. clean checks its result with pandoc when pandoc is installed. compare needs LibreOffice; on Nix it finds the Python that LibreOffice links against.
---

# docx

Review and edit Word `.docx` packages as WordprocessingML. A `.docx` is a zip of XML parts; `word/document.xml` holds the body, `word/headerN.xml` and `word/footerN.xml` the headers and footers, `word/comments.xml` the comments.

## Commands

`scripts/docx.clj` prints JSON on success and a message on stderr on failure.

| Command | Output |
|---|---|
| `docx.clj inspect DOCX` | `headers`, `footers` (one string per part, paragraphs joined by ` \| `), `tracked_changes` (part name to `{ins, del}` counts), `version_history` (rows of cell text) |
| `docx.clj changes DOCX` | One object per paragraph with tracked changes: `heading` (nearest heading above, by paragraph style), `before`, `after` |
| `docx.clj comments DOCX` | One object per comment: `id`, `author`, `date`, `text`, `heading`, and the anchored text `before` and `after` the tracked changes |
| `docx.clj edit IN SCRIPT OUT --author NAME [--initials XX] [--date ISO] [--no-tracking]` | Applies an edit script (below) as tracked changes; reports each edit |
| `docx.clj clean IN OUT [--keep-tracking] [--no-verify]` | Writes the clean copy: all tracked changes accepted, all comments removed, tracking turned off; reports the pandoc check |
| `docx.clj compare OLD NEW OUT` | Writes `OUT`, a `.docx` that shows OLD to NEW as tracked changes |

Exit codes: 0 success; 1 usage or input error; 2 an edit did not match (nothing written); 3 the clean copy differs from pandoc's accept view.

- Text reads as if all tracked changes were accepted: inserted text is included, deleted text is not. `changes` and `comments` give both sides.
- `version_history` is the last table with a header row that has a cell exactly `Version` and a cell that names a DCR or a description. Blank rows are dropped. A placeholder such as `<DD-Mmm-YYYY>` in the last row shows a field that the author has not completed.
- A large revision gives hundreds of `changes` entries. Redirect the output to a file and read it in slices.
- `changes` and `comments` report the nearest paragraph with a heading style. Many documents number sections without heading styles; then render the document (LibreOffice to PDF, `pdftotext -layout`) and look up the changed text to find its section number.

## Edit scripts

A JSON (`.json`) or EDN (`.edn`) list of edits, applied in order:

```json
[
  {"op": "replace", "old": "ISO/IEC 62304", "new": "IEC 62304:2006+A1:2015",
   "comment": "Cite the current amended edition (clause 1)."},
  {"op": "replace", "in": {"starts": "The software system"}, "old": "shall", "new": "should"},
  {"op": "insert-after", "after": {"starts": "The SDP shall identify"}, "text": "New requirement text.",
   "comment": "Reason: clause 5.2.4."},
  {"op": "delete", "paragraph": {"equals": "Obsolete sentence."}}
]
```

- `replace` changes the only occurrence of `old` in one paragraph: by default the paragraph that contains `old`; with `in`, the paragraph that the selector picks.
- A selector is a string (the paragraph contains it) or `{"starts" | "contains" | "equals": text}`. It must pick exactly one paragraph. Text is matched in the accepted view (insertions in, deletions out). Automatic section numbers are not paragraph text: select by the paragraph's words.
- `insert-after` copies the anchor's paragraph properties (or `"style": "StyleId"`) and the run properties of its first text run.
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

`docx.clj clean IN OUT` accepts insertions, deletions, moves, property changes, table-row and cell changes, and deleted paragraph marks (the paragraph's remaining content joins the next paragraph, which keeps its properties). It removes comment anchors, references and comment parts with their relationships and content types, and removes `trackRevisions` from the settings unless `--keep-tracking`.

The check compares the clean copy, paragraph by paragraph, with pandoc's accept view of the input. Pandoc does not apply a deleted paragraph mark (it keeps an empty list item) and adds a space where it merges paragraphs, so the check strips list markers and ignores whitespace inside a paragraph; it still compares every character and the paragraph count. Before publication, open the clean copy in Word and check lists and merged paragraphs.

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
| `docx.edit` | Tracked editing | `tracked-replace`, `tracked-insert-after`, `tracked-delete`, `update-unique-paragraph`, `max-id`, `id-source`, `enable-tracking` |
| `docx.edit` | Comments, scripts | `ensure-comments-part`, `add-comment`, `selector`, `apply-script` |
| `docx.clean` | Clean copy | `clean-xml`, `clean-entries` |
| `docx.review` | Review | `inspect`, `changes`, `comments`, `compare-docx`, `lo-python` |

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

`scripts/test-docx.bb` builds its fixtures from hand-written WordprocessingML at run time; no fixture file is committed. Tracked edits are checked against pandoc's accept and reject views (SKIP without pandoc) and, because pandoc drops empty paragraphs, against the paragraph marks directly. The compare test needs LibreOffice and prints SKIP without it. `DOCX_TEST_SELFCHECK=1` adds one assertion that must fail.
