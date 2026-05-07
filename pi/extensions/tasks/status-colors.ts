/**
 * Canonical ANSI colors for task metadata.
 *
 * Applied consistently in the /tasks overlay and the persistent pinned overlay.
 * Uses direct ANSI escape codes (not theme keys) so the palette is fixed
 * regardless of the active theme.
 */

const RESET = "\x1b[0m";
const DIM = "\x1b[2m";
const ITALIC = "\x1b[3m";

const GREEN = "\x1b[32m";
const YELLOW = "\x1b[33m";
const BLUE = "\x1b[34m";
const MAGENTA = "\x1b[35m";
const RED = "\x1b[31m";
const ORANGE = "\x1b[38;5;208m";
const CYAN = "\x1b[36m";

export const STATUS_ANSI: Record<string, string> = {
  TODO: YELLOW,
  WAITING: ORANGE,
  STARTED: BLUE,
  DONE: GREEN,
  CANCELLED: RED,
};

export const PRIORITY_ANSI: Record<string, string> = {
  A: ORANGE,
  B: YELLOW,
  C: GREEN,
  D: BLUE,
};

function color(open: string | undefined, text: string): string {
  if (!open) return text;
  return `${open}${text}${RESET}`;
}

/**
 * Return a status token wrapped in its canonical color.
 * Pass `text` to color arbitrary content with the same hue.
 */
export function colorStatus(status: string, text?: string): string {
  return color(STATUS_ANSI[status], text ?? status.padEnd(9));
}

/** Return an org priority token wrapped in its canonical color. */
export function colorPriority(priority: string | null): string {
  if (!priority) return "";
  return color(PRIORITY_ANSI[priority], `[#${priority}]`);
}

/** Return text tinted in the local-draft colour (magenta). */
export function colorLocal(text: string): string {
  return color(MAGENTA, text);
}

/** Return a visibly tag-styled token, distinct from title text. */
export function colorTags(tags: string[]): string {
  if (tags.length === 0) return "";
  return `${DIM}${ITALIC}${MAGENTA}:${tags.join(":")}:${RESET}`;
}

/**
 * Return a styled run for `:LINKED_ISSUES:` badges, one per item, using the
 * canonical CYAN hue. Each badge is prefixed with the ⤴ glyph to make the
 * group visually distinct from tags. Pass an empty array to get an empty
 * string (callers should suppress when the property is absent).
 */
export function colorIssues(labels: string[]): string {
  if (labels.length === 0) return "";
  const parts = labels.map((label) => `⤴${label}`);
  return `${CYAN}${parts.join(" ")}${RESET}`;
}
