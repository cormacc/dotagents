/**
 * Meridian-Claude extension for pi
 *
 * Routes pi conversations through Meridian (https://github.com/rynfar/meridian),
 * which bridges the Claude Code Agent SDK to the standard Anthropic API.
 * This allows pi to use a Claude Max subscription instead of API billing.
 *
 * Prerequisites:
 *   npm install -g @rynfar/meridian
 *   claude login
 *
 * The extension:
 *   1. Starts Meridian if it isn't already running (e.g. as a Home Manager service)
 *   2. Overrides the anthropic provider to route via Meridian
 *   3. Replaces pi's system prompt with Claude Code's system prompt so Anthropic's
 *      backend treats the session as Claude Code usage (required for Max billing)
 *   4. Injects pi's original system prompt into the first user message so the model
 *      still has pi's tool and behaviour instructions in context
 *
 * To run Meridian as a Home Manager service instead of letting the extension
 * manage it, add the Meridian flake to your inputs and enable services.meridian.
 * The extension will detect the running proxy and skip the spawn step.
 */

import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { streamSimpleAnthropic } from "@mariozechner/pi-ai";
import type { Context, UserMessage } from "@mariozechner/pi-ai";
import { spawn } from "child_process";
import type { ChildProcess } from "child_process";

// ---------------------------------------------------------------------------
// Claude Code system prompt
// Sourced from https://github.com/ianjwhite99/opencode-with-claude
// This is what makes Anthropic's backend count the session as Claude Code usage.
// ---------------------------------------------------------------------------

const CLAUDE_CODE_SYSTEM_PROMPT = `You are Anthropic's Claude Code, Anthropic's official CLI for Claude. Here are some rules.

<rules>
No emojis unless the user asks for them.

Output is rendered in a CLI using CommonMark markdown with a monospace font. Keep responses brief.

Text output is how you talk to the user. Tools are for doing work, not for communication — don't use Bash, code comments, or similar as a chat channel.

Don't create files unless absolutely necessary. Edit existing files instead. This applies to markdown too.

Be technically accurate and direct. Skip the praise, superlatives, and emotional validation. If the user is wrong, say so — respectful correction beats false confirmation. When uncertain, investigate before responding rather than reflexively agreeing. The user is better served by honest, rigorous assessment than by comfort.

Use TodoWrite liberally to plan work, break down complex tasks, and give the user visibility into progress. Mark items complete immediately when finished — don't batch completions. Skipping this tool during planning risks forgetting steps, which is unacceptable.

Users will mostly ask for software engineering work: bugs, features, refactors, explanations, etc. General approach: plan with TodoWrite when the task warrants it. Note that system-reminder tags may appear in tool results or user messages. These are injected by the system with relevant context — they aren't tied to the specific message they appear in.

Prefer the Task tool for file search to save context. Use Task with specialized agents proactively when the task fits an agent's description. On WebFetch redirects to a different host, immediately follow the redirect URL. Call independent tools in parallel. Call dependent tools sequentially — never guess at values from incomplete prior calls. If the user says in parallel, send one message with multiple tool blocks. Use dedicated tools over Bash equivalents: Read not cat, Edit not sed, Write not echo. Bash is for actual shell operations only. Never use Bash to communicate with the user. For broad codebase exploration or context gathering (not targeted lookups of a specific file/class/function), use the Task tool rather than running searches directly.

Always use TodoWrite to plan and track work throughout the conversation.

When citing specific code, include file_path:line_number so the user can jump to it.
</rules>`;

// ---------------------------------------------------------------------------
// Context transformation
// ---------------------------------------------------------------------------

/**
 * Build a Meridian-compatible context from pi's context.
 *
 * - System prompt → replaced with CLAUDE_CODE_SYSTEM_PROMPT
 * - Pi's original system prompt → prepended to the first user message content
 *
 * We can't insert pi's system prompt as a separate user message: the Anthropic
 * API requires strictly alternating user/assistant turns and would reject two
 * consecutive user messages. Merging it into the first user message's content
 * keeps the sequence valid while ensuring the model always has pi's instructions
 * at the top of every conversation.
 */
