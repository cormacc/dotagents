#!/usr/bin/env tsx
/** Closure-time summary-refresh detection tests. */

import {
  evaluateSummaryRefresh,
  hasSummaryHeading,
  parseOrgTimestamp,
} from "./summary.ts";

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

// ── hasSummaryHeading ────────────────────────────────────────────────

assertEqual(
  hasSummaryHeading("* Summary\nbody\n* Context\n"),
  true,
  "hasSummaryHeading: detects top-level * Summary heading",
);

assertEqual(
  hasSummaryHeading("* Context\n* Plan\n"),
  false,
  "hasSummaryHeading: returns false when section is absent",
);

assertEqual(
  hasSummaryHeading("* Implementation\n** Outcome\nNot a top-level Summary heading.\n"),
  false,
  "hasSummaryHeading: a level-2 ** heading under * Implementation does not count as * Summary",
);

assertEqual(
  hasSummaryHeading("** Summary\nNot a top-level heading.\n"),
  false,
  "hasSummaryHeading: nested ** Summary does not count",
);

// ── parseOrgTimestamp ────────────────────────────────────────────────

const t1 = parseOrgTimestamp("2026-05-01 Fri 09:41");
assertEqual(t1 !== null, true, "parseOrgTimestamp: parses date+time form");
assertEqual(
  new Date(t1!).getFullYear(),
  2026,
  "parseOrgTimestamp: year decoded",
);

const t2 = parseOrgTimestamp("2026-05-01 Fri");
assertEqual(t2 !== null, true, "parseOrgTimestamp: parses date-only form");

assertEqual(
  parseOrgTimestamp("not a timestamp"),
  null,
  "parseOrgTimestamp: rejects non-timestamp input",
);

assertEqual(
  parseOrgTimestamp(null),
  null,
  "parseOrgTimestamp: null input returns null",
);

// ── evaluateSummaryRefresh ───────────────────────────────────────────

const richContent = [
  "* Summary",
  "Compact final-state paragraph.",
  "** Decisions",
  "- Foo :: Bar.",
  "* Context",
  "Background.",
  "",
].join("\n");

const noSummaryContent = [
  "* Context",
  "Background.",
  "* Implementation",
  "Did the thing.",
  "",
].join("\n");

assertEqual(
  evaluateSummaryRefresh(noSummaryContent, "2026-05-01 Fri 09:00", Date.now()),
  "missing",
  "evaluateSummaryRefresh: record without * Summary returns missing",
);

assertEqual(
  evaluateSummaryRefresh(richContent, null, Date.now()),
  null,
  "evaluateSummaryRefresh: present + no STARTED → no prompt",
);

assertEqual(
  evaluateSummaryRefresh(richContent, "2026-05-01 Fri 09:00", null),
  null,
  "evaluateSummaryRefresh: present + no mtime → no prompt",
);

const startedAt = parseOrgTimestamp("2026-05-01 Fri 09:00")!;

assertEqual(
  evaluateSummaryRefresh(richContent, "2026-05-01 Fri 09:00", startedAt + 10_000),
  null,
  "evaluateSummaryRefresh: file touched after STARTED → no prompt",
);

assertEqual(
  evaluateSummaryRefresh(
    richContent,
    "2026-05-01 Fri 09:00",
    startedAt - 5 * 60 * 1000,
  ),
  "stale",
  "evaluateSummaryRefresh: file mtime predates STARTED → stale",
);

assertEqual(
  evaluateSummaryRefresh(
    richContent,
    "2026-05-01 Fri 09:00",
    startedAt - 30_000,
  ),
  null,
  "evaluateSummaryRefresh: 60s grace window absorbs same-minute saves",
);

console.log(`\n# ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
