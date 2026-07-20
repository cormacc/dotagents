// SPDX-License-Identifier: EPL-2.0
// Copyright © 2026-present Marko Kocic <marko@euptera.com>

import {
  DEFAULT_MAX_BYTES,
  DEFAULT_MAX_LINES,
  defineTool,
  truncateHead,
} from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { evalExpr } from "../nrepl-client";

const MAX_TIMEOUT_MS = 2_147_483_647;

export function createEvalTool(runEval: typeof evalExpr = evalExpr) {
  return defineTool({
    name: "clojure_eval",
    label: "Clojure Eval",
    description: "Evaluate Clojure code via nREPL. Requires an existing nREPL connection (see clojure_find_nrepl_port to find one). Output is limited to pi's standard 2000 lines or 50KB.",
    promptSnippet: "Evaluate Clojure code",
    parameters: Type.Object({
      code: Type.String({ description: "Clojure code to evaluate" }),
      port: Type.Integer({ minimum: 1, maximum: 65_535, description: "nREPL TCP port" }),
      host: Type.Optional(
        Type.String({ description: "nREPL host", default: "localhost" }),
      ),
      ns: Type.Optional(Type.String({ description: "Target namespace" })),
      timeout: Type.Optional(
        Type.Integer({
          minimum: 1,
          maximum: MAX_TIMEOUT_MS,
          description: "Timeout in milliseconds, including connection setup (default: 30000)",
        }),
      ),
    }),

    async execute(_toolCallId, params, signal, _onUpdate, _ctx) {
      try {
        const result = await runEval({
          host: params.host ?? "localhost",
          port: params.port,
          code: params.code,
          ns: params.ns,
          timeout: params.timeout,
          signal,
        });

        const lines: string[] = [];
        if (result.vals.length > 0) lines.push(`=> ${result.vals.join("\n=> ")}`);
        if (result.out) lines.push(`stdout: ${result.out}`);
        if (result.err) lines.push(`stderr: ${result.err}`);

        const output = lines.length > 0 ? lines.join("\n") : "No output (nil or empty)";
        const truncation = truncateHead(output, {
          maxLines: DEFAULT_MAX_LINES,
          maxBytes: DEFAULT_MAX_BYTES,
        });

        return {
          content: [{ type: "text", text: truncation.content }],
          details: { output: truncation.content, truncation },
        };
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        throw new Error(`Clojure eval failed: ${message}`);
      }
    },
  });
}

export const evalTool = createEvalTool();
