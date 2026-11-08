/**
 * `/oh` sections, actions and pi-specific framing. `oh roster --format markdown` and `oh usage`
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

/** Subcommands that change session state; the other arguments are read-only sections. */
export const ACTIONS = ["account", "tier"] as const;
export type Action = (typeof ACTIONS)[number];

export const ACTION_DESCRIPTIONS: Record<Action, string> = {
  account: "Show or add Anthropic subscription accounts: account add [N]",
  tier: "Show or select this session's weight tier: tier <alias> | tier clear",
};

/** Split `/oh` arguments into an action and its remaining arguments, or return null for a section. */
export function parseAction(args: string): { action: Action; rest: string } | null {
  const match = /^\s*(\S+)(?:\s+([\s\S]*))?$/.exec(args);
  if (!match || !(ACTIONS as readonly string[]).includes(match[1])) return null;
  return { action: match[1] as Action, rest: (match[2] ?? "").trim() };
}

type Completion = { value: string; label: string; description?: string };

/**
 * Complete the whole `/oh` argument text, because Pi replaces all of it with the chosen value.
 * `aliases` is called only after `tier `, so the first word costs no `oh` process.
 */
export async function completeArguments(prefix: string, aliases: () => Promise<string[]>): Promise<Completion[] | null> {
  const text = prefix.trimStart();
  const space = text.search(/\s/);
  if (space === -1) {
    const items = [...SECTIONS.map((name) => ({ value: name, label: name, description: SECTION_DESCRIPTIONS[name] })),
      ...ACTIONS.map((name) => ({ value: `${name} `, label: name, description: ACTION_DESCRIPTIONS[name] }))]
      .filter((item) => item.label.startsWith(text));
    return items.length ? items : null;
  }
  const action = text.slice(0, space);
  const word = text.slice(space).trimStart();
  if (/\s/.test(word)) return null;
  const choices = action === "account" ? ["add"] : action === "tier" ? ["clear", ...await aliases().catch(() => [])] : [];
  const items = choices.filter((choice) => choice.startsWith(word)).map((choice) => ({ value: `${action} ${choice}`, label: choice }));
  return items.length ? items : null;
}

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
  "- Register another Anthropic subscription with `/oh account add [N]`, then sign in with `/login`. Select a weight tier for this session with `/oh tier <alias>`, or clear it with `/oh tier clear`. Bare `/oh account` and `/oh tier` show the current state.",
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
