// SPDX-License-Identifier: EPL-2.0
// Copyright © 2026-present Marko Kocic <marko@euptera.com>

import {
  DEFAULT_MAX_BYTES,
  DEFAULT_MAX_LINES,
  defineTool,
  truncateHead,
} from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import parinfer from "parinfer";

function detectImbalance(code: string): boolean {
  let depth = 0;
  let inString = false;

  for (let i = 0; i < code.length; i++) {
    const ch = code[i];
    if (inString && ch === "\\" && i + 1 < code.length) {
      i++;
      continue;
    }
    if (ch === '"') {
      inString = !inString;
      continue;
    }
    if (inString) continue;
    if (ch === ";") {
      while (i < code.length && code[i] !== "\n") i++;
      continue;
    }
    if (ch === "(" || ch === "[" || ch === "{") depth++;
    if (ch === ")" || ch === "]" || ch === "}") depth--;
    if (depth < 0) return true;
  }

  return depth !== 0;
}

function fixDelimiters(code: string): string {
  const result = parinfer.indentMode(code, { forceBalance: true });
  return result.text ?? code;
}

function boundOutput(text: string) {
  return truncateHead(text, {
    maxLines: DEFAULT_MAX_LINES,
    maxBytes: DEFAULT_MAX_BYTES,
  });
}

export const parenRepairTool = defineTool({
  name: "clojure_paren_repair",
  label: "Clojure Paren Repair",
  description: "Fix unbalanced delimiters in Clojure, ClojureScript, and Babashka code using parinfer. Output is limited to pi's standard 2000 lines or 50KB.",
  promptSnippet: "Fix unbalanced delimiters in Clojure code",
  parameters: Type.Object({
    code: Type.String({ description: "Clojure code with potentially unbalanced delimiters" }),
    check: Type.Optional(
      Type.Boolean({ description: "Only check if delimiters are balanced, don't fix" }),
    ),
  }),

  async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
    const code = params.code;
    const isImbalanced = detectImbalance(code);

    if (params.check) {
      return {
        content: [{ type: "text", text: isImbalanced ? "Code has unbalanced delimiters" : "Code has balanced delimiters" }],
        details: { balanced: !isImbalanced },
      };
    }

    if (!isImbalanced) {
      return {
        content: [{ type: "text", text: "Code is already balanced" }],
        details: { changed: false, balanced: true },
      };
    }

    const repaired = fixDelimiters(code);
    const changed = code !== repaired;
    const output = changed
      ? `Fixed delimiters:\n\`\`\`clojure\n${repaired}\n\`\`\``
      : "Could not repair delimiters";
    const truncation = boundOutput(output);

    return {
      content: [{ type: "text", text: truncation.content }],
      details: { changed, balanced: !detectImbalance(repaired), truncation },
    };
  },
});
