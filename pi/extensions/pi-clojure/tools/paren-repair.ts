// SPDX-License-Identifier: EPL-2.0
// Copyright © 2026-present Marko Kocic <marko@euptera.com>

import { Type } from "@sinclair/typebox";
import { defineTool } from "@mariozechner/pi-coding-agent";
import { indentMode } from "parinfer";

function detectImbalance(code: string): boolean {
  let depth = 0;
  let inString = false;

  for (let i = 0; i < code.length; i++) {
    const ch = code[i];

    // Handle escape sequences inside strings
    if (inString && ch === "\\" && i + 1 < code.length) {
      i++;
      continue;
    }

    if (ch === '"') {
      inString = !inString;
      continue;
    }

    if (inString) continue;

    // Skip line comments (';' is not a delimiter in Clojure)
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
  const result = indentMode(code, { forceBalance: true });
  return result.text ?? code;
}

export const parenRepairTool = defineTool({
  name: "clojure_paren_repair",
  label: "Clojure Paren Repair",
  description: "Fix unbalanced delimiters in Clojure, ClojureScript, and Babashka code using parinfer. Works with all Clojure-type source files (.clj, .cljs, .cljc, .bb). Standalone tool — does not require nREPL or any running process.",
  promptSnippet: "Fix unbalanced delimiters in Clojure code",
  parameters: Type.Object({
    code: Type.String({ description: "Clojure code with potentially unbalanced delimiters" }),
    check: Type.Optional(
      Type.Boolean({ description: "Only check if delimiters are balanced, don't fix" })
    ),
  }),

  async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
    const code = String(params.code);
    const check = params.check === true;

    const isImbalanced = detectImbalance(code);

    if (check) {
      return {
        content: [
          {
            type: "text",
            text: isImbalanced ? "Code has unbalanced delimiters" : "Code has balanced delimiters",
          },
        ],
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

    return {
      content: [
        {
          type: "text",
          text: changed
            ? `Fixed delimiters:\n\`\`\`clojure\n${repaired}\n\`\`\``
            : "Could not repair delimiters",
        },
      ],
      details: { changed, balanced: !detectImbalance(repaired) },
    };
  },
});

