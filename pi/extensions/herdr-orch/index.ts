import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { StringEnum } from "@earendil-works/pi-ai";
import { homedir } from "node:os";
import { join } from "node:path";
import { Type } from "typebox";

type OhEnvelope = {
  ok: boolean;
  schema: "herdr-orch/v1";
  result?: unknown;
  error?: { message?: unknown };
};

const StatusEnum = StringEnum(["idle", "working", "blocked", "done", "unknown"] as const, {
  description: "Agent lifecycle state",
});

const ReadSourceEnum = StringEnum(["visible", "recent", "recent-unwrapped", "detection"] as const, {
  description: "Terminal snapshot source",
});

const OutputFormatEnum = StringEnum(["text", "ansi"] as const, {
  description: "Output format; ansi preserves terminal styling",
});

const DirectionEnum = StringEnum(["right", "down"] as const, {
  description: "Split direction. When omitted, the tool chooses from the source pane geometry.",
});

const AgentKindEnum = StringEnum(
  [
    "pi",
    "claude",
    "codex",
    "gemini",
    "cursor",
    "devin",
    "agy",
    "cline",
    "omp",
    "mastracode",
    "opencode",
    "copilot",
    "kimi",
    "kiro",
    "droid",
    "amp",
    "grok",
    "hermes",
    "kilo",
    "qodercli",
    "maki",
  ] as const,
  { description: "Supported coding agent kind and canonical executable" },
);

const LayoutParams = Type.Object({
  action: StringEnum(
    [
      "current",
      "workspace_list",
      "workspace_create",
      "workspace_focus",
      "tab_list",
      "tab_create",
      "tab_focus",
      "pane_list",
      "pane_layout",
      "pane_split",
    ] as const,
    { description: "Layout action" },
  ),
  workspace: Type.Optional(Type.String({ description: "Opaque workspace ID" })),
  tab: Type.Optional(Type.String({ description: "Opaque tab ID" })),
  pane: Type.Optional(Type.String({ description: "Opaque source pane ID. Omit for current, pane_layout, or pane_split to use the caller's pane." })),
  label: Type.Optional(Type.String({ description: "Label for a new workspace or tab" })),
  direction: Type.Optional(DirectionEnum),
  cwd: Type.Optional(Type.String({ description: "Working directory. Defaults to the caller pane's foreground cwd." })),
  focus: Type.Optional(Type.Boolean({ description: "Change UI focus after creation. Defaults to false." })),
});

const PaneParams = Type.Object({
  action: StringEnum(["get", "run", "read", "wait_output", "send_text", "send_keys", "close"] as const, {
    description: "Raw pane action",
  }),
  pane: Type.String({ description: "Opaque pane ID returned by herdr_layout" }),
  command: Type.Optional(Type.String({ description: "Shell command to submit atomically with Enter for run" })),
  text: Type.Optional(Type.String({ description: "Literal text to send without Enter for send_text" })),
  keys: Type.Optional(Type.Array(Type.String(), { description: "Logical terminal keys for send_keys, such as esc, enter, up, or ctrl+c" })),
  match: Type.Optional(Type.String({ description: "Literal substring or Rust regular expression for wait_output" })),
  regex: Type.Optional(Type.Boolean({ description: "Treat match as a Rust regular expression" })),
  source: Type.Optional(ReadSourceEnum),
  lines: Type.Optional(Type.Integer({ minimum: 1, description: "Rendered terminal rows to read or search" })),
  format: Type.Optional(OutputFormatEnum),
  raw: Type.Optional(Type.Boolean({ description: "Keep ANSI escapes while matching wait_output" })),
  timeout: Type.Optional(Type.Integer({ minimum: 1, description: "Wait timeout in milliseconds; omitted means indefinite" })),
});

