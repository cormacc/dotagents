---
name: docx
description: Review and edit Word .docx files without Word. Report header, footer and version history text; list tracked changes paragraph by paragraph with the text before and after; compare two versions of a document into a tracked-changes .docx (redline) with LibreOffice; and edit cells, checkboxes and footnotes safely from Babashka through the docx.core namespace. Use whenever a task needs what changed in a Word document, a redline or comparison, the version history or header of a .docx, or a programmatic edit of a .docx form or template. For plain reading of a document's text, use convert-documents-to-markdown instead.
compatibility: Needs Babashka. The compare command also needs LibreOffice; on Nix it finds the Python that LibreOffice links against.
---

# docx

Review and edit Word `.docx` packages as WordprocessingML. A `.docx` is a zip of XML parts; `word/document.xml` holds the body, `word/headerN.xml` and `word/footerN.xml` the headers and footers.

## Commands

`scripts/docx.clj` prints JSON. Exit 0 on success, 1 on an error (message on stderr).

| Command | Output |
|---|---|
| `docx.clj inspect DOCX` | `headers`, `footers` (one string per part, paragraphs joined by ` \| `), `tracked_changes` (part name to `{ins, del}` counts), `version_history` (rows of cell text) |
| `docx.clj changes DOCX` | One object per paragraph with tracked changes: `heading` (nearest heading above, by paragraph style), `before`, `after` |
| `docx.clj compare OLD NEW OUT` | Writes `OUT`, a `.docx` that shows OLD to NEW as tracked changes |

- Text reads as if all tracked changes were accepted: inserted text is included, deleted text is not. `changes` gives both sides.
- `version_history` is the last table with a header row that has a cell exactly `Version` and a cell that names a DCR or a description. Blank rows are dropped. A placeholder such as `<DD-Mmm-YYYY>` in the last row shows a field that the author has not completed.
- A large revision gives hundreds of `changes` entries. Redirect the output to a file and read it in slices.
- For the full text with changes marked inline, `pandoc --track-changes=all -t markdown DOCX` is an alternative.

## Choosing between review routes

- The author tracked the changes: use `changes` on the draft. Word's own markup is the most faithful record.
- No tracked changes: run `compare` with the last approved version as OLD, then `changes` on the result. LibreOffice marks more than Word does: moved and re-flowed text appears as delete and insert. Describe substantive changes only. In one test on a procedure revision, LibreOffice marked 1381 insertions and 549 deletions where Word had tracked 597 and 112.
- To give a reader a redline, upload the tracked-changes `.docx` and print it to PDF from Word or Word Online. Check that the PDF shows the markup.

## Editing from Babashka: docx.core

Add the namespace to the classpath, then edit the parsed tree and write the package back:

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

Main functions (each has a docstring in `scripts/src/docx/core.clj`):

| Area | Functions |
|---|---|
| Package | `read-entries`, `entry-str`, `parse-entry`, `part-names`, `replace-entries`, `write-docx` |
| Tree | `w`, `w14`, `el?`, `tag=`, `children`, `descendants`, `text`, `xform`, `with-kids`, `paras`, `rows`, `cells` |
| Text | `set-par-text`, `set-cell-text`, `label+value` (keep a label, replace its hint), `append-run`, `update-para` |
| Tables | `heading`, `row-idx`, `update-row`, `update-cell`, `update-table` |
| Checkboxes | `checkbox?`, `n-checkboxes`, `set-checkbox`, `tick` (with `:expect n`) |
| Footnotes | `prune-footnotes` (after you remove footnote references) |

- Find tables, rows and paragraphs by their text, not by index, so that a changed template fails instead of writing to the wrong place. Lookup functions throw `ex-info` with `{:docx/error :layout}` when the text is not found.
- `scripts/src/docx/review.clj` holds `inspect`, `changes`, `compare-docx` and `lo-python` as functions.

## Gotchas

- Namespace prefixes: `mc:Ignorable` lists prefixes by name, so a writer that renames prefixes produces a file that Word rejects. `clojure.data.xml` (in Babashka) keeps the original prefixes; keep to it.
- `python-docx` `cell.text` and `paragraph.text` skip runs inside `w:ins`, so they lose inserted text in a document with tracked changes. `docx.core/text` reads every `w:t`.
- Word writes a UTF-8 byte-order mark in some parts. `entry-str` removes it; a direct `xml/parse-str` on the raw bytes fails with "Content is not allowed in prolog".
- After you delete a run that holds a `w:footnoteReference`, call `prune-footnotes`: LibreOffice otherwise shows the orphan footnote text against the remaining references.
- On Nix, the LibreOffice wrapper's `program/` directory holds symlinks to `libpyuno.so`. UNO started from the wrapper directory fails; `lo-python` resolves the real directory.
- LibreOffice renders some Word layouts differently (for example, an extra blank page). Compare with a render of the unmodified file before you treat a layout difference as a defect.

## Tests

`scripts/test-docx.bb` builds its fixtures from hand-written WordprocessingML at run time; no fixture file is committed. The compare test needs LibreOffice and prints SKIP without it. `DOCX_TEST_SELFCHECK=1` adds one assertion that must fail.
