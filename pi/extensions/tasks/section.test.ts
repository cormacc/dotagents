#!/usr/bin/env tsx
/** Tests for the level-1 org section reader. */

import { DEFAULT_SECTION, readSection, type SectionResult } from "./section.ts";

let passed = 0;
let failed = 0;

function assertEqual<T>(actual: T, expected: T, message: string): void {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    passed++;
    console.log(`ok - ${message}`);
  } else {
    failed++;
    console.log(`not ok - ${message}`);
    console.log(`  expected: ${e}`);
    console.log(`  actual:   ${a}`);
  }
}

function found(heading: string, body: string): SectionResult {
  return { found: true, heading, body };
}

function notFound(section: string): SectionResult {
  return { found: false, section };
}

// ── DEFAULT_SECTION export ───────────────────────────────────────────

assertEqual(
  DEFAULT_SECTION,
  "Summary",
  "DEFAULT_SECTION is 'Summary' (org-plan convention)",
);

// ── Section present (the simple case) ────────────────────────────────

const simple = [
  "#+TITLE: doc",
  "",
  "* Summary",
  "Compact paragraph.",
  "",
  "* Plan",
  "** TODO Step",
  "",
].join("\n");

assertEqual(
  readSection(simple, "Summary"),
  found("* Summary", "Compact paragraph.\n"),
  "section present: returns heading + body up to next * heading",
);

assertEqual(
  readSection(simple, "Plan"),
  found("* Plan", "** TODO Step\n"),
  "nested ** subheadings inside the matched section are preserved verbatim",
);

// ── Default-to-Summary path ──────────────────────────────────────────

assertEqual(
  readSection(simple),
  found("* Summary", "Compact paragraph.\n"),
  "no section argument defaults to Summary",
);

assertEqual(
  readSection(simple, ""),
  found("* Summary", "Compact paragraph.\n"),
  "empty-string section argument also defaults to Summary",
);

assertEqual(
  readSection(simple, "   "),
  found("* Summary", "Compact paragraph.\n"),
  "whitespace-only section argument also defaults to Summary",
);

// ── Section absent ───────────────────────────────────────────────────

assertEqual(
  readSection(simple, "Implementation"),
  notFound("Implementation"),
  "section absent: returns { found: false, section }",
);

assertEqual(
  readSection(simple, "implementation"),
  notFound("implementation"),
  "section absent: echoes the user-requested casing in `section`",
);

// ── Section is the last in the file ──────────────────────────────────

const trailingSection = [
  "* Context",
  "Background.",
  "",
  "* Open questions",
  "** OPEN Should we batch follow-ups?",
  "",
].join("\n");

assertEqual(
  readSection(trailingSection, "Open questions"),
  found(
    "* Open questions",
    "** OPEN Should we batch follow-ups?\n",
  ),
  "section that runs to EOF: body slice extends to end of file",
);

const noTrailingNewline = "* Summary\nbody line";
assertEqual(
  readSection(noTrailingNewline, "Summary"),
  found("* Summary", "body line"),
  "EOF mid-line (no trailing newline): body preserved verbatim",
);

// ── File with no headings ────────────────────────────────────────────

const noHeadings = "Just some prose.\nNo headings at all.\n";
assertEqual(
  readSection(noHeadings, "Summary"),
  notFound("Summary"),
  "file with zero headings: not found",
);

assertEqual(
  readSection("", "Summary"),
  notFound("Summary"),
  "empty file: not found",
);

// ── Literal `* ` inside a #+BEGIN_SRC block must not terminate ───────

const srcBlock = [
  "* Summary",
  "Example code below:",
  "#+BEGIN_SRC org",
  "* This looks like a heading but is inside SRC",
  "** And so does this",
  "#+END_SRC",
  "More body after the block.",
  "",
  "* Context",
  "Real next section.",
  "",
].join("\n");

assertEqual(
  readSection(srcBlock, "Summary"),
  found(
    "* Summary",
    [
      "Example code below:",
      "#+BEGIN_SRC org",
      "* This looks like a heading but is inside SRC",
      "** And so does this",
      "#+END_SRC",
      "More body after the block.",
      "",
    ].join("\n"),
  ),
  "literal `* ` inside #+BEGIN_SRC does not terminate the slice",
);

const exampleBlock = [
  "* Summary",
  "#+BEGIN_EXAMPLE",
  "* Fake heading in example",
  "#+END_EXAMPLE",
  "",
  "* Context",
  "Next.",
  "",
].join("\n");

assertEqual(
  readSection(exampleBlock, "Summary"),
  found(
    "* Summary",
    [
      "#+BEGIN_EXAMPLE",
      "* Fake heading in example",
      "#+END_EXAMPLE",
      "",
    ].join("\n"),
  ),
  "literal `* ` inside #+BEGIN_EXAMPLE is also shielded (generic #+BEGIN_<kind>)",
);

const lowercaseDirectives = [
  "* Summary",
  "#+begin_src",
  "* fake",
  "#+end_src",
  "",
  "* Plan",
  "later",
  "",
].join("\n");

assertEqual(
  readSection(lowercaseDirectives, "Summary"),
  found(
    "* Summary",
    "#+begin_src\n* fake\n#+end_src\n",
  ),
  "block directives are matched case-insensitively (#+begin_src / #+end_src)",
);

// ── Heading match: case-insensitive + trailing :tags: tolerant ──────

const taggedHeadings = [
  "* SUMMARY :memory:",
  "Yelling summary.",
  "",
  "* plan :wip:foo:",
  "later",
  "",
].join("\n");

assertEqual(
  readSection(taggedHeadings, "summary"),
  found("* SUMMARY :memory:", "Yelling summary.\n"),
  "case-insensitive heading match with trailing :tags:",
);

assertEqual(
  readSection(taggedHeadings, "Plan"),
  found("* plan :wip:foo:", "later\n"),
  "case-insensitive heading match with multiple tags",
);

// ── ** Summary is NOT a level-1 heading ──────────────────────────────

const nestedOnly = [
  "* Context",
  "Background.",
  "** Summary",
  "Nested, not level-1.",
  "",
].join("\n");

assertEqual(
  readSection(nestedOnly, "Summary"),
  notFound("Summary"),
  "** Summary (level-2) is not matched as a section",
);

// ── First matching section wins; duplicates ignored ──────────────────

const dupes = [
  "* Summary",
  "First.",
  "* Summary",
  "Second.",
  "",
].join("\n");

assertEqual(
  readSection(dupes, "Summary"),
  found("* Summary", "First."),
  "duplicate * Summary sections: first match wins, slice ends at next * heading",
);

// ── Empty body (heading immediately followed by next heading) ────────

const emptyBody = [
  "* Summary",
  "* Context",
  "Has body.",
  "",
].join("\n");

assertEqual(
  readSection(emptyBody, "Summary"),
  found("* Summary", ""),
  "empty body: heading directly followed by next * heading returns body=''",
);

console.log(`\n# ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