const AgentParams = Type.Object({
  action: StringEnum(["list", "get", "start", "prompt", "wait", "read", "send_keys", "focus", "rename"] as const, {
    description: "Agent lifecycle action",
  }),
  target: Type.Optional(Type.String({ description: "Unique live agent name or pane ID currently hosting the agent" })),
  pane: Type.Optional(Type.String({ description: "Existing available shell pane ID for start" })),
  name: Type.Optional(
    Type.String({
      pattern: "^[a-z][a-z0-9_-]{0,31}$",
      description: "Unique agent name for start or replacement name for rename",
    }),
  ),
  kind: Type.Optional(AgentKindEnum),
  agentArgs: Type.Optional(Type.Array(Type.String(), { description: "Native agent arguments passed unchanged after -- for start" })),
  prompt: Type.Optional(Type.String({ description: "Prompt text submitted atomically with Enter" })),
  wait: Type.Optional(Type.Boolean({ description: "Wait for lifecycle settlement after prompt. Defaults to true." })),
  until: Type.Optional(Type.Array(StatusEnum, { description: "Accepted lifecycle states for prompt with wait or wait; defaults to idle, done, or blocked" })),
  timeout: Type.Optional(Type.Integer({ minimum: 1, description: "Timeout in milliseconds; omitted means indefinite" })),
  source: Type.Optional(ReadSourceEnum),
  lines: Type.Optional(Type.Integer({ minimum: 1, description: "Rendered terminal rows to read" })),
  format: Type.Optional(OutputFormatEnum),
  keys: Type.Optional(Type.Array(Type.String(), { description: "Logical UI keys such as esc, enter, up, or ctrl+c" })),
  clearName: Type.Optional(Type.Boolean({ description: "Clear the current agent name for rename" })),
});

function launcherPath(): string {
  return process.env.HERDR_ORCH_BIN ?? join(homedir(), ".agents", "skills", "herdr-orch", "scripts", "oh");
}

function isEnvelope(value: unknown): value is OhEnvelope {
  return typeof value === "object" && value !== null &&
    "ok" in value && typeof value.ok === "boolean" &&
    "schema" in value && value.schema === "herdr-orch/v1";
}

function parseJson(text: string): unknown | null {
  const trimmed = text.trim();
  if (!trimmed) return null;
  try {
    return JSON.parse(trimmed) as unknown;
  } catch {
    return null;
  }
}

function errorMessage(...outputs: string[]): string | null {
  for (const output of outputs) {
    const value = parseJson(output);
    if (isEnvelope(value) && typeof value.error?.message === "string") return value.error.message;
    if (output.trim()) return output.trim();
  }
  return null;
}

function option(args: string[], flag: string, value: string | number | undefined): void {
  if (value !== undefined) args.push(flag, String(value));
}

function outputText(result: unknown): string {
  return typeof result === "string" ? result : JSON.stringify(result, null, 2);
}

