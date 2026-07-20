import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Box, Text } from "@earendil-works/pi-tui";

interface SystemPromptEntry {
  prompt: string;
}

export default function (pi: ExtensionAPI) {
  pi.registerEntryRenderer<SystemPromptEntry>("systemprompt", (entry, _options, theme) => {
    const box = new Box(1, 1, (text) => theme.bg("customMessageBg", text));
    box.addChild(new Text(
      `${theme.fg("toolTitle", theme.bold("System prompt"))}\n${entry.data?.prompt ?? "No system prompt loaded."}`,
      0,
      0,
    ));
    return box;
  });

  pi.registerCommand("systemprompt", {
    description: "Show the current system prompt without adding it to model context",
    handler: async (_args, ctx) => {
      const prompt = ctx.getSystemPrompt();
      if (!prompt) {
        if (ctx.hasUI) ctx.ui.notify("No system prompt loaded.", "warning");
        return;
      }

      if (ctx.mode === "tui") {
        pi.appendEntry<SystemPromptEntry>("systemprompt", { prompt });
      } else if (ctx.mode === "rpc") {
        ctx.ui.notify(prompt, "info");
      }
    },
  });
}
