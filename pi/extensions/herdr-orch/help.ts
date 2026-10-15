/**
 * `/oh` sections and pi-specific framing. `oh roster --format markdown` owns the listing
 * itself, the same renderer that generates the README.org listings, so this file adds only
 * what is specific to a pi session: the Herdr status of this pane and the usage notes.
 */

export const SECTIONS = ["help", "personas", "traits", "models"] as const;
export type Section = (typeof SECTIONS)[number];

export const SECTION_DESCRIPTIONS: Record<Section, string> = {
  help: "Personas, traits, models, defaults, and usage",
  personas: "Subagent personas and their defaults",
  traits: "Traits and the personas that select them",
  models: "Model aliases and per-harness spellings",
};

export function parseSection(args: string): Section | null {
  const arg = args.trim();
  if (!arg) return "help";
  return (SECTIONS as readonly string[]).includes(arg) ? (arg as Section) : null;
}

/** `oh` arguments for one rendering. `help` is every section, which is `oh`'s default. */
export function rosterArgs(section: Section, detail: boolean): string[] {
  return [
    "roster",
    "--format",
    "markdown",
    ...(section === "help" ? [] : ["--section", section]),
    ...(detail ? ["--detail"] : []),
  ];
}

const USAGE = [
  "- Ask the agent to delegate, for example: *Spawn a light scout to find the config loader.*",
  "- Name a weight (`heavy`, `middle`, `light`, `feather`) or another alias to pick the model for that one spawn.",
  "- Write `#name` in a delegation request to ask the agent to add `--trait name` to that spawn.",
  "- Write `%name` in your own prompt to expand that trait into your message (pi `traits` extension).",
  "- Override a persona or trait with a same-named file in a higher layer: project `.agents/subagents/` or `.agents/traits/`, or the same under `~/.agents/`.",
  "- Show one section with `/oh personas`, `/oh traits`, or `/oh models`. Expand the entry with the tool-output expand key (default `ctrl+o`) for paths, timeouts, retro, every alias, and the resolution layers.",
];

/** Frame the `oh` rendering: the full listing gets the pane's Herdr status and usage notes. */
export function composeHelp(section: Section, listing: string, herdr: boolean): string {
  if (section !== "help") return listing.trimEnd();
  const status = herdr
    ? "Herdr: this pane can delegate (`HERDR_ENV=1`)."
    : "Herdr: not detected (`HERDR_ENV` is not `1`). Delegation needs a Herdr pane.";
  return [status, "", listing.trimEnd(), "", "## Usage", "", ...USAGE].join("\n");
}