function buildMeridianContext(context: Context): Context {
  const { systemPrompt, messages, tools } = context;

  if (!systemPrompt || messages.length === 0) {
    return { systemPrompt: CLAUDE_CODE_SYSTEM_PROMPT, messages, tools };
  }

  const prefix = `<pi-instructions>\n${systemPrompt}\n</pi-instructions>\n\n`;
  const firstMsg = messages[0];
  let newFirstMsg: UserMessage;

  if (firstMsg.role === "user") {
    if (typeof firstMsg.content === "string") {
      newFirstMsg = { ...firstMsg, content: prefix + firstMsg.content };
    } else {
      // Content array (text + image blocks): prepend a text block
      newFirstMsg = {
        ...firstMsg,
        content: [{ type: "text" as const, text: prefix }, ...firstMsg.content],
      };
    }
  } else {
    // Unexpected: first message isn't a user message — leave it alone
    newFirstMsg = firstMsg as UserMessage;
  }

  return {
    systemPrompt: CLAUDE_CODE_SYSTEM_PROMPT,
    messages: [newFirstMsg, ...messages.slice(1)],
    tools,
  };
}

// ---------------------------------------------------------------------------
// Proxy lifecycle helpers
// ---------------------------------------------------------------------------

async function isProxyHealthy(port: string): Promise<boolean> {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/health`, {
      signal: AbortSignal.timeout(1500),
    });
    return res.ok;
  } catch {
    return false;
  }
}

async function waitForProxy(port: string, maxMs = 15000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < maxMs) {
    if (await isProxyHealthy(port)) return;
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error(`Meridian proxy did not become healthy within ${maxMs}ms on port ${port}`);
}

// ---------------------------------------------------------------------------
// Extension entry point
// ---------------------------------------------------------------------------

export default async function (pi: ExtensionAPI) {
  const port = process.env.MERIDIAN_PORT ?? "3456";
  const baseUrl = `http://127.0.0.1:${port}`;
  let proc: ChildProcess | null = null;

  if (await isProxyHealthy(port)) {
    console.log(`[meridian-claude] Using existing Meridian proxy at ${baseUrl}`);
  } else {
    // Attempt to spawn the meridian binary from PATH
    proc = spawn("meridian", [], {
      env: {
        ...process.env,
        MERIDIAN_PASSTHROUGH: "true",
        MERIDIAN_DEFAULT_AGENT: "pi",
        MERIDIAN_PORT: port,
      },
      stdio: "ignore",
      detached: false,
    });

    proc.on("error", (err: Error) => {
      console.error(`[meridian-claude] Failed to start meridian: ${err.message}`);
      console.error(
        "[meridian-claude] Install: npm install -g @rynfar/meridian  then: claude login",
      );
    });

    try {
      await waitForProxy(port);
      console.log(`[meridian-claude] Meridian proxy started at ${baseUrl}`);
    } catch (err) {
      proc.kill();
      proc = null;
      console.error(
        `[meridian-claude] ${err instanceof Error ? err.message : err}`,
      );
      console.error("[meridian-claude] Falling back to direct Anthropic API");
      return;
    }
  }

  // Override the anthropic provider so every model call goes via Meridian.
  // Omitting `models` preserves the existing model list; only baseUrl,
  // apiKey, headers, and streamSimple are replaced.
  pi.registerProvider("anthropic", {
    baseUrl,
    apiKey: "x", // Meridian authenticates via Claude Code OAuth; any non-empty value works
    api: "anthropic-messages",
    headers: { "x-meridian-agent": "pi" },
    streamSimple: (model, context, options) =>
      streamSimpleAnthropic(
        model,
        buildMeridianContext(context),
        // Ensure the dummy apiKey is passed through so streamSimpleAnthropic
        // doesn't fall back to ANTHROPIC_API_KEY from the environment.
        { ...options, apiKey: options?.apiKey ?? "x" },
      ),
  });

  // Clean up the spawned process on exit
  if (proc) {
    let cleaned = false;
    const cleanup = () => {
      if (cleaned) return;
      cleaned = true;
      proc?.kill("SIGTERM");
    };
    process.on("exit", cleanup);
    process.on("SIGINT", () => {
      cleanup();
      process.exit(0);
    });
    process.on("SIGTERM", () => {
      cleanup();
      process.exit(0);
    });
  }
}
