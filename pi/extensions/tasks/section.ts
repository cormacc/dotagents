/**
 * Pure org-file primitive: extract a single top-level (level-1) section
 * from an org buffer.
 *
 * Returns the section's heading line verbatim plus the body up to (but
 * not including) the next column-0 `* ` heading, with nested `**`/`***`
 * subheadings preserved. Source-block (`#+BEGIN_…` / `#+END_…`) regions
 * are tracked so literal `* ` lines inside example/src/quote blocks do
 * not terminate the slice early.
 *
 * Heading match is case-insensitive and tolerates a trailing org tag
 * suffix on the heading line (e.g. `* Summary :memory:`).
 *
 * Lives in the tasks extension for now; will migrate to a dedicated
 * `pi/extensions/org/` extension once tree-sitter infrastructure lands
 * (TASKS.org task f361c429-45dd-4364-9fa3-1f77bd7c600a). The tool
 * contract is namespaced `org_*` so that move is a no-churn migration.
 */

/**
 * Discriminated union return:
 *
 * - `{ found: true, heading, body }` — matched section. `heading` is the
 *   verbatim heading line (including any trailing `:tags:` the source
 *   used). `body` is the slice between the heading line (exclusive) and
 *   the next level-1 heading (exclusive), or EOF when the matched
 *   section is the last one in the file.
 * - `{ found: false, section }` — no section with the requested name was
 *   present. `section` echoes the (default-resolved) query for callers
 *   that want to message it.
 */
export type SectionResult =
  | { found: true; heading: string; body: string }
  | { found: false; section: string };

/** Default section name used when caller omits the parameter. */
export const DEFAULT_SECTION = "Summary";

const LEVEL_1_HEADING = /^\* (.+?)\s*$/;
/**
 * Trailing org tag suffix, e.g. ` :foo:bar:`. Org tags are typically
 * alphanumeric + `_@#%`, separated and bracketed by colons. We strip
 * conservatively to recover the heading's *item text* for comparison.
 */
const TRAILING_TAGS = /^(.*?)\s+(:[A-Za-z0-9_@#%:-]+:)\s*$/;
const BLOCK_OPEN = /^\s*#\+BEGIN_(\w+)\b/i;
const BLOCK_CLOSE = /^\s*#\+END_(\w+)\s*$/i;

/**
 * Parse a level-1 heading line. Returns the heading's item text with
 * any trailing `:tags:` stripped, or `null` if `line` is not a column-0
 * single-asterisk heading.
 */
function parseLevel1HeadingText(line: string): string | null {
  const m = LEVEL_1_HEADING.exec(line);
  if (!m) return null;
  const raw = m[1]!;
  const tagsMatch = TRAILING_TAGS.exec(raw);
  return (tagsMatch ? tagsMatch[1]! : raw).trim();
}

/**
 * Extract a single level-1 section from an org buffer.
 *
 * @param content  Full file contents (UTF-8 text).
 * @param section  Section name to match, case-insensitively, ignoring
 *                 trailing org `:tags:` on the heading. Defaults to
 *                 {@link DEFAULT_SECTION} (`"Summary"`).
 */
export function readSection(
  content: string,
  section: string = DEFAULT_SECTION,
): SectionResult {
  const requested = section && section.trim().length > 0
    ? section.trim()
    : DEFAULT_SECTION;
  const target = requested.toLowerCase();
  const lines = content.split("\n");

  let inBlock = false;
  let blockKind = "";
  let matchStart = -1; // index into `lines` of the matched heading line
  let matchHeading = "";

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;

    // Source/example/quote blocks shield their contents from heading
    // scanning — a literal `* ` inside such a block is body text, not
    // a section boundary.
    if (inBlock) {
      const close = BLOCK_CLOSE.exec(line);
      if (close && close[1]!.toLowerCase() === blockKind) {
        inBlock = false;
        blockKind = "";
      }
      continue;
    }
    const open = BLOCK_OPEN.exec(line);
    if (open) {
      inBlock = true;
      blockKind = open[1]!.toLowerCase();
      continue;
    }

    // Only column-0 single-asterisk lines are level-1 headings. The
    // `startsWith("* ")` cheap pre-filter excludes `**`/`***`/etc. and
    // any indented `*` text without needing the regex.
    if (!line.startsWith("* ")) continue;
    const headingText = parseLevel1HeadingText(line);
    if (headingText === null) continue;

    if (matchStart === -1) {
      if (headingText.toLowerCase() === target) {
        matchStart = i;
        matchHeading = line;
      }
      continue;
    }

    // We already have our section; this is the next level-1 heading
    // and therefore the slice boundary.
    const body = lines.slice(matchStart + 1, i).join("\n");
    return { found: true, heading: matchHeading, body };
  }

  if (matchStart === -1) {
    return { found: false, section: requested };
  }
  // Section was the last in the file → body runs to EOF.
  const body = lines.slice(matchStart + 1).join("\n");
  return { found: true, heading: matchHeading, body };
}
