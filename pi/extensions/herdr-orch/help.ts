/**
 * `/oh` sections and pi-specific framing. `oh roster --format markdown` and `oh usage`
 * own their listings, so this file adds only what is specific to a pi session: the Herdr
 * status of this pane, usage placement, and the usage notes.
 */

export const SECTIONS = ["help", "personas", "traits", "models", "usage", "workflows"] as const;
export type Section = (typeof SECTIONS)[number];

export const SECTION_DESCRIPTIONS: Record<Section, string> = {
  help: "Personas, traits, models, workflows, defaults, and usage",
  personas: "Subagent personas and their defaults",
  traits: "Traits and the personas that select them",
  models: "Model aliases and per-harness spellings",
  usage: "Subscription allowance by provider",
  workflows: "Ordinary skills that opt into the workflow catalogue",
};

export function parseSection(args: string): Section | null {
  const arg = args.trim();
  if (!arg) return "help";
  return (SECTIONS as readonly string[]).includes(arg) ? (arg as Section) : null;
}

/** `oh` arguments for one rendering. `help` is every section, which is `oh`'s default. */
export function rosterArgs(section: Exclude<Section, "usage">, detail: boolean): string[] {
  return [
    "roster",
    "--format",
    "markdown",
    ...(section === "help" ? [] : ["--section", section]),
    ...(detail ? ["--detail"] : []),
  ];
}

/** `oh usage` arguments for one rendering. */
export function usageArgs(detail: boolean): string[] {
  return ["usage", "--format", "markdown", ...(detail ? ["--detail"] : [])];
}

const USAGE = [
  "- Ask the agent to delegate, for example: *Spawn a light scout to find the config loader.*",
  "- *Spawn a subagent* starts a fresh context (the default); *fork a subagent* inherits your completed earlier turns, not the current one (`--context fork`, Pi only); put same-turn findings in the assignment. A persona is optional for a fresh child.",
  "- *Measure* a treatment with `--context measurement --measurement-config PATH`: a headless Pi sample with no pane unless `--tab` or `--split` adds a read-only monitor.",
  "- Root-owned staged delegation, repeated measurements, and the approval-gated context smoke are in `skills/herdr-orch/references/workflow-recipes.md`.",
  "- Name a weight (`heavy`, `middle`, `light`, `feather`) or another alias to pick the model for that one spawn.",
  "- Write `#name` in a delegation request to ask the agent to add `--trait name` to that spawn.",
  "- Write `%name` in your own prompt to expand that trait into your message (pi `traits` extension).",
  "- Workflow skills opt in with `metadata: {herdr-orch: workflow}` in `SKILL.md`. Listing a skill does not run it or grant permission to delegate.",
  "- Override a persona or trait with a same-named file in a higher layer: project `.agents/subagents/` or `.agents/traits/`, or the same under `~/.agents/`.",
  "- Show one section with `/oh personas`, `/oh traits`, `/oh models`, `/oh usage`, or `/oh workflows`. Expand the entry with the tool-output expand key (default `ctrl+o`) for paths, timeouts, retro, every alias, usage credential sources and reset times, and the resolution layers.",
];

/** Frame the `oh` rendering: the full listing gets the pane's Herdr status and usage notes. */
export function composeHelp(section: Section, listing: string, herdr: boolean, usage = ""): string {
  if (section !== "help") return listing.trimEnd();
  const status = herdr
    ? "Herdr: this pane can delegate (`HERDR_ENV=1`)."
    : "Herdr: not detected (`HERDR_ENV` is not `1`). Delegation needs a Herdr pane.";
  const usageBlock = usage.trimEnd();
  return [
    status,
    "",
    listing.trimEnd(),
    ...(usageBlock ? ["", "## Subscription usage", "", usageBlock] : []),
    "",
    "## Usage",
    "",
    ...USAGE,
  ].join("\n");
}