export default function (pi: ExtensionAPI) {
  async function execute(args: string[], signal?: AbortSignal) {
    const result = await pi.exec(launcherPath(), args, { signal });
    if (signal?.aborted || result.killed) throw new Error("Aborted");
    if (result.code !== 0) {
      throw new Error(errorMessage(result.stdout, result.stderr) ?? `oh ${args.join(" ")} failed with exit code ${result.code}`);
    }

    const value = parseJson(result.stdout);
    if (isEnvelope(value)) {
      if (!value.ok) throw new Error(errorMessage(result.stdout, result.stderr) ?? `oh ${args.join(" ")} failed`);
      return {
        content: [{ type: "text" as const, text: outputText(value.result ?? null) }],
        details: value.result ?? null,
      };
    }

    return {
      content: [{ type: "text" as const, text: result.stdout }],
      details: { output: result.stdout },
    };
  }

  pi.registerTool({
    name: "herdr_layout",
    label: "Herdr Layout",
    description:
      "Create and inspect Herdr terminal topology. Workspaces contain tabs; tabs contain panes. Creating a workspace or tab also creates a root pane, while splitting creates another pane. Layout actions never start an agent or ordinary command. Read pane IDs from results and pass them to herdr_pane or herdr_agent. Creation defaults to the caller's cwd and preserves UI focus. pane_split defaults to the caller's pane and chooses right or down from its geometry.",
    promptSnippet: "Inspect or create Herdr workspaces, tabs, and pane topology",
    promptGuidelines: [
      "Use herdr_layout, herdr_pane, and herdr_agent only when the user explicitly mentions Herdr or asks to inspect or control Herdr.",
      "Use herdr_layout to create terminal topology before starting a process or agent. Default to a sibling pane in the caller's current tab and cwd; create a tab or workspace only when requested.",
      "Read opaque workspace, tab, and pane IDs from herdr_layout results instead of constructing them, and preserve UI focus unless the user asks to switch context.",
    ],
    parameters: LayoutParams,
    async execute(_toolCallId, params, signal) {
      switch (params.action) {
        case "current":
          return execute(["pane", "current"], signal);
        case "workspace_list":
          return execute(["ws", "list"], signal);
        case "workspace_create": {
          const args = ["ws", "create"];
          option(args, "--cwd", params.cwd);
          option(args, "--label", params.label);
          if (params.focus) args.push("--focus");
          return execute(args, signal);
        }
        case "workspace_focus":
          return execute(["ws", "focus", ...(params.workspace ? [params.workspace] : [])], signal);
        case "tab_list": {
          const args = ["tab", "list"];
          option(args, "--workspace", params.workspace);
          return execute(args, signal);
        }
        case "tab_create": {
          const args = ["tab", "create"];
          option(args, "--workspace", params.workspace);
          option(args, "--cwd", params.cwd);
          option(args, "--label", params.label);
          if (params.focus) args.push("--focus");
          return execute(args, signal);
        }
        case "tab_focus":
          return execute(["tab", "focus", ...(params.tab ? [params.tab] : [])], signal);
        case "pane_list": {
          const args = ["pane", "list"];
          option(args, "--workspace", params.workspace);
          return execute(args, signal);
        }
        case "pane_layout": {
          const pane = params.pane ?? process.env.HERDR_PANE_ID;
          return execute(["pane", "layout", ...(pane ? [pane] : [])], signal);
        }
        case "pane_split": {
          const args = ["pane", "split"];
          option(args, "--direction", params.direction);
          option(args, "--cwd", params.cwd);
          if (params.focus) args.push("--focus");
          return execute(args, signal);
        }
      }
    },
  });

  pi.registerTool({
    name: "herdr_pane",
    label: "Herdr Pane",
    description:
      "Control a raw Herdr terminal pane. Use for shells, tests, servers, builds, logs, and other ordinary processes: run a command, read output, wait for matching output, send literal text or terminal keys, inspect, or close. Pane actions target opaque pane IDs and do not validate agent identity or interpret agent lifecycle. Use herdr_agent instead when controlling a recognized coding agent. Read output is truncated to 2000 lines or 50KB.",
    promptSnippet: "Run and inspect ordinary commands in Herdr terminal panes",
    promptGuidelines: [
      "Use herdr_pane for ordinary commands and raw terminal control; use herdr_agent for coding-agent prompts, lifecycle waits, reads, and interactive keys.",
      "Use herdr_pane wait_output for tests, servers, builds, and watchers. It searches existing output immediately; use recent-unwrapped for logs and transcripts.",
      "Do not close a Herdr pane you did not create unless the user explicitly asks. herdr_pane always refuses to close the pane running the current pi process.",
    ],
    parameters: PaneParams,
    async execute(_toolCallId, params, signal) {
      switch (params.action) {
        case "get":
          return execute(["pane", "get", params.pane], signal);
        case "run":
          return execute(["pane", "run", params.pane, ...(params.command ? [params.command] : [])], signal);
        case "read": {
          const args = ["pane", "read", params.pane];
          option(args, "--source", params.source);
          option(args, "--lines", params.lines);
          option(args, "--format", params.format);
          return execute(args, signal);
        }
        case "wait_output": {
          const args = ["pane", "wait-output", params.pane];
          option(args, params.regex ? "--regex" : "--match", params.match);
          option(args, "--source", params.source);
          option(args, "--lines", params.lines);
          option(args, "--timeout", params.timeout);
          if (params.raw) args.push("--raw");
          return execute(args, signal);
        }
        case "send_text":
          return execute(["pane", "send-text", params.pane, ...(params.text ? [params.text] : [])], signal);
        case "send_keys":
          return execute(["pane", "send-keys", params.pane, ...(params.keys ?? [])], signal);
        case "close":
          return execute(["pane", "close", params.pane], signal);
      }
    },
  });

  pi.registerTool({
    name: "herdr_agent",
    label: "Herdr Agent",
    description:
      "Control a recognized coding agent occupying an existing Herdr pane. Starting requires an available interactive shell pane created through herdr_layout and never creates or changes layout. Agent targets are unique live names or the pane ID currently hosting the agent, never terminal IDs or bare kind labels. Use prompt, wait, read, and send_keys instead of raw pane input. Lifecycle states are working, blocked, done, idle, and unknown; prompt and wait default to the first settled idle, done, or blocked state. Read output is truncated to 2000 lines or 50KB.",
    promptSnippet: "Start, prompt, wait for, read, and interact with coding agents in Herdr",
    promptGuidelines: [
      "Use herdr_agent for recognized coding agents. Use herdr_layout to create an available shell pane first; herdr_agent start never creates or moves terminal layout.",
      "For normal helper work, use herdr_layout pane_split, then herdr_agent start, herdr_agent prompt with wait enabled, and herdr_agent read. Use herdr_pane only for ordinary processes or intentional raw terminal control.",
      "Treat herdr_agent idle and done as ready states, blocked as requiring inspection or input, and unknown as uncertain rather than completed. CLI reads do not mark done work as seen.",
      "If herdr_agent read cannot recover a full alternate-screen response after increasing lines, ask the agent to write its complete response to a temporary Markdown file and return the path, then read that file directly.",
    ],
    parameters: AgentParams,
    async execute(_toolCallId, params, signal) {
      switch (params.action) {
        case "list":
          return execute(["agent", "list"], signal);
        case "get":
          return execute(["agent", "get", ...(params.target ? [params.target] : [])], signal);
        case "start": {
          const args = ["agent", "start", ...(params.name ? [params.name] : [])];
          option(args, "--kind", params.kind);
          option(args, "--pane", params.pane);
          if (params.agentArgs?.length) args.push("--", ...params.agentArgs);
          return execute(args, signal);
        }
        case "prompt":
          return execute(["agent", "prompt", ...(params.target ? [params.target] : []), ...(params.prompt ? [params.prompt] : [])], signal);
        case "wait": {
          const args = ["agent", "wait", ...(params.target ? [params.target] : [])];
          for (const status of params.until ?? []) option(args, "--until", status);
          option(args, "--timeout", params.timeout);
          return execute(args, signal);
        }
        case "read": {
          const args = ["agent", "read", ...(params.target ? [params.target] : [])];
          option(args, "--source", params.source);
          option(args, "--lines", params.lines);
          option(args, "--format", params.format);
          return execute(args, signal);
        }
        case "send_keys":
          return execute(["agent", "send-keys", ...(params.target ? [params.target] : []), ...(params.keys ?? [])], signal);
        case "focus":
          return execute(["agent", "focus", ...(params.target ? [params.target] : [])], signal);
        case "rename": {
          const args = ["agent", "rename", ...(params.target ? [params.target] : [])];
          if (params.clearName) args.push("--clear");
          else if (params.name) args.push(params.name);
          return execute(args, signal);
        }
      }
    },
  });
}
